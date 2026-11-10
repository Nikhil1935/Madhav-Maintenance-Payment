"""
Database layer for the UPI Payment Proof & Maintenance Tracker (Madhav Maintenance Payment).
Uses SQLite with strict schema validation and role-isolated access.
"""

import sqlite3
import os
from datetime import datetime
from typing import Dict, List, Optional, Any
from werkzeug.security import generate_password_hash, check_password_hash

DEFAULT_DB_PATH = os.path.join(os.path.dirname(__file__), "storage", "upi_payments.db")


def get_db_connection(db_path: Optional[str] = None) -> sqlite3.Connection:
    path = db_path or os.environ.get("UPI_TRACKER_DB", DEFAULT_DB_PATH)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    conn = sqlite3.connect(path)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA foreign_keys = ON;")
    return conn


def init_db(db_path: Optional[str] = None) -> None:
    conn = get_db_connection(db_path)
    with conn:
        conn.execute(
            """
            CREATE TABLE IF NOT EXISTS admins (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                username TEXT UNIQUE NOT NULL,
                password_hash TEXT NOT NULL,
                created_at TEXT NOT NULL
            );
            """
        )

        conn.execute(
            """
            CREATE TABLE IF NOT EXISTS payments (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                resident_name TEXT NOT NULL,
                flat_number TEXT NOT NULL,
                payment_amount REAL NOT NULL,
                upi_id TEXT NOT NULL,
                transaction_amount REAL NOT NULL,
                transaction_id TEXT UNIQUE NOT NULL,
                transaction_date TEXT NOT NULL,
                account_holder_name TEXT NOT NULL,
                sender_name TEXT NOT NULL DEFAULT 'Not shown on receipt',
                receiver_name TEXT NOT NULL DEFAULT 'Not shown on receipt',
                payment_app TEXT DEFAULT 'UPI',
                screenshot_filename TEXT NOT NULL,
                verification_status TEXT NOT NULL DEFAULT 'Pending',
                admin_notes TEXT DEFAULT '',
                created_at TEXT NOT NULL
            );
            """
        )

        # Ensure sender_name and receiver_name columns exist on older databases
        existing_cols = {
            row["name"]
            for row in conn.execute("PRAGMA table_info(payments);").fetchall()
        }
        if "sender_name" not in existing_cols:
            conn.execute(
                "ALTER TABLE payments ADD COLUMN sender_name TEXT NOT NULL DEFAULT 'Not shown on receipt';"
            )
            conn.execute("UPDATE payments SET sender_name = account_holder_name;")
        if "receiver_name" not in existing_cols:
            conn.execute(
                "ALTER TABLE payments ADD COLUMN receiver_name TEXT NOT NULL DEFAULT 'Not shown on receipt';"
            )

        conn.execute(
            """
            CREATE TABLE IF NOT EXISTS meetings (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                meeting_date TEXT NOT NULL,
                topic TEXT NOT NULL,
                body TEXT NOT NULL,
                created_at TEXT NOT NULL
            );
            """
        )

        # Seed an initial society meeting notification if the table is empty
        cur_m = conn.execute("SELECT COUNT(*) AS cnt FROM meetings;").fetchone()
        if cur_m and cur_m["cnt"] == 0:
            conn.execute(
                """
                INSERT INTO meetings (meeting_date, topic, body, created_at)
                VALUES (?, ?, ?, ?)
                """,
                (
                    "2026-10-28 • 07:00 PM",
                    "Monthly Maintenance & Society General Body Meeting",
                    "Dear Residents of Madhav Apartment, our monthly society meeting will be held in the ground-floor community hall to review maintenance collections, security upgrades, and upcoming festival arrangements. All flat owners and residents are requested to attend on time.",
                    datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
                ),
            )

        # Ensure default admin account exists (admin / Admin@123)
        cur = conn.execute("SELECT id FROM admins WHERE username = ?", ("admin",))
        if cur.fetchone() is None:
            default_pw = os.environ.get("UPI_ADMIN_PASSWORD", "Admin@123")
            conn.execute(
                "INSERT INTO admins (username, password_hash, created_at) VALUES (?, ?, ?)",
                ("admin", generate_password_hash(default_pw), datetime.now().isoformat()),
            )
    conn.close()


def verify_admin(username: str, password: str, db_path: Optional[str] = None) -> bool:
    conn = get_db_connection(db_path)
    try:
        row = conn.execute(
            "SELECT password_hash FROM admins WHERE username = ?", (username.strip(),)
        ).fetchone()
        if not row:
            return False
        return check_password_hash(row["password_hash"], password)
    finally:
        conn.close()


def insert_payment(
    data: Dict[str, Any],
    db_path: Optional[str] = None,
    allow_resubmit: bool = False,
) -> Dict[str, Any]:
    """
    Inserts a new UPI payment record submitted by a resident.
    Automatically detects if Payment Amount matches OCR Transaction Amount.
    If allow_resubmit is True and transaction_id already exists, updates the record smoothly.
    """
    conn = get_db_connection(db_path)
    try:
        payment_amt = round(float(data["payment_amount"]), 2)
        tx_amt = round(float(data["transaction_amount"]), 2)
        tx_id = str(data["transaction_id"]).strip()

        # Automatic verification status check
        if abs(payment_amt - tx_amt) < 0.01:
            status = data.get("verification_status") or "Verified"
        else:
            status = "Amount Mismatch"

        sender_val = (
            str(data.get("sender_name") or data.get("account_holder_name") or "Not shown on receipt").strip()
            or "Not shown on receipt"
        )
        receiver_val = (
            str(data.get("receiver_name") or "Not shown on receipt").strip()
            or "Not shown on receipt"
        )

        now_iso = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
        params = (
            str(data["resident_name"]).strip(),
            str(data["flat_number"]).strip().upper(),
            payment_amt,
            str(data["upi_id"]).strip(),
            tx_amt,
            tx_id,
            str(data["transaction_date"]).strip(),
            sender_val,
            sender_val,
            receiver_val,
            str(data.get("payment_app", "UPI")).strip(),
            str(data["screenshot_filename"]).strip(),
            status,
            str(data.get("admin_notes", "")).strip(),
            now_iso,
        )
        try:
            cur = conn.execute(
                """
                INSERT INTO payments (
                    resident_name,
                    flat_number,
                    payment_amount,
                    upi_id,
                    transaction_amount,
                    transaction_id,
                    transaction_date,
                    account_holder_name,
                    sender_name,
                    receiver_name,
                    payment_app,
                    screenshot_filename,
                    verification_status,
                    admin_notes,
                    created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                params,
            )
            conn.commit()
            payment_id = cur.lastrowid
        except sqlite3.IntegrityError:
            if not allow_resubmit:
                raise
            conn.execute(
                """
                UPDATE payments
                SET resident_name = ?,
                    flat_number = ?,
                    payment_amount = ?,
                    upi_id = ?,
                    transaction_amount = ?,
                    transaction_date = ?,
                    account_holder_name = ?,
                    sender_name = ?,
                    receiver_name = ?,
                    payment_app = ?,
                    screenshot_filename = ?,
                    verification_status = ?,
                    created_at = ?
                WHERE transaction_id = ?
                """,
                (
                    str(data["resident_name"]).strip(),
                    str(data["flat_number"]).strip().upper(),
                    payment_amt,
                    str(data["upi_id"]).strip(),
                    tx_amt,
                    str(data["transaction_date"]).strip(),
                    sender_val,
                    sender_val,
                    receiver_val,
                    str(data.get("payment_app", "UPI")).strip(),
                    str(data["screenshot_filename"]).strip(),
                    status,
                    now_iso,
                    tx_id,
                ),
            )
            conn.commit()
            row = conn.execute(
                "SELECT id FROM payments WHERE transaction_id = ?", (tx_id,)
            ).fetchone()
            payment_id = row["id"] if row else 1

        return {
            "id": payment_id,
            "verification_status": status,
            "created_at": now_iso,
        }
    finally:
        conn.close()


def list_payments_for_admin(
    search: str = "",
    status_filter: str = "",
    flat_filter: str = "",
    db_path: Optional[str] = None,
) -> List[Dict[str, Any]]:
    """
    Strictly for authenticated Admins. Returns all payment records with optional filters.
    """
    conn = get_db_connection(db_path)
    try:
        query = "SELECT * FROM payments WHERE 1=1"
        params: List[Any] = []

        if search.strip():
            q = f"%{search.strip()}%"
            query += """ AND (
                resident_name LIKE ? OR
                flat_number LIKE ? OR
                upi_id LIKE ? OR
                transaction_id LIKE ? OR
                account_holder_name LIKE ? OR
                sender_name LIKE ? OR
                receiver_name LIKE ?
            )"""
            params.extend([q, q, q, q, q, q, q])

        if status_filter.strip() and status_filter.strip().lower() != "all":
            query += " AND LOWER(verification_status) = LOWER(?)"
            params.append(status_filter.strip())

        if flat_filter.strip():
            query += " AND UPPER(flat_number) = UPPER(?)"
            params.append(flat_filter.strip())

        query += " ORDER BY id DESC"
        rows = conn.execute(query, params).fetchall()
        return [dict(r) for r in rows]
    finally:
        conn.close()


def get_payment_by_id(payment_id: int, db_path: Optional[str] = None) -> Optional[Dict[str, Any]]:
    conn = get_db_connection(db_path)
    try:
        row = conn.execute("SELECT * FROM payments WHERE id = ?", (payment_id,)).fetchone()
        return dict(row) if row else None
    finally:
        conn.close()


def update_payment_admin(
    payment_id: int, updates: Dict[str, Any], db_path: Optional[str] = None
) -> Optional[Dict[str, Any]]:
    conn = get_db_connection(db_path)
    try:
        existing = conn.execute("SELECT * FROM payments WHERE id = ?", (payment_id,)).fetchone()
        if not existing:
            return None

        allowed_fields = {
            "resident_name",
            "flat_number",
            "payment_amount",
            "upi_id",
            "transaction_amount",
            "transaction_id",
            "transaction_date",
            "account_holder_name",
            "sender_name",
            "receiver_name",
            "payment_app",
            "verification_status",
            "admin_notes",
        }
        set_clauses = []
        values = []
        for k, v in updates.items():
            if k in allowed_fields and v is not None:
                set_clauses.append(f"{k} = ?")
                if k in ("payment_amount", "transaction_amount"):
                    values.append(round(float(v), 2))
                elif k == "flat_number":
                    values.append(str(v).strip().upper())
                else:
                    values.append(str(v).strip())

        if set_clauses:
            values.append(payment_id)
            conn.execute(
                f"UPDATE payments SET {', '.join(set_clauses)} WHERE id = ?",
                values,
            )
            conn.commit()

        updated = conn.execute("SELECT * FROM payments WHERE id = ?", (payment_id,)).fetchone()
        return dict(updated) if updated else None
    finally:
        conn.close()


def delete_payment_admin(payment_id: int, db_path: Optional[str] = None) -> bool:
    conn = get_db_connection(db_path)
    try:
        cur = conn.execute("DELETE FROM payments WHERE id = ?", (payment_id,))
        conn.commit()
        return cur.rowcount > 0
    finally:
        conn.close()


# --------------------------------------------------------------------------- #
# Society Meetings Broadcasts (Read-Only for Residents, Send/Delete by Admin) #
# --------------------------------------------------------------------------- #

def list_meetings(db_path: Optional[str] = None) -> List[Dict[str, Any]]:
    """Returns all society meeting messages in chronological order (Google Messages style)."""
    conn = get_db_connection(db_path)
    try:
        rows = conn.execute("SELECT * FROM meetings ORDER BY id ASC").fetchall()
        return [dict(r) for r in rows]
    finally:
        conn.close()


def insert_meeting(
    meeting_date: str, topic: str, body: str, db_path: Optional[str] = None
) -> Dict[str, Any]:
    """Strictly for authenticated Admins to broadcast a new society meeting notification."""
    conn = get_db_connection(db_path)
    try:
        now_iso = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
        cur = conn.execute(
            """
            INSERT INTO meetings (meeting_date, topic, body, created_at)
            VALUES (?, ?, ?, ?)
            """,
            (meeting_date.strip(), topic.strip(), body.strip(), now_iso),
        )
        conn.commit()
        row = conn.execute("SELECT * FROM meetings WHERE id = ?", (cur.lastrowid,)).fetchone()
        return dict(row)
    finally:
        conn.close()


def delete_meeting_admin(meeting_id: int, db_path: Optional[str] = None) -> bool:
    conn = get_db_connection(db_path)
    try:
        cur = conn.execute("DELETE FROM meetings WHERE id = ?", (meeting_id,))
        conn.commit()
        return cur.rowcount > 0
    finally:
        conn.close()
