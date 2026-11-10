package com.payvault.upitracker.model

import com.google.gson.annotations.SerializedName

/**
 * Extracted UPI Screenshot details from ML Kit + Backend OCR.
 * Includes strict receipt verification status (`isValid` / `errorMessage`),
 * plus `senderName` (editable) and `receiverName` (read-only).
 */
data class OcrExtractedData(
    @SerializedName("upi_id") val upiId: String = "",
    @SerializedName("transaction_amount") val transactionAmount: Double = 0.0,
    @SerializedName("payment_amount") val paymentAmount: Double = 0.0,
    @SerializedName("transaction_id") val transactionId: String = "",
    @SerializedName("transaction_date") val transactionDate: String = "",
    @SerializedName("account_holder_name") val accountHolderName: String = "",
    @SerializedName("sender_name") val senderName: String = "",
    @SerializedName("receiver_name") val receiverName: String = "",
    @SerializedName("payment_app") val paymentApp: String = "",
    val isValid: Boolean = false,
    val errorMessage: String? = null
)

data class OcrExtractResponse(
    @SerializedName("ok") val ok: Boolean = false,
    @SerializedName("valid") val valid: Boolean = false,
    @SerializedName("extracted") val extracted: OcrExtractedData? = null,
    @SerializedName("error") val error: String? = null,
    @SerializedName("missing") val missing: List<String>? = null
)

/**
 * Confirmation returned to a Regular User upon submitting a screenshot.
 * Contains ZERO stored records from the database (strict Admin-only privacy).
 */
data class SubmissionReceiptResponse(
    @SerializedName("ok") val ok: Boolean,
    @SerializedName("message") val message: String?,
    @SerializedName("error") val error: String?,
    @SerializedName("receipt") val receipt: ReceiptInfo?
)

data class ReceiptInfo(
    @SerializedName("reference_no") val referenceNo: String,
    @SerializedName("flat_number") val flatNumber: String,
    @SerializedName("submitted_at") val submittedAt: String,
    @SerializedName("status") val status: String
)

/**
 * Society Meeting Broadcast Models (Google Messages style read-only feed for residents,
 * send/delete restricted to Admin Vault).
 */
data class SocietyMeetingRecord(
    @SerializedName("id") val id: Int,
    @SerializedName("meeting_date") val meetingDate: String,
    @SerializedName("topic") val topic: String,
    @SerializedName("body") val body: String,
    @SerializedName("created_at") val createdAt: String
)

data class MeetingsListResponse(
    @SerializedName("ok") val ok: Boolean,
    @SerializedName("meetings") val meetings: List<SocietyMeetingRecord>?
)

data class CreateMeetingRequest(
    @SerializedName("meeting_date") val meetingDate: String,
    @SerializedName("topic") val topic: String,
    @SerializedName("body") val body: String
)

data class CreateMeetingResponse(
    @SerializedName("ok") val ok: Boolean,
    @SerializedName("meeting") val meeting: SocietyMeetingRecord?,
    @SerializedName("error") val error: String?
)

/**
 * Admin Authentication Request & Response.
 */
data class AdminLoginRequest(
    @SerializedName("username") val username: String,
    @SerializedName("password") val password: String
)

data class AdminLoginResponse(
    @SerializedName("ok") val ok: Boolean,
    @SerializedName("username") val username: String?,
    @SerializedName("token") val token: String?,
    @SerializedName("error") val error: String?
)

/**
 * Stored Payment Record — Accessible strictly to authenticated Admins.
 */
data class PaymentRecord(
    @SerializedName("id") val id: Int,
    @SerializedName("resident_name") val residentName: String,
    @SerializedName("flat_number") val flatNumber: String,
    @SerializedName("payment_amount") val paymentAmount: Double,
    @SerializedName("upi_id") val upiId: String,
    @SerializedName("transaction_amount") val transactionAmount: Double,
    @SerializedName("transaction_id") val transactionId: String,
    @SerializedName("transaction_date") val transactionDate: String,
    @SerializedName("account_holder_name") val accountHolderName: String,
    @SerializedName("sender_name") val senderName: String? = null,
    @SerializedName("receiver_name") val receiverName: String? = null,
    @SerializedName("payment_app") val paymentApp: String,
    @SerializedName("screenshot_filename") val screenshotFilename: String,
    @SerializedName("verification_status") val verificationStatus: String
)

data class AdminStats(
    @SerializedName("total_entries") val totalEntries: Int,
    @SerializedName("total_payment_amount") val totalPaymentAmount: Double,
    @SerializedName("total_transaction_amount") val totalTransactionAmount: Double,
    @SerializedName("verified_count") val verifiedCount: Int,
    @SerializedName("mismatch_count") val mismatchCount: Int
)

data class AdminPaymentsResponse(
    @SerializedName("ok") val ok: Boolean,
    @SerializedName("payments") val payments: List<PaymentRecord>,
    @SerializedName("stats") val stats: AdminStats
)

/**
 * Supported Export Formats so the Admin can switch between CSV formats and Excel (.xlsx) anytime.
 */
enum class ExportFormatOption(val key: String, val displayLabel: String) {
    STANDARD_CSV("standard_csv", "Standard CSV (UTF-8, Comma)"),
    CORE_6_CSV("core_6_csv", "Strict 6-Column CSV"),
    EXCEL_BOM_CSV("excel_bom_csv", "Excel-Compatible CSV (UTF-8 BOM)"),
    SEMICOLON_CSV("semicolon_csv", "Semicolon-Separated CSV (;)"),
    TSV("tsv", "Tab-Separated Values (.tsv)"),
    PIPE_CSV("pipe_csv", "Pipe-Delimited CSV (|)"),
    ACCOUNTING_CSV("accounting_csv", "Society Accounting & Variance CSV"),
    XLSX("xlsx", "Microsoft Excel Workbook (.xlsx)")
}

enum class ExportColumnOption(val key: String, val label: String, val defaultChecked: Boolean) {
    RESIDENT_NAME("resident_name", "1. Name", true),
    FLAT_NUMBER("flat_number", "2. Flat number", true),
    PAYMENT_AMOUNT("payment_amount", "3. Payment amount", true),
    UPI_ID("upi_id", "4. UPI ID", true),
    TRANSACTION_AMOUNT("transaction_amount", "5. Transaction amount", true),
    TRANSACTION_ID("transaction_id", "6. Transaction ID", true),
    TRANSACTION_DATE("transaction_date", "Date", true),
    SENDER_NAME("sender_name", "Sender name", true),
    RECEIVER_NAME("receiver_name", "Receiver name", true),
    PAYMENT_APP("payment_app", "Payment App", false),
    STATUS("verification_status", "Status", false)
}
