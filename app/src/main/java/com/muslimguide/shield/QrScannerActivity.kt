package com.muslimguide.shield

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.view.View
import android.view.Window
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.firebase.database.FirebaseDatabase
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import org.json.JSONObject
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Lightweight, high-performance QR Code Scanner Activity.
 * Powered purely by CameraX + ZXing core (~500KB library size, 0 native ML overhead).
 *
 * Features:
 * 1. Live real-time Camera QR scanning with rotation-resilient frame analysis.
 * 2. Parses Guardian UID & Name from Android Enterprise provisioning bundles or raw strings.
 * 3. Automatic device linking to Firebase RTDB for active guardian administration.
 * 4. Clean vector-based UI with no emojis.
 */
class QrScannerActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var cardScannerFrame: MaterialCardView
    private lateinit var viewScanLaser: View
    private lateinit var btnBack: MaterialCardView
    private lateinit var btnGalleryPicker: MaterialCardView
    private lateinit var layoutProcessing: FrameLayout
    private lateinit var layoutPermissionDenied: LinearLayout
    private lateinit var btnGrantPermission: MaterialButton

    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var isProcessingScan = false
    private lateinit var cameraExecutor: ExecutorService
    private var laserAnimator: ObjectAnimator? = null

    // Lightweight reusable ZXing reader
    private val zxingReader = MultiFormatReader().apply {
        setHints(
            mapOf(
                DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                DecodeHintType.CHARACTER_SET to "UTF-8"
            )
        )
    }

    // Camera Permission request launcher
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            layoutPermissionDenied.visibility = View.GONE
            startCamera()
        } else {
            layoutPermissionDenied.visibility = View.VISIBLE
            ShieldToast.showWarning(
                this,
                "কিউআর কোড স্ক্যান করতে ক্যামেরা ব্যবহারের অনুমতি প্রয়োজন।",
                "পারমিশন প্রয়োজন"
            )
        }
    }

    // Gallery QR Picker launcher
    private val galleryPickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            decodeQrFromUri(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_qr_scanner)

        window.statusBarColor = Color.parseColor("#090D16")
        window.navigationBarColor = Color.parseColor("#090D16")

        initViews()
        setupListeners()
        startLaserAnimation()

        cameraExecutor = Executors.newSingleThreadExecutor()

        val galleryUriStr = intent.getStringExtra("gallery_uri")
        if (!galleryUriStr.isNullOrBlank()) {
            decodeQrFromUri(Uri.parse(galleryUriStr))
        } else {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                requestPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
    }

    private fun initViews() {
        previewView = findViewById(R.id.previewView)
        cardScannerFrame = findViewById(R.id.cardScannerFrame)
        viewScanLaser = findViewById(R.id.viewScanLaser)
        btnBack = findViewById(R.id.btnBack)
        btnGalleryPicker = findViewById(R.id.btnGalleryPicker)
        layoutProcessing = findViewById(R.id.layoutProcessing)
        layoutPermissionDenied = findViewById(R.id.layoutPermissionDenied)
        btnGrantPermission = findViewById(R.id.btnGrantPermission)
    }

    private fun setupListeners() {
        btnBack.setOnClickListener {
            finish()
        }

        btnGalleryPicker.setOnClickListener {
            galleryPickerLauncher.launch("image/*")
        }

        btnGrantPermission.setOnClickListener {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startLaserAnimation() {
        cardScannerFrame.post {
            val height = cardScannerFrame.height.toFloat()
            if (height > 0) {
                laserAnimator?.cancel()
                laserAnimator = ObjectAnimator.ofFloat(viewScanLaser, "translationY", 0f, height - 10f).apply {
                    duration = 1800
                    repeatMode = ValueAnimator.REVERSE
                    repeatCount = ValueAnimator.INFINITE
                    interpolator = AccelerateDecelerateInterpolator()
                    start()
                }
            }
        }
    }

    private fun allPermissionsGranted() = ContextCompat.checkSelfPermission(
        this, Manifest.permission.CAMERA
    ) == PackageManager.PERMISSION_GRANTED

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder()
                .build()
                .also {
                    it.surfaceProvider = previewView.surfaceProvider
                }

            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                processImageProxyZxing(imageProxy)
            }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider?.unbindAll()
                camera = cameraProvider?.bindToLifecycle(
                    this, cameraSelector, preview, imageAnalysis
                )
            } catch (exc: Exception) {
                android.util.Log.e("QrScanner", "Camera binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(this))
    }

    /**
     * Pure ZXing Frame Analyzer:
     * Extracts Luminance Y-Plane from ImageProxy and decodes QR codes without heavy ML models.
     */
    @SuppressLint("UnsafeOptInUsageError")
    private fun processImageProxyZxing(imageProxy: ImageProxy) {
        if (isProcessingScan) {
            imageProxy.close()
            return
        }

        try {
            val rotationDegrees = imageProxy.imageInfo.rotationDegrees
            val width = imageProxy.width
            val height = imageProxy.height

            val rawLuminance = extractLuminanceBytes(imageProxy)

            // Handle rotation for ZXing
            val (rotatedBytes, finalWidth, finalHeight) = when (rotationDegrees) {
                90 -> Triple(rotateYuv90(rawLuminance, width, height), height, width)
                180 -> Triple(rotateYuv180(rawLuminance, width, height), width, height)
                270 -> Triple(rotateYuv270(rawLuminance, width, height), height, width)
                else -> Triple(rawLuminance, width, height)
            }

            val source = PlanarYUVLuminanceSource(
                rotatedBytes,
                finalWidth,
                finalHeight,
                0,
                0,
                finalWidth,
                finalHeight,
                false
            )

            var rawResult: String? = null

            try {
                val binaryBitmap = BinaryBitmap(HybridBinarizer(source))
                val result = zxingReader.decodeWithState(binaryBitmap)
                rawResult = result.text
            } catch (_: Exception) {
                // Fallback attempt: inverted binarizer (for high-contrast/dark-mode QR codes)
                try {
                    val invertedBitmap = BinaryBitmap(HybridBinarizer(source.invert()))
                    val result = zxingReader.decodeWithState(invertedBitmap)
                    rawResult = result.text
                } catch (_: Exception) {}
            } finally {
                zxingReader.reset()
            }

            if (!rawResult.isNullOrBlank()) {
                val guardianUid = extractGuardianUid(rawResult)
                val guardianName = extractGuardianName(rawResult)
                val isSelf = extractIsSelfDevice(rawResult)

                if (!guardianUid.isNullOrBlank() && !isProcessingScan) {
                    isProcessingScan = true
                    runOnUiThread {
                        handleSuccessfulScan(guardianUid, guardianName, isSelf)
                    }
                }
            }
        } catch (_: Exception) {
            // Frame skip / decode exception
        } finally {
            imageProxy.close()
        }
    }

    /**
     * Extracts Y plane luminance bytes safely considering row stride and pixel stride.
     */
    private fun extractLuminanceBytes(image: ImageProxy): ByteArray {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val width = image.width
        val height = image.height

        if (rowStride == width && pixelStride == 1) {
            val data = ByteArray(buffer.remaining())
            buffer.get(data)
            return data
        }

        val data = ByteArray(width * height)
        var pos = 0
        val rowBuffer = ByteArray(rowStride)
        for (row in 0 until height) {
            buffer.position(row * rowStride)
            val bytesToRead = Math.min(rowStride, buffer.remaining())
            buffer.get(rowBuffer, 0, bytesToRead)
            for (col in 0 until width) {
                data[pos++] = rowBuffer[col * pixelStride]
            }
        }
        return data
    }

    private fun rotateYuv90(data: ByteArray, width: Int, height: Int): ByteArray {
        val rotated = ByteArray(data.size)
        var i = 0
        for (x in 0 until width) {
            for (y in height - 1 downTo 0) {
                rotated[i++] = data[y * width + x]
            }
        }
        return rotated
    }

    private fun rotateYuv180(data: ByteArray, width: Int, height: Int): ByteArray {
        val rotated = ByteArray(data.size)
        val size = width * height
        for (i in 0 until size) {
            rotated[size - 1 - i] = data[i]
        }
        return rotated
    }

    private fun rotateYuv270(data: ByteArray, width: Int, height: Int): ByteArray {
        val rotated = ByteArray(data.size)
        var i = 0
        for (x in width - 1 downTo 0) {
            for (y in 0 until height) {
                rotated[i++] = data[y * width + x]
            }
        }
        return rotated
    }

    /**
     * Extracts guardian UID from:
     * 1. Android Enterprise Device Owner Provisioning JSON (PROVISIONING_ADMIN_EXTRAS_BUNDLE)
     * 2. General JSON payloads with guardian_uid, guardianUid, or uid
     * 3. Deep link URLs with uid= query parameter
     * 4. Raw Firebase UID string
     */
    private fun extractGuardianUid(rawText: String?): String? {
        if (rawText.isNullOrBlank()) return null
        val trimmed = rawText.trim()

        // 1. JSON Payload
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            try {
                val json = JSONObject(trimmed)

                // Android Enterprise Provisioning Extras
                if (json.has("android.app.extra.PROVISIONING_ADMIN_EXTRAS_BUNDLE")) {
                    val extras = json.getJSONObject("android.app.extra.PROVISIONING_ADMIN_EXTRAS_BUNDLE")
                    if (extras.has("guardian_uid")) {
                        val uid = extras.getString("guardian_uid").trim()
                        if (uid.isNotEmpty()) return uid
                    }
                }

                // Direct keys
                val candidateKeys = listOf("guardian_uid", "guardianUid", "guardian_id", "uid", "userId", "user_id")
                for (key in candidateKeys) {
                    if (json.has(key)) {
                        val uid = json.getString(key).trim()
                        if (uid.isNotEmpty()) return uid
                    }
                }
            } catch (_: Exception) {}
        }

        // 2. Query URL / Deep link (muslimguide://shield?uid=...)
        if (trimmed.contains("uid=")) {
            try {
                val uri = Uri.parse(trimmed)
                val uid = uri.getQueryParameter("uid")
                    ?: uri.getQueryParameter("guardian_uid")
                    ?: uri.getQueryParameter("guardianUid")
                if (!uid.isNullOrBlank()) return uid.trim()
            } catch (_: Exception) {}
        }

        // 3. Raw UID (Firebase UIDs are usually 20 to 45 alphanumeric characters)
        if (trimmed.matches(Regex("^[a-zA-Z0-9_-]{20,45}$"))) {
            return trimmed
        }

        return null
    }

    private fun extractGuardianName(rawText: String?): String? {
        if (rawText.isNullOrBlank()) return null
        val trimmed = rawText.trim()
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            try {
                val json = JSONObject(trimmed)
                if (json.has("android.app.extra.PROVISIONING_ADMIN_EXTRAS_BUNDLE")) {
                    val extras = json.getJSONObject("android.app.extra.PROVISIONING_ADMIN_EXTRAS_BUNDLE")
                    if (extras.has("guardian_name")) {
                        val name = extras.getString("guardian_name").trim()
                        if (name.isNotEmpty()) return name
                    }
                }
                val candidateKeys = listOf("guardian_name", "guardianName", "name", "userName")
                for (key in candidateKeys) {
                    if (json.has(key)) {
                        val name = json.getString(key).trim()
                        if (name.isNotEmpty()) return name
                    }
                }
            } catch (_: Exception) {}
        }
        return null
    }

    private fun extractIsSelfDevice(rawText: String?): Boolean {
        if (rawText.isNullOrBlank()) return false
        val trimmed = rawText.trim()
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            try {
                val json = JSONObject(trimmed)
                if (json.optBoolean("is_self_device", false) ||
                    json.optString("mode", "").equals("SELF", ignoreCase = true) ||
                    json.optString("role", "").equals("SELF", ignoreCase = true)
                ) {
                    return true
                }
            } catch (_: Exception) {}
        }
        if (trimmed.contains("self=true") || trimmed.contains("mode=self")) {
            return true
        }
        return false
    }

    private fun handleSuccessfulScan(guardianUid: String, guardianName: String? = null, isSelfDevice: Boolean = false) {
        vibratePhone()

        // 1. Save Guardian UID, Name and is_self_device in shared preferences
        val editor = getSharedPreferences("shield_prefs", Context.MODE_PRIVATE).edit()
        editor.putString("guardian_uid", guardianUid)
        editor.putBoolean("is_self_device", isSelfDevice)
        if (!guardianName.isNullOrBlank()) {
            editor.putString("guardian_name", guardianName)
        }
        editor.apply()

        // 2. If already Device Owner, link to Firebase RTDB immediately
        if (AdminReceiver.isDeviceOwner(this)) {
            linkToFirebaseRtdb(guardianUid, isSelfDevice)
        }

        // 3. Show Success Dialog
        showSuccessDialog(guardianUid, guardianName, isSelfDevice)
    }

    private fun linkToFirebaseRtdb(guardianUid: String, isSelfDevice: Boolean = false) {
        try {
            val androidId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "DEVICE_001"
            val hash = (Math.abs(androidId.hashCode().toLong()) % 90000000 + 10000000).toString()
            val deviceToken = "MG-${hash.substring(0, 4)}-${hash.substring(4, 8)}"

            val deviceData = mapOf(
                "activeToken"  to deviceToken,
                "deviceModel"  to "${Build.MANUFACTURER} ${Build.MODEL}",
                "status"       to "ACTIVE",
                "isSelfDevice" to isSelfDevice,
                "deviceType"   to if (isSelfDevice) "SELF" else "CHILD",
                "linkedAt"     to System.currentTimeMillis()
            )

            FirebaseDatabase.getInstance().getReference("guardian_devices")
                .child(guardianUid)
                .setValue(deviceData)
                .addOnSuccessListener {
                    android.util.Log.d("QrScanner", "Device successfully linked to Guardian UID in RTDB (isSelf=$isSelfDevice)")
                }
        } catch (e: Exception) {
            android.util.Log.w("QrScanner", "Failed to link in RTDB: ${e.message}")
        }
    }

    private fun showSuccessDialog(guardianUid: String, guardianName: String? = null, isSelfDevice: Boolean = false) {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_qr_success)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.setCancelable(false)

        val tvUid = dialog.findViewById<TextView>(R.id.tvDialogGuardianUid)
        val tvMsg = dialog.findViewById<TextView>(R.id.tvSuccessMessage)
        val btnDone = dialog.findViewById<MaterialButton>(R.id.btnDone)

        tvUid.text = if (isSelfDevice) {
            if (!guardianName.isNullOrBlank()) "আপনার অ্যাকাউন্ট: $guardianName (স্ব-সুরক্ষা)" else "আপনার অ্যাকাউন্ট সংযুক্ত সম্পন্ন"
        } else {
            if (!guardianName.isNullOrBlank()) "অভিভাবক: $guardianName" else "অভিভাবক অ্যাকাউন্ট সংযুক্ত সম্পন্ন"
        }

        if (AdminReceiver.isDeviceOwner(this)) {
            tvMsg.text = if (isSelfDevice) {
                "ডিভাইস ওনার ও আপনার নিজস্ব সুরক্ষা সফলভাবে লিঙ্ক করা হয়েছে!"
            } else {
                "ডিভাইস ওনার ও অভিভাবক অ্যাকাউন্ট সফলভাবে লিঙ্ক করা হয়েছে!"
            }
        } else {
            tvMsg.text = if (isSelfDevice) {
                "আপনার অ্যাকাউন্ট লিঙ্ক সংরক্ষিত হয়েছে! এখন হোম স্ক্রিন থেকে 'প্লাগইন সক্রিয় করুন' বাটনে চাপ দিন।"
            } else {
                "অভিভাবক অ্যাকাউন্ট সফলভাবে লিঙ্ক হয়েছে! এখন হোম স্ক্রিন থেকে 'প্লাগইন সক্রিয় করুন' বাটনে চাপ দিন।"
            }
        }

        btnDone.setOnClickListener {
            dialog.dismiss()
            val resultIntent = Intent().apply {
                putExtra("guardian_uid", guardianUid)
                putExtra("is_self_device", isSelfDevice)
                if (!guardianName.isNullOrBlank()) {
                    putExtra("guardian_name", guardianName)
                }
            }
            setResult(Activity.RESULT_OK, resultIntent)
            finish()
        }

        dialog.show()
    }

    private fun vibratePhone() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vibratorManager?.defaultVibrator?.vibrate(
                    VibrationEffect.createOneShot(120, VibrationEffect.DEFAULT_AMPLITUDE)
                )
            } else {
                @Suppress("DEPRECATION")
                val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                @Suppress("DEPRECATION")
                vibrator?.vibrate(120)
            }
        } catch (_: Exception) {}
    }

    /**
     * Decodes QR code from gallery image URI using lightweight pure ZXing
     */
    private fun decodeQrFromUri(uri: Uri) {
        showProcessing(true, "ছবি বিশ্লেষণ করা হচ্ছে...")
        cameraExecutor.execute {
            try {
                val inputStream = contentResolver.openInputStream(uri)
                val bitmap = BitmapFactory.decodeStream(inputStream)
                inputStream?.close()

                if (bitmap == null) {
                    runOnUiThread {
                        showProcessing(false)
                        ShieldToast.showError(this@QrScannerActivity, "ছবি লোড করা যায়নি", "ত্রুটি")
                    }
                    return@execute
                }

                val width = bitmap.width
                val height = bitmap.height
                val pixels = IntArray(width * height)
                bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

                val source = RGBLuminanceSource(width, height, pixels)
                val binaryBitmap = BinaryBitmap(HybridBinarizer(source))
                val hints = mapOf<DecodeHintType, Any>(
                    DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                    DecodeHintType.CHARACTER_SET to "UTF-8"
                )

                var rawText: String? = null
                try {
                    val result = MultiFormatReader().decode(binaryBitmap, hints)
                    rawText = result.text
                } catch (_: Exception) {
                    try {
                        val invertedBitmap = BinaryBitmap(HybridBinarizer(source.invert()))
                        val result = MultiFormatReader().decode(invertedBitmap, hints)
                        rawText = result.text
                    } catch (_: Exception) {}
                }

                val guardianUid = extractGuardianUid(rawText)
                val guardianName = extractGuardianName(rawText)

                runOnUiThread {
                    showProcessing(false)
                    if (!guardianUid.isNullOrBlank()) {
                        handleSuccessfulScan(guardianUid, guardianName)
                    } else {
                        ShieldToast.showWarning(
                            this@QrScannerActivity,
                            "ছবিতে কোনো বৈধ QR কোড পাওয়া যায়নি।",
                            "কিউআর পাওয়া যায়নি"
                        )
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    showProcessing(false)
                    ShieldToast.showWarning(
                        this@QrScannerActivity,
                        "ছবি বিশ্লেষণ ব্যর্থ হয়েছে।",
                        "ত্রুটি"
                    )
                }
            }
        }
    }

    private fun showProcessing(show: Boolean, text: String = "যাচাই করা হচ্ছে...") {
        layoutProcessing.visibility = if (show) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.tvProcessingText)?.text = text
    }

    override fun onDestroy() {
        super.onDestroy()
        laserAnimator?.cancel()
        cameraExecutor.shutdown()
    }
}
