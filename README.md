# Madhav Maintenance Payment

![Kotlin](https://img.shields.io/badge/Kotlin-0095D5?style=for-the-badge&logo=kotlin&logoColor=white)
![Android](https://img.shields.io/badge/Android-3DDC84?style=for-the-badge&logo=android&logoColor=white)
![Python](https://img.shields.io/badge/Python-3776AB?style=for-the-badge&logo=python&logoColor=white)
![Flask](https://img.shields.io/badge/Flask-000000?style=for-the-badge&logo=flask&logoColor=white)
![SQLite](https://img.shields.io/badge/SQLite-07405E?style=for-the-badge&logo=sqlite&logoColor=white)

An end-to-end **UPI Payment Screenshot Tracker** built for modern housing societies. It features an **Apple Cupertino-inspired UI**, **automated OCR receipt extraction**, **strict Admin-only data isolation**, and a **Multi-Format CSV & Native Excel (`.xlsx`) Export Studio**.

---

## 🏗 Architecture Overview

### 1. Backend & Cupertino Web/Admin Portal (`backend/`)
Built with **Python (Flask)** + **SQLite** with zero external binary dependencies required for Excel/CSV generation.
- **Strict Role Isolation**:
  - **Regular Users (Residents)** have **write-only** access. They can upload their Google Pay, PhonePe, BHIM UPI, or bank transaction screenshots along with their **Full Name**, **Flat Number**, and **Payment Amount**, receiving a submission receipt—with **zero visibility** into stored records.
  - **Administrators** authenticate via a secure login session to unlock the **Admin Vault**, where they can view all submissions, verify amounts, and export data.

### 2. Android Application (`android-app/`)
Built natively in **Kotlin** utilizing modern Android development practices.
- **Jetpack Compose** for a fluid, reactive UI.
- **Google ML Kit Text Recognition v2** for on-device OCR.
- **Retrofit** for seamless REST API communication with the backend.
- Supports the Android **Share Intent (`Intent.ACTION_SEND`)** so residents can share payment screenshots directly from their UPI apps into the tracker.

---

## 📊 Exportable Columns & Multi-Format Options

Each entry securely records:
`Name` | `Flat number` | `Payment amount` | `UPI ID` | `Transaction amount` | `Transaction ID` | `Date` | `Account holder's name`

**Supported Export Formats (Switchable by Admin):**
- `standard_csv`: Standard Comma-Separated CSV (UTF-8)
- `core_6_csv`: Strict 6-Column CSV
- `excel_bom_csv`: Microsoft Excel-Optimized CSV (`UTF-8 with BOM`)
- `semicolon_csv`: Semicolon-Separated CSV (`;`) for European/ERP tools
- `tsv`: Tab-Separated Values (`.tsv`)
- `pipe_csv`: Pipe-Delimited CSV (`|`)
- `accounting_csv`: Society Accounting & Variance Reconciliation CSV
- `custom_csv`: Dynamic column selection, delimiter override, and date format customization
- `xlsx`: Native OpenXML Microsoft Excel Workbook (`.xlsx`)

---

## 🚀 Quick Start

### 1. Run the Backend & Web Portal
Navigate to the `backend/` directory and run the Flask server:
```bash
cd backend
python3 server.py
```
Open `http://127.0.0.1:5050` in your browser:
- **Resident Upload Tab**: Upload any UPI screenshot or click "Quick Test Receipt" to auto-generate a realistic screenshot and watch OCR populate the fields.
- **Admin Vault Tab**: Sign in with `admin` / `Admin@123` to view and export submissions.

### 2. Run the Automated Test Suite
To ensure backend integrity, run the pytest suite:
```bash
python3 -m pytest backend/test_app.py -v
```

### 3. Open the Android App
Open the `android-app/` directory in **Android Studio**. Build and run the app on your emulator or physical device. The emulator connects automatically to `http://10.0.2.2:5050/`.

---

## 📄 License
This project is open-source and available under the [MIT License](LICENSE).
