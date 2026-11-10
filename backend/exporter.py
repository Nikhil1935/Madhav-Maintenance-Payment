"""
Multi-Format CSV & Native OpenXML Excel (.xlsx) Export Engine.
Supports multiple CSV presets, custom delimiters/column ordering, and native .xlsx generation.
"""

import csv
import io
import zipfile
from datetime import datetime
from typing import List, Dict, Any, Optional, Tuple
from xml.sax.saxutils import escape


# Available Column Definitions (key -> human-readable header)
AVAILABLE_COLUMNS: Dict[str, str] = {
    "resident_name": "Name",
    "flat_number": "Flat number",
    "payment_amount": "Payment amount",
    "upi_id": "UPI ID",
    "transaction_amount": "Transaction amount",
    "transaction_id": "Transaction ID",
    "transaction_date": "Date",
    "account_holder_name": "Account holder's name",
    "sender_name": "Sender name",
    "receiver_name": "Receiver name",
    "payment_app": "Payment App",
    "verification_status": "Status",
}

# Core 6 columns + Date & Account holder's name
DEFAULT_COLUMN_KEYS: List[str] = [
    "resident_name",
    "flat_number",
    "payment_amount",
    "upi_id",
    "transaction_amount",
    "transaction_id",
    "transaction_date",
    "account_holder_name",
]

STRICT_6_COLUMN_KEYS: List[str] = [
    "resident_name",
    "flat_number",
    "payment_amount",
    "upi_id",
    "transaction_amount",
    "transaction_id",
]

CSV_FORMATS_METADATA: Dict[str, Dict[str, Any]] = {
    "standard_csv": {
        "label": "Standard CSV (UTF-8, Comma-Separated)",
        "description": "Includes the 6 core columns + Date & Account Holder Name",
        "delimiter": ",",
        "extension": "csv",
        "mime": "text/csv; charset=utf-8",
        "bom": False,
        "columns": DEFAULT_COLUMN_KEYS,
    },
    "core_6_csv": {
        "label": "Strict 6-Column CSV (Name, Flat, Payment, UPI, Tx Amt, Tx ID)",
        "description": "Exact 6 columns only, comma-delimited",
        "delimiter": ",",
        "extension": "csv",
        "mime": "text/csv; charset=utf-8",
        "bom": False,
        "columns": STRICT_6_COLUMN_KEYS,
    },
    "excel_bom_csv": {
        "label": "Excel-Compatible CSV (UTF-8 with BOM)",
        "description": "Optimized for direct double-click opening in Microsoft Excel",
        "delimiter": ",",
        "extension": "csv",
        "mime": "text/csv; charset=utf-8",
        "bom": True,
        "columns": DEFAULT_COLUMN_KEYS + ["payment_app", "verification_status"],
    },
    "semicolon_csv": {
        "label": "Semicolon-Separated CSV (;)",
        "description": "Regional Excel / European ERP compatible semicolon format",
        "delimiter": ";",
        "extension": "csv",
        "mime": "text/csv; charset=utf-8",
        "bom": True,
        "columns": DEFAULT_COLUMN_KEYS,
    },
    "tsv": {
        "label": "Tab-Separated Values (.tsv)",
        "description": "Ideal for direct copy-paste into spreadsheets & databases",
        "delimiter": "\t",
        "extension": "tsv",
        "mime": "text/tab-separated-values; charset=utf-8",
        "bom": False,
        "columns": DEFAULT_COLUMN_KEYS + ["verification_status"],
    },
    "pipe_csv": {
        "label": "Pipe-Delimited CSV (|)",
        "description": "Legacy banking & flat-file import format",
        "delimiter": "|",
        "extension": "csv",
        "mime": "text/csv; charset=utf-8",
        "bom": False,
        "columns": DEFAULT_COLUMN_KEYS,
    },
    "accounting_csv": {
        "label": "Society Accounting & Reconciliation CSV",
        "description": "Ordered by Date & UTR with Variance column for Tally / Zoho Books",
        "delimiter": ",",
        "extension": "csv",
        "mime": "text/csv; charset=utf-8",
        "bom": True,
        "columns": [
            "transaction_date",
            "transaction_id",
            "flat_number",
            "resident_name",
            "account_holder_name",
            "upi_id",
            "payment_amount",
            "transaction_amount",
            "variance",
            "verification_status",
        ],
    },
}


def format_date_value(raw_date: str, date_style: str = "iso") -> str:
    """Formats a date string into the requested style ('iso', 'dmy', 'us')."""
    if not raw_date:
        return ""
    raw_date = str(raw_date).strip()
    parsed = None
    for fmt in ("%Y-%m-%d %H:%M:%S", "%Y-%m-%d %H:%M", "%Y-%m-%d", "%d/%m/%Y", "%d-%m-%Y"):
        try:
            parsed = datetime.strptime(raw_date, fmt)
            break
        except ValueError:
            continue
    if not parsed:
        return raw_date

    if date_style == "dmy":
        return parsed.strftime("%d/%m/%Y")
    elif date_style == "us":
        return parsed.strftime("%m/%d/%Y")
    return parsed.strftime("%Y-%m-%d")


def generate_csv_bytes(
    records: List[Dict[str, Any]],
    format_key: str = "standard_csv",
    custom_delimiter: Optional[str] = None,
    custom_columns: Optional[List[str]] = None,
    date_style: str = "iso",
) -> Tuple[bytes, str, str]:
    """
    Generates CSV bytes, filename, and MIME type for any preset or custom configuration.
    """
    preset = CSV_FORMATS_METADATA.get(format_key, CSV_FORMATS_METADATA["standard_csv"])

    delimiter_map = {
        "comma": ",",
        "semicolon": ";",
        "tab": "\t",
        "pipe": "|",
        ",": ",",
        ";": ";",
        "\t": "\t",
        "|": "|",
    }
    delimiter = (
        delimiter_map.get(custom_delimiter, preset["delimiter"])
        if custom_delimiter
        else preset["delimiter"]
    )

    if custom_columns:
        valid_cols = [c for c in custom_columns if c in AVAILABLE_COLUMNS or c == "variance"]
        columns = valid_cols if valid_cols else preset["columns"]
    else:
        columns = preset["columns"]

    output = io.StringIO()
    if preset.get("bom"):
        output.write("\ufeff")

    writer = csv.writer(output, delimiter=delimiter, quoting=csv.QUOTE_MINIMAL, lineterminator="\r\n")

    # Header row
    headers = []
    for col in columns:
        if col == "variance":
            headers.append("Variance (Tx - Payment)")
        else:
            headers.append(AVAILABLE_COLUMNS.get(col, col))
    writer.writerow(headers)

    # Data rows
    for row in records:
        out_row = []
        for col in columns:
            if col == "variance":
                pay_amt = float(row.get("payment_amount", 0) or 0)
                tx_amt = float(row.get("transaction_amount", 0) or 0)
                out_row.append(f"{tx_amt - pay_amt:.2f}")
            elif col in ("payment_amount", "transaction_amount"):
                val = float(row.get(col, 0) or 0)
                out_row.append(f"{val:.2f}")
            elif col == "transaction_date":
                out_row.append(format_date_value(str(row.get(col, "")), date_style))
            else:
                out_row.append(str(row.get(col, "") or ""))
        writer.writerow(out_row)

    ext = "tsv" if delimiter == "\t" else preset.get("extension", "csv")
    timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
    filename = f"upi_payments_{format_key}_{timestamp}.{ext}"
    mime = "text/tab-separated-values; charset=utf-8" if ext == "tsv" else preset["mime"]

    return output.getvalue().encode("utf-8"), filename, mime


def _col_letter(idx: int) -> str:
    """Converts 0-based column index to Excel column letter (A, B, C...)."""
    result = ""
    n = idx + 1
    while n > 0:
        n, rem = divmod(n - 1, 26)
        result = chr(65 + rem) + result
    return result


def generate_xlsx_bytes(
    records: List[Dict[str, Any]],
    custom_columns: Optional[List[str]] = None,
    date_style: str = "iso",
) -> Tuple[bytes, str, str]:
    """
    Generates a genuine OpenXML (.xlsx) Excel workbook using pure Python zipfile + XML.
    Works across Microsoft Excel, Apple Numbers, LibreOffice, and Google Sheets.
    """
    columns = (
        [c for c in custom_columns if c in AVAILABLE_COLUMNS]
        if custom_columns
        else DEFAULT_COLUMN_KEYS + ["payment_app", "verification_status"]
    )
    if not columns:
        columns = DEFAULT_COLUMN_KEYS

    headers = [AVAILABLE_COLUMNS.get(c, c) for c in columns]

    # Build worksheet XML rows
    sheet_rows_xml = []

    # Row 1: Styled Header Row (s="1" -> Bold white text on Apple System Blue #007AFF fill)
    header_cells = []
    for c_idx, h_text in enumerate(headers):
        ref = f"{_col_letter(c_idx)}1"
        header_cells.append(
            f'<c r="{ref}" t="inlineStr" s="1"><is><t>{escape(str(h_text))}</t></is></c>'
        )
    sheet_rows_xml.append(f'<row r="1" ht="24" customHeight="1">{"".join(header_cells)}</row>')

    # Data Rows (starting at row 2)
    for r_idx, rec in enumerate(records, start=2):
        row_cells = []
        for c_idx, col_key in enumerate(columns):
            ref = f"{_col_letter(c_idx)}{r_idx}"
            if col_key in ("payment_amount", "transaction_amount"):
                num_val = float(rec.get(col_key, 0.0) or 0.0)
                row_cells.append(f'<c r="{ref}" s="2"><v>{num_val:.2f}</v></c>')
            else:
                if col_key == "transaction_date":
                    raw_val = format_date_value(str(rec.get(col_key, "")), date_style)
                else:
                    raw_val = str(rec.get(col_key, "") or "")
                row_cells.append(
                    f'<c r="{ref}" t="inlineStr" s="0"><is><t>{escape(raw_val)}</t></is></c>'
                )
        sheet_rows_xml.append(f'<row r="{r_idx}" ht="20" customHeight="1">{"".join(row_cells)}</row>')

    # Column widths
    cols_xml_parts = []
    for c_idx in range(len(columns)):
        col_num = c_idx + 1
        cols_xml_parts.append(
            f'<col min="{col_num}" max="{col_num}" width="22" customWidth="1"/>'
        )
    cols_xml = f"<cols>{''.join(cols_xml_parts)}</cols>"

    worksheet_xml = f"""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
  {cols_xml}
  <sheetData>
    {"".join(sheet_rows_xml)}
  </sheetData>
</worksheet>"""

    content_types_xml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
  <Default Extension="xml" ContentType="application/xml"/>
  <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
  <Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
  <Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>
</Types>"""

    root_rels_xml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
</Relationships>"""

    workbook_xml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"
          xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
  <sheets>
    <sheet name="UPI Payments" sheetId="1" r:id="rId1"/>
  </sheets>
</workbook>"""

    workbook_rels_xml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
  <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
</Relationships>"""

    styles_xml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
  <numFmts count="1">
    <numFmt numFmtId="164" formatCode="#,##0.00"/>
  </numFmts>
  <fonts count="2">
    <font><sz val="11"/><color rgb="FF1D1D1F"/><name val="Calibri"/></font>
    <font><b/><sz val="11"/><color rgb="FFFFFFFF"/><name val="Calibri"/></font>
  </fonts>
  <fills count="3">
    <fill><patternFill patternType="none"/></fill>
    <fill><patternFill patternType="gray125"/></fill>
    <fill><patternFill patternType="solid"><fgColor rgb="FF007AFF"/><bgColor indexed="64"/></patternFill></fill>
  </fills>
  <borders count="1">
    <border><left/><right/><top/><bottom/><diagonal/></border>
  </borders>
  <cellStyleXfs count="1">
    <xf numFmtId="0" fontId="0" fillId="0" borderId="0"/>
  </cellStyleXfs>
  <cellXfs count="3">
    <xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>
    <xf numFmtId="0" fontId="1" fillId="2" borderId="0" xfId="0" applyFont="1" applyFill="1"/>
    <xf numFmtId="164" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/>
  </cellXfs>
</styleSheet>"""

    buf = io.BytesIO()
    with zipfile.ZipFile(buf, mode="w", compression=zipfile.ZIP_DEFLATED) as zf:
        zf.writestr("[Content_Types].xml", content_types_xml)
        zf.writestr("_rels/.rels", root_rels_xml)
        zf.writestr("xl/workbook.xml", workbook_xml)
        zf.writestr("xl/_rels/workbook.xml.rels", workbook_rels_xml)
        zf.writestr("xl/styles.xml", styles_xml)
        zf.writestr("xl/worksheets/sheet1.xml", worksheet_xml)

    timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
    filename = f"upi_payments_sheet_{timestamp}.xlsx"
    mime = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    return buf.getvalue(), filename, mime
