/**
 * Madhav Maintenance Payment — Resident UPI Screenshot Upload, Google Messages Meetings & Admin Vault
 */

let selectedScreenshotFile = null;
let currentOcrText = "";
let adminBearerToken = "";
let isCurrentReceiptValid = false;
let tesseractLoaderPromise = null;

document.addEventListener("DOMContentLoaded", () => {
  initTheme();
  setupLiveMappingListeners();
  setupDragAndDrop();
  checkAdminSession();
  loadSocietyMeetings();

  document.addEventListener("click", (e) => {
    const wrapper = document.querySelector(".menu-wrapper");
    const dropdown = document.getElementById("topLeftDropdown");
    if (dropdown && wrapper && !wrapper.contains(e.target)) {
      dropdown.classList.remove("open");
    }
  });
});

function initTheme() {
  const saved = localStorage.getItem("madhav_theme") || "light";
  applyTheme(saved);
}

function applyTheme(mode) {
  const isDark = mode === "dark";
  if (isDark) {
    document.documentElement.setAttribute("data-theme", "dark");
  } else {
    document.documentElement.removeAttribute("data-theme");
  }
  const label = document.getElementById("darkModeLabel");
  const status = document.getElementById("darkModeStatus");
  if (label) label.textContent = isDark ? "☀️ Light Mode" : "🌙 Dark Mode";
  if (status) status.textContent = isDark ? "On" : "Off";
}

function toggleDarkMode() {
  const isCurrentlyDark = document.documentElement.getAttribute("data-theme") === "dark";
  const next = isCurrentlyDark ? "light" : "dark";
  localStorage.setItem("madhav_theme", next);
  applyTheme(next);
}

function toggleTopLeftMenu(event) {
  event.stopPropagation();
  const dropdown = document.getElementById("topLeftDropdown");
  if (dropdown) {
    dropdown.classList.toggle("open");
  }
}

function selectMenuDestination(viewName) {
  const dropdown = document.getElementById("topLeftDropdown");
  if (dropdown) dropdown.classList.remove("open");
  switchView(viewName);
}

function showToast(message) {
  const container = document.getElementById("toastContainer");
  if (!container) return;
  const toast = document.createElement("div");
  toast.className = "toast";
  toast.textContent = message;
  container.appendChild(toast);
  setTimeout(() => {
    toast.style.opacity = "0";
    toast.style.transition = "opacity 0.25s ease";
    setTimeout(() => toast.remove(), 260);
  }, 3400);
}

function switchView(viewName) {
  const resView = document.getElementById("residentView");
  const meetView = document.getElementById("meetingsView");
  const admView = document.getElementById("adminView");
  const payBtn = document.getElementById("tabPaymentBtn");
  const meetBtn = document.getElementById("tabMeetingsBtn");
  const homeTabControl = document.getElementById("homeTabControl");

  resView.classList.remove("active");
  meetView.classList.remove("active");
  admView.classList.remove("active");
  if (payBtn) payBtn.classList.remove("active");
  if (meetBtn) meetBtn.classList.remove("active");

  if (viewName === "admin") {
    admView.classList.add("active");
    if (homeTabControl) homeTabControl.style.display = "none";
    checkAdminSession();
  } else if (viewName === "meetings") {
    meetView.classList.add("active");
    if (homeTabControl) homeTabControl.style.display = "inline-flex";
    if (meetBtn) meetBtn.classList.add("active");
    loadSocietyMeetings();
  } else {
    resView.classList.add("active");
    if (homeTabControl) homeTabControl.style.display = "inline-flex";
    if (payBtn) payBtn.classList.add("active");
  }
}

/* ==========================================================================
   Live Column Mapping Preview (Resident Form)
   ========================================================================== */

function setupLiveMappingListeners() {
  const ids = [
    "residentName",
    "flatNumber",
    "paymentAmount",
    "ocrSenderName",
    "ocrReceiverName",
    "ocrUpiId",
    "ocrTransactionAmount",
    "ocrTransactionId",
    "ocrTransactionDate",
  ];
  ids.forEach((id) => {
    const el = document.getElementById(id);
    if (el) {
      el.addEventListener("input", updateMappedPreview);
    }
  });
}

function updateMappedPreview() {
  const val = (id) => (document.getElementById(id)?.value || "").trim();
  document.getElementById("mapName").textContent = val("residentName") || "—";
  document.getElementById("mapFlat").textContent = (val("flatNumber") || "—").toUpperCase();
  document.getElementById("mapPayAmt").textContent = val("paymentAmount")
    ? `₹${Number(val("paymentAmount")).toFixed(2)}`
    : "—";
  document.getElementById("mapUpi").textContent = val("ocrUpiId") || "—";
  document.getElementById("mapTxAmt").textContent = val("ocrTransactionAmount")
    ? `₹${Number(val("ocrTransactionAmount")).toFixed(2)}`
    : "—";
  document.getElementById("mapTxId").textContent = val("ocrTransactionId") || "—";
  document.getElementById("mapDate").textContent = val("ocrTransactionDate") || "—";
  document.getElementById("mapHolder").textContent = val("ocrSenderName") || "—";
  const recvEl = document.getElementById("mapReceiver");
  if (recvEl) recvEl.textContent = val("ocrReceiverName") || "—";
}

/* ==========================================================================
   Drag & Drop + Screenshot Selection + OCR Extraction
   ========================================================================== */

function setupDragAndDrop() {
  const dz = document.getElementById("dropzone");
  if (!dz) return;

  ["dragenter", "dragover"].forEach((evtName) => {
    dz.addEventListener(evtName, (e) => {
      e.preventDefault();
      e.stopPropagation();
      dz.classList.add("dragover");
    });
  });

  ["dragleave", "drop"].forEach((evtName) => {
    dz.addEventListener(evtName, (e) => {
      e.preventDefault();
      e.stopPropagation();
      dz.classList.remove("dragover");
    });
  });

  dz.addEventListener("drop", (e) => {
    const files = e.dataTransfer?.files;
    if (files && files.length > 0) {
      processSelectedFile(files[0], "");
    }
  });
}

function loadTesseractJs() {
  if (window.Tesseract) return Promise.resolve(window.Tesseract);
  if (tesseractLoaderPromise) return tesseractLoaderPromise;
  tesseractLoaderPromise = new Promise((resolve, reject) => {
    const script = document.createElement("script");
    script.src = "https://cdn.jsdelivr.net/npm/tesseract.js@5/dist/tesseract.min.js";
    script.onload = () => resolve(window.Tesseract);
    script.onerror = () => reject(new Error("Could not load browser OCR engine"));
    document.head.appendChild(script);
  });
  return tesseractLoaderPromise;
}

function clearExtractedFormFields() {
  isCurrentReceiptValid = false;
  document.getElementById("paymentAmount").value = "";
  document.getElementById("ocrSenderName").value = "";
  document.getElementById("ocrReceiverName").value = "";
  document.getElementById("ocrUpiId").value = "";
  document.getElementById("ocrTransactionAmount").value = "";
  document.getElementById("ocrTransactionId").value = "";
  document.getElementById("ocrTransactionDate").value = "";
  document.getElementById("ocrPaymentApp").value = "";
  updateMappedPreview();
}

function setInvalidScreenshotError(errorMessage) {
  const banner = document.getElementById("invalidScreenshotBanner");
  const textEl = document.getElementById("invalidScreenshotText");
  if (!banner || !textEl) return;
  if (errorMessage) {
    textEl.textContent = errorMessage;
    banner.style.display = "block";
  } else {
    banner.style.display = "none";
  }
}

function handleFileSelected(event) {
  const file = event.target.files?.[0];
  if (file) {
    processSelectedFile(file, "");
  }
}

async function processSelectedFile(file, simulatedOcrText = "") {
  selectedScreenshotFile = file;
  currentOcrText = simulatedOcrText;
  clearExtractedFormFields();
  setInvalidScreenshotError(null);

  document.getElementById("dropzoneTitle").textContent = `Selected: ${file.name}`;
  const previewImg = document.getElementById("previewImage");
  const emptyState = document.getElementById("emptyPreviewState");

  const reader = new FileReader();
  reader.onload = (e) => {
    previewImg.src = e.target.result;
    previewImg.style.display = "block";
    emptyState.style.display = "none";
  };
  reader.readAsDataURL(file);

  const badge = document.getElementById("ocrStatusBadge");
  badge.className = "badge badge-pending";
  badge.textContent = "Verifying Payment Receipt...";

  let ocrTextToSend = simulatedOcrText;
  if (!ocrTextToSend) {
    try {
      const TesseractLib = await loadTesseractJs();
      if (TesseractLib) {
        const result = await TesseractLib.recognize(file, "eng");
        ocrTextToSend = result?.data?.text || "";
        currentOcrText = ocrTextToSend;
      }
    } catch (_) {
      // Fall through to server-side PNG metadata / text validation
    }
  }

  const formData = new FormData();
  formData.append("screenshot", file);
  formData.append("ocr_text", ocrTextToSend);
  formData.append("resident_name", document.getElementById("residentName").value || "");

  try {
    const resp = await fetch("/api/ocr/extract", {
      method: "POST",
      body: formData,
    });
    const data = await resp.json();
    if (!resp.ok || !data.ok || !data.extracted) {
      throw new Error(
        data.error ||
          "Invalid input: Please upload a valid payment receipt screenshot from Google Pay, PhonePe, Paytm, BHIM UPI, or your banking app."
      );
    }

    const ext = data.extracted;
    const formattedAmt =
      ext.transaction_amount !== undefined ? Number(ext.transaction_amount).toFixed(2) : "";

    // Auto-populate Payment Amount (editable) and Transaction Amount (read-only)
    document.getElementById("paymentAmount").value = formattedAmt;
    document.getElementById("ocrTransactionAmount").value = formattedAmt;
    document.getElementById("ocrSenderName").value =
      ext.sender_name || ext.account_holder_name || "";
    document.getElementById("ocrReceiverName").value = ext.receiver_name || "";
    document.getElementById("ocrUpiId").value = ext.upi_id || "";
    document.getElementById("ocrTransactionId").value = ext.transaction_id || "";
    document.getElementById("ocrTransactionDate").value = ext.transaction_date || "";
    document.getElementById("ocrPaymentApp").value = ext.payment_app || "";

    isCurrentReceiptValid = true;
    setInvalidScreenshotError(null);
    badge.className = "badge badge-verified";
    badge.textContent = `✓ Verified (${ext.payment_app || "UPI"})`;
    updateMappedPreview();
    showToast(`Verified ₹${formattedAmt} payment receipt from ${ext.payment_app || "UPI"}!`);
  } catch (err) {
    clearExtractedFormFields();
    const msg =
      err.message ||
      "Invalid input: Please upload a valid payment screenshot from Google Pay, PhonePe, Paytm, BHIM UPI, or your bank app.";
    setInvalidScreenshotError(msg);
    badge.className = "badge badge-rejected";
    badge.textContent = "Invalid Screenshot";
    showToast(msg);
  }
}

/* ==========================================================================
   Built-in Realistic UPI Screenshot Generator (GPay, PhonePe, BHIM, Bank)
   ========================================================================== */

function generateSampleReceipt(appType) {
  const sampleProfiles = [
    { name: "Aarav Mehta", flat: "A-402", amount: 3500.0, handle: "okicici" },
    { name: "Priya Nair", flat: "B-204", amount: 4200.0, handle: "ybl" },
    { name: "Rohan Kulkarni", flat: "C-101", amount: 2800.0, handle: "upi" },
    { name: "Ananya Verma", flat: "D-503", amount: 5000.0, handle: "okhdfcbank" },
  ];

  const residentInput = document.getElementById("residentName");
  const flatInput = document.getElementById("flatNumber");

  const chosen = sampleProfiles[Math.floor(Math.random() * sampleProfiles.length)];
  if (!residentInput.value.trim()) residentInput.value = chosen.name;
  if (!flatInput.value.trim()) flatInput.value = chosen.flat;

  const senderName = residentInput.value.trim();
  const receiverName = "Madhav Apartment Maintenance";
  const amount = chosen.amount.toFixed(2);
  const utr = String(Math.floor(100000000000 + Math.random() * 899999999999));
  const today = new Date().toISOString().slice(0, 10);

  const slug = senderName.toLowerCase().replace(/[^a-z0-9]/g, "").slice(0, 12) || "resident";
  const handleMap = {
    "Google Pay": "okicici",
    "PhonePe": "ybl",
    "BHIM UPI": "upi",
    "Bank Transfer": "hdfcbank",
  };
  const upiId = `${slug}@${handleMap[appType] || "okaxis"}`;

  // Draw visual receipt on canvas
  const canvas = document.getElementById("receiptCanvas");
  const ctx = canvas.getContext("2d");

  const themeColors = {
    "Google Pay": { header: "#1A73E8", accent: "#E8F0FE" },
    "PhonePe": { header: "#5F259F", accent: "#F3E8FF" },
    "BHIM UPI": { header: "#00875A", accent: "#E6F4EA" },
    "Bank Transfer": { header: "#0F172A", accent: "#F1F5F9" },
  };
  const theme = themeColors[appType] || themeColors["Google Pay"];

  ctx.fillStyle = "#F8FAFC";
  ctx.fillRect(0, 0, canvas.width, canvas.height);

  ctx.fillStyle = theme.header;
  ctx.fillRect(0, 0, canvas.width, 175);

  ctx.fillStyle = "#FFFFFF";
  ctx.font = "bold 28px -apple-system, sans-serif";
  ctx.fillText(`${appType} • Payment Successful`, 44, 72);
  ctx.font = "18px -apple-system, sans-serif";
  ctx.fillText(`Paid to: ${receiverName}`, 44, 115);

  ctx.fillStyle = "#FFFFFF";
  ctx.fillRect(36, 145, 608, 665);

  ctx.fillStyle = "#0F172A";
  ctx.font = "bold 54px -apple-system, sans-serif";
  ctx.fillText(`₹${amount}`, 72, 240);

  ctx.fillStyle = "#16A34A";
  ctx.font = "bold 20px -apple-system, sans-serif";
  ctx.fillText("✓ Completed • Verified UPI Transaction", 72, 282);

  ctx.strokeStyle = "#E2E8F0";
  ctx.lineWidth = 2;
  ctx.beginPath();
  ctx.moveTo(72, 315);
  ctx.lineTo(600, 315);
  ctx.stroke();

  const rows = [
    ["Sender Name (From)", senderName],
    ["Receiver Name (To)", receiverName],
    ["Sender UPI ID", upiId],
    ["Transaction Amount", `₹${amount}`],
    ["UPI Transaction ID (UTR)", utr],
    ["Transaction Date", today],
    ["Payment App", appType],
  ];

  let y = 368;
  rows.forEach(([label, value]) => {
    ctx.fillStyle = "#64748B";
    ctx.font = "18px -apple-system, sans-serif";
    ctx.fillText(label, 72, y);

    ctx.fillStyle = "#0F172A";
    ctx.font = "bold 20px -apple-system, monospace";
    ctx.fillText(String(value), 72, y + 28);
    y += 64;
  });

  const simulatedOcrString = [
    `${appType} Payment Successful`,
    `Paid ₹${amount}`,
    `Paid to: ${receiverName}`,
    `From: ${senderName}`,
    `UPI ID: ${upiId}`,
    `UPI transaction ID: ${utr}`,
    `Date: ${today}`,
  ].join("\n");

  canvas.toBlob((blob) => {
    if (!blob) return;
    const fileName = `${appType.toLowerCase().replace(/\s+/g, "_")}_${utr}.png`;
    const file = new File([blob], fileName, { type: "image/png" });
    processSelectedFile(file, simulatedOcrString);
  }, "image/png");
}

/* ==========================================================================
   Submit Resident Payment Proof (Write-Only)
   ========================================================================== */

async function handleResidentSubmit(event) {
  event.preventDefault();

  const residentName = document.getElementById("residentName").value.trim();
  const flatNumber = document.getElementById("flatNumber").value.trim().toUpperCase();
  const paymentAmount = document.getElementById("paymentAmount").value.trim();
  const senderName = document.getElementById("ocrSenderName").value.trim();
  const receiverName = document.getElementById("ocrReceiverName").value.trim();
  const upiId = document.getElementById("ocrUpiId").value.trim();
  const txAmount = document.getElementById("ocrTransactionAmount").value.trim();
  const txId = document.getElementById("ocrTransactionId").value.trim();
  const txDate = document.getElementById("ocrTransactionDate").value.trim();
  const paymentApp = document.getElementById("ocrPaymentApp").value;

  if (!residentName || !flatNumber || !paymentAmount) {
    showToast("Please fill in your Full Name, Flat Number, and Payment Amount.");
    return;
  }
  if (!selectedScreenshotFile || !isCurrentReceiptValid || !txId) {
    showToast("Please upload a valid UPI or bank payment receipt screenshot.");
    return;
  }

  const submitBtn = document.getElementById("submitPaymentBtn");
  submitBtn.disabled = true;
  submitBtn.textContent = "Encrypting & Uploading...";

  const formData = new FormData();
  formData.append("resident_name", residentName);
  formData.append("flat_number", flatNumber);
  formData.append("payment_amount", paymentAmount);
  formData.append("sender_name", senderName);
  formData.append("account_holder_name", senderName);
  formData.append("receiver_name", receiverName);
  formData.append("upi_id", upiId);
  formData.append("transaction_amount", txAmount);
  formData.append("transaction_id", txId);
  formData.append("transaction_date", txDate);
  formData.append("payment_app", paymentApp);
  formData.append("ocr_text", currentOcrText);
  formData.append("allow_resubmit", "true");
  formData.append("screenshot", selectedScreenshotFile);

  try {
    const resp = await fetch("/api/payments/submit", {
      method: "POST",
      body: formData,
    });
    const data = await resp.json();
    if (!resp.ok || !data.ok) {
      throw new Error(data.error || "Submission failed.");
    }

    const receiptBox = document.getElementById("submissionReceiptBox");
    const receiptText = document.getElementById("submissionReceiptText");
    receiptBox.style.display = "flex";
    receiptText.innerHTML = `
      <strong>Reference:</strong> ${data.receipt.reference_no} &nbsp;|&nbsp;
      <strong>Flat:</strong> ${data.receipt.flat_number} &nbsp;|&nbsp;
      <strong>Submitted:</strong> ${data.receipt.submitted_at}<br/>
      ${data.message}
    `;

    showToast("Payment proof submitted to Admin Vault!");

    document.getElementById("residentPaymentForm").reset();
    selectedScreenshotFile = null;
    currentOcrText = "";
    clearExtractedFormFields();
    setInvalidScreenshotError(null);
    document.getElementById("dropzoneTitle").textContent = "Tap to Select Payment Screenshot";
    document.getElementById("ocrStatusBadge").className = "badge badge-pending";
    document.getElementById("ocrStatusBadge").textContent = "Awaiting Screenshot";
    updateMappedPreview();
  } catch (err) {
    showToast(err.message || "Error submitting payment proof.");
  } finally {
    submitBtn.disabled = false;
    submitBtn.innerHTML = "<span>🔐</span> Submit Proof to Admin Vault";
  }
}

/* ==========================================================================
   Society Meetings (Google Messages Style, Read-Only for Residents)
   ========================================================================== */

async function loadSocietyMeetings() {
  const feed = document.getElementById("meetingsFeed");
  const countBadge = document.getElementById("menuMeetingsCount");
  if (!feed) return;

  try {
    const resp = await fetch("/api/meetings");
    const data = await resp.json();
    const meetings = data.meetings || [];
    if (countBadge) {
      countBadge.textContent = `${meetings.length}`;
    }

    if (meetings.length === 0) {
      feed.innerHTML = `
        <div style="text-align: center; padding: 48px 20px; color: var(--text-secondary);">
          <div style="font-size: 36px; margin-bottom: 10px;">💬</div>
          <div style="font-weight: 600;">No society meeting notifications yet</div>
          <div style="font-size: 12.5px; color: var(--text-tertiary); margin-top: 4px;">
            Messages sent by the Admin will appear here like Google Messages.
          </div>
        </div>
      `;
      return;
    }

    feed.innerHTML = meetings
      .map(
        (m) => `
        <div class="gmessages-row">
          <img src="/static/img/app_logo.jpg" alt="Admin" class="gmessages-avatar" />
          <div class="gmessages-bubble">
            <div class="gmessages-date-pill">
              📅 Meeting Date: ${escapeHtml(m.meeting_date)}
            </div>
            <div class="gmessages-topic">${escapeHtml(m.topic)}</div>
            <div class="gmessages-body">${escapeHtml(m.body)}</div>
            <div class="gmessages-meta">
              Madhav Society Admin • Posted ${escapeHtml(m.created_at)}
            </div>
          </div>
        </div>
      `
      )
      .join("");
  } catch (_) {
    feed.innerHTML = `
      <div style="text-align: center; padding: 32px; color: var(--text-secondary);">
        Could not load society meeting messages.
      </div>
    `;
  }
}

async function handleAdminSendMeeting(event) {
  event.preventDefault();
  const meetingDate = document.getElementById("adminMeetingDate").value.trim();
  const topic = document.getElementById("adminMeetingTopic").value.trim();
  const body = document.getElementById("adminMeetingBody").value.trim();

  if (!meetingDate || !topic || !body) {
    showToast("Please enter Meeting Date, Topic/Subject, and Message Body.");
    return;
  }

  try {
    const resp = await fetch("/api/admin/meetings", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        ...(adminBearerToken ? { Authorization: `Bearer ${adminBearerToken}` } : {}),
      },
      body: JSON.stringify({ meeting_date: meetingDate, topic, body }),
    });
    const data = await resp.json();
    if (!resp.ok || !data.ok) {
      throw new Error(data.error || "Failed to send meeting notification.");
    }
    document.getElementById("adminMeetingDate").value = "";
    document.getElementById("adminMeetingTopic").value = "";
    document.getElementById("adminMeetingBody").value = "";
    showToast("Meeting notification broadcast to all residents!");
    loadSocietyMeetings();
  } catch (err) {
    showToast(err.message || "Could not send meeting notification.");
  }
}

/* ==========================================================================
   Admin Authentication & Admin-Only Data Operations
   ========================================================================== */

async function checkAdminSession() {
  try {
    const resp = await fetch("/api/admin/session", {
      headers: adminBearerToken ? { Authorization: `Bearer ${adminBearerToken}` } : {},
    });
    const data = await resp.json();
    toggleAdminGate(Boolean(data.authenticated));
    if (data.authenticated) {
      loadAdminPayments();
    }
  } catch (_) {
    toggleAdminGate(false);
  }
}

function toggleAdminGate(isAuthenticated) {
  const loginGate = document.getElementById("adminLoginGate");
  const dashboard = document.getElementById("adminDashboardContent");
  if (isAuthenticated) {
    loginGate.style.display = "none";
    dashboard.style.display = "block";
  } else {
    loginGate.style.display = "block";
    dashboard.style.display = "none";
  }
}

async function handleAdminLogin(event) {
  event.preventDefault();
  const username = document.getElementById("adminUsername").value.trim();
  const password = document.getElementById("adminPassword").value;

  try {
    const resp = await fetch("/api/admin/login", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ username, password }),
    });
    const data = await resp.json();
    if (!resp.ok || !data.ok) {
      throw new Error(data.error || "Invalid credentials.");
    }
    adminBearerToken = data.token || "";
    toggleAdminGate(true);
    showToast("Admin Vault unlocked!");
    loadAdminPayments();
  } catch (err) {
    showToast(err.message || "Admin login failed.");
  }
}

async function handleAdminLogout() {
  await fetch("/api/admin/logout", { method: "POST" });
  adminBearerToken = "";
  toggleAdminGate(false);
  showToast("Admin Vault locked.");
}

async function loadAdminPayments() {
  const search = document.getElementById("adminSearchInput")?.value || "";
  const status = document.getElementById("adminStatusFilter")?.value || "all";

  const params = new URLSearchParams({ search, status });
  try {
    const resp = await fetch(`/api/admin/payments?${params.toString()}`, {
      headers: adminBearerToken ? { Authorization: `Bearer ${adminBearerToken}` } : {},
    });
    if (resp.status === 401) {
      toggleAdminGate(false);
      return;
    }
    const data = await resp.json();
    if (!data.ok) return;

    renderAdminStats(data.stats);
    renderAdminTable(data.payments);
  } catch (err) {
    showToast("Failed to load payment records.");
  }
}

function renderAdminStats(stats) {
  if (!stats) return;
  document.getElementById("kpiTotalEntries").textContent = stats.total_entries;
  document.getElementById("kpiPaymentTotal").textContent = `₹${Number(
    stats.total_payment_amount
  ).toLocaleString("en-IN", { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;
  document.getElementById("kpiTxTotal").textContent = `₹${Number(
    stats.total_transaction_amount
  ).toLocaleString("en-IN", { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;
  document.getElementById("kpiVerifiedCount").textContent = `${stats.verified_count} Verified`;
  document.getElementById("kpiMismatchSub").textContent = `${stats.mismatch_count} Amount Mismatch(es)`;
}

function getStatusBadgeHtml(status) {
  const s = (status || "Pending").toLowerCase();
  if (s === "verified") return `<span class="badge badge-verified">✓ Verified</span>`;
  if (s.includes("mismatch")) return `<span class="badge badge-mismatch">⚠ Amount Mismatch</span>`;
  if (s === "rejected") return `<span class="badge badge-rejected">✕ Rejected</span>`;
  return `<span class="badge badge-pending">● Pending</span>`;
}

function escapeHtml(str) {
  return String(str ?? "")
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;");
}

function renderAdminTable(payments) {
  const tbody = document.getElementById("adminTableBody");
  if (!payments || payments.length === 0) {
    tbody.innerHTML = `
      <tr>
        <td colspan="12" style="text-align: center; padding: 36px; color: var(--text-secondary);">
          No UPI payment entries match the current filter.
        </td>
      </tr>
    `;
    return;
  }

  tbody.innerHTML = payments
    .map((p) => {
      const nextStatus = p.verification_status === "Verified" ? "Pending" : "Verified";
      const sender = p.sender_name || p.account_holder_name || "Not shown on receipt";
      const receiver = p.receiver_name || "Not shown on receipt";
      return `
        <tr>
          <td class="mono-text">#${p.id}</td>
          <td><strong>${escapeHtml(p.resident_name)}</strong></td>
          <td><span class="badge badge-pending">${escapeHtml(p.flat_number)}</span></td>
          <td>₹${Number(p.payment_amount).toFixed(2)}</td>
          <td class="mono-text">${escapeHtml(p.upi_id)}</td>
          <td><strong>₹${Number(p.transaction_amount).toFixed(2)}</strong></td>
          <td class="mono-text">${escapeHtml(p.transaction_id)}</td>
          <td>${escapeHtml(p.transaction_date)}</td>
          <td>${escapeHtml(sender)}</td>
          <td>${escapeHtml(receiver)}</td>
          <td>${getStatusBadgeHtml(p.verification_status)}</td>
          <td style="white-space: nowrap;">
            <button type="button" class="chip-btn" onclick="viewScreenshotModal('${escapeHtml(
              p.screenshot_filename
            )}', '${escapeHtml(p.resident_name)} (${escapeHtml(p.flat_number)})')">
              🖼️ Proof
            </button>
            <button type="button" class="chip-btn" onclick="updatePaymentStatus(${p.id}, '${nextStatus}')">
              ${p.verification_status === "Verified" ? "Unverify" : "✓ Verify"}
            </button>
            <button type="button" class="chip-btn" style="color: var(--apple-red);" onclick="deletePaymentRecord(${
              p.id
            })">
              🗑️
            </button>
          </td>
        </tr>
      `;
    })
    .join("");
}

async function updatePaymentStatus(paymentId, newStatus) {
  try {
    const resp = await fetch(`/api/admin/payments/${paymentId}`, {
      method: "PATCH",
      headers: {
        "Content-Type": "application/json",
        ...(adminBearerToken ? { Authorization: `Bearer ${adminBearerToken}` } : {}),
      },
      body: JSON.stringify({ verification_status: newStatus }),
    });
    if (resp.ok) {
      showToast(`Record #${paymentId} marked as ${newStatus}.`);
      loadAdminPayments();
    }
  } catch (_) {
    showToast("Failed to update status.");
  }
}

async function deletePaymentRecord(paymentId) {
  try {
    const resp = await fetch(`/api/admin/payments/${paymentId}`, {
      method: "DELETE",
      headers: adminBearerToken ? { Authorization: `Bearer ${adminBearerToken}` } : {},
    });
    if (resp.ok) {
      showToast(`Deleted payment record #${paymentId}.`);
      loadAdminPayments();
    }
  } catch (_) {
    showToast("Failed to delete record.");
  }
}

/* ==========================================================================
   Multi-Format CSV & Excel (.xlsx) Export Studio
   ========================================================================== */

function getSelectedExportColumns() {
  const checkboxes = document.querySelectorAll("#exportColumnsBar input[type='checkbox']:checked");
  return Array.from(checkboxes).map((cb) => cb.value);
}

function handleFormatPresetChange() {
  const preset = document.getElementById("exportFormatSelect").value;
  const checkboxes = document.querySelectorAll("#exportColumnsBar input[type='checkbox']");
  const strict6 = [
    "resident_name",
    "flat_number",
    "payment_amount",
    "upi_id",
    "transaction_amount",
    "transaction_id",
  ];
  const default8 = [...strict6, "transaction_date", "account_holder_name"];

  if (preset === "core_6_csv") {
    checkboxes.forEach((cb) => {
      cb.checked = strict6.includes(cb.value);
    });
  } else if (preset === "excel_bom_csv" || preset === "xlsx" || preset === "accounting_csv") {
    checkboxes.forEach((cb) => {
      cb.checked = true;
    });
  } else {
    checkboxes.forEach((cb) => {
      cb.checked = default8.includes(cb.value);
    });
  }
}

function buildExportUrl(overrideFormat = null) {
  const format = overrideFormat || document.getElementById("exportFormatSelect").value;
  const delimiter = document.getElementById("exportDelimiterSelect").value;
  const dateStyle = document.getElementById("exportDateStyleSelect").value;
  const search = document.getElementById("adminSearchInput")?.value || "";
  const status = document.getElementById("adminStatusFilter")?.value || "all";
  const cols = getSelectedExportColumns();

  const params = new URLSearchParams();
  params.set("format", format);
  if (delimiter) params.set("delimiter", delimiter);
  if (dateStyle) params.set("date_style", dateStyle);
  if (cols.length > 0 && format !== "accounting_csv") {
    params.set("columns", cols.join(","));
  }
  if (search) params.set("search", search);
  if (status && status !== "all") params.set("status", status);

  return `/api/admin/export?${params.toString()}`;
}

function triggerAdminExport(overrideFormat = null) {
  const url = buildExportUrl(overrideFormat);
  window.location.href = url;
  showToast("Preparing download...");
}

async function previewCsvModal() {
  const selectedFormat = document.getElementById("exportFormatSelect").value;
  const formatForPreview = selectedFormat === "xlsx" ? "standard_csv" : selectedFormat;
  const url = buildExportUrl(formatForPreview);

  try {
    const resp = await fetch(url, {
      headers: adminBearerToken ? { Authorization: `Bearer ${adminBearerToken}` } : {},
    });
    const text = await resp.text();
    openModal(
      `Live CSV Preview (${formatForPreview})`,
      `<pre class="mono-text" style="background: #1C1C1E; color: #F2F2F7; padding: 16px; border-radius: 12px; overflow-x: auto; font-size: 12px; line-height: 1.55;">${escapeHtml(
        text
      )}</pre>`
    );
  } catch (_) {
    showToast("Could not load CSV preview.");
  }
}

function viewScreenshotModal(filename, caption) {
  openModal(
    `UPI Payment Proof — ${caption}`,
    `<div style="text-align: center;">
      <img src="/api/admin/screenshots/${encodeURIComponent(
        filename
      )}" alt="UPI Proof" style="max-width: 100%; border-radius: 14px; box-shadow: 0 8px 24px rgba(0,0,0,0.12);" />
    </div>`
  );
}

function openModal(title, bodyHtml) {
  document.getElementById("modalTitle").textContent = title;
  document.getElementById("modalBody").innerHTML = bodyHtml;
  document.getElementById("modalBackdrop").classList.add("open");
}

function closeModal() {
  document.getElementById("modalBackdrop").classList.remove("open");
}
