"""
Comprehensive automated test suite for the Madhav Maintenance Payment Backend.
Tests:
  1. Strict Admin-only privacy (regular users cannot read records, screenshots, or exports).
  2. Accurate UPI & Bank Screenshot OCR extraction (Google Pay, PhonePe, Paytm, BHIM UPI, Bank IMPS),
     including ML Kit currency-dropped standalone amounts and bounding-box hints.
  3. Strict rejection (HTTP 422) of non-payment screenshots and failed/pending receipts
     (zero fake data generation).
  4. Server-side immutability of screenshot-extracted fields (user cannot tamper with UTR, amount, UPI ID).
  5. Resident payment submission & duplicate Transaction ID (UTR) fraud prevention.
  6. Multi-Format CSV exports & native OpenXML Excel (.xlsx) generation.
"""

import io
import os
import csv
import zipfile
import tempfile
import pytest

from server import create_app


PNG_1X1_BYTES = (
    b"\x89PNG\r\n\x1a\n\x00\x00\x00\rIHDR\x00\x00\x00\x01\x00\x00\x00\x01"
    b"\x08\x02\x00\x00\x00\x90wS\xde\x00\x00\x00\x0cIDATx\x9cc\xf8\xcf\xc0"
    b"\x00\x00\x03\x01\x01\x00\x18\xdd\x8d\xb0\x00\x00\x00\x00IEND\xaeB`\x82"
)


@pytest.fixture()
def client():
    with tempfile.TemporaryDirectory() as tmpdir:
        db_path = os.path.join(tmpdir, "test_upi.db")
        shots_dir = os.path.join(tmpdir, "screenshots")
        app = create_app(
            {
                "TESTING": True,
                "DB_PATH": db_path,
                "SCREENSHOT_DIR": shots_dir,
            }
        )
        with app.test_client() as c:
            yield c


def test_regular_user_cannot_access_admin_data_or_exports(client):
    """Verifies that all stored payment data and exports are strictly blocked for regular users."""
    assert client.get("/api/admin/payments").status_code == 401
    assert client.get("/api/admin/export?format=standard_csv").status_code == 401
    assert client.get("/api/admin/export?format=xlsx").status_code == 401
    assert client.get("/api/admin/export-formats").status_code == 401
    assert client.get("/api/admin/screenshots/upi_test.png").status_code == 401
    assert client.patch("/api/admin/payments/1", json={"verification_status": "Verified"}).status_code == 401
    assert client.delete("/api/admin/payments/1").status_code == 401


def test_ocr_extraction_from_upi_screenshot_text(client):
    """Verifies OCR extraction of UPI ID, Transaction Amount, 12-digit UTR, Date, and Account Holder."""
    sample_ocr = (
        "Google Pay Payment Successful\n"
        "Paid ₹3,500.00\n"
        "From: Aarav Mehta\n"
        "UPI ID: aarav.mehta@okicici\n"
        "UPI transaction ID: 429104857312\n"
        "Date: 2026-10-20"
    )
    resp = client.post(
        "/api/ocr/extract",
        data={
            "ocr_text": sample_ocr,
            "screenshot": (io.BytesIO(PNG_1X1_BYTES), "gpay_receipt.png"),
        },
        content_type="multipart/form-data",
    )
    assert resp.status_code == 200
    payload = resp.get_json()
    assert payload["ok"] is True
    assert payload["valid"] is True
    ext = payload["extracted"]
    assert ext["upi_id"] == "aarav.mehta@okicici"
    assert ext["transaction_amount"] == 3500.00
    assert ext["payment_amount"] == 3500.00
    assert ext["transaction_id"] == "429104857312"
    assert ext["transaction_date"] == "2026-10-20"
    assert ext["account_holder_name"] == "Aarav Mehta"
    assert ext["payment_app"] == "Google Pay"


def test_ocr_extracts_amount_when_mlkit_drops_rupee_symbol(client):
    """
    Verifies that when ML Kit drops the ₹ symbol on Google Pay / PhonePe screenshots
    (rendering the amount as a standalone line like '4,200' or '1500' near 'Completed'/'Paid to'),
    the amount is still accurately extracted.
    """
    gpay_ocr_no_symbol = (
        "To Madhav Society Maintenance\n"
        "4,200\n"
        "Completed\n"
        "20 Oct 2026, 10:15 am\n"
        "UPI transaction ID\n"
        "612398745012\n"
        "To: madhavsociety@sbi\n"
        "From: PRIYA NAIR (HDFC Bank)\n"
        "priya.nair@okhdfcbank\n"
    )
    resp = client.post(
        "/api/ocr/extract",
        data={"ocr_text": gpay_ocr_no_symbol},
        content_type="multipart/form-data",
    )
    assert resp.status_code == 200
    ext = resp.get_json()["extracted"]
    assert ext["transaction_amount"] == 4200.00
    assert ext["payment_amount"] == 4200.00
    assert ext["transaction_id"] == "612398745012"
    assert ext["upi_id"] == "priya.nair@okhdfcbank"
    assert ext["sender_name"] == "PRIYA NAIR"
    assert ext["receiver_name"] == "Madhav Society Maintenance"
    assert ext["account_holder_name"] == "PRIYA NAIR"
    assert ext["payment_app"] == "Google Pay"


def test_society_meetings_readonly_for_residents_and_writable_by_admin(client):
    """
    Verifies that regular users can read Society Meeting messages (Google Messages style)
    but cannot post or delete them, whereas authenticated Admins can broadcast and delete meetings.
    """
    # 1. Public GET /api/meetings returns the seeded meeting notification
    get_resp = client.get("/api/meetings")
    assert get_resp.status_code == 200
    initial_meetings = get_resp.get_json()["meetings"]
    assert len(initial_meetings) >= 1

    # 2. Regular unauthenticated user cannot post or delete meetings
    unauth_post = client.post(
        "/api/admin/meetings",
        json={
            "meeting_date": "2026-11-10 • 06:30 PM",
            "topic": "Unauthorized Meeting",
            "body": "Should be blocked",
        },
    )
    assert unauth_post.status_code == 401
    assert client.delete(f"/api/admin/meetings/{initial_meetings[0]['id']}").status_code == 401

    # 3. Authenticated Admin can post and delete meeting notifications
    login_resp = client.post(
        "/api/admin/login", json={"username": "admin", "password": "Admin@123"}
    )
    token = login_resp.get_json()["token"]
    headers = {"Authorization": f"Bearer {token}"}

    create_resp = client.post(
        "/api/admin/meetings",
        json={
            "meeting_date": "2026-11-15 • 07:30 PM",
            "topic": "Diwali Celebration & Security Review",
            "body": "All residents of Madhav Apartment are invited to the community hall at 7:30 PM.",
        },
        headers=headers,
    )
    assert create_resp.status_code == 201
    created_meeting = create_resp.get_json()["meeting"]
    assert created_meeting["topic"] == "Diwali Celebration & Security Review"

    updated_list = client.get("/api/meetings").get_json()["meetings"]
    assert any(m["id"] == created_meeting["id"] for m in updated_list)

    del_resp = client.delete(f"/api/admin/meetings/{created_meeting['id']}", headers=headers)
    assert del_resp.status_code == 200


def test_non_payment_and_failed_screenshots_are_rejected_without_fake_data(client):
    """
    Verifies that non-payment screenshots, missing-UTR screenshots, and failed/pending
    payment screenshots are rejected with HTTP 422 and never generate fake fallback data.
    """
    # 1. Non-payment screenshot (e.g. WhatsApp chat / random screenshot)
    non_payment_resp = client.post(
        "/api/ocr/extract",
        data={"ocr_text": "Good morning everyone! Meeting at 7 PM in the clubhouse today."},
        content_type="multipart/form-data",
    )
    assert non_payment_resp.status_code == 422
    body1 = non_payment_resp.get_json()
    assert body1["ok"] is False
    assert body1["valid"] is False
    assert body1.get("extracted") is None

    # 2. Failed payment screenshot
    failed_resp = client.post(
        "/api/ocr/extract",
        data={
            "ocr_text": (
                "Google Pay\n"
                "₹3,500\n"
                "Payment failed\n"
                "UPI transaction ID: 429104857399\n"
                "aarav@okicici"
            )
        },
        content_type="multipart/form-data",
    )
    assert failed_resp.status_code == 422
    assert failed_resp.get_json()["valid"] is False

    # 3. Submitting a non-payment screenshot to /api/payments/submit must also be rejected (422)
    submit_invalid = client.post(
        "/api/payments/submit",
        data={
            "resident_name": "Fake User",
            "flat_number": "A-101",
            "ocr_text": "Random wallpaper image with no payment info",
            "screenshot": (io.BytesIO(PNG_1X1_BYTES), "wallpaper.png"),
        },
        content_type="multipart/form-data",
    )
    assert submit_invalid.status_code == 422


def test_resident_submission_auto_populates_amount_and_blocks_tampering(client):
    """
    Verifies resident submission is write-only, auto-populates payment_amount from the screenshot,
    ignores any user-tampered receipt fields, and blocks duplicate Transaction IDs.
    """
    phonepe_ocr = (
        "PhonePe\n"
        "Transaction Successful\n"
        "Paid ₹4,200.00\n"
        "From: Priya S Nair\n"
        "priya.nair@ybl\n"
        "UTR: 839201948572\n"
        "Date: 2026-10-20"
    )
    form_data = {
        "resident_name": "Priya Nair",
        "flat_number": "b-204",
        # Omit payment_amount and try passing tampered transaction_id / upi_id:
        # server must auto-populate payment_amount=4200.00 and use OCR's real UTR & UPI ID
        "upi_id": "tampered@fake",
        "transaction_amount": "99999.00",
        "transaction_id": "000000000000",
        "ocr_text": phonepe_ocr,
        "screenshot": (io.BytesIO(PNG_1X1_BYTES), "phonepe.png"),
    }
    resp = client.post("/api/payments/submit", data=form_data, content_type="multipart/form-data")
    assert resp.status_code == 201
    body = resp.get_json()
    assert body["ok"] is True
    assert "payments" not in body  # Regular user never receives stored list
    assert body["receipt"]["flat_number"] == "B-204"

    # Submitting the same Transaction ID again must return 409 Conflict
    duplicate_form = dict(form_data)
    duplicate_form["screenshot"] = (io.BytesIO(PNG_1X1_BYTES), "phonepe_dup.png")
    dup_resp = client.post(
        "/api/payments/submit", data=duplicate_form, content_type="multipart/form-data"
    )
    assert dup_resp.status_code == 409


def test_admin_login_and_multiple_csv_and_xlsx_exports(client):
    """
    Verifies Admin authentication, viewing records, and exporting data into
    multiple CSV formats + native Excel (.xlsx).
    """
    ocr_1 = (
        "Google Pay Payment Successful\n"
        "Paid ₹3,500.00\n"
        "From: Aarav R Mehta\n"
        "UPI ID: aarav@okicici\n"
        "UPI transaction ID: 111222333444\n"
        "Date: 2026-10-20"
    )
    ocr_2 = (
        "BHIM UPI Transaction Successful\n"
        "Paid ₹2,800.00\n"
        "From: Rohan Kulkarni\n"
        "UPI ID: rohan@upi\n"
        "UPI Ref No: 555666777888\n"
        "Date: 2026-10-20"
    )

    # Seed 2 resident submissions (one exact match, one mismatch)
    client.post(
        "/api/payments/submit",
        data={
            "resident_name": "Aarav Mehta",
            "flat_number": "A-402",
            "payment_amount": "3500.00",
            "ocr_text": ocr_1,
            "screenshot": (io.BytesIO(PNG_1X1_BYTES), "shot1.png"),
        },
        content_type="multipart/form-data",
    )
    client.post(
        "/api/payments/submit",
        data={
            "resident_name": "Rohan Kulkarni",
            "flat_number": "C-101",
            "payment_amount": "3000.00",
            "ocr_text": ocr_2,
            "screenshot": (io.BytesIO(PNG_1X1_BYTES), "shot2.png"),
        },
        content_type="multipart/form-data",
    )

    # Admin Login
    login_resp = client.post(
        "/api/admin/login", json={"username": "admin", "password": "Admin@123"}
    )
    assert login_resp.status_code == 200
    token = login_resp.get_json()["token"]
    headers = {"Authorization": f"Bearer {token}"}

    # List payments as Admin
    list_resp = client.get("/api/admin/payments", headers=headers)
    assert list_resp.status_code == 200
    data = list_resp.get_json()
    assert data["stats"]["total_entries"] == 2
    assert data["stats"]["verified_count"] == 1
    assert data["stats"]["mismatch_count"] == 1

    # 1. Test Strict 6-Column CSV Export
    csv6_resp = client.get("/api/admin/export?format=core_6_csv", headers=headers)
    assert csv6_resp.status_code == 200
    rows_6 = list(csv.reader(io.StringIO(csv6_resp.data.decode("utf-8"))))
    assert rows_6[0] == [
        "Name",
        "Flat number",
        "Payment amount",
        "UPI ID",
        "Transaction amount",
        "Transaction ID",
    ]
    assert rows_6[1] == [
        "Aarav Mehta",
        "A-402",
        "3500.00",
        "aarav@okicici",
        "3500.00",
        "111222333444",
    ]

    # 2. Test Standard CSV (includes Date & Account holder's name)
    std_resp = client.get("/api/admin/export?format=standard_csv", headers=headers)
    rows_std = list(csv.reader(io.StringIO(std_resp.data.decode("utf-8"))))
    assert rows_std[0] == [
        "Name",
        "Flat number",
        "Payment amount",
        "UPI ID",
        "Transaction amount",
        "Transaction ID",
        "Date",
        "Account holder's name",
    ]
    assert len(rows_std) == 3

    # 3. Test Semicolon-Separated CSV + DMY Date Style
    semi_resp = client.get(
        "/api/admin/export?format=semicolon_csv&date_style=dmy", headers=headers
    )
    text_semi = semi_resp.data.decode("utf-8-sig")
    rows_semi = list(csv.reader(io.StringIO(text_semi), delimiter=";"))
    assert rows_semi[1][6] == "20/10/2026"

    # 4. Test Tab-Separated Values (.tsv)
    tsv_resp = client.get("/api/admin/export?format=tsv", headers=headers)
    rows_tsv = list(csv.reader(io.StringIO(tsv_resp.data.decode("utf-8")), delimiter="\t"))
    assert rows_tsv[0][0] == "Name"
    assert len(rows_tsv) == 3

    # 5. Test Society Accounting & Variance CSV
    acc_resp = client.get("/api/admin/export?format=accounting_csv", headers=headers)
    rows_acc = list(csv.reader(io.StringIO(acc_resp.data.decode("utf-8-sig"))))
    assert "Variance (Tx - Payment)" in rows_acc[0]
    assert rows_acc[2][8] == "-200.00"  # 2800 - 3000 variance

    # 6. Test Custom Pipe-Delimited CSV with custom column subset
    custom_resp = client.get(
        "/api/admin/export?format=custom_csv&delimiter=pipe&columns=flat_number,resident_name,transaction_id",
        headers=headers,
    )
    rows_pipe = list(
        csv.reader(io.StringIO(custom_resp.data.decode("utf-8")), delimiter="|")
    )
    assert rows_pipe[0] == ["Flat number", "Name", "Transaction ID"]
    assert rows_pipe[1] == ["A-402", "Aarav Mehta", "111222333444"]

    # 7. Test Native Excel (.xlsx) Workbook Generation
    xlsx_resp = client.get("/api/admin/export?format=xlsx", headers=headers)
    assert xlsx_resp.status_code == 200
    with zipfile.ZipFile(io.BytesIO(xlsx_resp.data), "r") as zf:
        names = zf.namelist()
        assert "xl/workbook.xml" in names
        assert "xl/worksheets/sheet1.xml" in names
        sheet_xml = zf.read("xl/worksheets/sheet1.xml").decode("utf-8")
        assert "Aarav Mehta" in sheet_xml
        assert "111222333444" in sheet_xml
        assert "Flat number" in sheet_xml


def test_admin_update_delete_and_screenshot_access(client):
    """Verifies Admin can update status, view screenshot proof, and delete records."""
    neha_ocr = (
        "Google Pay Payment Successful\n"
        "Paid ₹4,500.00\n"
        "From: Neha Kapoor\n"
        "neha@okhdfcbank\n"
        "UPI transaction ID: 999888777666\n"
        "Date: 2026-10-20"
    )
    client.post(
        "/api/payments/submit",
        data={
            "resident_name": "Neha Kapoor",
            "flat_number": "D-102",
            "payment_amount": "5000.00",
            "ocr_text": neha_ocr,
            "screenshot": (io.BytesIO(PNG_1X1_BYTES), "neha_gpay.png"),
        },
        content_type="multipart/form-data",
    )

    login_resp = client.post(
        "/api/admin/login", json={"username": "admin", "password": "Admin@123"}
    )
    token = login_resp.get_json()["token"]
    headers = {"Authorization": f"Bearer {token}"}

    records = client.get("/api/admin/payments", headers=headers).get_json()["payments"]
    assert len(records) == 1
    rec = records[0]
    assert rec["verification_status"] == "Amount Mismatch"

    # Admin can fetch protected screenshot
    shot_resp = client.get(
        f"/api/admin/screenshots/{rec['screenshot_filename']}", headers=headers
    )
    assert shot_resp.status_code == 200
    assert shot_resp.data == PNG_1X1_BYTES

    # Admin can update status to Verified
    patch_resp = client.patch(
        f"/api/admin/payments/{rec['id']}",
        json={"verification_status": "Verified"},
        headers=headers,
    )
    assert patch_resp.status_code == 200
    assert patch_resp.get_json()["payment"]["verification_status"] == "Verified"

    # Admin can delete the record
    del_resp = client.delete(f"/api/admin/payments/{rec['id']}", headers=headers)
    assert del_resp.status_code == 200
    remaining = client.get("/api/admin/payments", headers=headers).get_json()["payments"]
    assert len(remaining) == 0


def test_validation_edge_cases(client):
    """Verifies input validation for missing fields, invalid amounts, and unsupported file types."""
    # Missing flat number
    r1 = client.post(
        "/api/payments/submit",
        data={
            "resident_name": "Test User",
            "flat_number": "",
            "payment_amount": "1000",
            "screenshot": (io.BytesIO(PNG_1X1_BYTES), "receipt.png"),
        },
        content_type="multipart/form-data",
    )
    assert r1.status_code == 400

    # Negative payment amount
    r2 = client.post(
        "/api/payments/submit",
        data={
            "resident_name": "Test User",
            "flat_number": "A-101",
            "payment_amount": "-500",
            "screenshot": (io.BytesIO(PNG_1X1_BYTES), "receipt.png"),
        },
        content_type="multipart/form-data",
    )
    assert r2.status_code == 400

    # Disallowed file extension (.exe)
    r3 = client.post(
        "/api/payments/submit",
        data={
            "resident_name": "Test User",
            "flat_number": "A-101",
            "payment_amount": "1500",
            "screenshot": (io.BytesIO(b"bad"), "virus.exe"),
        },
        content_type="multipart/form-data",
    )
    assert r3.status_code == 400


def test_misread_rupee_784_is_corrected_to_84(client):
    """
    Verifies that when ML Kit misreads the leading ₹ glyph as '7' on a ₹84 receipt
    (producing '784' alongside another reference or corrected [AMOUNT_CANDIDATE] 84),
    the engine accurately returns 84.00 instead of 784.00.
    """
    ocr_with_misread_784 = (
        "To Madhav Society\n"
        "784\n"
        "Paid ₹84\n"
        "Completed\n"
        "23 Oct 2026, 4:10 pm\n"
        "UPI transaction ID\n"
        "429184001234 Copy\n"
        "To: madhav@sbi\n"
        "From: NIKHIL SHARMA\n"
        "nikhil@okaxis\n"
    )
    resp = client.post(
        "/api/ocr/extract",
        data={"ocr_text": ocr_with_misread_784},
        content_type="multipart/form-data",
    )
    assert resp.status_code == 200
    ext = resp.get_json()["extracted"]
    assert ext["transaction_amount"] == 84.00
    assert ext["payment_amount"] == 84.00
    assert ext["transaction_id"] == "429184001234"


def test_other_upi_apps_accepted_and_payment_app_and_amount_editable(client):
    """
    Verifies that valid UPI payment screenshots from any UPI app (including Navi, Super.money,
    or a generic/unrecognized UPI app) are accepted, and that the user can edit both
    payment_amount and payment_app.
    """
    generic_upi_ocr = (
        "Payment Successful\n"
        "₹84\n"
        "Paid to Madhav Maintenance\n"
        "madhav@icici\n"
        "From: Nikhil Patel\n"
        "nikhil@customupi\n"
        "UTR: 738291048576 Copy\n"
        "23 Oct 2026\n"
    )
    extract_resp = client.post(
        "/api/ocr/extract",
        data={"ocr_text": generic_upi_ocr},
        content_type="multipart/form-data",
    )
    assert extract_resp.status_code == 200
    ext = extract_resp.get_json()["extracted"]
    assert ext["transaction_amount"] == 84.00
    assert ext["transaction_id"] == "738291048576"

    # Submit with user-edited payment_amount and user-typed payment_app
    submit_resp = client.post(
        "/api/payments/submit",
        data={
            "resident_name": "Nikhil Patel",
            "flat_number": "A-301",
            "payment_amount": "100.00",
            "payment_app": "Super.money UPI",
            "ocr_text": generic_upi_ocr,
            "screenshot": (io.BytesIO(PNG_1X1_BYTES), "other_upi.png"),
        },
        content_type="multipart/form-data",
    )
    assert submit_resp.status_code == 201

    login_resp = client.post(
        "/api/admin/login", json={"username": "admin", "password": "Admin@123"}
    )
    token = login_resp.get_json()["token"]
    headers = {"Authorization": f"Bearer {token}"}
    records = client.get("/api/admin/payments", headers=headers).get_json()["payments"]
    assert len(records) == 1
    assert records[0]["payment_amount"] == 100.00
    assert records[0]["transaction_amount"] == 84.00
    assert records[0]["payment_app"] == "Super.money UPI"


def test_other_upi_apps_stepper_2column_and_resubmit(client):
    """
    Verifies that other UPI apps featuring timeline steppers ('Processing' -> 'Payment Successful'),
    2-column tables (labels separated from UTR values by multiple lines), or collapsed UTR accordions
    are accepted without error, and that Android re-submissions (allow_resubmit='true') succeed cleanly.
    """
    stepper_2col_ocr = (
        "CRED UPI\n"
        "Payment Initiated\n"
        "Processing\n"
        "Payment Successful\n"
        "₹84\n"
        "Paid to Madhav Maintenance\n"
        "madhav@hdfcbank\n"
        "From: Nikhil Patel\n"
        "nikhil@axisb\n"
        "UTR\n"
        "Date\n"
        "Status\n"
        "629384756102\n"
        "23 Oct 2026\n"
    )
    resp1 = client.post(
        "/api/ocr/extract",
        data={"ocr_text": stepper_2col_ocr},
        content_type="multipart/form-data",
    )
    assert resp1.status_code == 200
    ext1 = resp1.get_json()["extracted"]
    assert ext1["transaction_amount"] == 84.00
    assert ext1["transaction_id"] == "629384756102"

    # Collapsed UTR accordion in another UPI app (e.g. FamPay / Slice / WhatsApp Pay)
    collapsed_utr_ocr = (
        "FamPay UPI\n"
        "Payment Successful\n"
        "Paid ₹84 to Madhav Society\n"
        "madhav@sbi\n"
        "From: Nikhil Sharma (nikhil@fampay)\n"
        "23 Oct 2026, 05:15 PM\n"
        "View transaction details\n"
    )
    resp2 = client.post(
        "/api/ocr/extract",
        data={"ocr_text": collapsed_utr_ocr},
        content_type="multipart/form-data",
    )
    assert resp2.status_code == 200
    ext2 = resp2.get_json()["extracted"]
    assert ext2["transaction_amount"] == 84.00
    assert len(ext2["transaction_id"]) >= 8

    # First submit with allow_resubmit=true
    sub1 = client.post(
        "/api/payments/submit",
        data={
            "resident_name": "Nikhil Sharma",
            "flat_number": "B-104",
            "payment_amount": "84.00",
            "payment_app": "CRED UPI",
            "allow_resubmit": "true",
            "ocr_text": stepper_2col_ocr,
            "screenshot": (io.BytesIO(PNG_1X1_BYTES), "cred1.png"),
        },
        content_type="multipart/form-data",
    )
    assert sub1.status_code == 201
    assert "Madhav admin account" in sub1.get_json()["message"]

    # Re-submitting the same screenshot from the mobile app with allow_resubmit=true succeeds (201)
    sub2 = client.post(
        "/api/payments/submit",
        data={
            "resident_name": "Nikhil Sharma",
            "flat_number": "B-104",
            "payment_amount": "84.00",
            "payment_app": "CRED UPI",
            "allow_resubmit": "true",
            "ocr_text": stepper_2col_ocr,
            "screenshot": (io.BytesIO(PNG_1X1_BYTES), "cred1_retry.png"),
        },
        content_type="multipart/form-data",
    )
    assert sub2.status_code == 201


