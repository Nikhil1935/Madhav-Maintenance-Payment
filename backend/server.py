"""
Main Flask Backend & API Server for the UPI Payment Proof & Maintenance Tracker.
Enforces strict role isolation:
  - Regular users can ONLY upload screenshots and submit payment proofs (write-only).
  - Only authenticated Admins can view stored entries, inspect screenshots, or export CSV/Excel files.
"""

import os
import re
import uuid
import sqlite3
from functools import wraps
from typing import Optional
from flask import (
    Flask,
    request,
    jsonify,
    session,
    send_from_directory,
    render_template,
    Response,
)
from itsdangerous import URLSafeTimedSerializer, BadSignature, SignatureExpired
from werkzeug.utils import secure_filename

from database import (
    init_db,
    verify_admin,
    insert_payment,
    list_payments_for_admin,
    get_payment_by_id,
    update_payment_admin,
    delete_payment_admin,
    list_meetings,
    insert_meeting,
    delete_meeting_admin,
)
from ocr_engine import analyze_receipt_text
from exporter import (
    AVAILABLE_COLUMNS,
    CSV_FORMATS_METADATA,
    generate_csv_bytes,
    generate_xlsx_bytes,
)

ALLOWED_EXTENSIONS = {"png", "jpg", "jpeg", "webp"}


def create_app(test_config: Optional[dict] = None) -> Flask:
    app = Flask(__name__, template_folder="templates", static_folder="static")
    app.config["SECRET_KEY"] = os.environ.get(
        "UPI_SECRET_KEY", "cupertino-upi-tracker-secret-key-2026"
    )
    app.config["MAX_CONTENT_LENGTH"] = 16 * 1024 * 1024  # 16 MB max screenshot upload
    app.config["DB_PATH"] = os.path.join(os.path.dirname(__file__), "storage", "upi_payments.db")
    app.config["SCREENSHOT_DIR"] = os.path.join(
        os.path.dirname(__file__), "storage", "screenshots"
    )

    if test_config:
        app.config.update(test_config)

    os.makedirs(app.config["SCREENSHOT_DIR"], exist_ok=True)
    init_db(app.config["DB_PATH"])

    serializer = URLSafeTimedSerializer(app.config["SECRET_KEY"])

    def generate_admin_token(username: str) -> str:
        return serializer.dumps({"role": "admin", "username": username}, salt="admin-auth")

    def is_admin_authenticated() -> bool:
        if session.get("is_admin") is True:
            return True
        auth_header = request.headers.get("Authorization", "")
        token = ""
        if auth_header.startswith("Bearer "):
            token = auth_header.split(" ", 1)[1].strip()
        elif request.args.get("token"):
            token = request.args.get("token", "").strip()
        if token:
            try:
                payload = serializer.loads(token, salt="admin-auth", max_age=86400 * 7)
                return payload.get("role") == "admin"
            except (BadSignature, SignatureExpired):
                return False
        return False

    def admin_required(f):
        @wraps(f)
        def decorated(*args, **kwargs):
            if not is_admin_authenticated():
                return (
                    jsonify(
                        {
                            "error": "Unauthorized. All payment records and exports are restricted strictly to the Admin.",
                            "code": "ADMIN_ONLY",
                        }
                    ),
                    401,
                )
            return f(*args, **kwargs)

        return decorated

    def allowed_file(filename: str) -> bool:
        return "." in filename and filename.rsplit(".", 1)[1].lower() in ALLOWED_EXTENSIONS

    # ------------------------------------------------------------------ #
    # UI Entry Route (Cupertino Resident Upload + Protected Admin Vault) #
    # ------------------------------------------------------------------ #

    @app.route("/")
    def index():
        return render_template("index.html")

    # ------------------------------------------------------------------ #
    # Public / Resident Endpoints (Strictly Write-Only, No Data Leak)    #
    # ------------------------------------------------------------------ #

    @app.route("/api/ocr/extract", methods=["POST"])
    def api_ocr_extract():
        """
        Validates that the screenshot's OCR text is a successful payment receipt and returns
        the extracted, read-only fields. Invalid / non-payment screenshots get HTTP 422.
        """
        ocr_text = request.form.get("ocr_text", "")
        file_obj = request.files.get("screenshot")
        image_bytes = file_obj.read() if file_obj else None
        result = analyze_receipt_text(ocr_text, image_bytes=image_bytes)
        if not result["valid"]:
            return (
                jsonify({"ok": False, "valid": False, "error": result["error"], "missing": result["missing"]}),
                422,
            )
        return jsonify({"ok": True, "valid": True, "extracted": result["extracted"]})

    @app.route("/api/payments/submit", methods=["POST"])
    def api_submit_payment():
        """
        Write-only endpoint for residents to submit their payment screenshot and flat details.

        Receipt fields (amount, transaction ID, UPI ID, date, payer name, app) are ALWAYS
        re-derived on the server from the screenshot's OCR text. Any such values sent by the
        client are ignored, so they cannot be edited by the user.
        Does NOT return any stored database records to regular users.
        """
        resident_name = (request.form.get("resident_name") or "").strip()
        flat_number = (request.form.get("flat_number") or "").strip().upper()
        payment_amount_str = (request.form.get("payment_amount") or "").strip()
        ocr_text = request.form.get("ocr_text") or ""

        if not resident_name:
            return jsonify({"error": "Full Name is required."}), 400
        if not flat_number:
            return jsonify({"error": "Flat Number is required (e.g., A-101)."}), 400

        payment_amount: Optional[float] = None
        if payment_amount_str:
            try:
                payment_amount = round(float(payment_amount_str), 2)
                if payment_amount <= 0:
                    raise ValueError()
            except ValueError:
                return jsonify({"error": "Payment Amount must be a valid positive number."}), 400

        file_obj = request.files.get("screenshot")
        if not file_obj or not file_obj.filename:
            return jsonify({"error": "Payment screenshot is required."}), 400
        if not allowed_file(file_obj.filename):
            return jsonify({"error": "Unsupported file format. Please upload PNG, JPG, or WEBP."}), 400

        image_bytes = file_obj.read()
        if not image_bytes:
            return jsonify({"error": "Uploaded screenshot file is empty."}), 400

        analysis = analyze_receipt_text(ocr_text, image_bytes=image_bytes)
        if not analysis["valid"]:
            return (
                jsonify({"ok": False, "valid": False, "error": analysis["error"], "missing": analysis["missing"]}),
                422,
            )
        receipt = analysis["extracted"]
        # Populate payment_amount from screenshot if not edited by resident
        if payment_amount is None:
            payment_amount = receipt["transaction_amount"]

        # Sender name and Payment App are auto-extracted from the screenshot and editable by the user;
        # Receiver name and all other receipt fields remain strictly locked to the screenshot OCR.
        submitted_sender = (
            request.form.get("sender_name")
            or request.form.get("account_holder_name")
            or ""
        ).strip()
        final_sender_name = submitted_sender if submitted_sender else receipt["sender_name"]
        final_receiver_name = receipt["receiver_name"]

        submitted_payment_app = (request.form.get("payment_app") or "").strip()
        final_payment_app = (
            submitted_payment_app
            or (receipt.get("payment_app") or "").strip()
            or "UPI"
        )

        ext = file_obj.filename.rsplit(".", 1)[1].lower()
        saved_filename = f"upi_{uuid.uuid4().hex[:12]}.{ext}"
        save_path = os.path.join(app.config["SCREENSHOT_DIR"], saved_filename)
        with open(save_path, "wb") as f:
            f.write(image_bytes)

        allow_resubmit = (
            "[DETECTED_TX_ID:" in ocr_text
            or (request.form.get("allow_resubmit") or "").strip().lower() == "true"
        )

        try:
            result = insert_payment(
                {
                    "resident_name": resident_name,
                    "flat_number": flat_number,
                    "payment_amount": payment_amount,
                    "upi_id": receipt["upi_id"],
                    "transaction_amount": receipt["transaction_amount"],
                    "transaction_id": receipt["transaction_id"],
                    "transaction_date": receipt["transaction_date"],
                    "account_holder_name": final_sender_name,
                    "sender_name": final_sender_name,
                    "receiver_name": final_receiver_name,
                    "payment_app": final_payment_app,
                    "screenshot_filename": saved_filename,
                },
                db_path=app.config["DB_PATH"],
                allow_resubmit=allow_resubmit,
            )
        except sqlite3.IntegrityError:
            # Remove duplicate screenshot file
            if os.path.exists(save_path):
                os.remove(save_path)
            return (
                jsonify(
                    {
                        "error": f"Duplicate Transaction ID ({receipt['transaction_id']}). This payment has already been submitted."
                    }
                ),
                409,
            )

        # Return only confirmation receipt—no stored records are exposed to regular users
        return (
            jsonify(
                {
                    "ok": True,
                    "message": "Your maintenance receipt is sent to the Madhav admin account.",
                    "receipt": {
                        "reference_no": f"RCPT-{result['id']:04d}",
                        "flat_number": flat_number,
                        "submitted_at": result["created_at"],
                        "status": "Submitted to Admin",
                    },
                }
            ),
            201,
        )

    # ------------------------------------------------------------------ #
    # Society Meetings Endpoints (Read-Only for Residents, Admin Sends)  #
    # ------------------------------------------------------------------ #

    @app.route("/api/meetings", methods=["GET"])
    def api_list_meetings():
        """
        Read-only endpoint for residents to view Society Meeting notifications
        sent by the Admin (styled like Google Messages).
        """
        meetings = list_meetings(db_path=app.config["DB_PATH"])
        return jsonify({"ok": True, "meetings": meetings})

    @app.route("/api/admin/meetings", methods=["POST"])
    @admin_required
    def api_admin_create_meeting():
        """
        Strictly restricted to authenticated Admins inside the Admin Vault.
        Broadcasts a new society meeting message to all residents.
        """
        payload = request.get_json(silent=True) or request.form
        meeting_date = (payload.get("meeting_date") or "").strip()
        topic = (payload.get("topic") or "").strip()
        body = (payload.get("body") or "").strip()

        if not meeting_date or not topic or not body:
            return (
                jsonify({"error": "Meeting Date, Topic/Subject, and Message Body are all required."}),
                400,
            )

        created = insert_meeting(
            meeting_date=meeting_date,
            topic=topic,
            body=body,
            db_path=app.config["DB_PATH"],
        )
        return jsonify({"ok": True, "meeting": created}), 201

    @app.route("/api/admin/meetings/<int:meeting_id>", methods=["DELETE"])
    @admin_required
    def api_admin_delete_meeting(meeting_id: int):
        deleted = delete_meeting_admin(meeting_id, db_path=app.config["DB_PATH"])
        if not deleted:
            return jsonify({"error": "Meeting notification not found."}), 404
        return jsonify({"ok": True, "deleted_id": meeting_id})

    # ------------------------------------------------------------------ #
    # Admin Authentication & Admin-Only Data Endpoints                   #
    # ------------------------------------------------------------------ #

    @app.route("/api/admin/login", methods=["POST"])
    def api_admin_login():
        payload = request.get_json(silent=True) or request.form
        username = (payload.get("username") or "admin").strip()
        password = payload.get("password") or ""

        if verify_admin(username, password, db_path=app.config["DB_PATH"]):
            session["is_admin"] = True
            session["admin_username"] = username
            token = generate_admin_token(username)
            return jsonify(
                {
                    "ok": True,
                    "username": username,
                    "token": token,
                    "message": "Admin authenticated successfully.",
                }
            )
        return jsonify({"error": "Invalid Admin credentials."}), 401

    @app.route("/api/admin/logout", methods=["POST"])
    def api_admin_logout():
        session.clear()
        return jsonify({"ok": True, "message": "Logged out of Admin Vault."})

    @app.route("/api/admin/session", methods=["GET"])
    def api_admin_session():
        return jsonify({"authenticated": is_admin_authenticated()})

    @app.route("/api/admin/export-formats", methods=["GET"])
    @admin_required
    def api_admin_export_formats():
        return jsonify(
            {
                "ok": True,
                "csv_formats": CSV_FORMATS_METADATA,
                "available_columns": AVAILABLE_COLUMNS,
            }
        )

    @app.route("/api/admin/payments", methods=["GET"])
    @admin_required
    def api_admin_list_payments():
        search = request.args.get("search", "")
        status_filter = request.args.get("status", "")
        flat_filter = request.args.get("flat", "")

        records = list_payments_for_admin(
            search=search,
            status_filter=status_filter,
            flat_filter=flat_filter,
            db_path=app.config["DB_PATH"],
        )

        all_records = list_payments_for_admin(db_path=app.config["DB_PATH"])
        total_payment = round(sum(float(r["payment_amount"]) for r in all_records), 2)
        total_tx = round(sum(float(r["transaction_amount"]) for r in all_records), 2)
        verified_count = sum(1 for r in all_records if r["verification_status"] == "Verified")
        mismatch_count = sum(
            1 for r in all_records if r["verification_status"] == "Amount Mismatch"
        )

        return jsonify(
            {
                "ok": True,
                "payments": records,
                "stats": {
                    "total_entries": len(all_records),
                    "total_payment_amount": total_payment,
                    "total_transaction_amount": total_tx,
                    "verified_count": verified_count,
                    "mismatch_count": mismatch_count,
                },
            }
        )

    @app.route("/api/admin/payments/<int:payment_id>", methods=["PATCH"])
    @admin_required
    def api_admin_update_payment(payment_id: int):
        payload = request.get_json(silent=True) or {}
        try:
            updated = update_payment_admin(
                payment_id, payload, db_path=app.config["DB_PATH"]
            )
        except sqlite3.IntegrityError:
            return jsonify({"error": "Transaction ID already exists on another record."}), 409
        except ValueError:
            return jsonify({"error": "Invalid numeric value provided."}), 400

        if not updated:
            return jsonify({"error": "Payment record not found."}), 404
        return jsonify({"ok": True, "payment": updated})

    @app.route("/api/admin/payments/<int:payment_id>", methods=["DELETE"])
    @admin_required
    def api_admin_delete_payment(payment_id: int):
        existing = get_payment_by_id(payment_id, db_path=app.config["DB_PATH"])
        if not existing:
            return jsonify({"error": "Payment record not found."}), 404

        deleted = delete_payment_admin(payment_id, db_path=app.config["DB_PATH"])
        if deleted:
            img_path = os.path.join(
                app.config["SCREENSHOT_DIR"], existing.get("screenshot_filename", "")
            )
            if os.path.isfile(img_path):
                try:
                    os.remove(img_path)
                except OSError:
                    pass
        return jsonify({"ok": True, "deleted_id": payment_id})

    @app.route("/api/admin/screenshots/<path:filename>", methods=["GET"])
    @admin_required
    def api_admin_get_screenshot(filename: str):
        safe_name = secure_filename(filename)
        return send_from_directory(app.config["SCREENSHOT_DIR"], safe_name)

    @app.route("/api/admin/export", methods=["GET"])
    @admin_required
    def api_admin_export():
        """
        Exports all (or filtered) payment records in any requested CSV format or native .xlsx Excel sheet.
        Query params:
          - format: 'standard_csv' | 'core_6_csv' | 'excel_bom_csv' | 'semicolon_csv' | 'tsv' | 'pipe_csv' | 'accounting_csv' | 'custom_csv' | 'xlsx'
          - delimiter: 'comma' | 'semicolon' | 'tab' | 'pipe' (for custom_csv)
          - columns: comma-separated list of column keys
          - date_style: 'iso' | 'dmy' | 'us'
          - search, status, flat: optional filters
        """
        export_format = (request.args.get("format") or "standard_csv").strip().lower()
        custom_delimiter = request.args.get("delimiter")
        columns_param = (request.args.get("columns") or "").strip()
        custom_columns = (
            [c.strip() for c in columns_param.split(",") if c.strip()]
            if columns_param
            else None
        )
        date_style = (request.args.get("date_style") or "iso").strip().lower()

        records = list_payments_for_admin(
            search=request.args.get("search", ""),
            status_filter=request.args.get("status", ""),
            flat_filter=request.args.get("flat", ""),
            db_path=app.config["DB_PATH"],
        )

        # Order chronologically (oldest to newest) for spreadsheet exports
        records_ordered = list(reversed(records))

        if export_format == "xlsx":
            file_bytes, filename, mime = generate_xlsx_bytes(
                records_ordered,
                custom_columns=custom_columns,
                date_style=date_style,
            )
        else:
            file_bytes, filename, mime = generate_csv_bytes(
                records_ordered,
                format_key=export_format,
                custom_delimiter=custom_delimiter,
                custom_columns=custom_columns,
                date_style=date_style,
            )

        return Response(
            file_bytes,
            mimetype=mime,
            headers={
                "Content-Disposition": f'attachment; filename="{filename}"',
                "Cache-Control": "no-store",
            },
        )

    @app.route("/download-apk")
    def download_apk():
        """Allows friends/residents to download the Android APK directly from the server link."""
        project_root = Path(__file__).resolve().parent.parent
        apk_candidates = [
            project_root / "Madhav-Maintenance-Payment.apk",
            project_root / "android-app" / "app" / "build" / "outputs" / "apk" / "debug" / "app-debug.apk",
        ]
        for apk_path in apk_candidates:
            if apk_path.is_file():
                return send_from_directory(
                    str(apk_path.parent),
                    apk_path.name,
                    as_attachment=True,
                    download_name="Madhav-Maintenance-Payment.apk",
                    mimetype="application/vnd.android.package-archive",
                )
        return jsonify({"error": "APK file not found on server."}), 404

    return app


app = create_app()

if __name__ == "__main__":
    port = int(os.environ.get("PORT", 5050))
    app.run(host="0.0.0.0", port=port, debug=True)
