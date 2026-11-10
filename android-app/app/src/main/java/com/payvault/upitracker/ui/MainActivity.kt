package com.payvault.upitracker.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.payvault.upitracker.R
import com.payvault.upitracker.model.*
import com.payvault.upitracker.network.LocalAndroidVaultStore
import com.payvault.upitracker.network.PayVaultApiClient
import com.payvault.upitracker.ocr.UpiScreenshotOcrAnalyzer
import com.payvault.upitracker.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Check if launched via "Share Screenshot" from Google Pay / PhonePe / BHIM
        val sharedImageUri: Uri? = if (intent?.action == Intent.ACTION_SEND && intent.type?.startsWith("image/") == true) {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        } else {
            null
        }

        val prefs = getSharedPreferences("madhav_prefs", Context.MODE_PRIVATE)
        val initialDark = prefs.getBoolean("dark_mode", false)

        setContent {
            var isDarkMode by remember { mutableStateOf(initialDark) }

            CupertinoPayVaultTheme(darkTheme = isDarkMode) {
                PayVaultRootScreen(
                    initialSharedUri = sharedImageUri,
                    isDarkMode = isDarkMode,
                    onToggleDarkMode = { newValue ->
                        isDarkMode = newValue
                        prefs.edit().putBoolean("dark_mode", newValue).apply()
                    }
                )
            }
        }
    }
}

@Composable
fun PayVaultRootScreen(
    initialSharedUri: Uri? = null,
    isDarkMode: Boolean,
    onToggleDarkMode: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val palette = LocalPayVaultPalette.current

    // Main screen mode: "home" (Resident Upload + Society Meetings) or "admin" (Admin Vault)
    var currentScreen by remember { mutableStateOf("home") }
    // Home section tab: "payment" (UPI/Bank Screenshot) or "meetings" (Society Meetings)
    var homeSectionTab by remember { mutableStateOf("payment") }

    var showTopLeftMenu by remember { mutableStateOf(false) }
    var showServerSettings by remember { mutableStateOf(false) }
    var serverUrlInput by remember { mutableStateOf(PayVaultApiClient.currentBaseUrl) }

    Scaffold(
        containerColor = palette.groupedBg,
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding(),
        topBar = {
            Surface(
                color = palette.cardBg,
                shadowElevation = 2.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    // 1(a), 1(b), 2(a-c), 6: Food-Delivery-Style Header with Top-Left 3-Dot Menu & New Logo
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            // 2(a) Top-Left Three-Dot Menu Button
                            Box {
                                Surface(
                                    color = palette.groupedBg,
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .clickable { showTopLeftMenu = true }
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text(
                                            text = "⋮",
                                            fontSize = 20.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = palette.textPrimary
                                        )
                                    }
                                }

                                DropdownMenu(
                                    expanded = showTopLeftMenu,
                                    onDismissRequest = { showTopLeftMenu = false },
                                    modifier = Modifier
                                        .background(palette.cardBg)
                                        .width(245.dp)
                                ) {
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                "🏠  Home (Resident Portal)",
                                                fontWeight = FontWeight.SemiBold,
                                                color = palette.textPrimary,
                                                fontSize = 14.sp
                                            )
                                        },
                                        onClick = {
                                            currentScreen = "home"
                                            homeSectionTab = "payment"
                                            showTopLeftMenu = false
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                "💬  Society Meetings",
                                                fontWeight = FontWeight.SemiBold,
                                                color = palette.textPrimary,
                                                fontSize = 14.sp
                                            )
                                        },
                                        onClick = {
                                            currentScreen = "home"
                                            homeSectionTab = "meetings"
                                            showTopLeftMenu = false
                                        }
                                    )
                                    // 2(b) Go to Admin Vault from Top-Left Menu
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                "🔒  Admin Vault",
                                                fontWeight = FontWeight.SemiBold,
                                                color = AppleBlue,
                                                fontSize = 14.sp
                                            )
                                        },
                                        onClick = {
                                            currentScreen = "admin"
                                            showTopLeftMenu = false
                                        }
                                    )
                                    HorizontalDivider(color = palette.hairline)
                                    // 2(c) Dark Mode Toggle Button inside Menu Bar
                                    DropdownMenuItem(
                                        text = {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    text = if (isDarkMode) "☀️  Dark Mode (On)" else "🌙  Dark Mode (Off)",
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = palette.textPrimary,
                                                    fontSize = 14.sp
                                                )
                                                Switch(
                                                    checked = isDarkMode,
                                                    onCheckedChange = { onToggleDarkMode(it) }
                                                )
                                            }
                                        },
                                        onClick = {
                                            onToggleDarkMode(!isDarkMode)
                                        }
                                    )
                                    HorizontalDivider(color = palette.hairline)
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                "⚙️  Server IP Settings",
                                                fontWeight = FontWeight.Medium,
                                                color = palette.textSecondary,
                                                fontSize = 13.5.sp
                                            )
                                        },
                                        onClick = {
                                            showServerSettings = !showServerSettings
                                            showTopLeftMenu = false
                                        }
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.width(10.dp))

                            // 6. Official Madhav Apartment Logo
                            Image(
                                painter = painterResource(id = R.drawable.app_logo),
                                contentDescription = "Madhav Apartment Logo",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .border(1.5.dp, MadhavGold, CircleShape)
                            )

                            Spacer(modifier = Modifier.width(10.dp))

                            // 1(b) Food-Delivery-Style Proportional Title Block
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "MADHAV APARTMENT",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    letterSpacing = 0.8.sp,
                                    color = AppleBlue,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "Madhav Maintenance Payment",
                                    fontSize = 14.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = palette.textPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        if (currentScreen == "admin") {
                            TextButton(
                                onClick = { currentScreen = "home" },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = "← Home",
                                    color = AppleBlue,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 13.sp
                                )
                            }
                        }
                    }

                    // Optional Collapsible Server IP Settings Drawer
                    AnimatedVisibility(visible = showServerSettings) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 10.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(palette.groupedBg)
                                .padding(12.dp)
                        ) {
                            Text(
                                text = "Backend Server URL (Mac Wi-Fi IP or USB):",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = palette.textSecondary
                            )
                            OutlinedTextField(
                                value = serverUrlInput,
                                onValueChange = { serverUrlInput = it },
                                singleLine = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 6.dp)
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        serverUrlInput = "http://10.144.94.94:5050/"
                                        PayVaultApiClient.updateBaseUrl(serverUrlInput)
                                        Toast.makeText(context, "Set to Mac Wi-Fi IP", Toast.LENGTH_SHORT).show()
                                    },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("Wi-Fi IP", fontSize = 11.sp)
                                }
                                OutlinedButton(
                                    onClick = {
                                        serverUrlInput = "http://127.0.0.1:5050/"
                                        PayVaultApiClient.updateBaseUrl(serverUrlInput)
                                        Toast.makeText(context, "Set to USB (adb reverse)", Toast.LENGTH_SHORT).show()
                                    },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("USB Cable", fontSize = 11.sp)
                                }
                                Button(
                                    onClick = {
                                        if (PayVaultApiClient.updateBaseUrl(serverUrlInput)) {
                                            showServerSettings = false
                                            Toast.makeText(context, "Saved: ${PayVaultApiClient.currentBaseUrl}", Toast.LENGTH_SHORT).show()
                                        } else {
                                            Toast.makeText(context, "URL must start with http://", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = AppleBlue),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("Apply", fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (currentScreen == "home") {
                ResidentHomeScreen(
                    initialSharedUri = initialSharedUri,
                    activeSectionTab = homeSectionTab,
                    onChangeSectionTab = { homeSectionTab = it }
                )
            } else {
                AdminVaultScreen()
            }
        }
    }
}

@Composable
fun SegmentedTabButton(
    title: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val palette = LocalPayVaultPalette.current
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) palette.cardBg else Color.Transparent)
            .clickable { onClick() }
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = title,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (selected) palette.textPrimary else palette.textSecondary
        )
    }
}

/**
 * 3 & 4: Home Page Content:
 *   (a) Application name in top header
 *   (b) Resident details (with editable Payment Amount)
 *   (c) UPI / Bank payment screenshot tab (paired with Society Meetings tab)
 *   (d) Verified receipt details block (Sender Name editable, Receiver Name & other fields locked)
 */
@Composable
fun ResidentHomeScreen(
    initialSharedUri: Uri? = null,
    activeSectionTab: String,
    onChangeSectionTab: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val palette = LocalPayVaultPalette.current

    var residentName by remember { mutableStateOf("") }
    var flatNumber by remember { mutableStateOf("") }
    // 7: Payment amount is auto-filled from the screenshot AND editable by the resident
    var paymentAmount by remember { mutableStateOf("") }
    var userEditedPaymentAmount by remember { mutableStateOf(false) }

    var selectedUri by remember { mutableStateOf(initialSharedUri) }
    var rawOcrText by remember { mutableStateOf("") }

    // 7: Under Verified Receipt Details:
    //    - Sender Name is editable
    //    - Payment App is auto-detected AND editable (so user can type if app is unrecognized)
    //    - Receiver Name and all other receipt fields are strictly read-only
    var senderName by remember { mutableStateOf("") }
    var receiverName by remember { mutableStateOf("") }
    var upiId by remember { mutableStateOf("") }
    var transactionAmount by remember { mutableStateOf("") }
    var transactionId by remember { mutableStateOf("") }
    var transactionDate by remember { mutableStateOf("") }
    var paymentApp by remember { mutableStateOf("") }

    var isScanningOcr by remember { mutableStateOf(false) }
    var isReceiptValid by remember { mutableStateOf(false) }
    var ocrErrorMessage by remember { mutableStateOf<String?>(null) }

    var isSubmitting by remember { mutableStateOf(false) }
    var receiptConfirmation by remember { mutableStateOf<String?>(null) }

    fun clearExtractedFields(resetPaymentAmt: Boolean = true) {
        if (resetPaymentAmt) {
            paymentAmount = ""
            userEditedPaymentAmount = false
        }
        senderName = ""
        receiverName = ""
        upiId = ""
        transactionAmount = ""
        transactionId = ""
        transactionDate = ""
        paymentApp = ""
        isReceiptValid = false
    }

    suspend fun runBackendOcrValidation(uri: Uri, localRawText: String, localFallbackError: String?) {
        if (selectedUri != uri) return
        try {
            val bytes = withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            } ?: return
            val textType = "text/plain".toMediaTypeOrNull()
            val imgBody = bytes.toRequestBody("image/png".toMediaTypeOrNull())
            val part = MultipartBody.Part.createFormData("screenshot", "upi_scan.png", imgBody)
            val resp = PayVaultApiClient.api.extractOcrFromScreenshot(
                ocrText = localRawText.toRequestBody(textType),
                residentName = residentName.toRequestBody(textType),
                paymentAmount = paymentAmount.toRequestBody(textType),
                screenshot = part
            )
            if (selectedUri != uri) return
            val body = resp.body()
            val ext = body?.extracted
            if (resp.isSuccessful && body?.ok == true && ext != null && ext.transactionAmount > 0 && ext.transactionId.isNotBlank()) {
                val formattedAmt = String.format(Locale.US, "%.2f", ext.transactionAmount)
                if (!userEditedPaymentAmount || paymentAmount.isBlank()) {
                    paymentAmount = formattedAmt
                }
                transactionAmount = formattedAmt
                senderName = ext.senderName.ifBlank { ext.accountHolderName }
                receiverName = ext.receiverName.ifBlank { "Not shown on receipt" }
                upiId = ext.upiId
                transactionId = ext.transactionId
                transactionDate = ext.transactionDate
                paymentApp = ext.paymentApp
                isReceiptValid = true
                ocrErrorMessage = null
            } else if (!isReceiptValid && selectedUri == uri) {
                clearExtractedFields(resetPaymentAmt = !userEditedPaymentAmount)
                ocrErrorMessage = body?.error
                    ?: localFallbackError
                    ?: "Invalid input: Please upload a valid UPI or bank payment receipt screenshot."
            }
        } catch (_: Exception) {
            if (!isReceiptValid && selectedUri == uri) {
                ocrErrorMessage = localFallbackError
                    ?: "Invalid input: Please upload a valid UPI or bank payment receipt screenshot."
            }
        } finally {
            if (selectedUri == uri) {
                isScanningOcr = false
            }
        }
    }

    // Trigger on-device ML Kit OCR + receipt verification whenever an image URI is chosen
    LaunchedEffect(selectedUri) {
        val uri = selectedUri
        if (uri == null) {
            clearExtractedFields(resetPaymentAmt = true)
            ocrErrorMessage = null
            isScanningOcr = false
            return@LaunchedEffect
        }

        clearExtractedFields(resetPaymentAmt = true)
        ocrErrorMessage = null
        isScanningOcr = true

        UpiScreenshotOcrAnalyzer.analyzeImageUri(
            context = context,
            imageUri = uri,
            onResult = { raw, ext ->
                if (selectedUri != uri) return@analyzeImageUri
                rawOcrText = raw
                if (ext.isValid && ext.transactionAmount > 0 && ext.transactionId.isNotBlank()) {
                    val formattedAmt = String.format(Locale.US, "%.2f", ext.transactionAmount)
                    if (!userEditedPaymentAmount || paymentAmount.isBlank()) {
                        paymentAmount = formattedAmt
                    }
                    transactionAmount = formattedAmt
                    senderName = ext.senderName.ifBlank { ext.accountHolderName }
                    receiverName = ext.receiverName.ifBlank { "Not shown on receipt" }
                    upiId = ext.upiId
                    transactionId = ext.transactionId
                    transactionDate = ext.transactionDate
                    paymentApp = ext.paymentApp
                    isReceiptValid = true
                    ocrErrorMessage = null
                    isScanningOcr = false
                } else {
                    scope.launch {
                        runBackendOcrValidation(uri, raw, ext.errorMessage)
                    }
                }
            },
            onError = {
                if (selectedUri != uri) return@analyzeImageUri
                scope.launch {
                    runBackendOcrValidation(
                        uri,
                        "",
                        "Could not read screenshot text. Please upload a clear payment receipt screenshot."
                    )
                }
            }
        )
    }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            selectedUri = uri
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 3(b) & 7: Resident Details Card (Payment Amount is auto-filled AND editable)
        CupertinoGroupedCard(title = "RESIDENT DETAILS") {
            CupertinoTextFieldRow(
                label = "Full Name *",
                value = residentName,
                placeholder = "e.g., Aarav Mehta",
                onValueChange = { residentName = it }
            )
            HorizontalDivider(color = palette.hairline)
            CupertinoTextFieldRow(
                label = "Flat Number *",
                value = flatNumber,
                placeholder = "e.g., A-402",
                onValueChange = { flatNumber = it.uppercase() }
            )
            HorizontalDivider(color = palette.hairline)
            CupertinoTextFieldRow(
                label = "Payment Amt (₹) ✎",
                value = paymentAmount,
                placeholder = "Auto-filled (tap to edit)",
                keyboardType = KeyboardType.Decimal,
                onValueChange = {
                    paymentAmount = it
                    userEditedPaymentAmount = true
                }
            )
        }

        // 3(c) & 4(a): Section Tabs — UPI/Bank Payment Screenshot Tab vs. Society Meetings Tab
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(palette.subtleBg)
                .padding(3.dp)
        ) {
            SegmentedTabButton(
                title = "💳 UPI / Bank Screenshot",
                selected = activeSectionTab == "payment",
                modifier = Modifier.weight(1f),
                onClick = { onChangeSectionTab("payment") }
            )
            SegmentedTabButton(
                title = "💬 Society Meetings",
                selected = activeSectionTab == "meetings",
                modifier = Modifier.weight(1f),
                onClick = { onChangeSectionTab("meetings") }
            )
        }

        if (activeSectionTab == "payment") {
            // 3(c): UPI / Bank Payment Screenshot Block
            CupertinoGroupedCard(title = "UPI / BANK PAYMENT SCREENSHOT") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(AppleBlue.copy(alpha = 0.08f))
                        .border(1.dp, AppleBlue.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
                        .clickable {
                            photoPickerLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        }
                        .padding(18.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = when {
                                selectedUri == null -> "Tap to Select Payment Screenshot"
                                isScanningOcr -> "Verifying Payment Receipt..."
                                isReceiptValid -> "Valid Receipt Verified ✓ (Tap to Change)"
                                else -> "Invalid Screenshot — Tap to Upload Valid Receipt"
                            },
                            fontWeight = FontWeight.SemiBold,
                            color = if (ocrErrorMessage != null) AppleRed else AppleBlue,
                            fontSize = 14.5.sp
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "All UPI Apps (GPay, PhonePe, Paytm, BHIM, Navi, CRED, etc.) & Bank Transfers",
                            fontSize = 11.5.sp,
                            color = palette.textSecondary
                        )
                    }
                }

                if (selectedUri != null) {
                    Spacer(modifier = Modifier.height(12.dp))
                    AsyncImage(
                        model = selectedUri,
                        contentDescription = "Selected UPI Screenshot",
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp)
                            .clip(RoundedCornerShape(12.dp))
                    )
                }

                AnimatedVisibility(visible = ocrErrorMessage != null) {
                    Surface(
                        color = AppleRed.copy(alpha = 0.12f),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp)
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text(
                                text = "⚠️ Invalid Payment Screenshot",
                                color = AppleRed,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.5.sp
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = ocrErrorMessage.orEmpty(),
                                color = palette.textPrimary,
                                fontSize = 12.5.sp
                            )
                        }
                    }
                }
            }

            // 3(d) & 7: Verified Receipt Details Block
            // Sender Name and Payment App are editable; Receiver Name and all other receipt fields are read-only
            CupertinoGroupedCard(title = "VERIFIED RECEIPT DETAILS") {
                CupertinoTextFieldRow(
                    label = "Sender Name ✎",
                    value = senderName,
                    placeholder = "Auto-fetched (editable)",
                    onValueChange = { senderName = it }
                )
                HorizontalDivider(color = palette.hairline)
                CupertinoReadOnlyFieldRow(
                    label = "Receiver Name 🔒",
                    value = receiverName,
                    placeholder = "Auto-fetched receiver name"
                )
                HorizontalDivider(color = palette.hairline)
                CupertinoTextFieldRow(
                    label = "Payment App ✎",
                    value = paymentApp,
                    placeholder = "Auto-detected or type app name",
                    onValueChange = { paymentApp = it }
                )
                HorizontalDivider(color = palette.hairline)
                CupertinoReadOnlyFieldRow(
                    label = "Tx Amount (₹) 🔒",
                    value = if (transactionAmount.isNotBlank()) "₹$transactionAmount" else "",
                    placeholder = "Auto-extracted amount"
                )
                HorizontalDivider(color = palette.hairline)
                CupertinoReadOnlyFieldRow(
                    label = "Transaction ID 🔒",
                    value = transactionId,
                    placeholder = "12-digit UTR / Ref ID",
                    isMonospace = true
                )
                HorizontalDivider(color = palette.hairline)
                CupertinoReadOnlyFieldRow(
                    label = "UPI ID 🔒",
                    value = upiId,
                    placeholder = "Auto-extracted UPI ID",
                    isMonospace = true
                )
                HorizontalDivider(color = palette.hairline)
                CupertinoReadOnlyFieldRow(
                    label = "Date 🔒",
                    value = transactionDate,
                    placeholder = "Auto-extracted date"
                )
            }

            Button(
                onClick = {
                    val uri = selectedUri
                    if (residentName.isBlank() || flatNumber.isBlank() || paymentAmount.isBlank()) {
                        Toast.makeText(
                            context,
                            "Please enter your Full Name, Flat Number, and Payment Amount.",
                            Toast.LENGTH_SHORT
                        ).show()
                        return@Button
                    }
                    if (uri == null || !isReceiptValid || transactionId.isBlank()) {
                        Toast.makeText(
                            context,
                            ocrErrorMessage ?: "Please upload a valid UPI or bank payment receipt screenshot.",
                            Toast.LENGTH_LONG
                        ).show()
                        return@Button
                    }
                    isSubmitting = true
                    scope.launch {
                        try {
                            val bytes = withContext(Dispatchers.IO) {
                                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                            } ?: throw IllegalStateException("Cannot read image file")

                            val submittedFlat = flatNumber.trim().uppercase()
                            val submittedAmtNum = paymentAmount.trim().toDoubleOrNull()
                                ?: transactionAmount.trim().toDoubleOrNull()
                                ?: 0.0
                            val submittedTxAmtNum = transactionAmount.trim().toDoubleOrNull() ?: submittedAmtNum
                            val submittedAmtStr = String.format(Locale.US, "%.2f", submittedAmtNum)
                            val finalApp = paymentApp.trim().ifBlank { "UPI" }

                            // 1. Always persist to On-Device Local Admin Vault so it is 100% reliable
                            val localFileName = withContext(Dispatchers.IO) {
                                LocalAndroidVaultStore.saveScreenshotFile(context, bytes)
                            }
                            val localRecord = LocalAndroidVaultStore.recordOrUpdatePayment(
                                context = context,
                                residentName = residentName,
                                flatNumber = submittedFlat,
                                paymentAmount = submittedAmtNum,
                                upiId = upiId,
                                transactionAmount = submittedTxAmtNum,
                                transactionId = transactionId,
                                transactionDate = transactionDate,
                                senderName = senderName,
                                receiverName = receiverName,
                                paymentApp = finalApp,
                                screenshotFilename = localFileName
                            )
                            var refNo = String.format(Locale.US, "RCPT-%04d", localRecord.id)

                            // 2. Also sync with Backend Server if reachable
                            try {
                                val textType = "text/plain".toMediaTypeOrNull()
                                val imgBody = bytes.toRequestBody("image/png".toMediaTypeOrNull())
                                val part = MultipartBody.Part.createFormData("screenshot", "upi_receipt.png", imgBody)

                                val resp = PayVaultApiClient.api.submitResidentPayment(
                                    residentName = residentName.toRequestBody(textType),
                                    flatNumber = submittedFlat.toRequestBody(textType),
                                    paymentAmount = submittedAmtStr.toRequestBody(textType),
                                    senderName = senderName.toRequestBody(textType),
                                    receiverName = receiverName.toRequestBody(textType),
                                    upiId = upiId.toRequestBody(textType),
                                    transactionAmount = transactionAmount.toRequestBody(textType),
                                    transactionId = transactionId.toRequestBody(textType),
                                    transactionDate = transactionDate.toRequestBody(textType),
                                    accountHolderName = senderName.toRequestBody(textType),
                                    paymentApp = finalApp.toRequestBody(textType),
                                    ocrText = rawOcrText.toRequestBody(textType),
                                    screenshot = part
                                )
                                val body = resp.body()
                                if (resp.isSuccessful && body?.ok == true && !body.receipt?.referenceNo.isNullOrBlank()) {
                                    refNo = body.receipt!!.referenceNo
                                }
                            } catch (_: Exception) {
                                // Backend sync optional when local vault has already stored the verified proof
                            }

                            // 3. Clear form and trigger the Green Pop-Up Confirmation Modal Dialog
                            ocrErrorMessage = null
                            residentName = ""
                            flatNumber = ""
                            selectedUri = null
                            clearExtractedFields(resetPaymentAmt = true)
                            receiptConfirmation =
                                "Reference: $refNo • Flat $submittedFlat • Amount: ₹$submittedAmtStr"
                        } catch (e: Exception) {
                            Toast.makeText(
                                context,
                                "Could not read screenshot image: ${e.localizedMessage}",
                                Toast.LENGTH_LONG
                            ).show()
                        } finally {
                            isSubmitting = false
                        }
                    }
                },
                enabled = !isSubmitting && !isScanningOcr && isReceiptValid,
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AppleBlue),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
            ) {
                Text(
                    text = when {
                        isSubmitting -> "Encrypting & Uploading..."
                        isScanningOcr -> "Scanning Receipt..."
                        else -> "Submit Verified Proof to Admin Vault"
                    },
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            // Green Pop-Up Confirmation Dialog (Pops up in the center of the screen upon submission)
            if (receiptConfirmation != null) {
                Dialog(onDismissRequest = { receiptConfirmation = null }) {
                    Surface(
                        color = Color(0xFFECFDF5),
                        shape = RoundedCornerShape(22.dp),
                        shadowElevation = 10.dp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(2.dp, Color(0xFF16A34A), RoundedCornerShape(22.dp))
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(64.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF16A34A)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "✓",
                                    color = Color.White,
                                    fontSize = 34.sp,
                                    fontWeight = FontWeight.ExtraBold
                                )
                            }
                            Spacer(modifier = Modifier.height(14.dp))
                            Text(
                                text = "Maintenance Receipt Sent!",
                                color = Color(0xFF14532D),
                                fontSize = 19.sp,
                                fontWeight = FontWeight.ExtraBold,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Your maintenance receipt is sent to the Madhav admin account.",
                                color = Color(0xFF166534),
                                fontSize = 14.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                textAlign = TextAlign.Center,
                                lineHeight = 20.sp
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Surface(
                                color = Color(0xFFD1FAE5),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = receiptConfirmation.orEmpty(),
                                    color = Color(0xFF065F46),
                                    fontSize = 12.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(12.dp)
                                )
                            }
                            Spacer(modifier = Modifier.height(18.dp))
                            Button(
                                onClick = { receiptConfirmation = null },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF16A34A)),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(46.dp)
                            ) {
                                Text(
                                    text = "OK, Done",
                                    color = Color.White,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
        } else {
            // 4(a-c): Society Meetings Section (Styled like Google Messages, Read-Only for Residents)
            SocietyMeetingsGoogleMessagesCard()
        }
    }
}

/**
 * 4(a-c): Google Messages-style Read-Only Society Meetings Feed.
 */
@Composable
fun SocietyMeetingsGoogleMessagesCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val palette = LocalPayVaultPalette.current

    var meetings by remember { mutableStateOf<List<SocietyMeetingRecord>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    fun loadMeetings() {
        isLoading = true
        scope.launch {
            val localList = LocalAndroidVaultStore.getLocalMeetings(context)
            var remoteList: List<SocietyMeetingRecord> = emptyList()
            try {
                val resp = PayVaultApiClient.api.getSocietyMeetings()
                if (resp.isSuccessful && resp.body()?.ok == true) {
                    remoteList = resp.body()?.meetings.orEmpty()
                }
            } catch (_: Exception) {
            } finally {
                val combined = (remoteList + localList).distinctBy { "${it.meetingDate}|${it.topic}" }
                meetings = combined
                isLoading = false
            }
        }
    }

    LaunchedEffect(Unit) {
        loadMeetings()
    }

    Surface(
        color = palette.cardBg,
        shape = RoundedCornerShape(18.dp),
        shadowElevation = 1.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Google Messages Conversation Top Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(palette.subtleBg.copy(alpha = 0.55f))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.app_logo),
                        contentDescription = "Society Admin Avatar",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .border(1.dp, MadhavGold, CircleShape)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "Madhav Society Admin",
                            fontSize = 14.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = palette.textPrimary
                        )
                        Text(
                            text = "Official Meeting Broadcasts • Read-Only",
                            fontSize = 11.5.sp,
                            color = palette.textSecondary
                        )
                    }
                }
                TextButton(onClick = { loadMeetings() }) {
                    Text("🔄 Refresh", fontSize = 12.sp, color = AppleBlue, fontWeight = FontWeight.SemiBold)
                }
            }

            HorizontalDivider(color = palette.hairline)

            // Message Bubbles Stream
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                if (isLoading) {
                    Text(
                        text = "Loading society meeting messages...",
                        fontSize = 13.sp,
                        color = palette.textSecondary,
                        modifier = Modifier.padding(vertical = 24.dp)
                    )
                } else if (meetings.isEmpty()) {
                    Text(
                        text = "No society meeting notifications have been posted yet.",
                        fontSize = 13.5.sp,
                        color = palette.textSecondary,
                        modifier = Modifier.padding(vertical = 24.dp)
                    )
                } else {
                    meetings.forEach { msg ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.Top
                        ) {
                            Image(
                                painter = painterResource(id = R.drawable.app_logo),
                                contentDescription = "Admin",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .border(1.dp, MadhavGold, CircleShape)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Surface(
                                color = palette.groupedBg,
                                shape = RoundedCornerShape(
                                    topStart = 4.dp,
                                    topEnd = 18.dp,
                                    bottomEnd = 18.dp,
                                    bottomStart = 18.dp
                                ),
                                modifier = Modifier.weight(1f)
                            ) {
                                Column(modifier = Modifier.padding(14.dp)) {
                                    // Meeting Date Badge
                                    Surface(
                                        color = AppleBlue.copy(alpha = 0.12f),
                                        shape = RoundedCornerShape(50),
                                        modifier = Modifier.padding(bottom = 6.dp)
                                    ) {
                                        Text(
                                            text = "📅 Meeting Date: ${msg.meetingDate}",
                                            color = AppleBlue,
                                            fontSize = 11.5.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                        )
                                    }
                                    // Topic / Subject
                                    Text(
                                        text = msg.topic,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 15.sp,
                                        color = palette.textPrimary
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    // Body
                                    Text(
                                        text = msg.body,
                                        fontSize = 13.5.sp,
                                        color = palette.textPrimary,
                                        lineHeight = 19.sp
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = "Posted ${msg.createdAt}",
                                        fontSize = 10.5.sp,
                                        color = palette.textSecondary,
                                        modifier = Modifier.align(Alignment.End)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            HorizontalDivider(color = palette.hairline)

            // Google Messages Read-Only Footer Bar
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(palette.subtleBg.copy(alpha = 0.45f))
                    .padding(vertical = 10.dp, horizontal = 14.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "🔒 Read-only channel • Only Admin can send meeting messages",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.textSecondary
                )
            }
        }
    }
}

/**
 * 5: Full-Featured Mobile Admin Vault — Brings ALL Web Admin features to the phone:
 *   - 4 KPI Summary Cards (Total Submissions, Declared Payment Total, OCR Transaction Total, Verification Health)
 *   - Broadcast Society Meeting Notification Composer + Sent Meetings Manager
 *   - Multi-Format CSV & Excel (.xlsx) Export Studio with Delimiter Override, Date Style, Custom Column Selection & Live CSV Preview Modal
 *   - Search & Status Filter Toolbar
 *   - Payment Records with View Proof Image Modal, Verify/Unverify Button, Reject Button, and Delete Button
 */
@Composable
fun AdminVaultScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val palette = LocalPayVaultPalette.current

    var adminToken by remember { mutableStateOf<String?>(null) }
    var usernameInput by remember { mutableStateOf("admin") }
    var passwordInput by remember { mutableStateOf("Admin@123") }

    var payments by remember { mutableStateOf<List<PaymentRecord>>(emptyList()) }
    var stats by remember { mutableStateOf<AdminStats?>(null) }
    var meetings by remember { mutableStateOf<List<SocietyMeetingRecord>>(emptyList()) }

    // Search & Status Filter State
    var searchQuery by remember { mutableStateOf("") }
    var statusFilter by remember { mutableStateOf("all") }

    // Meeting Broadcast Composer State
    var meetingDateInput by remember { mutableStateOf("") }
    var meetingTopicInput by remember { mutableStateOf("") }
    var meetingBodyInput by remember { mutableStateOf("") }

    // Multi-Format Export Studio State
    var selectedExportFormat by remember { mutableStateOf(ExportFormatOption.STANDARD_CSV) }
    var selectedDelimiter by remember { mutableStateOf("") }
    var selectedDateStyle by remember { mutableStateOf("iso") }
    var selectedColumns by remember {
        mutableStateOf(
            ExportColumnOption.entries.filter { it.defaultChecked }.map { it.key }.toSet()
        )
    }

    // Modals for Proof Image & Live CSV Preview
    var proofModalRecord by remember { mutableStateOf<PaymentRecord?>(null) }
    var csvPreviewText by remember { mutableStateOf<String?>(null) }

    fun refreshAdminData(token: String, search: String = searchQuery, status: String = statusFilter) {
        scope.launch {
            var remotePayments: List<PaymentRecord> = emptyList()
            var remoteMeetings: List<SocietyMeetingRecord> = emptyList()
            try {
                val resp = PayVaultApiClient.api.getAdminPayments(
                    bearerToken = "Bearer $token",
                    search = "",
                    status = "all"
                )
                if (resp.isSuccessful && resp.body()?.ok == true) {
                    remotePayments = resp.body()!!.payments
                }
                val meetResp = PayVaultApiClient.api.getSocietyMeetings()
                if (meetResp.isSuccessful && meetResp.body()?.ok == true) {
                    remoteMeetings = meetResp.body()?.meetings.orEmpty()
                }
            } catch (_: Exception) {
            }
            val (mergedList, mergedStats) = LocalAndroidVaultStore.getMergedPaymentsAndStats(
                context = context,
                remotePayments = remotePayments,
                search = search,
                statusFilter = status
            )
            payments = mergedList
            stats = mergedStats
            val localMeetings = LocalAndroidVaultStore.getLocalMeetings(context)
            meetings = (remoteMeetings + localMeetings).distinctBy { "${it.meetingDate}|${it.topic}" }
        }
    }

    if (adminToken == null) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
            verticalArrangement = Arrangement.Center
        ) {
            CupertinoGroupedCard(title = "RESTRICTED ADMIN VAULT 🔒") {
                Text(
                    text = "Only authorized Administrators can view resident payment records, verify proofs, broadcast society meetings, or export CSV/Excel sheets.",
                    fontSize = 13.sp,
                    color = palette.textSecondary,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
                CupertinoTextFieldRow(
                    label = "Username",
                    value = usernameInput,
                    placeholder = "admin",
                    onValueChange = { usernameInput = it }
                )
                HorizontalDivider(color = palette.hairline)
                OutlinedTextField(
                    value = passwordInput,
                    onValueChange = { passwordInput = it },
                    label = { Text("Admin Password") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = {
                        scope.launch {
                            val cleanUser = usernameInput.trim()
                            val isValidLocalAdmin =
                                cleanUser.equals("admin", ignoreCase = true) && passwordInput == "Admin@123"
                            try {
                                val resp = PayVaultApiClient.api.adminLogin(
                                    AdminLoginRequest(cleanUser, passwordInput)
                                )
                                val body = resp.body()
                                if (resp.isSuccessful && body?.ok == true && body.token != null) {
                                    adminToken = body.token
                                    refreshAdminData(body.token)
                                } else if (isValidLocalAdmin) {
                                    adminToken = "local_admin_token"
                                    refreshAdminData("local_admin_token")
                                } else {
                                    Toast.makeText(context, "Invalid Admin credentials", Toast.LENGTH_SHORT).show()
                                }
                            } catch (_: Exception) {
                                if (isValidLocalAdmin) {
                                    adminToken = "local_admin_token"
                                    refreshAdminData("local_admin_token")
                                } else {
                                    Toast.makeText(context, "Invalid Admin credentials", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AppleBlue),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("🔓 Unlock Admin Vault", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    } else {
        val token = adminToken!!

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Top Admin Action Bar: Refresh & Lock Vault
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "ADMINISTRATOR CONSOLE",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = AppleBlue
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { refreshAdminData(token) },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text("🔄 Refresh", fontSize = 12.sp)
                        }
                        OutlinedButton(
                            onClick = {
                                adminToken = null
                                Toast.makeText(context, "Admin Vault locked", Toast.LENGTH_SHORT).show()
                            },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text("🚪 Lock Vault", fontSize = 12.sp, color = AppleRed)
                        }
                    }
                }
            }

            // 4 KPI Summary Cards (2x2 Grid matching Web Portal)
            item {
                stats?.let { s ->
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            AdminKpiTile(
                                label = "Total Submissions",
                                value = "${s.totalEntries}",
                                subtitle = "Stored in Vault",
                                valueColor = palette.textPrimary,
                                modifier = Modifier.weight(1f)
                            )
                            AdminKpiTile(
                                label = "Declared Payment",
                                value = "₹${"%.2f".format(Locale.US, s.totalPaymentAmount)}",
                                subtitle = "Payment Amt Sum",
                                valueColor = palette.textPrimary,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            AdminKpiTile(
                                label = "OCR Transaction",
                                value = "₹${"%.2f".format(Locale.US, s.totalTransactionAmount)}",
                                subtitle = "Verified Tx Sum",
                                valueColor = AppleBlue,
                                modifier = Modifier.weight(1f)
                            )
                            AdminKpiTile(
                                label = "Verification Health",
                                value = "${s.verifiedCount} Verified",
                                subtitle = "${s.mismatchCount} Mismatch(es)",
                                valueColor = AppleGreen,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }

            // 4(d): Broadcast Society Meeting Notification Composer (Restricted to Admin Vault)
            item {
                CupertinoGroupedCard(title = "📢 BROADCAST SOCIETY MEETING MESSAGE") {
                    Text(
                        text = "Send an official society meeting notification to all residents (appears in the Google Messages-style Society Meetings tab).",
                        fontSize = 12.sp,
                        color = palette.textSecondary,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    CupertinoTextFieldRow(
                        label = "Meeting Date *",
                        value = meetingDateInput,
                        placeholder = "e.g., 2026-11-05 • 07:00 PM",
                        onValueChange = { meetingDateInput = it }
                    )
                    HorizontalDivider(color = palette.hairline)
                    CupertinoTextFieldRow(
                        label = "Topic / Subject *",
                        value = meetingTopicInput,
                        placeholder = "e.g., Monthly Maintenance & AGM",
                        onValueChange = { meetingTopicInput = it }
                    )
                    HorizontalDivider(color = palette.hairline)
                    OutlinedTextField(
                        value = meetingBodyInput,
                        onValueChange = { meetingBodyInput = it },
                        label = { Text("Meeting Message Body *") },
                        placeholder = { Text("Write agenda, venue, and instructions for residents...") },
                        minLines = 3,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Button(
                        onClick = {
                            if (meetingDateInput.isBlank() || meetingTopicInput.isBlank() || meetingBodyInput.isBlank()) {
                                Toast.makeText(
                                    context,
                                    "Please fill in Meeting Date, Topic, and Message Body.",
                                    Toast.LENGTH_SHORT
                                ).show()
                                return@Button
                            }
                            scope.launch {
                                LocalAndroidVaultStore.addLocalMeeting(
                                    context = context,
                                    meetingDate = meetingDateInput,
                                    topic = meetingTopicInput,
                                    body = meetingBodyInput
                                )
                                try {
                                    PayVaultApiClient.api.createSocietyMeeting(
                                        bearerToken = "Bearer $token",
                                        body = CreateMeetingRequest(
                                            meetingDate = meetingDateInput.trim(),
                                            topic = meetingTopicInput.trim(),
                                            body = meetingBodyInput.trim()
                                        )
                                    )
                                } catch (_: Exception) {
                                }
                                meetingDateInput = ""
                                meetingTopicInput = ""
                                meetingBodyInput = ""
                                Toast.makeText(
                                    context,
                                    "Meeting message sent to all residents!",
                                    Toast.LENGTH_SHORT
                                ).show()
                                refreshAdminData(token)
                            }
                        },
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AppleBlue),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("📤 Send Meeting Message to Residents", fontWeight = FontWeight.SemiBold)
                    }

                    if (meetings.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Sent Meeting Broadcasts (${meetings.size}):",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = palette.textSecondary
                        )
                        meetings.forEach { m ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 6.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "${m.meetingDate} — ${m.topic}",
                                        fontSize = 12.5.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = palette.textPrimary
                                    )
                                }
                                TextButton(
                                    onClick = {
                                        scope.launch {
                                            LocalAndroidVaultStore.deleteLocalMeeting(context, m.id)
                                            try {
                                                PayVaultApiClient.api.deleteSocietyMeeting(
                                                    "Bearer $token",
                                                    m.id
                                                )
                                            } catch (_: Exception) {
                                            }
                                            refreshAdminData(token)
                                        }
                                    }
                                ) {
                                    Text("🗑️ Delete", color = AppleRed, fontSize = 11.5.sp)
                                }
                            }
                        }
                    }
                }
            }

            // 5: Full Multi-Format CSV & Excel (.xlsx) Export Studio
            item {
                CupertinoGroupedCard(title = "📊 MULTI-FORMAT CSV & EXCEL (.XLSX) EXPORT STUDIO") {
                    Text(
                        text = "Select format preset, delimiter override, date style, and custom columns:",
                        fontSize = 12.5.sp,
                        color = palette.textSecondary
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    ExportFormatOption.entries.forEach { option ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedExportFormat = option }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = selectedExportFormat == option,
                                onClick = { selectedExportFormat = option }
                            )
                            Text(
                                text = option.displayLabel,
                                fontSize = 13.sp,
                                color = palette.textPrimary,
                                fontWeight = if (selectedExportFormat == option) FontWeight.SemiBold else FontWeight.Normal
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Delimiter Override:",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = palette.textSecondary
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(
                            "" to "Default",
                            "comma" to "Comma (,)",
                            "semicolon" to "Semicolon (;)",
                            "tab" to "Tab (\\t)",
                            "pipe" to "Pipe (|)"
                        ).forEach { (key, label) ->
                            FilterChip(
                                selected = selectedDelimiter == key,
                                onClick = { selectedDelimiter = key },
                                label = { Text(label, fontSize = 12.sp) }
                            )
                        }
                    }

                    Text(
                        text = "Date Format Style:",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = palette.textSecondary
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(
                            "iso" to "ISO (YYYY-MM-DD)",
                            "dmy" to "Indian (DD/MM/YYYY)",
                            "us" to "US (MM/DD/YYYY)"
                        ).forEach { (key, label) ->
                            FilterChip(
                                selected = selectedDateStyle == key,
                                onClick = { selectedDateStyle = key },
                                label = { Text(label, fontSize = 12.sp) }
                            )
                        }
                    }

                    Text(
                        text = "Included Columns:",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = palette.textSecondary
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        ExportColumnOption.entries.forEach { col ->
                            val isSelected = selectedColumns.contains(col.key)
                            FilterChip(
                                selected = isSelected,
                                onClick = {
                                    selectedColumns = if (isSelected && selectedColumns.size > 1) {
                                        selectedColumns - col.key
                                    } else {
                                        selectedColumns + col.key
                                    }
                                },
                                label = { Text(col.label, fontSize = 11.5.sp) }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    try {
                                        val fmt = if (selectedExportFormat == ExportFormatOption.XLSX) {
                                            ExportFormatOption.STANDARD_CSV.key
                                        } else {
                                            selectedExportFormat.key
                                        }
                                        val colsParam = if (fmt == "accounting_csv") null else selectedColumns.joinToString(",")
                                        val resp = PayVaultApiClient.api.exportPaymentsData(
                                            bearerToken = "Bearer $token",
                                            format = fmt,
                                            delimiter = selectedDelimiter.ifBlank { null },
                                            dateStyle = selectedDateStyle,
                                            columns = colsParam,
                                            search = searchQuery.ifBlank { null },
                                            status = if (statusFilter == "all") null else statusFilter
                                        )
                                        if (resp.isSuccessful) {
                                            csvPreviewText = resp.body()?.string().orEmpty()
                                        } else {
                                            csvPreviewText = LocalAndroidVaultStore.generateLocalCsvPreview(
                                                payments,
                                                selectedExportFormat,
                                                selectedDelimiter,
                                                selectedColumns
                                            )
                                        }
                                    } catch (_: Exception) {
                                        csvPreviewText = LocalAndroidVaultStore.generateLocalCsvPreview(
                                            payments,
                                            selectedExportFormat,
                                            selectedDelimiter,
                                            selectedColumns
                                        )
                                    }
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("👁️ Preview CSV", fontSize = 12.5.sp)
                        }

                        Button(
                            onClick = {
                                scope.launch {
                                    var bytes: ByteArray? = null
                                    try {
                                        val colsParam = if (selectedExportFormat == ExportFormatOption.ACCOUNTING_CSV) {
                                            null
                                        } else {
                                            selectedColumns.joinToString(",")
                                        }
                                        val resp = PayVaultApiClient.api.exportPaymentsData(
                                            bearerToken = "Bearer $token",
                                            format = selectedExportFormat.key,
                                            delimiter = selectedDelimiter.ifBlank { null },
                                            dateStyle = selectedDateStyle,
                                            columns = colsParam,
                                            search = searchQuery.ifBlank { null },
                                            status = if (statusFilter == "all") null else statusFilter
                                        )
                                        if (resp.isSuccessful) {
                                            bytes = resp.body()?.bytes()
                                        }
                                    } catch (_: Exception) {
                                    }
                                    if (bytes == null) {
                                        bytes = LocalAndroidVaultStore.generateLocalCsvPreview(
                                            payments,
                                            selectedExportFormat,
                                            selectedDelimiter,
                                            selectedColumns
                                        ).toByteArray(Charsets.UTF_8)
                                    }
                                    try {
                                        val ext = when (selectedExportFormat) {
                                            ExportFormatOption.XLSX -> "xlsx"
                                            ExportFormatOption.TSV -> "tsv"
                                            else -> "csv"
                                        }
                                        val fileName = "madhav_${selectedExportFormat.key}_${System.currentTimeMillis()}.$ext"
                                        withContext(Dispatchers.IO) {
                                            val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
                                                ?: context.filesDir
                                            val outFile = File(dir, fileName)
                                            FileOutputStream(outFile).use { it.write(bytes) }
                                        }
                                        Toast.makeText(
                                            context,
                                            "Saved $fileName to Documents!",
                                            Toast.LENGTH_LONG
                                        ).show()
                                    } catch (e: Exception) {
                                        Toast.makeText(context, "Export failed: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = AppleBlue),
                            modifier = Modifier.weight(1.3f)
                        ) {
                            Text("⬇️ Download", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }

            // 5: Search & Status Filter Toolbar
            item {
                CupertinoGroupedCard(title = "🔍 SEARCH & FILTER RECORDS") {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = {
                            searchQuery = it
                            refreshAdminData(token, search = it, status = statusFilter)
                        },
                        placeholder = { Text("Search Name, Flat, UPI ID, UTR, Sender...") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf("all" to "All", "Verified" to "✓ Verified", "Amount Mismatch" to "⚠ Mismatch", "Pending" to "● Pending", "Rejected" to "✕ Rejected").forEach { (key, label) ->
                            FilterChip(
                                selected = statusFilter == key,
                                onClick = {
                                    statusFilter = key
                                    refreshAdminData(token, search = searchQuery, status = key)
                                },
                                label = { Text(label, fontSize = 12.sp) }
                            )
                        }
                    }
                }
            }

            // 5: Payment Records with Verify, Reject, View Proof & Delete Actions
            if (payments.isEmpty()) {
                item {
                    CupertinoGroupedCard(title = "PAYMENT RECORDS") {
                        Text(
                            text = "No UPI payment records match the current filter.",
                            color = palette.textSecondary,
                            fontSize = 13.5.sp
                        )
                    }
                }
            } else {
                items(payments, key = { it.id }) { rec ->
                    val senderDisplay = rec.senderName?.takeIf { it.isNotBlank() } ?: rec.accountHolderName
                    val receiverDisplay = rec.receiverName?.takeIf { it.isNotBlank() } ?: "Not shown on receipt"
                    val isVerified = rec.verificationStatus.equals("Verified", ignoreCase = true)

                    CupertinoGroupedCard(title = "#${rec.id} • FLAT ${rec.flatNumber} • ${rec.verificationStatus.uppercase()}") {
                        Text("1. Name: ${rec.residentName}", fontWeight = FontWeight.Bold, color = palette.textPrimary)
                        Text("2. Flat number: ${rec.flatNumber}", fontSize = 13.5.sp, color = palette.textPrimary)
                        Text("3. Payment amount: ₹${"%.2f".format(Locale.US, rec.paymentAmount)}", fontSize = 13.5.sp, color = palette.textPrimary)
                        Text("4. UPI ID: ${rec.upiId}", fontFamily = FontFamily.Monospace, fontSize = 12.5.sp, color = palette.textPrimary)
                        Text("5. Transaction amount: ₹${"%.2f".format(Locale.US, rec.transactionAmount)}", fontWeight = FontWeight.SemiBold, color = AppleBlue)
                        Text("6. Transaction ID: ${rec.transactionId}", fontFamily = FontFamily.Monospace, fontSize = 12.5.sp, color = palette.textPrimary)
                        Text("Sender Name: $senderDisplay", fontSize = 13.sp, color = palette.textPrimary)
                        Text("Receiver Name: $receiverDisplay", fontSize = 13.sp, color = palette.textSecondary)
                        Text("Date: ${rec.transactionDate}  •  App: ${rec.paymentApp}", fontSize = 12.sp, color = palette.textSecondary)

                        Spacer(modifier = Modifier.height(10.dp))
                        HorizontalDivider(color = palette.hairline)
                        Spacer(modifier = Modifier.height(8.dp))

                        // Admin Actions Row: Proof, Verify/Unverify, Reject, Delete
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = { proofModalRecord = rec },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("🖼️ Proof", fontSize = 12.sp)
                            }

                            Button(
                                onClick = {
                                    val nextStatus = if (isVerified) "Pending" else "Verified"
                                    scope.launch {
                                        LocalAndroidVaultStore.updateLocalPaymentStatus(context, rec.id, nextStatus)
                                        try {
                                            PayVaultApiClient.api.updatePaymentStatus(
                                                bearerToken = "Bearer $token",
                                                paymentId = rec.id,
                                                body = mapOf("verification_status" to nextStatus)
                                            )
                                        } catch (_: Exception) {
                                        }
                                        Toast.makeText(context, "Marked #${rec.id} as $nextStatus", Toast.LENGTH_SHORT).show()
                                        refreshAdminData(token)
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isVerified) AppleOrange else AppleGreen
                                ),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                modifier = Modifier.weight(1.1f)
                            ) {
                                Text(if (isVerified) "Unverify" else "✓ Verify", fontSize = 12.sp, color = Color.White)
                            }

                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        LocalAndroidVaultStore.deleteLocalPayment(context, rec.id)
                                        try {
                                            PayVaultApiClient.api.deletePaymentRecord(
                                                bearerToken = "Bearer $token",
                                                paymentId = rec.id
                                            )
                                        } catch (_: Exception) {
                                        }
                                        Toast.makeText(context, "Deleted record #${rec.id}", Toast.LENGTH_SHORT).show()
                                        refreshAdminData(token)
                                    }
                                },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Text("🗑️", fontSize = 12.sp, color = AppleRed)
                            }
                        }
                    }
                }
            }
        }

        // Screenshot Proof Lightbox Dialog
        proofModalRecord?.let { rec ->
            Dialog(onDismissRequest = { proofModalRecord = null }) {
                Surface(
                    color = palette.cardBg,
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "UPI Proof — ${rec.residentName} (Flat ${rec.flatNumber})",
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            color = palette.textPrimary
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        val localFile = LocalAndroidVaultStore.getLocalScreenshotFile(context, rec.screenshotFilename)
                        val imageModel: Any = if (localFile != null) {
                            localFile
                        } else {
                            val imgUrl = "${PayVaultApiClient.currentBaseUrl}api/admin/screenshots/${rec.screenshotFilename}"
                            ImageRequest.Builder(context)
                                .data(imgUrl)
                                .addHeader("Authorization", "Bearer $token")
                                .crossfade(true)
                                .build()
                        }
                        AsyncImage(
                            model = imageModel,
                            contentDescription = "Uploaded UPI Proof",
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(360.dp)
                                .clip(RoundedCornerShape(12.dp))
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = { proofModalRecord = null },
                            colors = ButtonDefaults.buttonColors(containerColor = AppleBlue),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Close Preview")
                        }
                    }
                }
            }
        }

        // Live CSV Preview Dialog
        csvPreviewText?.let { csvText ->
            Dialog(onDismissRequest = { csvPreviewText = null }) {
                Surface(
                    color = palette.cardBg,
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Live CSV Output Preview",
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            color = palette.textPrimary
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 120.dp, max = 340.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(0xFF141B27))
                                .padding(12.dp)
                                .verticalScroll(rememberScrollState())
                                .horizontalScroll(rememberScrollState())
                        ) {
                            Text(
                                text = csvText,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.5.sp,
                                color = Color(0xFFF2F5F9)
                            )
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = { csvPreviewText = null },
                            colors = ButtonDefaults.buttonColors(containerColor = AppleBlue),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Close CSV Preview")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AdminKpiTile(
    label: String,
    value: String,
    subtitle: String,
    valueColor: Color,
    modifier: Modifier = Modifier
) {
    val palette = LocalPayVaultPalette.current
    Surface(
        color = palette.cardBg,
        shape = RoundedCornerShape(14.dp),
        shadowElevation = 1.dp,
        modifier = modifier
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(text = label, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = palette.textSecondary)
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = value, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = valueColor)
            Text(text = subtitle, fontSize = 11.sp, color = palette.textSecondary)
        }
    }
}

@Composable
fun CupertinoGroupedCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    val palette = LocalPayVaultPalette.current
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = palette.textSecondary,
            modifier = Modifier.padding(start = 6.dp, bottom = 6.dp)
        )
        Surface(
            color = palette.cardBg,
            shape = RoundedCornerShape(16.dp),
            shadowElevation = 1.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                content = content
            )
        }
    }
}

@Composable
fun CupertinoTextFieldRow(
    label: String,
    value: String,
    placeholder: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    onValueChange: (String) -> Unit
) {
    val palette = LocalPayVaultPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.5.sp,
            color = palette.textPrimary,
            modifier = Modifier.width(132.dp)
        )
        TextField(
            value = value,
            onValueChange = onValueChange,
            enabled = true,
            readOnly = false,
            placeholder = { Text(placeholder, color = palette.textSecondary.copy(alpha = 0.65f), fontSize = 13.5.sp) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            shape = RoundedCornerShape(10.dp),
            colors = TextFieldDefaults.colors(
                focusedTextColor = palette.textPrimary,
                unfocusedTextColor = palette.textPrimary,
                focusedContainerColor = palette.subtleBg,
                unfocusedContainerColor = palette.subtleBg.copy(alpha = 0.7f),
                focusedIndicatorColor = AppleBlue,
                unfocusedIndicatorColor = Color.Transparent
            ),
            modifier = Modifier
                .weight(1f)
                .border(1.dp, AppleBlue.copy(alpha = 0.25f), RoundedCornerShape(10.dp))
        )
    }
}

/**
 * Strictly non-editable (read-only) display row for fields extracted from the payment screenshot.
 */
@Composable
fun CupertinoReadOnlyFieldRow(
    label: String,
    value: String,
    placeholder: String,
    isMonospace: Boolean = false
) {
    val palette = LocalPayVaultPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.5.sp,
            color = palette.textPrimary,
            modifier = Modifier.width(132.dp)
        )
        Text(
            text = if (value.isNotBlank()) value else placeholder,
            fontSize = 13.5.sp,
            fontWeight = if (value.isNotBlank()) FontWeight.SemiBold else FontWeight.Normal,
            fontFamily = if (isMonospace && value.isNotBlank()) FontFamily.Monospace else FontFamily.Default,
            color = if (value.isNotBlank()) palette.textPrimary else palette.textSecondary.copy(alpha = 0.55f),
            modifier = Modifier.weight(1f)
        )
    }
}
