package com.muslimguide.shield

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.textfield.TextInputEditText
import com.muslimguide.shield.adb.AdbWirelessManager
import com.muslimguide.shield.adb.AdbWirelessNotificationService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.tasks.await
import com.google.firebase.database.FirebaseDatabase
import android.provider.Settings as AndroidSettings
import com.google.android.material.bottomsheet.BottomSheetDialog

class MainActivity : AppCompatActivity() {

    // ── State ──────────────────────────────────────────
    enum class ShieldState {
        NEEDS_AUTH,    // guardian_uid নেই
        NEEDS_ADB,     // uid আছে কিন্তু Device Owner না
        ADB_ACTIVE,    // ADB connected, commands running
        FULLY_PAIRED   // Device Owner = true
    }

    // ── Root ScrollView ───────────────────────────────
    private lateinit var nestedScrollView: NestedScrollView

    // ── Header views (সব layer-এ দৃশ্যমান) ──────────
    private lateinit var cardContainer: MaterialCardView
    private lateinit var frameIcon: FrameLayout
    private lateinit var ivShieldIcon: ImageView
    private lateinit var tvStatusBadge: TextView
    private lateinit var tvGuardianName: TextView
    private lateinit var tvStatusDescription: TextView

    // ── Layer containers ──────────────────────────────
    private lateinit var layerAuth: LinearLayout
    private lateinit var layerAdbSetup: LinearLayout
    private lateinit var layerAdbTerminal: LinearLayout
    private lateinit var layerPaired: LinearLayout

    // ── Layer 1: Auth (Dynamic Self-Protection & Child Pairing) ──
    private lateinit var cardMainAppAction: MaterialCardView
    private lateinit var frameMainAppIcon: FrameLayout
    private lateinit var ivMainAppIcon: ImageView
    private lateinit var tvMainAppCardTitle: TextView
    private lateinit var tvMainAppSubtitle: TextView
    private lateinit var tvMainAppBadge: TextView
    private lateinit var btnAutoSyncMainApp: MaterialButton
    private lateinit var btnDownloadMainAppAuth: MaterialButton
    private lateinit var btnScanQr: MaterialButton
    private lateinit var btnUploadQr: MaterialButton

    // ── Layer 2: ADB Setup ────────────────────────────
    private lateinit var btnAutoActivate: MaterialCardView
    private lateinit var btnManualConnect: MaterialButton
    private lateinit var etAdbPort: TextInputEditText
    private lateinit var etAdbPairingCode: TextInputEditText
    private lateinit var btnOpenDeveloperSettings: MaterialButton

    // ── Layer 3: Terminal ─────────────────────────────
    private lateinit var cardTerminalOutput: MaterialCardView
    private lateinit var tvTerminalOutput: TextView
    private lateinit var btnCopyPcCommand: MaterialCardView
    private lateinit var btnCancelAdbTerminal: MaterialButton

    // ── Layer 4: Paired ───────────────────────────────
    private lateinit var btnHideAppIcon: MaterialButton
    private lateinit var btnOpenMainAppPaired: MaterialButton

    // ── State vars ────────────────────────────────────
    private var connectedAdbPort: Int = 0
    private var pendingGuardianUid: String? = null
    private var pendingGuardianName: String? = null
    private var isSelfDevice: Boolean = false

    // ── Notification Permission Launcher (Android 13+) ─
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        startAdbPairingFlow()
    }

    // ── Gallery QR Picker launcher ─────────────────────
    private val galleryQrLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            val intent = Intent(this, QrScannerActivity::class.java).apply {
                putExtra("gallery_uri", uri.toString())
            }
            qrScannerLauncher.launch(intent)
        }
    }

    // ── QR Scanner launcher ───────────────────────────
    private val qrScannerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val scannedUid = result.data?.getStringExtra("guardian_uid")
                ?: getSharedPreferences("shield_prefs", Context.MODE_PRIVATE)
                    .getString("guardian_uid", null)
            val scannedName = result.data?.getStringExtra("guardian_name")
                ?: getSharedPreferences("shield_prefs", Context.MODE_PRIVATE)
                    .getString("guardian_name", null)
            val isSelfScanned = result.data?.getBooleanExtra("is_self_device", false) ?: false
            saveIsSelfDevice(isSelfScanned)

            if (!scannedName.isNullOrBlank()) {
                saveGuardianName(scannedName)
            }
            if (!scannedUid.isNullOrBlank()) {
                saveGuardianUid(scannedUid)
                val successMsg = if (isSelfScanned) {
                    "আপনার নিজস্ব অ্যাকাউন্ট সফলভাবে লিঙ্ক হয়েছে!"
                } else {
                    "অভিভাবক অ্যাকাউন্ট সফলভাবে লিঙ্ক হয়েছে!"
                }
                ShieldToast.showSuccess(this, successMsg, title = "লিঙ্ক সম্পন্ন")
                if (AdminReceiver.isDeviceOwner(this)) {
                    linkToFirebase()
                }
                renderState()
            }
        }
    }


    // ── ADB Status receiver ───────────────────────────
    private val adbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AdbWirelessNotificationService.ACTION_ADB_STATUS_UPDATE) {
                val message = intent.getStringExtra(AdbWirelessNotificationService.EXTRA_STATUS_MESSAGE) ?: return
                val port = intent.getIntExtra(AdbWirelessNotificationService.EXTRA_CONNECT_PORT, 0)
                if (port > 0) connectedAdbPort = port

                // Show terminal layer when ADB is active
                showTerminalLayer()
                appendTerminal(message)

                if (message.contains("সংযুক্ত", ignoreCase = true) || message.contains("connected", ignoreCase = true)) {
                    appendTerminal("✓ ADB সংযুক্ত! ডিভাইস ওনার পারমিশন সেট হচ্ছে...")
                    runFullAutoActivation(connectedAdbPort)
                }
            }
        }
    }

    // ══════════════════════════════════════════════════
    // Lifecycle
    // ══════════════════════════════════════════════════

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        setupListeners()

        // Guardian UID ও Name: মূল অ্যাপ Intent থেকে আসে
        val uidFromIntent = intent?.getStringExtra("guardian_uid")
        val nameFromIntent = intent?.getStringExtra("guardian_name")
        val isSelfFromIntent = intent?.getBooleanExtra("is_self_device", false) ?: false
        if (isSelfFromIntent) {
            saveIsSelfDevice(true)
        } else {
            isSelfDevice = getSharedPreferences("shield_prefs", Context.MODE_PRIVATE)
                .getBoolean("is_self_device", false)
        }

        if (!nameFromIntent.isNullOrBlank()) {
            saveGuardianName(nameFromIntent)
        } else {
            pendingGuardianName = getSharedPreferences("shield_prefs", Context.MODE_PRIVATE)
                .getString("guardian_name", null)
        }
        if (!uidFromIntent.isNullOrBlank()) {
            saveGuardianUid(uidFromIntent)
        } else {
            pendingGuardianUid = getSharedPreferences("shield_prefs", Context.MODE_PRIVATE)
                .getString("guardian_uid", null)
        }

        // 2. মূল অ্যাপ ইনস্টল থাকলে এবং লগইন অবস্থায় থাকলে স্বয়ংক্রিয়ভাবে অথেন্টিকেশন নেওয়া হবে
        if (pendingGuardianUid.isNullOrBlank()) {
            checkAndAutoAuthenticateFromMainApp()
        }

        renderState()
    }

    override fun onResume() {
        super.onResume()
        if (pendingGuardianUid.isNullOrBlank()) {
            checkAndAutoAuthenticateFromMainApp()
        }
        renderState()
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(AdbWirelessNotificationService.ACTION_ADB_STATUS_UPDATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(adbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(adbReceiver, filter)
        }
    }

    override fun onStop() {
        super.onStop()
        try { unregisterReceiver(adbReceiver) } catch (_: Exception) {}
    }

    // ══════════════════════════════════════════════════
    // Init
    // ══════════════════════════════════════════════════

    private fun initViews() {
        // Root
        nestedScrollView = findViewById(R.id.nestedScrollView)

        // Header
        cardContainer   = findViewById(R.id.cardShieldContainer)
        frameIcon       = findViewById(R.id.frameIconContainer)
        ivShieldIcon    = findViewById(R.id.ivShieldIcon)
        tvStatusBadge   = findViewById(R.id.tvStatusBadge)
        tvGuardianName  = findViewById(R.id.tvGuardianName)
        tvStatusDescription = findViewById(R.id.tvStatusDescription)

        // Layers
        layerAuth        = findViewById(R.id.layerAuth)
        layerAdbSetup    = findViewById(R.id.layerAdbSetup)
        layerAdbTerminal = findViewById(R.id.layerAdbTerminal)
        layerPaired      = findViewById(R.id.layerPaired)

        // Layer 1 — Auth
        cardMainAppAction      = findViewById(R.id.cardMainAppAction)
        frameMainAppIcon       = findViewById(R.id.frameMainAppIcon)
        ivMainAppIcon          = findViewById(R.id.ivMainAppIcon)
        tvMainAppCardTitle     = findViewById(R.id.tvMainAppCardTitle)
        tvMainAppSubtitle      = findViewById(R.id.tvMainAppSubtitle)
        tvMainAppBadge         = findViewById(R.id.tvMainAppBadge)
        btnAutoSyncMainApp     = findViewById(R.id.btnAutoSyncMainApp)
        btnDownloadMainAppAuth = findViewById(R.id.btnDownloadMainAppAuth)
        btnScanQr              = findViewById(R.id.btnScanQr)
        btnUploadQr            = findViewById(R.id.btnUploadQr)

        // Layer 2 — ADB Setup
        btnAutoActivate  = findViewById(R.id.btnAutoActivate)
        btnManualConnect = findViewById(R.id.btnManualConnect)
        etAdbPort        = findViewById(R.id.etAdbPort)
        etAdbPairingCode = findViewById(R.id.etAdbPairingCode)
        btnOpenDeveloperSettings = findViewById(R.id.btnOpenDeveloperSettings)

        // Layer 3 — Terminal
        cardTerminalOutput = findViewById(R.id.cardTerminalOutput)
        tvTerminalOutput   = findViewById(R.id.tvTerminalOutput)
        btnCopyPcCommand   = findViewById(R.id.btnCopyPcCommand)
        btnCancelAdbTerminal = findViewById(R.id.btnCancelAdbTerminal)

        // Layer 4 — Paired
        btnHideAppIcon      = findViewById(R.id.btnHideAppIcon)
        btnOpenMainAppPaired = findViewById(R.id.btnOpenMainAppPaired)
    }

    private fun setupListeners() {
        // Auth layer actions
        btnDownloadMainAppAuth.setOnClickListener { openDownloadPage() }
        btnAutoSyncMainApp.setOnClickListener {
            if (checkAndAutoAuthenticateFromMainApp()) {
                renderState()
            } else {
                showAutoSyncBottomSheet()
            }
        }

        btnScanQr.setOnClickListener { openQrScanner() }
        btnUploadQr.setOnClickListener { galleryQrLauncher.launch("image/*") }

        // ADB setup actions
        btnAutoActivate.setOnClickListener { handleAutoActivateClick() }
        btnManualConnect.setOnClickListener { handleAutoActivateClick() }
        btnOpenDeveloperSettings.setOnClickListener { openDeveloperSettings() }

        // Terminal actions
        btnCopyPcCommand.setOnClickListener { copyAdbCommand() }
        btnCancelAdbTerminal.setOnClickListener { cancelAdbTerminalSession() }

        // Paired actions
        btnHideAppIcon.setOnClickListener      { hideAppIcon() }
        btnOpenMainAppPaired.setOnClickListener { openMuslimGuideApp() }

        // Online Support and Tutorials
        findViewById<MaterialButton?>(R.id.btnOpenShieldSupport)?.setOnClickListener {
            showShieldSupportBottomSheet()
        }
    }

    // ══════════════════════════════════════════════════
    // State Machine
    // ══════════════════════════════════════════════════

    private fun getCurrentState(): ShieldState {
        return when {
            AdminReceiver.isDeviceOwner(this) -> ShieldState.FULLY_PAIRED
            !pendingGuardianUid.isNullOrBlank() -> ShieldState.NEEDS_ADB
            else -> ShieldState.NEEDS_AUTH
        }
    }

    private fun renderState() {
        val state = getCurrentState()

        // Hide all layers first
        layerAuth.visibility        = View.GONE
        layerAdbSetup.visibility    = View.GONE
        layerAdbTerminal.visibility = View.GONE
        layerPaired.visibility      = View.GONE

        when (state) {
            ShieldState.NEEDS_AUTH   -> renderAuthLayer()
            ShieldState.NEEDS_ADB    -> renderAdbSetupLayer()
            ShieldState.ADB_ACTIVE   -> { renderAdbSetupLayer(); showTerminalLayer() }
            ShieldState.FULLY_PAIRED -> renderPairedLayer()
        }
    }

    private fun renderAuthLayer() {
        tvGuardianName.visibility = View.GONE
        val isInstalled = isMainAppInstalled()

        if (isInstalled) {
            setHeaderAmber(
                badge = "● সিঙ্ক প্রয়োজন",
                description = "মুসলিম গাইড অ্যাকাউন্টের সাথে সিঙ্ক করুন অথবা বাচ্চার জন্য কিউআর স্ক্যান করুন।"
            )
            cardMainAppAction.strokeColor = Color.parseColor("#40059669")
            frameMainAppIcon.background = roundedBg("#2010B981")
            ivMainAppIcon.setColorFilter(Color.parseColor("#10B981"))
            ivMainAppIcon.setImageResource(R.drawable.ic_privacy_shield)
            tvMainAppCardTitle.text = "স্ব-সুরক্ষা মোড"
            tvMainAppBadge.text = "ইনস্টল আছে"
            tvMainAppBadge.setTextColor(Color.parseColor("#10B981"))
            tvMainAppBadge.background = roundedBg("#2010B981")
            tvMainAppSubtitle.text = "আপনার ডিভাইসে MuslimGuideBD ইনস্টল রয়েছে। এক ক্লিকে অ্যাকাউন্টের সাথে সিঙ্ক করে স্ব-সুরক্ষা মোড চালু করুন।"
            btnAutoSyncMainApp.visibility = View.VISIBLE
            btnDownloadMainAppAuth.visibility = View.GONE
        } else {
            setHeaderAmber(
                badge = "● অথেন্টিকেশন প্রয়োজন",
                description = "স্ব-সুরক্ষার জন্য মূল অ্যাপ ডাউনলোড করুন অথবা বাচ্চার জন্য কিউআর স্ক্যান করুন।"
            )
            cardMainAppAction.strokeColor = Color.parseColor("#402563EB")
            frameMainAppIcon.background = roundedBg("#202563EB")
            ivMainAppIcon.setColorFilter(Color.parseColor("#38BDF8"))
            ivMainAppIcon.setImageResource(R.drawable.ic_download)
            tvMainAppCardTitle.text = "মূল অ্যাপ ডাউনলোড"
            tvMainAppBadge.text = "রেকমেন্ডেড"
            tvMainAppBadge.setTextColor(Color.parseColor("#38BDF8"))
            tvMainAppBadge.background = roundedBg("#2038BDF8")
            tvMainAppSubtitle.text = "এই ডিভাইসে পূর্ণ স্ব-সুরক্ষা মোড সক্রিয় করতে প্রথমে মূল MuslimGuideBD অ্যাপটি ডাউনলোড করুন।"
            btnAutoSyncMainApp.visibility = View.GONE
            btnDownloadMainAppAuth.visibility = View.VISIBLE
        }
        layerAuth.visibility = View.VISIBLE
    }

    private fun openDownloadPage() {
        val urls = listOf(
            "https://muslimguide-bd.web.app",
            "market://details?id=com.muslimguide.bd"
        )
        for (url in urls) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                return
            } catch (_: Exception) { /* try next */ }
        }
        ShieldToast.showError(this, "ব্রাউজার ওপেন করা যায়নি", title = "ত্রুটি")
    }

    private fun renderAdbSetupLayer() {
        val guardianName = pendingGuardianName
        val uid = pendingGuardianUid ?: ""
        if (guardianName.isNullOrBlank() && uid.isNotBlank()) {
            fetchGuardianNameIfNeeded(uid)
        }

        val badgeText = if (isSelfDevice) "✅ স্ব-সুরক্ষা অ্যাকাউন্ট সংযুক্ত" else "✅ অভিভাবক অ্যাকাউন্ট সংযুক্ত"
        val descText = if (isSelfDevice) {
            "আপনার নিজস্ব ডিভাইসে ১৮+ কন্টেন্ট সুরক্ষা সক্রিয় করতে নিচে ADB সংযোগ সম্পন্ন করুন।"
        } else {
            "ডিভাইস ওনার পারমিশন সেট করতে নিচে ADB সংযোগ সম্পন্ন করুন।"
        }

        setHeaderGreen(badge = badgeText, description = descText)
        tvGuardianName.visibility = View.VISIBLE
        tvGuardianName.text = if (isSelfDevice) {
            if (!guardianName.isNullOrBlank()) "আপনার অ্যাকাউন্ট: $guardianName (স্ব-সুরক্ষা)" else "আপনার অ্যাকাউন্ট সংযুক্ত (স্ব-সুরক্ষা)"
        } else {
            if (!guardianName.isNullOrBlank()) "অভিভাবক: $guardianName" else "অভিভাবক অ্যাকাউন্ট সংযুক্ত"
        }

        layerAdbSetup.visibility = View.VISIBLE
    }

    private fun showTerminalLayer() {
        layerAdbTerminal.visibility = View.VISIBLE
    }

    private fun renderPairedLayer() {
        val descText = if (isSelfDevice) {
            "আপনার নিজস্ব ডিভাইসে ডিভাইস ওনার ও ১৮+ সুরক্ষা কার্যকর। হোমস্ক্রিন পরিষ্কার রাখতে আইকন হাইড করুন।"
        } else {
            "ডিভাইস ওনার ও ১৮+ সুরক্ষা কার্যকর। হোমস্ক্রিন পরিষ্কার রাখতে আইকন হাইড করুন।"
        }
        setHeaderGreen(
            badge = "● স্থায়ী ডিভাইস সুরক্ষা সক্রিয়",
            description = descText
        )
        val guardianName = pendingGuardianName
        if (!guardianName.isNullOrBlank()) {
            tvGuardianName.visibility = View.VISIBLE
            tvGuardianName.text = if (isSelfDevice) {
                "আপনার অ্যাকাউন্ট: $guardianName (স্ব-সুরক্ষা)"
            } else {
                "অভিভাবক: $guardianName"
            }
        }
        layerPaired.visibility = View.VISIBLE
        ShieldService.start(this)

        // Conditionally update the Open/Download button based on main app install state
        if (isMainAppInstalled()) {
            btnOpenMainAppPaired.text = "মুসলিমগাইড অ্যাপ খুলুন"
            btnOpenMainAppPaired.setOnClickListener { openMuslimGuideApp() }
        } else {
            btnOpenMainAppPaired.text = "মুসলিমগাইড অ্যাপ ডাউনলোড করুন"
            btnOpenMainAppPaired.setOnClickListener {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://muslimguide-bd.web.app")))
                } catch (_: Exception) {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.muslimguide.bd")))
                    } catch (_: Exception) {
                        ShieldToast.showError(this, "ব্রাউজার ওপেন করা যায়নি", title = "ত্রুটি")
                    }
                }
            }
        }
    }

    // ── Header color helpers ──────────────────────────

    private fun setHeaderAmber(badge: String, description: String) {
        val amber = Color.parseColor("#F59E0B")
        cardContainer.strokeColor = Color.parseColor("#60F59E0B")
        ivShieldIcon.setColorFilter(amber)
        tvStatusBadge.setTextColor(amber)
        tvStatusBadge.background = roundedBg("#20F59E0B")
        tvStatusBadge.text = badge
        frameIcon.background = roundedBg("#20F59E0B")
        tvStatusDescription.text = description
    }

    private fun setHeaderGreen(badge: String, description: String) {
        val green = Color.parseColor("#10B981")
        cardContainer.strokeColor = Color.parseColor("#6010B981")
        ivShieldIcon.setColorFilter(green)
        tvStatusBadge.setTextColor(green)
        tvStatusBadge.background = roundedBg("#2010B981")
        tvStatusBadge.text = badge
        frameIcon.background = roundedBg("#2010B981")
        tvStatusDescription.text = description
    }

    private fun roundedBg(colorHex: String): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = 32f
        setColor(Color.parseColor(colorHex))
    }

    /**
     * Checks whether the main app (MuslimGuideBD) is installed and has an active logged-in user.
     * If authenticated, it automatically retrieves and saves guardian_uid & guardian_name via ContentProvider IPC.
     */
    private fun checkAndAutoAuthenticateFromMainApp(): Boolean {
        return try {
            val uri = Uri.parse("content://com.muslimguide.bd.shield.auth")
            val bundle = contentResolver.call(uri, "getGuardianAuth", null, null)
            if (bundle != null && bundle.getBoolean("is_authenticated", false)) {
                val uid = bundle.getString("guardian_uid")
                val name = bundle.getString("guardian_name")
                saveIsSelfDevice(true) // Auto-authentication from same device = Self Protection!
                if (!uid.isNullOrBlank()) {
                    saveGuardianUid(uid)
                    if (!name.isNullOrBlank()) {
                        saveGuardianName(name)
                    } else {
                        fetchGuardianNameIfNeeded(uid)
                    }
                    ShieldToast.showSuccess(
                        this,
                        "MuslimGuideBD থেকে আপনার অ্যাকাউন্ট সফলভাবে সংযুক্ত হয়েছে (স্ব-সুরক্ষা মোড)",
                        "অথেন্টিকেশন সফল"
                    )
                    true
                } else false
            } else false
        } catch (e: Exception) {
            android.util.Log.d("ShieldMain", "Auto-auth check from main app: ${e.message}")
            false
        }
    }

    private fun isMainAppInstalled(): Boolean {
        return try {
            packageManager.getPackageInfo("com.muslimguide.bd", 0)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun showAutoSyncBottomSheet() {
        val dialog = BottomSheetDialog(this, R.style.BottomSheetDialogTheme)
        val view = layoutInflater.inflate(R.layout.bottom_sheet_auto_sync, null)
        dialog.setContentView(view)
        dialog.behavior.peekHeight = resources.displayMetrics.heightPixels / 2

        val stateNotInstalled     = view.findViewById<android.view.View>(R.id.stateNotInstalled)
        val stateNeedsUpdate      = view.findViewById<android.view.View?>(R.id.stateNeedsUpdate)
        val stateNotAuthenticated = view.findViewById<android.view.View>(R.id.stateNotAuthenticated)

        fun hideAll() {
            stateNotInstalled.visibility     = android.view.View.GONE
            stateNeedsUpdate?.visibility     = android.view.View.GONE
            stateNotAuthenticated.visibility = android.view.View.GONE
        }

        if (!isMainAppInstalled()) {
            hideAll(); stateNotInstalled.visibility = android.view.View.VISIBLE
            view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnDownloadMainApp)
                .setOnClickListener {
                    try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://muslimguide-bd.web.app"))) }
                    catch (_: Exception) { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.muslimguide.bd"))) }
                    dialog.dismiss()
                }
            view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnDismissNotInstalled)
                .setOnClickListener { dialog.dismiss() }
        } else {
            val installedVersionCode = try {
                val pkgInfo = packageManager.getPackageInfo("com.muslimguide.bd", 0)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P)
                    pkgInfo.longVersionCode.toInt()
                else @Suppress("DEPRECATION") pkgInfo.versionCode
            } catch (_: Exception) { 0 }

            // Default: show not-authenticated state
            hideAll(); stateNotAuthenticated.visibility = android.view.View.VISIBLE
            view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnOpenMainAppAuth)
                .setOnClickListener { openMuslimGuideApp(); dialog.dismiss() }
            view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnDismissNotAuth)
                .setOnClickListener { dialog.dismiss() }

            // Async: check Firebase for latest versionCode
            lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    val latestCode = FirebaseDatabase.getInstance()
                        .getReference("app_config/main_app/latest_version_code")
                        .get().await().getValue(Int::class.java) ?: 0
                    withContext(kotlinx.coroutines.Dispatchers.Main) {
                        if (latestCode > 0 && installedVersionCode in 1 until latestCode && stateNeedsUpdate != null) {
                            hideAll(); stateNeedsUpdate.visibility = android.view.View.VISIBLE
                            view.findViewById<com.google.android.material.button.MaterialButton?>(R.id.btnUpdateMainApp)
                                ?.setOnClickListener {
                                    try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.muslimguide.bd"))) }
                                    catch (_: Exception) { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://muslimguide-bd.web.app"))) }
                                    dialog.dismiss()
                                }
                            view.findViewById<com.google.android.material.button.MaterialButton?>(R.id.btnDismissNeedsUpdate)
                                ?.setOnClickListener { dialog.dismiss() }
                        }
                    }
                } catch (_: Exception) { /* stay on stateNotAuthenticated */ }
            }
        }
        dialog.show()
    }

    private fun showShieldSupportBottomSheet() {
        val dialog = BottomSheetDialog(this, R.style.BottomSheetDialogTheme)
        val view = layoutInflater.inflate(R.layout.bottom_sheet_shield_support, null)
        dialog.setContentView(view)
        dialog.behavior.peekHeight = resources.displayMetrics.heightPixels * 3 / 4

        val btnWireless = view.findViewById<MaterialCardView>(R.id.btnSupportWirelessVideo)
        val btnQr = view.findViewById<MaterialCardView>(R.id.btnSupportQrVideo)
        val btnPc = view.findViewById<MaterialCardView>(R.id.btnSupportPcVideo)
        val btnWhatsapp = view.findViewById<MaterialCardView>(R.id.btnSupportWhatsapp)
        val btnTelegram = view.findViewById<MaterialCardView>(R.id.btnSupportTelegram)
        val btnDismiss = view.findViewById<MaterialButton>(R.id.btnDismissSupportSheet)

        btnDismiss.setOnClickListener { dialog.dismiss() }

        var wirelessUrl: String? = null
        var qrUrl: String? = null
        var pcUrl: String? = null
        var whatsappUrl: String? = null
        var telegramUrl: String? = null

        fun openUrl(url: String?, fallbackMsg: String) {
            if (!url.isNullOrBlank()) {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                } catch (_: Exception) {
                    ShieldToast.showError(this, "লিঙ্কটি ওপেন করা সম্ভব হয়নি।")
                }
            } else {
                ShieldToast.showWarning(this, fallbackMsg)
            }
        }

        btnWireless.setOnClickListener {
            openUrl(wirelessUrl, "ওয়্যারলেস টিউটোরিয়াল ভিডিও লিঙ্ক এখনো যুক্ত করা হয়নি।")
        }
        btnQr.setOnClickListener {
            openUrl(qrUrl, "কিউআর স্ক্যান টিউটোরিয়াল ভিডিও লিঙ্ক এখনো যুক্ত করা হয়নি।")
        }
        btnPc.setOnClickListener {
            openUrl(pcUrl, "পিসি টিউটোরিয়াল ভিডিও লিঙ্ক এখনো যুক্ত করা হয়নি।")
        }
        btnWhatsapp.setOnClickListener {
            openUrl(whatsappUrl, "WhatsApp সাপোর্ট লিঙ্ক এখনো যুক্ত করা হয়নি।")
        }
        btnTelegram.setOnClickListener {
            openUrl(telegramUrl, "Telegram সাপোর্ট লিঙ্ক এখনো যুক্ত করা হয়নি।")
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val snapshot = FirebaseDatabase.getInstance()
                    .getReference("app_config/community_shield")
                    .get().await()

                if (snapshot.exists()) {
                    wirelessUrl = snapshot.child("tutorial_wireless").getValue(String::class.java)
                        ?: snapshot.child("wirelessUrl").getValue(String::class.java)
                    qrUrl = snapshot.child("tutorial_qr").getValue(String::class.java)
                        ?: snapshot.child("qrUrl").getValue(String::class.java)
                    pcUrl = snapshot.child("tutorial_pc").getValue(String::class.java)
                        ?: snapshot.child("pcUrl").getValue(String::class.java)
                    whatsappUrl = snapshot.child("support_whatsapp").getValue(String::class.java)
                        ?: snapshot.child("whatsappUrl").getValue(String::class.java)
                    telegramUrl = snapshot.child("support_telegram").getValue(String::class.java)
                        ?: snapshot.child("telegramUrl").getValue(String::class.java)
                }
            } catch (_: Exception) {
                // Ignore or handle offline gracefully
            }
        }

        dialog.show()
    }

    // ══════════════════════════════════════════════════    // ══════════════════════════════════════════════════
    // ADB Activation
    // ══════════════════════════════════════════════════

    private fun handleAutoActivateClick() {
        if (AdminReceiver.isDeviceOwner(this)) {
            ShieldToast.showSuccess(this, "স্থায়ী সুরক্ষা ইতিমধ্যে সক্রিয় রয়েছে!", title = "সুরক্ষিত")
            renderState()
            return
        }

        val manualPort = etAdbPort.text?.toString()?.trim()?.toIntOrNull()
        val manualCode = etAdbPairingCode.text?.toString()?.trim()

        if (manualPort != null && manualPort > 0) {
            if (!manualCode.isNullOrBlank()) {
                showTerminalLayer()
                appendTerminal("$ ম্যানুয়াল পোর্ট ($manualPort) ও কোড ($manualCode) দিয়ে পেয়ারিং শুরু...")
                val pairingIntent = Intent(this, AdbWirelessNotificationService::class.java).apply {
                    action = AdbWirelessNotificationService.ACTION_SEND_PAIRING_CODE
                    putExtra(AdbWirelessNotificationService.EXTRA_PAIRING_CODE, manualCode)
                    putExtra(AdbWirelessNotificationService.EXTRA_PAIRING_PORT, manualPort.toString())
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(pairingIntent)
                } else {
                    startService(pairingIntent)
                }
            } else {
                showTerminalLayer()
                appendTerminal("$ ম্যানুয়াল পোর্ট ($manualPort) দিয়ে সরাসরি সংযোগ শুরু...")
                runFullAutoActivation(manualPort)
            }
            return
        }

        // Notification permission check (Android 13+): automatically prompt if needed
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !isNotificationPermissionGranted()) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }

        startAdbPairingFlow()
    }

    private fun startAdbPairingFlow() {
        showTerminalLayer()
        appendTerminal("\n═══════════════════════════════════════")
        appendTerminal("$ ওয়্যারলেস ডিবাগিং পেয়ারিং সার্ভিস শুরু হচ্ছে...")
        appendTerminal("$ নোটিফিকেশন থেকে ৬-সংখ্যার কোডটি দিন...")
        AdbWirelessNotificationService.start(this)

        openDeveloperSettings()
    }

    private fun openDeveloperSettings() {
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
            ShieldToast.showInfo(this, "Wireless Debugging অপশন খুঁজুন", title = "সেটিংস")
        } catch (_: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            } catch (_: Exception) {
                ShieldToast.showError(this, "সেটিংস ওপেন করা যায়নি", title = "ত্রুটি")
            }
        }
    }

    private fun cancelAdbTerminalSession() {
        AdbWirelessNotificationService.stop(this)
        appendTerminal("\n[!] ব্যবহারকারী কর্তৃক সংযোগ প্রক্রিয়া বাতিল করা হয়েছে।")
        ShieldToast.showInfo(this, "কানেকশন প্রক্রিয়া বাতিল করা হয়েছে", title = "বাতিল")
        renderState()
    }

    private fun isNotificationPermissionGranted(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun runFullAutoActivation(port: Int) {
        lifecycleScope.launch {
            appendTerminal("$ ADB পোর্ট $port তে সংযোগ স্থাপন হচ্ছে...")
            AdbWirelessManager.getInstance(this@MainActivity).activateCommunityShield(
                port = port
            ) { event ->
                runOnUiThread {
                    when (event) {
                        is AdbWirelessManager.ActivationEvent.Progress ->
                            appendTerminal("[ধাপ ${event.step}/${event.total}] ${event.message}")
                        is AdbWirelessManager.ActivationEvent.Output ->
                            appendTerminal(event.line)
                        is AdbWirelessManager.ActivationEvent.Success -> {
                            appendTerminal("\n✓ ${event.message}\n")
                            ShieldToast.showSuccess(this@MainActivity, "স্থায়ী ডিভাইস সুরক্ষা সক্রিয় হয়েছে!", title = "সফল")
                            linkToFirebase()
                            renderState()
                        }
                        is AdbWirelessManager.ActivationEvent.Failure -> {
                            appendTerminal("\n✗ ${event.message}\n")
                            ShieldToast.showWarning(this@MainActivity, "সক্রিয়করণ সম্পন্ন হয়নি। নির্দেশনা দেখুন।", title = "মনোযোগ দিন")
                        }
                    }
                }
            }
        }
    }

    // ══════════════════════════════════════════════════
    // Firebase Linking (single merged function)
    // ══════════════════════════════════════════════════

    private fun linkToFirebase() {
        val guardianUid = pendingGuardianUid
            ?: getSharedPreferences("shield_prefs", Context.MODE_PRIVATE)
                .getString("guardian_uid", null)
        if (guardianUid.isNullOrBlank()) {
            appendTerminal("[INFO] Guardian UID নেই — Firebase link skip")
            return
        }

        val androidId = AndroidSettings.Secure.getString(
            contentResolver, AndroidSettings.Secure.ANDROID_ID
        ) ?: "UNKNOWN"
        val hashNum = (Math.abs(androidId.hashCode().toLong()) % 90000000 + 10000000).toString()
        val deviceToken = "MG-${hashNum.substring(0, 4)}-${hashNum.substring(4, 8)}"
        val deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}"
        val now = System.currentTimeMillis()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val rtdb = FirebaseDatabase.getInstance()

                rtdb.getReference("devices").child(deviceToken).updateChildren(
                    mapOf(
                        "guardian_uid" to guardianUid,
                        "owner_uid"    to guardianUid,
                        "deviceModel"  to deviceModel,
                        "shieldActive" to true,
                        "status"       to "ACTIVE",
                        "isSelfDevice" to isSelfDevice,
                        "deviceType"   to if (isSelfDevice) "SELF" else "CHILD",
                        "linkedAt"     to now
                    )
                )
                rtdb.getReference("guardian_devices").child(guardianUid).setValue(
                    mapOf(
                        "activeToken"  to deviceToken,
                        "deviceModel"  to deviceModel,
                        "status"       to "ACTIVE",
                        "isSelfDevice" to isSelfDevice,
                        "deviceType"   to if (isSelfDevice) "SELF" else "CHILD",
                        "linkedAt"     to now
                    )
                )
                rtdb.getReference("shield_approved").child(deviceToken).setValue(true)

                withContext(Dispatchers.Main) {
                    appendTerminal("[OK] Firebase লিঙ্ক সম্পন্ন → Token: $deviceToken (${if (isSelfDevice) "স্ব-সুরক্ষা" else "অভিভাবক লিঙ্ক"})")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    appendTerminal("[WARN] Firebase link ব্যর্থ: ${e.localizedMessage}")
                }
            }
        }
    }

    // ══════════════════════════════════════════════════
    // Helpers
    // ══════════════════════════════════════════════════

    private fun saveIsSelfDevice(isSelf: Boolean) {
        isSelfDevice = isSelf
        getSharedPreferences("shield_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean("is_self_device", isSelf).apply()
    }

    private fun saveGuardianUid(uid: String) {
        pendingGuardianUid = uid
        getSharedPreferences("shield_prefs", Context.MODE_PRIVATE)
            .edit().putString("guardian_uid", uid).apply()
    }

    private fun saveGuardianName(name: String) {
        pendingGuardianName = name
        getSharedPreferences("shield_prefs", Context.MODE_PRIVATE)
            .edit().putString("guardian_name", name).apply()
    }

    private fun fetchGuardianNameIfNeeded(uid: String) {
        if (!pendingGuardianName.isNullOrBlank()) return
        FirebaseDatabase.getInstance().getReference("users").child(uid).child("name")
            .get().addOnSuccessListener { snapshot ->
                val name = snapshot.getValue(String::class.java)
                if (!name.isNullOrBlank()) {
                    saveGuardianName(name)
                    runOnUiThread {
                        tvGuardianName.text = if (isSelfDevice) {
                            "আপনার অ্যাকাউন্ট: $name (স্ব-সুরক্ষা)"
                        } else {
                            "অভিভাবক: $name"
                        }
                        tvGuardianName.visibility = View.VISIBLE
                    }
                }
            }
    }

    private fun appendTerminal(line: String) {
        tvTerminalOutput.append("\n$line")
        nestedScrollView.post {
            nestedScrollView.fullScroll(View.FOCUS_DOWN)
        }
    }

    private fun copyAdbCommand() {
        val cmd = "adb shell dpm set-device-owner com.muslimguide.shield/.AdminReceiver"
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Shield ADB Command", cmd))
        ShieldToast.showSuccess(this, "কমান্ড কপি করা হয়েছে", title = "কপিকৃত")
        appendTerminal("$ কমান্ড ক্লিপবোর্ডে কপি:\n$cmd")
    }

    private fun openQrScanner() {
        qrScannerLauncher.launch(Intent(this, QrScannerActivity::class.java))
    }

    private fun openMuslimGuideApp() {
        try {
            var intent = packageManager.getLaunchIntentForPackage("com.muslimguide.bd")
            if (intent == null) {
                intent = Intent().apply {
                    component = ComponentName(
                        "com.muslimguide.bd",
                        "com.muslimguide.bd.ui.community.MuslimCommunityActivity"
                    )
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
            }
            startActivity(intent)
        } catch (e: Exception) {
            ShieldToast.showWarning(this, "মুসলিমগাইড অ্যাপটি ইনস্টল পাওয়া যায়নি", title = "অ্যাপ পাওয়া যায়নি")
        }
    }

    private fun hideAppIcon() {
        try {
            packageManager.setComponentEnabledSetting(
                ComponentName(this, MainActivity::class.java),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
            ShieldToast.showSuccess(
                this,
                "প্লাগইন আইকন হাইড হয়েছে। সুরক্ষা ব্যাকগ্রাউন্ডে সক্রিয় থাকবে।",
                title = "সফল"
            )
            finish()
        } catch (e: Exception) {
            ShieldToast.showError(this, "আইকন হাইড করতে ব্যর্থ হয়েছে", title = "ত্রুটি")
        }
    }
}