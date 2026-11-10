package com.payvault.upitracker.network

import com.payvault.upitracker.BuildConfig
import com.payvault.upitracker.model.AdminLoginRequest
import com.payvault.upitracker.model.AdminLoginResponse
import com.payvault.upitracker.model.AdminPaymentsResponse
import com.payvault.upitracker.model.CreateMeetingRequest
import com.payvault.upitracker.model.CreateMeetingResponse
import com.payvault.upitracker.model.MeetingsListResponse
import com.payvault.upitracker.model.OcrExtractResponse
import com.payvault.upitracker.model.SubmissionReceiptResponse
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Multipart
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query

interface PayVaultApiService {

    /**
     * Backend OCR fallback/assist endpoint for extracting UPI details from screenshot bytes.
     */
    @Multipart
    @POST("api/ocr/extract")
    suspend fun extractOcrFromScreenshot(
        @Part("ocr_text") ocrText: RequestBody,
        @Part("resident_name") residentName: RequestBody,
        @Part("payment_amount") paymentAmount: RequestBody,
        @Part screenshot: MultipartBody.Part
    ): Response<OcrExtractResponse>

    /**
     * Write-only endpoint for Regular Users to submit their UPI payment screenshot and flat details.
     */
    @Multipart
    @POST("api/payments/submit")
    suspend fun submitResidentPayment(
        @Part("resident_name") residentName: RequestBody,
        @Part("flat_number") flatNumber: RequestBody,
        @Part("payment_amount") paymentAmount: RequestBody,
        @Part("sender_name") senderName: RequestBody,
        @Part("receiver_name") receiverName: RequestBody,
        @Part("upi_id") upiId: RequestBody,
        @Part("transaction_amount") transactionAmount: RequestBody,
        @Part("transaction_id") transactionId: RequestBody,
        @Part("transaction_date") transactionDate: RequestBody,
        @Part("account_holder_name") accountHolderName: RequestBody,
        @Part("payment_app") paymentApp: RequestBody,
        @Part("ocr_text") ocrText: RequestBody,
        @Part screenshot: MultipartBody.Part
    ): Response<SubmissionReceiptResponse>

    /**
     * Read-only endpoint for all residents to view Society Meeting notifications (Google Messages style).
     */
    @GET("api/meetings")
    suspend fun getSocietyMeetings(): Response<MeetingsListResponse>

    /**
     * Admin-only endpoint to broadcast a new Society Meeting notification.
     */
    @POST("api/admin/meetings")
    suspend fun createSocietyMeeting(
        @Header("Authorization") bearerToken: String,
        @Body body: CreateMeetingRequest
    ): Response<CreateMeetingResponse>

    /**
     * Admin-only endpoint to delete a Society Meeting notification.
     */
    @DELETE("api/admin/meetings/{id}")
    suspend fun deleteSocietyMeeting(
        @Header("Authorization") bearerToken: String,
        @Path("id") meetingId: Int
    ): Response<ResponseBody>

    /**
     * Admin Authentication — unlocks the Admin Vault and returns a signed Bearer token.
     */
    @POST("api/admin/login")
    suspend fun adminLogin(
        @Body credentials: AdminLoginRequest
    ): Response<AdminLoginResponse>

    /**
     * Admin-only endpoint — fetches all stored UPI payment records and KPI statistics.
     */
    @GET("api/admin/payments")
    suspend fun getAdminPayments(
        @Header("Authorization") bearerToken: String,
        @Query("search") search: String = "",
        @Query("status") status: String = "all"
    ): Response<AdminPaymentsResponse>

    /**
     * Admin-only endpoint — updates verification status of a payment record.
     */
    @PATCH("api/admin/payments/{id}")
    suspend fun updatePaymentStatus(
        @Header("Authorization") bearerToken: String,
        @Path("id") paymentId: Int,
        @Body body: Map<String, String>
    ): Response<ResponseBody>

    /**
     * Admin-only endpoint — deletes a payment record.
     */
    @DELETE("api/admin/payments/{id}")
    suspend fun deletePaymentRecord(
        @Header("Authorization") bearerToken: String,
        @Path("id") paymentId: Int
    ): Response<ResponseBody>

    /**
     * Admin-only Multi-Format CSV & Native Excel (.xlsx) Export endpoint.
     */
    @GET("api/admin/export")
    suspend fun exportPaymentsData(
        @Header("Authorization") bearerToken: String,
        @Query("format") format: String,
        @Query("delimiter") delimiter: String? = null,
        @Query("date_style") dateStyle: String = "iso",
        @Query("columns") columns: String? = null,
        @Query("search") search: String? = null,
        @Query("status") status: String? = null
    ): Response<ResponseBody>
}

object PayVaultApiClient {
    @Volatile
    var currentBaseUrl: String = BuildConfig.BASE_API_URL
        private set

    @Volatile
    private var retrofitInstance: PayVaultApiService = buildService(currentBaseUrl)

    val api: PayVaultApiService
        get() = retrofitInstance

    fun updateBaseUrl(newUrl: String): Boolean {
        val cleaned = newUrl.trim().let { if (it.endsWith("/")) it else "$it/" }
        if (!cleaned.startsWith("http://") && !cleaned.startsWith("https://")) {
            return false
        }
        return try {
            retrofitInstance = buildService(cleaned)
            currentBaseUrl = cleaned
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun buildService(baseUrl: String): PayVaultApiService {
        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(PayVaultApiService::class.java)
    }
}
