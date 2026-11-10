package com.payvault.upitracker.network

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.payvault.upitracker.model.AdminStats
import com.payvault.upitracker.model.ExportColumnOption
import com.payvault.upitracker.model.ExportFormatOption
import com.payvault.upitracker.model.PaymentRecord
import com.payvault.upitracker.model.SocietyMeetingRecord
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import kotlin.math.abs

/**
 * On-Device Local Admin Vault Store that mirrors & syncs with the Flask backend.
 * Ensures that:
 *   1. Submitting a verified payment receipt on the phone ALWAYS succeeds smoothly (zero false errors),
 *      even if the laptop backend is offline or if the same screenshot is tested multiple times.
 *   2. All submitted records, proof screenshots, Verify/Unverify actions, CSV exports, and Society
 *      Meetings are immediately available in the mobile Admin Vault.
 */
object LocalAndroidVaultStore {

    private const val PREFS_NAME = "madhav_local_vault_store"
    private const val KEY_PAYMENTS = "stored_payments_json"
    private const val KEY_MEETINGS = "stored_meetings_json"
    private val gson = Gson()

    fun saveScreenshotFile(context: Context, imageBytes: ByteArray): String {
        val dir = File(context.filesDir, "screenshots")
        if (!dir.exists()) dir.mkdirs()
        val fileName = "local_upi_${UUID.randomUUID().toString().replace("-", "").take(12)}.png"
        val outFile = File(dir, fileName)
        FileOutputStream(outFile).use { it.write(imageBytes) }
        return fileName
    }

    fun getLocalScreenshotFile(context: Context, screenshotFilename: String): File? {
        val dir = File(context.filesDir, "screenshots")
        val f = File(dir, screenshotFilename)
        return if (f.exists()) f else null
    }

    @Synchronized
    fun getLocalPayments(context: Context): MutableList<PaymentRecord> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_PAYMENTS, null) ?: return mutableListOf()
        return try {
            val type = object : TypeToken<MutableList<PaymentRecord>>() {}.type
            gson.fromJson<MutableList<PaymentRecord>>(json, type) ?: mutableListOf()
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    @Synchronized
    private fun saveLocalPayments(context: Context, list: List<PaymentRecord>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_PAYMENTS, gson.toJson(list)).apply()
    }

    @Synchronized
    fun recordOrUpdatePayment(
        context: Context,
        residentName: String,
        flatNumber: String,
        paymentAmount: Double,
        upiId: String,
        transactionAmount: Double,
        transactionId: String,
        transactionDate: String,
        senderName: String,
        receiverName: String,
        paymentApp: String,
        screenshotFilename: String
    ): PaymentRecord {
        val current = getLocalPayments(context)
        val cleanTxId = transactionId.trim()
        val status = if (abs(paymentAmount - transactionAmount) < 0.01) "Verified" else "Amount Mismatch"
        val cleanSender = senderName.trim().ifBlank { "Not shown on receipt" }
        val cleanReceiver = receiverName.trim().ifBlank { "Not shown on receipt" }
        val cleanApp = paymentApp.trim().ifBlank { "UPI" }

        val existingIndex = current.indexOfFirst { it.transactionId.equals(cleanTxId, ignoreCase = true) }
        val recordId = if (existingIndex >= 0) {
            current[existingIndex].id
        } else {
            (current.maxOfOrNull { it.id } ?: 0) + 1
        }

        val record = PaymentRecord(
            id = recordId,
            residentName = residentName.trim(),
            flatNumber = flatNumber.trim().uppercase(),
            paymentAmount = paymentAmount,
            upiId = upiId.trim().ifBlank { "Not shown on receipt" },
            transactionAmount = transactionAmount,
            transactionId = cleanTxId,
            transactionDate = transactionDate.trim().ifBlank { "Not shown on receipt" },
            accountHolderName = cleanSender,
            senderName = cleanSender,
            receiverName = cleanReceiver,
            paymentApp = cleanApp,
            screenshotFilename = screenshotFilename,
            verificationStatus = status
        )

        if (existingIndex >= 0) {
            current[existingIndex] = record
        } else {
            current.add(0, record)
        }
        saveLocalPayments(context, current)
        return record
    }

    @Synchronized
    fun updateLocalPaymentStatus(context: Context, paymentId: Int, nextStatus: String): Boolean {
        val current = getLocalPayments(context)
        val idx = current.indexOfFirst { it.id == paymentId }
        if (idx < 0) return false
        current[idx] = current[idx].copy(verificationStatus = nextStatus)
        saveLocalPayments(context, current)
        return true
    }

    @Synchronized
    fun deleteLocalPayment(context: Context, paymentId: Int): Boolean {
        val current = getLocalPayments(context)
        val removed = current.removeAll { it.id == paymentId }
        if (removed) saveLocalPayments(context, current)
        return removed
    }

    fun getMergedPaymentsAndStats(
        context: Context,
        remotePayments: List<PaymentRecord>,
        search: String,
        statusFilter: String
    ): Pair<List<PaymentRecord>, AdminStats> {
        val local = getLocalPayments(context)
        val byTxId = linkedMapOf<String, PaymentRecord>()
        for (r in remotePayments) {
            byTxId[r.transactionId.uppercase()] = r
        }
        for (l in local) {
            val key = l.transactionId.uppercase()
            if (!byTxId.containsKey(key)) {
                byTxId[key] = l
            }
        }
        val mergedAll = byTxId.values.toList()
        val q = search.trim().lowercase()
        val filtered = mergedAll.filter { rec ->
            val matchesSearch = q.isEmpty() ||
                rec.residentName.lowercase().contains(q) ||
                rec.flatNumber.lowercase().contains(q) ||
                rec.upiId.lowercase().contains(q) ||
                rec.transactionId.lowercase().contains(q) ||
                rec.accountHolderName.lowercase().contains(q) ||
                rec.senderName.orEmpty().lowercase().contains(q) ||
                rec.receiverName.orEmpty().lowercase().contains(q) ||
                rec.paymentApp.lowercase().contains(q)
            val matchesStatus = statusFilter.isBlank() ||
                statusFilter.equals("all", ignoreCase = true) ||
                rec.verificationStatus.equals(statusFilter, ignoreCase = true)
            matchesSearch && matchesStatus
        }

        val stats = AdminStats(
            totalEntries = mergedAll.size,
            totalPaymentAmount = mergedAll.sumOf { it.paymentAmount },
            totalTransactionAmount = mergedAll.sumOf { it.transactionAmount },
            verifiedCount = mergedAll.count { it.verificationStatus.equals("Verified", ignoreCase = true) },
            mismatchCount = mergedAll.count { !it.verificationStatus.equals("Verified", ignoreCase = true) }
        )
        return filtered to stats
    }

    fun generateLocalCsvPreview(
        records: List<PaymentRecord>,
        format: ExportFormatOption,
        delimiterOverride: String,
        selectedColumns: Set<String>
    ): String {
        val delim = when {
            delimiterOverride == "semicolon" || format == ExportFormatOption.SEMICOLON_CSV -> ";"
            delimiterOverride == "tab" || format == ExportFormatOption.TSV -> "\t"
            delimiterOverride == "pipe" || format == ExportFormatOption.PIPE_CSV -> "|"
            else -> ","
        }
        val cols = ExportColumnOption.entries.filter { selectedColumns.contains(it.key) }
        val sb = StringBuilder()
        sb.append(cols.joinToString(delim) { it.label }).append("\n")
        for (rec in records.reversed()) {
            val row = cols.map { col ->
                val raw = when (col) {
                    ExportColumnOption.RESIDENT_NAME -> rec.residentName
                    ExportColumnOption.FLAT_NUMBER -> rec.flatNumber
                    ExportColumnOption.PAYMENT_AMOUNT -> "%.2f".format(Locale.US, rec.paymentAmount)
                    ExportColumnOption.UPI_ID -> rec.upiId
                    ExportColumnOption.TRANSACTION_AMOUNT -> "%.2f".format(Locale.US, rec.transactionAmount)
                    ExportColumnOption.TRANSACTION_ID -> rec.transactionId
                    ExportColumnOption.TRANSACTION_DATE -> rec.transactionDate
                    ExportColumnOption.SENDER_NAME -> rec.senderName ?: rec.accountHolderName
                    ExportColumnOption.RECEIVER_NAME -> rec.receiverName ?: "Not shown on receipt"
                    ExportColumnOption.PAYMENT_APP -> rec.paymentApp
                    ExportColumnOption.STATUS -> rec.verificationStatus
                }
                if (raw.contains(delim) || raw.contains("\"")) {
                    "\"${raw.replace("\"", "\"\"")}\""
                } else {
                    raw
                }
            }
            sb.append(row.joinToString(delim)).append("\n")
        }
        return sb.toString()
    }

    @Synchronized
    fun getLocalMeetings(context: Context): MutableList<SocietyMeetingRecord> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_MEETINGS, null)
        if (json == null) {
            val defaultMeeting = mutableListOf(
                SocietyMeetingRecord(
                    id = 1,
                    meetingDate = "2026-11-01 • 07:00 PM",
                    topic = "Monthly Maintenance & Diwali Preparation Review",
                    body = "Dear Residents of Madhav Apartment, our monthly general body meeting will be held in the Ground Floor Society Hall at 7:00 PM. Agenda: October maintenance ledger review, security gate upgrades, and festive lighting arrangements.",
                    createdAt = "2026-10-20 18:00:00"
                )
            )
            saveLocalMeetings(context, defaultMeeting)
            return defaultMeeting
        }
        return try {
            val type = object : TypeToken<MutableList<SocietyMeetingRecord>>() {}.type
            gson.fromJson<MutableList<SocietyMeetingRecord>>(json, type) ?: mutableListOf()
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    @Synchronized
    private fun saveLocalMeetings(context: Context, list: List<SocietyMeetingRecord>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_MEETINGS, gson.toJson(list)).apply()
    }

    @Synchronized
    fun addLocalMeeting(
        context: Context,
        meetingDate: String,
        topic: String,
        body: String
    ): SocietyMeetingRecord {
        val current = getLocalMeetings(context)
        val nextId = (current.maxOfOrNull { it.id } ?: 0) + 1
        val nowStr = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        val rec = SocietyMeetingRecord(
            id = nextId,
            meetingDate = meetingDate.trim(),
            topic = topic.trim(),
            body = body.trim(),
            createdAt = nowStr
        )
        current.add(0, rec)
        saveLocalMeetings(context, current)
        return rec
    }

    @Synchronized
    fun deleteLocalMeeting(context: Context, meetingId: Int) {
        val current = getLocalMeetings(context)
        if (current.removeAll { it.id == meetingId }) {
            saveLocalMeetings(context, current)
        }
    }
}
