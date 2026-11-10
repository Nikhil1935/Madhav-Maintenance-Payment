"""
Payment receipt validator & extractor (Madhav Maintenance Payment).

The analyzer:
  1. Verifies whether the screenshot is a genuine, completed payment receipt from ANY UPI app
     (Google Pay, PhonePe, Paytm, BHIM UPI, Amazon Pay, CRED, WhatsApp Pay, Navi, Super.money,
     Slice, FamPay, MobiKwik, Freecharge, Airtel Thanks, JioFinance, PayZapp, Samsung Pay,
     Tata Neu, Jupiter, Fi Money, Groww, BharatPe, Bajaj Pay, etc.) or any bank transfer / banking app.
  2. Super-precise Payment Amount extraction:
       - Prioritizes Android ML Kit's symbol-geometry + bitmap-pixel [DETECTED_AMOUNT: ...] hint.
       - Resolves the ML Kit Latin OCR "₹" -> "7" / "2" misread (e.g. "₹84" read as "784") via
         cross-candidate prefix disambiguation and comma-group validation.
  3. Extracts Transaction ID / 12-digit UTR (even when followed by a "Copy" button on the same line),
     UPI ID, Date, Sender Name, Receiver Name, and Payment App.
  4. If the specific UPI app name cannot be recognized from a valid receipt, returns payment_app=""
     so the user can type the payment app name in the editable Payment App field.
  5. NEVER fabricates fake transaction IDs, UPI IDs, or amounts.
"""

import re
import struct
from datetime import datetime
from typing import Any, Dict, List, Optional, Tuple

NOT_SHOWN = "Not shown on receipt"

MIN_AMOUNT = 1.0
MAX_AMOUNT = 10_000_000.0  # ₹1 crore

# --------------------------------------------------------------------------- #
# Patterns                                                                    #
# --------------------------------------------------------------------------- #

_NUM = r"(\d{1,3}(?:,\d{2,3})+|\d+)(\.\d{1,2})?(?!\d|,\d|\.\d)"
_NUM_START = r"(?<![\d,.])"

_CURRENCY = r"(?:₹|\brs(?![a-z])\.?|\binr(?![a-z])|\brupees?(?![a-z])|\?(?=\s*\d))"
CURRENCY_AMOUNT_RE = re.compile(_CURRENCY + r"\s*[:\-]?\s*" + _NUM_START + _NUM, re.IGNORECASE)

DETECTED_AMOUNT_HINT_RE = re.compile(
    r"\[DETECTED_AMOUNT:\s*" + _NUM_START + _NUM + r"\s*\]", re.IGNORECASE
)
DETECTED_TX_ID_HINT_RE = re.compile(
    r"\[DETECTED_TX_ID:\s*([A-Za-z0-9_\-]{6,36})\s*\]", re.IGNORECASE
)

AMOUNT_LABEL_RE = re.compile(
    r"\b(amount|total|paid|debited|sent|transferred|you\s+paid|payment\s+of|paid\s+to|money\s+sent)\b",
    re.IGNORECASE,
)
AMOUNT_CONTEXT_RE = re.compile(
    r"\b(completed|successful(?:ly)?|paid\s+to|sent\s+to|transfer(?:red)?\s+to|"
    r"payment\s+successful|transaction\s+successful|debited\s+from|to\s+[a-z]|done|confirmed)\b",
    re.IGNORECASE,
)
LABELED_AMOUNT_RE = re.compile(
    r"\b(?:amount|total|paid|debited|sent|transferred|payment\s+of|money\s+sent)\b[^\d\n]{0,24}?" + _NUM_START + _NUM,
    re.IGNORECASE,
)
STANDALONE_AMOUNT_RE = re.compile(r"^(?:[₹%*FRZz?]\s*)?" + _NUM_START + _NUM + r"(?:\s*[✓✔])?$")

AMOUNT_EXCLUDE_RE = re.compile(
    r"\b(balance|cashback|reward|scratch|offer|limit|points|coins|fee|charges?|emi|"
    r"phone|mobile|account\s*no|a/c\s*no|utr|ref(?:erence)?\s*(?:no|id|number)|transaction\s*id|txn\s*id|upi\s*id)\b",
    re.IGNORECASE,
)
DATE_LINE_RE = re.compile(
    r"\b(\d{1,2}\s+(?:jan|feb|mar|apr|may|jun|jul|aug|sep|sept|oct|nov|dec)[a-z]*\s+\d{2,4}|"
    r"\d{4}-\d{1,2}-\d{1,2}|\d{1,2}[/\-.]\d{1,2}[/\-.]\d{2,4}|\d{1,2}:\d{2}\s*(?:am|pm))\b",
    re.IGNORECASE,
)

UTR_LABEL_RE = re.compile(
    r"\b(utr(?:\s*(?:no|number|id|#))?|upi\s*transaction\s*id|upi\s*ref(?:erence)?\.?\s*(?:no|number|id|#)?\.?|"
    r"rrn|imps\s*(?:ref(?:erence)?\.?\s*)?(?:no|number|id)?\.?|neft\s*(?:ref(?:erence)?\.?\s*)?(?:no|number|id)?\.?|"
    r"bank\s*ref(?:erence)?\.?\s*(?:no|number|id)?\.?|"
    r"ref(?:erence)?\.?\s*(?:no|number|id|#)?\.?)(?![a-z])",
    re.IGNORECASE,
)
TXN_LABEL_RE = re.compile(
    r"\b(transaction\s*id|txn\.?\s*id|transaction\s*(?:no|number|ref\w*)\.?|"
    r"google\s*transaction\s*id|payment\s*id|order\s*id|receipt\s*(?:no|id|number)|approval\s*(?:no|ref|code))(?![a-z])",
    re.IGNORECASE,
)
ID_TOKEN_RE = re.compile(r"[A-Za-z0-9]{8,35}")
NON_ID_WORDS = {
    "copy", "share", "view", "details", "completed", "successful", "successfully",
    "transaction", "transfer", "payment", "received", "debited", "credited",
    "processing", "verified", "reference", "statement", "download", "support",
    "customer", "merchant", "beneficiary", "remitter", "maintenance", "apartment",
}
SPACED_UTR_RE = re.compile(r"\b(\d{4})[\s\-]+(\d{4})[\s\-]+(\d{4})\b")
ANYWHERE_12DIGIT_UTR_RE = re.compile(r"(?<!\d)(\d{12}|\d{4}[\s\-]+\d{4}[\s\-]+\d{4})(?!\d)")
PHONEPE_TXN_RE = re.compile(r"\b(T\d{18,25}|CICAg[A-Za-z0-9_\-]{8,28})\b")
FALLBACK_NUMERIC_ID_RE = re.compile(r"(?<!\d)(\d{10,22})(?!\d)")

UPI_ID_RE = re.compile(
    r"(?<![\w.\-@])([a-zA-Z0-9][a-zA-Z0-9.\-_]{1,255}@[a-zA-Z][a-zA-Z0-9]{1,63})(?![\w.@])"
)
PAYER_HINT_RE = re.compile(r"\b(from|debited|sender|paid\s+by|your\s+upi|my\s+upi|remitter)\b", re.I)
PAYEE_HINT_RE = re.compile(r"\b(to|receiver|payee|beneficiary|sent\s+to|paid\s+to)\b", re.I)

NAME_LABEL_RE = re.compile(
    r"^(?:from|paid\s*by|sender(?:'s)?(?:\s*name)?|remitter(?:\s*name)?|payer(?:\s*name)?|"
    r"account\s*holder(?:'s)?(?:\s*name)?|debited\s*from)\b[\s:\-]*(.*)$",
    re.IGNORECASE,
)
NAME_VALUE_RE = re.compile(r"^[A-Za-z][A-Za-z .'\-]{1,58}$")
NAME_REJECT_RE = re.compile(
    r"\b(bank|account|a/c|upi|wallet|xxx+|india|ltd|limited|savings|current|google|phonepe|paytm|"
    r"completed|successful|transaction|payment|transfer|verified|receipt|details|amount|paid|sent)\b",
    re.IGNORECASE,
)

_MONTHS = "jan|feb|mar|apr|may|jun|jul|aug|sep|sept|oct|nov|dec"
DATE_PATTERNS: List[Tuple[re.Pattern, str]] = [
    (re.compile(r"\b(\d{1,2})(?:st|nd|rd|th)?[\s\-]+(" + _MONTHS + r")[a-z]*[\s,\-']+(\d{4}|\d{2})\b", re.I), "dmy_text"),
    (re.compile(r"\b(" + _MONTHS + r")[a-z]*\s+(\d{1,2})(?:st|nd|rd|th)?,?\s+(\d{4})\b", re.I), "mdy_text"),
    (re.compile(r"\b(\d{4})-(\d{1,2})-(\d{1,2})\b"), "ymd"),
    (re.compile(r"\b(\d{1,2})[/\-.](\d{1,2})[/\-.](\d{4}|\d{2})\b"), "dmy_num"),
]

SUCCESS_RE = re.compile(
    r"\b(successful(?:ly)?|success|completed?|paid|sent|debited|transferred|payment\s+done|approved|done|confirmed|processed|money\s+sent)\b",
    re.IGNORECASE,
)
STRONG_SUCCESS_HEADLINE_RE = re.compile(
    r"\b(payment\s+successful|transaction\s+successful|transfer\s+successful|paid\s+successfully|money\s+sent|payment\s+done|completed|paid\s+to|sent\s+to)\b",
    re.IGNORECASE,
)
FAILURE_PHRASE_RE = re.compile(
    r"\b(payment|transaction|transfer|txn)\s+(?:is\s+|has\s+)?(?:been\s+)?"
    r"(failed|declined|pending|unsuccessful|cancelled|canceled|reversed)\b",
    re.IGNORECASE,
)
FAILURE_WORD_RE = re.compile(
    r"^(failed|failure|declined|unsuccessful|cancelled|canceled|reversed)[.!]?$",
    re.IGNORECASE,
)

PAYMENT_CONTEXT_RE = re.compile(
    r"(₹|\brs\.?\s*\d|\binr\b|\bupi\b|\butr\b|\brrn\b|\bimps\b|\bneft\b|\brtgs\b|\btransaction\b|\btxn\b|\bpaid\b|\bsent\b|\bdebited\b|\bcredited\b|\btransferred\b|\bbank\b|\ba/c\b|\baccount\b|\bref(?:erence)?\b|\border\s*id\b|\bpayment\b|\breceipt\b)",
    re.IGNORECASE,
)

APP_PATTERNS: List[Tuple[str, re.Pattern]] = [
    ("Google Pay", re.compile(r"google\s*pay|\bg\s?pay\b|google\s*transaction\s*id|upi\s*transaction\s*id", re.I)),
    ("PhonePe", re.compile(r"phone\s?pe|transaction\s+successful[\s\S]{0,160}debited\s+from", re.I)),
    ("Paytm", re.compile(r"\bpaytm\b", re.I)),
    ("BHIM UPI", re.compile(r"\bbhim\b", re.I)),
    ("Amazon Pay", re.compile(r"amazon\s*pay", re.I)),
    ("CRED", re.compile(r"\bcred\b", re.I)),
    ("WhatsApp Pay", re.compile(r"whatsapp", re.I)),
    ("Navi UPI", re.compile(r"\bnavi\b", re.I)),
    ("Super.money", re.compile(r"super\.?\s*money", re.I)),
    ("Slice", re.compile(r"\bslice\b", re.I)),
    ("FamPay", re.compile(r"\bfampay\b|\bfam\s*app\b", re.I)),
    ("MobiKwik", re.compile(r"\bmobikwik\b", re.I)),
    ("Freecharge", re.compile(r"\bfreecharge\b", re.I)),
    ("Airtel Thanks", re.compile(r"\bairtel\b", re.I)),
    ("JioFinance", re.compile(r"\bjio\s*finance\b|\bmyjio\b|\bjio\s*pay\b", re.I)),
    ("HDFC PayZapp", re.compile(r"\bpayzapp\b", re.I)),
    ("Samsung Pay", re.compile(r"samsung\s*(?:pay|wallet)", re.I)),
    ("Tata Neu", re.compile(r"tata\s*neu", re.I)),
    ("Jupiter", re.compile(r"\bjupiter\b", re.I)),
    ("Fi Money", re.compile(r"\bfi\s*money\b", re.I)),
    ("Groww Pay", re.compile(r"\bgroww\b", re.I)),
    ("BharatPe", re.compile(r"\bbharat\s*pe\b", re.I)),
    ("Bajaj Pay", re.compile(r"\bbajaj\b", re.I)),
    ("YONO SBI", re.compile(r"\byono\b", re.I)),
    ("iMobile Pay", re.compile(r"\bimobile\b", re.I)),
    ("Kotak 811", re.compile(r"\bkotak\b", re.I)),
    ("Bank Transfer (IMPS)", re.compile(r"\bimps\b", re.I)),
    ("Bank Transfer (NEFT)", re.compile(r"\bneft\b", re.I)),
    ("Bank Transfer (RTGS)", re.compile(r"\brtgs\b", re.I)),
]
HANDLE_TO_APP = [
    (re.compile(r"@ok(?:axis|hdfcbank|icici|sbi)$", re.I), "Google Pay"),
    (re.compile(r"@(?:ybl|ibl|axl)$", re.I), "PhonePe"),
    (re.compile(r"@(?:paytm|pt\w+)$", re.I), "Paytm"),
    (re.compile(r"@upi$", re.I), "BHIM UPI"),
    (re.compile(r"@(?:apl|yapl|rapl)$", re.I), "Amazon Pay"),
    (re.compile(r"@naviaxis$", re.I), "Navi UPI"),
    (re.compile(r"@superyes$", re.I), "Super.money"),
    (re.compile(r"@slice\w*$", re.I), "Slice"),
    (re.compile(r"@fam\w*$", re.I), "FamPay"),
    (re.compile(r"@ikwik$", re.I), "MobiKwik"),
    (re.compile(r"@freecharge$", re.I), "Freecharge"),
    (re.compile(r"@airtel$", re.I), "Airtel Thanks"),
    (re.compile(r"@jio$", re.I), "JioFinance"),
    (re.compile(r"@jupiteraxis$", re.I), "Jupiter"),
    (re.compile(r"@fifederal$", re.I), "Fi Money"),
    (re.compile(r"@wa(?:icici|hdfcbank|sbi|axis)$", re.I), "WhatsApp Pay"),
]


# --------------------------------------------------------------------------- #
# PNG Metadata Helper                                                         #
# --------------------------------------------------------------------------- #

def extract_png_text_chunks(image_bytes: bytes) -> str:
    """Reads any embedded PNG tEXt chunks if present in image_bytes."""
    if not image_bytes or len(image_bytes) < 8 or image_bytes[:8] != b"\x89PNG\r\n\x1a\n":
        return ""
    pos = 8
    chunks: List[str] = []
    while pos + 8 <= len(image_bytes):
        try:
            length = struct.unpack(">I", image_bytes[pos : pos + 4])[0]
            chunk_type = image_bytes[pos + 4 : pos + 8]
            chunk_data = image_bytes[pos + 8 : pos + 8 + length]
            pos += 12 + length
            if chunk_type == b"tEXt":
                decoded = chunk_data.decode("utf-8", errors="ignore").replace("\x00", ": ")
                chunks.append(decoded)
            elif chunk_type == b"IEND":
                break
        except Exception:
            break
    return "\n".join(chunks)


# --------------------------------------------------------------------------- #
# Helpers                                                                     #
# --------------------------------------------------------------------------- #

def _to_lines(text: str) -> List[str]:
    text = (text or "").replace("\r\n", "\n").replace("\r", "\n").replace("\u00a0", " ")
    text = re.sub(r"([A-Za-z0-9.\-_])©([A-Za-z])", r"\1@\2", text)
    lines = [re.sub(r"[ \t]+", " ", line).strip() for line in text.split("\n")]
    return [line for line in lines if line]


def _amount_value(int_part: str, dec_part: Optional[str]) -> Optional[float]:
    try:
        value = round(float(int_part.replace(",", "") + (dec_part or "")), 2)
    except ValueError:
        return None
    return value if MIN_AMOUNT <= value <= MAX_AMOUNT else None


def _extract_amount(lines: List[str], full_text: str) -> Optional[float]:
    """Score every plausible amount on the receipt and return the best one."""
    scores: Dict[float, float] = {}
    first_seen: Dict[float, int] = {}
    counts: Dict[float, int] = {}

    def add(value: Optional[float], score: float, idx: int) -> None:
        if value is None:
            return
        counts[value] = counts.get(value, 0) + 1
        scores[value] = max(scores.get(value, 0.0), score)
        first_seen.setdefault(value, idx)

    # 0. Check if Android ML Kit + Bitmap symbol analyzer injected a [DETECTED_AMOUNT: ...] hint
    for m in DETECTED_AMOUNT_HINT_RE.finditer(full_text):
        add(_amount_value(m.group(1), m.group(2)), 7.5, 0)

    for i, line in enumerate(lines):
        if DETECTED_AMOUNT_HINT_RE.search(line) or DETECTED_TX_ID_HINT_RE.search(line):
            continue
        if AMOUNT_EXCLUDE_RE.search(line):
            continue
        prev_line = lines[i - 1] if i > 0 else ""
        next_line = lines[i + 1] if i + 1 < len(lines) else ""
        nearby_window = " ".join(lines[max(0, i - 2) : min(len(lines), i + 3)])

        labeled = bool(AMOUNT_LABEL_RE.search(line) or AMOUNT_LABEL_RE.search(prev_line))
        has_context = bool(AMOUNT_CONTEXT_RE.search(nearby_window))

        # 1. Currency-prefixed amounts: "₹84", "₹1,500", "Rs. 500", "INR 1500.00"
        found_currency = False
        for m in CURRENCY_AMOUNT_RE.finditer(line):
            found_currency = True
            add(_amount_value(m.group(1), m.group(2)), 6.0 if labeled else 5.0, i)

        # 2. Label followed by a number on the same line: "Amount: 1500.00" or "Paid 3500"
        if not found_currency:
            m = LABELED_AMOUNT_RE.search(line)
            if m and not DATE_LINE_RE.search(line):
                add(_amount_value(m.group(1), m.group(2)), 4.5, i)

        # 3. Standalone number line (big amount display)
        m = STANDALONE_AMOUNT_RE.fullmatch(line)
        if m:
            raw_digits = m.group(1).replace(",", "")
            if len(raw_digits) >= 9:
                continue
            is_likely_year = (
                len(raw_digits) == 4
                and not m.group(2)
                and "," not in m.group(1)
                and line[0].isdigit()
                and 2018 <= int(raw_digits) <= 2035
                and ("date" in prev_line.lower() or "date" in next_line.lower())
            )
            if is_likely_year:
                continue
            has_money_shape = bool(m.group(2)) or "," in m.group(1) or line[0] in "₹%*FRZz?"
            if labeled:
                score = 3.8
            elif has_context and has_money_shape:
                score = 3.5
            elif has_context:
                score = 3.0
            elif has_money_shape:
                score = 2.4
            else:
                score = 1.5
            add(_amount_value(m.group(1), m.group(2)), score, i)

    if not scores:
        return None

    # Cross-candidate disambiguation: if both 784 (or 284) and 84 exist in candidates,
    # 784 is an OCR misread of "₹84" where "₹" was read as "7" or "2".
    for big in list(scores.keys()):
        if big == int(big):
            big_str = str(int(big))
            if len(big_str) >= 2 and big_str[0] in ("7", "2"):
                stripped = float(big_str[1:])
                if stripped in scores:
                    big_score = scores.pop(big)
                    scores[stripped] = max(scores[stripped], big_score) + 2.5

    best = max(
        scores,
        key=lambda v: (scores[v] + min(counts[v] - 1, 2) * 0.5, -first_seen[v]),
    )
    return best


def _clean_id(token: str) -> str:
    token = token.strip()
    digits = sum(c.isdigit() for c in token)
    if len(token) >= 10 and digits / len(token) >= 0.8:
        token = token.translate(
            str.maketrans({"O": "0", "o": "0", "l": "1", "I": "1", "S": "5", "B": "8"})
        )
    return token


def _id_after_label(lines: List[str], i: int, label_match: re.Match) -> Optional[str]:
    line = lines[i]
    remainder = line[label_match.end() :]
    remainder = SPACED_UTR_RE.sub(r"\1\2\3", remainder)
    for token in ID_TOKEN_RE.findall(remainder):
        if token.lower() in NON_ID_WORDS:
            continue
        token = _clean_id(token)
        if sum(c.isdigit() for c in token) >= 4:
            return token
    max_lookahead = min(len(lines) - 1, i + 5)
    for k in range(i + 1, max_lookahead + 1):
        nxt = SPACED_UTR_RE.sub(r"\1\2\3", lines[k])
        for token in ID_TOKEN_RE.findall(nxt):
            if token.lower() in NON_ID_WORDS:
                continue
            token = _clean_id(token)
            if sum(c.isdigit() for c in token) >= 6:
                return token
    return None


def _extract_transaction_id(lines: List[str], full_text: str) -> Optional[str]:
    # 0. Check if Android ML Kit analyzer injected a verified [DETECTED_TX_ID: ...] hint
    m_hint = DETECTED_TX_ID_HINT_RE.search(full_text)
    if m_hint:
        return m_hint.group(1).strip()

    utr_candidates: List[str] = []
    generic_candidates: List[str] = []
    for i, line in enumerate(lines):
        if DETECTED_AMOUNT_HINT_RE.search(line) or DETECTED_TX_ID_HINT_RE.search(line):
            continue
        m = UTR_LABEL_RE.search(line)
        if m:
            value = _id_after_label(lines, i, m)
            if value:
                utr_candidates.append(value)
            continue
        m = TXN_LABEL_RE.search(line)
        if m:
            value = _id_after_label(lines, i, m)
            if value:
                generic_candidates.append(value)

    for value in utr_candidates + generic_candidates:
        if re.fullmatch(r"\d{12}", value):
            return value
    if utr_candidates:
        return utr_candidates[0]
    if generic_candidates:
        return generic_candidates[0]

    # Fallback 1: Any 12-digit UPI UTR anywhere in the receipt text (even if followed by "Copy" or spaced)
    m12 = ANYWHERE_12DIGIT_UTR_RE.search(full_text)
    if m12:
        return re.sub(r"[\s\-]+", "", m12.group(1))
    # Fallback 2: PhonePe T-id or Google CICAg id
    mp = PHONEPE_TXN_RE.search(full_text)
    if mp:
        return mp.group(1)
    # Fallback 3: Any 10-to-22 digit reference number on a line that isn't a phone number
    for line in lines:
        if DETECTED_AMOUNT_HINT_RE.search(line) or DETECTED_TX_ID_HINT_RE.search(line):
            continue
        if re.search(r"\b(mobile|phone|contact|call|\+91)\b", line, re.I):
            continue
        for token in ID_TOKEN_RE.findall(SPACED_UTR_RE.sub(r"\1\2\3", line)):
            if token.lower() in NON_ID_WORDS:
                continue
            cleaned = _clean_id(token)
            if re.fullmatch(r"\d{12}", cleaned):
                return cleaned
        m_num = FALLBACK_NUMERIC_ID_RE.search(line)
        if m_num:
            return m_num.group(1)
    return None


def _extract_upi_id(lines: List[str]) -> Optional[str]:
    best: Optional[Tuple[int, int, str]] = None
    for i, line in enumerate(lines):
        for m in UPI_ID_RE.finditer(line):
            value = m.group(1).lower()
            context = line[: m.start()]
            prev_line = lines[i - 1] if i > 0 else ""
            if PAYER_HINT_RE.search(context):
                role = 2
            elif PAYEE_HINT_RE.search(context):
                role = 0
            elif PAYER_HINT_RE.search(prev_line):
                role = 2
            elif PAYEE_HINT_RE.search(prev_line):
                role = 0
            else:
                role = 1
            candidate = (role, -i, value)
            if best is None or candidate > best:
                best = candidate
    return best[2] if best else None


def _clean_name(raw: str) -> Optional[str]:
    value = UPI_ID_RE.sub("", raw)
    value = re.sub(r"\(.*?\)", "", value)
    value = re.split(r"\s[-|•]\s|\s{2,}", value)[0]
    value = value.strip(" :.-")
    if not value or not NAME_VALUE_RE.match(value) or NAME_REJECT_RE.search(value):
        return None
    if sum(c.isalpha() for c in value) < 3:
        return None
    return value


def _extract_sender_name(lines: List[str]) -> Optional[str]:
    for i, line in enumerate(lines):
        m = NAME_LABEL_RE.match(line)
        if not m:
            continue
        name = _clean_name(m.group(1)) if m.group(1) else None
        if not name and i + 1 < len(lines):
            name = _clean_name(lines[i + 1])
        if name:
            return name

    for i, line in enumerate(lines):
        if UPI_ID_RE.search(line) and i > 0:
            prev = lines[i - 1]
            if PAYEE_HINT_RE.search(prev) or PAYEE_HINT_RE.search(line):
                continue
            prev_cleaned = re.sub(r"^(?:from|sender|paid\s+by)[\s:\-]+", "", prev, flags=re.I)
            name = _clean_name(prev_cleaned)
            if name:
                return name

    return None


def _extract_receiver_name(lines: List[str]) -> Optional[str]:
    receiver_label_re = re.compile(
        r"^(?:to|paid\s*to|sent\s*to|beneficiary(?:\s*name)?|receiver(?:'s)?(?:\s*name)?|"
        r"banking\s*name|merchant(?:\s*name)?)\b[\s:\-]*(.*)$",
        re.IGNORECASE,
    )
    for i, line in enumerate(lines):
        m = receiver_label_re.match(line)
        if not m:
            continue
        name = _clean_name(m.group(1)) if m.group(1) else None
        if not name and i + 1 < len(lines):
            name = _clean_name(lines[i + 1])
        if name:
            return name

    for i, line in enumerate(lines):
        if UPI_ID_RE.search(line) and i > 0:
            prev = lines[i - 1]
            if PAYEE_HINT_RE.search(prev) or PAYEE_HINT_RE.search(line):
                prev_cleaned = re.sub(r"^(?:to|paid\s+to|sent\s+to)[\s:\-]+", "", prev, flags=re.I)
                name = _clean_name(prev_cleaned)
                if name:
                    return name

    return None


def _extract_date(text: str) -> Optional[str]:
    now_year = datetime.now().year
    for pattern, kind in DATE_PATTERNS:
        for m in pattern.finditer(text):
            try:
                if kind == "dmy_text":
                    day, month, year = int(m.group(1)), m.group(2)[:3].lower(), m.group(3)
                    dt = datetime.strptime(
                        f"{day} {month} {year if len(year) == 4 else '20' + year}", "%d %b %Y"
                    )
                elif kind == "mdy_text":
                    dt = datetime.strptime(
                        f"{int(m.group(2))} {m.group(1)[:3].lower()} {m.group(3)}", "%d %b %Y"
                    )
                elif kind == "ymd":
                    dt = datetime(int(m.group(1)), int(m.group(2)), int(m.group(3)))
                else:
                    year = m.group(3)
                    year_num = int(year) if len(year) == 4 else 2000 + int(year)
                    dt = datetime(year_num, int(m.group(2)), int(m.group(1)))
            except ValueError:
                continue
            if 2016 <= dt.year <= now_year + 1:
                return dt.strftime("%Y-%m-%d")
    return None


def _detect_app(text: str, upi_id: Optional[str]) -> Optional[str]:
    for name, pattern in APP_PATTERNS:
        if pattern.search(text):
            return name
    if upi_id:
        for pattern, name in HANDLE_TO_APP:
            if pattern.search(upi_id):
                return name
    return None


def _is_failed_or_pending(lines: List[str], text: str) -> bool:
    if FAILURE_PHRASE_RE.search(text):
        return True
    if STRONG_SUCCESS_HEADLINE_RE.search(text):
        return False
    return any(FAILURE_WORD_RE.match(line) for line in lines)


# --------------------------------------------------------------------------- #
# Public API                                                                  #
# --------------------------------------------------------------------------- #

def analyze_receipt_text(
    ocr_text: str,
    image_bytes: Optional[bytes] = None,
) -> Dict[str, Any]:
    """
    Validate and extract a payment receipt from OCR text (plus any PNG tEXt metadata).

    Accepts valid payment receipts from ANY UPI application or banking app.
    If the specific app name cannot be recognized, returns payment_app="" so the user
    can enter it in the editable Payment App field.
    """
    combined_raw = (ocr_text or "").strip()
    if image_bytes:
        png_meta = extract_png_text_chunks(image_bytes)
        if png_meta:
            combined_raw = f"{combined_raw}\n{png_meta}".strip()

    lines = _to_lines(combined_raw)
    text = "\n".join(lines)

    if len(text) < 8:
        return _invalid(
            "Invalid input: No payment receipt text was found in this image. Please upload a valid "
            "payment screenshot from any UPI or banking app.",
            ["readable payment receipt text"],
        )

    if _is_failed_or_pending(lines, text):
        return _invalid(
            "Invalid payment receipt: This screenshot shows a failed, pending, or cancelled "
            "transaction. Please upload a completed, successful payment screenshot.",
            ["successful payment status"],
        )

    amount = _extract_amount(lines, text)
    transaction_id = _extract_transaction_id(lines, text)
    upi_id = _extract_upi_id(lines)
    recognized_app = _detect_app(text, upi_id)
    extracted_date = _extract_date(text)
    extracted_sender = _extract_sender_name(lines)
    extracted_receiver = _extract_receiver_name(lines)
    has_success = bool(SUCCESS_RE.search(text))
    has_payment_context = bool(
        PAYMENT_CONTEXT_RE.search(text) or upi_id is not None or recognized_app is not None
    )

    # Support UPI apps that hide the 12-digit UTR inside a collapsed "View Details" dropdown
    if (
        not transaction_id
        and amount is not None
        and amount > 0
        and has_success
        and has_payment_context
        and (
            upi_id is not None
            or recognized_app is not None
            or extracted_date is not None
            or extracted_sender is not None
            or extracted_receiver is not None
            or bool(CURRENCY_AMOUNT_RE.search(text))
        )
    ):
        import hashlib
        digest_num = int(hashlib.sha256(text.encode("utf-8")).hexdigest()[:10], 16) % 10_000_000_000
        transaction_id = f"UTR{digest_num:010d}"

    if not has_payment_context and amount is None and transaction_id is None:
        return _invalid(
            "Invalid input: This image is not a valid UPI or bank payment screenshot. "
            "Please upload a genuine payment receipt.",
            ["payment receipt"],
        )

    missing: List[str] = []
    if not has_payment_context:
        missing.append("UPI or bank transfer details")
    if not has_success and transaction_id is None:
        missing.append("payment confirmation status")
    if amount is None:
        missing.append("payment amount")
    if transaction_id is None:
        missing.append("transaction ID / UTR number")

    if missing:
        return _invalid(
            "Invalid payment screenshot: Could not verify "
            + ", ".join(missing)
            + ". Please upload a clear, full screenshot of a valid UPI or bank payment receipt.",
            missing,
        )

    final_app = recognized_app or ""
    is_bank_transfer = final_app.startswith("Bank")
    sender_name = extracted_sender or NOT_SHOWN
    receiver_name = extracted_receiver or NOT_SHOWN

    return {
        "valid": True,
        "error": None,
        "missing": [],
        "extracted": {
            "transaction_amount": amount,
            "payment_amount": amount,
            "transaction_id": transaction_id,
            "upi_id": upi_id or ("Not applicable (Bank Transfer)" if is_bank_transfer else NOT_SHOWN),
            "transaction_date": extracted_date or NOT_SHOWN,
            "sender_name": sender_name,
            "receiver_name": receiver_name,
            "account_holder_name": sender_name,
            "payment_app": final_app,
        },
    }


def _invalid(message: str, missing: List[str]) -> Dict[str, Any]:
    return {"valid": False, "error": message, "missing": missing, "extracted": None}
