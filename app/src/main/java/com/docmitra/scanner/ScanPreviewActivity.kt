package com.docmitra.scanner

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min

class ScanPreviewActivity : AppCompatActivity() {

    private lateinit var ivPreview: ImageView
    private lateinit var overlayView: StampOverlayView
    private lateinit var tvPageIndicator: TextView
    private lateinit var etDocTitle: EditText
    private lateinit var stampControlsBar: LinearLayout
    private lateinit var tvLoadingBanner: TextView
    private lateinit var filterThumbRow: LinearLayout

    private val originalBitmaps = ArrayList<Bitmap>()
    private val editedBitmaps = ArrayList<Bitmap>()
    private val pageFilterModes = ArrayList<Int>()
    private var currentIndex = 0
    private var isRetakeMode = false
    private var validatedTargetFolder: File? = null

    private val filterNames = listOf(
        "No shadow",
        "Original",
        "Lighten",
        "Magic Color",
        "Doc Clear",
        "Grayscale"
    )

    private val extraScannerLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val scanResult = GmsDocumentScanningResult.fromActivityResultIntent(result.data)
            val pages = scanResult?.pages
            if (!pages.isNullOrEmpty()) {
                Thread {
                    val newOrig = ArrayList<Bitmap>()
                    val newEdit = ArrayList<Bitmap>()
                    for (p in pages) {
                        decodeSafeUri(p.imageUri, 1600)?.let { bmp ->
                            newOrig.add(bmp)
                            newEdit.add(applyOkenFilter(bmp, 3))
                        }
                    }
                    runOnUiThread {
                        if (isFinishing || isDestroyed || newOrig.isEmpty()) return@runOnUiThread
                        if (isRetakeMode && originalBitmaps.isNotEmpty()) {
                            originalBitmaps[currentIndex] = newOrig[0]
                            editedBitmaps[currentIndex] = newEdit[0]
                            pageFilterModes[currentIndex] = 3
                        } else {
                            for (i in newOrig.indices) {
                                originalBitmaps.add(newOrig[i])
                                editedBitmaps.add(newEdit[i])
                                pageFilterModes.add(3)
                            }
                            currentIndex = originalBitmaps.size - 1
                        }
                        updatePageDisplay()
                        rebuildFilterThumbnails()
                    }
                }.start()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )

        val secureBaseDir = getSecureProjectsRoot()
        val rawFolderPath = intent.getStringExtra("TARGET_PROJECT_DIR")
        if (!rawFolderPath.isNullOrBlank()) {
            try {
                val candidate = File(rawFolderPath).canonicalFile
                val canonicalRoot = secureBaseDir.canonicalFile
                if (candidate.parentFile == canonicalRoot && candidate.isDirectory) {
                    validatedTargetFolder = candidate
                }
            } catch (_: Exception) {
                validatedTargetFolder = null
            }
        }

        val density = resources.displayMetrics.density
        val dp = { v: Int -> (v * density).toInt() }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF18181B.toInt())
        }

        // Top Bar (OKEN Style: Back arrow + Editable Doc Name + Green Done button)
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xFF18181B.toInt())
            setPadding(dp(10), dp(10), dp(12), dp(8))
        }

        val btnBack = MaterialButton(this).apply {
            text = "←"
            textSize = 18f
            minWidth = dp(40)
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0x00000000)
            setOnClickListener { finish() }
        }

        val defaultDocName = validatedTargetFolder?.name
            ?: "Doc_${SimpleDateFormat("ddMM_HHmm", Locale.getDefault()).format(Date())}"

        etDocTitle = EditText(this).apply {
            setText(defaultDocName)
            hint = "Enter Document Name"
            textSize = 14.5f
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFFA1A1AA.toInt())
            setBackgroundColor(0x22FFFFFF)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            isSingleLine = true
            isEnabled = (validatedTargetFolder == null)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(8)
            }
        }

        val btnShareQuick = MaterialButton(this).apply {
            text = "PDF"
            textSize = 12f
            minWidth = 0
            setTypeface(null, Typeface.BOLD)
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xFFEA580C.toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(38)
            ).apply { marginEnd = dp(6) }
            setOnClickListener {
                if (editedBitmaps.isNotEmpty()) saveProjectAndFinish(shareImmediately = true)
            }
        }

        val btnDone = MaterialButton(this).apply {
            text = "Done"
            textSize = 13f
            minWidth = 0
            setTypeface(null, Typeface.BOLD)
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xFF16A34A.toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(38)
            )
            setOnClickListener {
                if (editedBitmaps.isNotEmpty()) saveProjectAndFinish(shareImmediately = false)
            }
        }

        topBar.addView(btnBack)
        topBar.addView(etDocTitle)
        topBar.addView(btnShareQuick)
        topBar.addView(btnDone)
        root.addView(topBar)

        // Center Document Stage
        val stageFrame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            ).apply { setMargins(dp(12), dp(4), dp(12), dp(4)) }
            setBackgroundColor(0xFF18181B.toInt())
        }

        ivPreview = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            scaleType = ImageView.ScaleType.FIT_CENTER
        }

        overlayView = StampOverlayView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        tvLoadingBanner = TextView(this).apply {
            text = "Loading HD Preview..."
            textSize = 15f
            setTextColor(0xFFFFFFFF.toInt())
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setBackgroundColor(0xCC18181B.toInt())
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        stageFrame.addView(ivPreview)
        stageFrame.addView(overlayView)
        stageFrame.addView(tvLoadingBanner)
        root.addView(stageFrame)

        // Page Switcher (< 1/1 >)
        val pageNavRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(2), 0, dp(4))
        }

        val btnPrev = MaterialButton(this).apply {
            text = "<"
            textSize = 14f
            minWidth = dp(40)
            setTextColor(0xFFD4D4D8.toInt())
            setBackgroundColor(0x00000000)
            setOnClickListener {
                if (currentIndex > 0) {
                    currentIndex--
                    updatePageDisplay()
                    rebuildFilterThumbnails()
                }
            }
        }

        tvPageIndicator = TextView(this).apply {
            text = "1/1"
            textSize = 13f
            setTextColor(0xFFFFFFFF.toInt())
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(12), 0, dp(12), 0)
        }

        val btnNext = MaterialButton(this).apply {
            text = ">"
            textSize = 14f
            minWidth = dp(40)
            setTextColor(0xFFD4D4D8.toInt())
            setBackgroundColor(0x00000000)
            setOnClickListener {
                if (currentIndex < editedBitmaps.size - 1) {
                    currentIndex++
                    updatePageDisplay()
                    rebuildFilterThumbnails()
                }
            }
        }

        pageNavRow.addView(btnPrev)
        pageNavRow.addView(tvPageIndicator)
        pageNavRow.addView(btnNext)
        root.addView(pageNavRow)

        // Stamp Controls Bar (Visible when Mohar/Sign placed)
        stampControlsBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xFF27272A.toInt())
            setPadding(dp(10), dp(4), dp(10), dp(4))
            visibility = View.GONE
        }

        val btnStampSmaller = MaterialButton(this).apply {
            text = "A-"
            textSize = 11f
            minWidth = 0
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xFF3F3F46.toInt())
            setOnClickListener { overlayView.adjustScale(-0.15f) }
        }

        val btnStampBigger = MaterialButton(this).apply {
            text = "A+"
            textSize = 11f
            minWidth = 0
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xFF3F3F46.toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(6) }
            setOnClickListener { overlayView.adjustScale(0.15f) }
        }

        val btnClearStamp = MaterialButton(this).apply {
            text = "Remove"
            textSize = 11f
            minWidth = 0
            setTextColor(0xFFFCA5A5.toInt())
            setBackgroundColor(0x00000000)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(6) }
            setOnClickListener {
                overlayView.clearStamp()
                stampControlsBar.visibility = View.GONE
            }
        }

        val btnFixStamp = MaterialButton(this).apply {
            text = "✓ Fix on Page"
            textSize = 12f
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xFF16A34A.toInt())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(8)
            }
            setOnClickListener { burnStampOntoCurrentPage() }
        }

        stampControlsBar.addView(btnStampSmaller)
        stampControlsBar.addView(btnStampBigger)
        stampControlsBar.addView(btnClearStamp)
        stampControlsBar.addView(btnFixStamp)
        root.addView(stampControlsBar)

        // OKEN-Style Horizontal Live Filter Thumbnail Strip
        val filterScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setBackgroundColor(0xFF27272A.toInt())
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }

        filterThumbRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        filterScroll.addView(filterThumbRow)
        root.addView(filterScroll)

        // OKEN-Style Bottom 5-Action Toolbar: Retake | Add | Rotate | Crop | Mohar/Sign
        val bottomToolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xFF18181B.toInt())
            setPadding(dp(4), dp(6), dp(4), dp(10))
        }

        fun makeBottomTool(icon: String, label: String, onClick: () -> Unit): LinearLayout {
            return LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener { if (editedBitmaps.isNotEmpty()) onClick() }

                val tvIcon = TextView(this@ScanPreviewActivity).apply {
                    text = icon
                    textSize = 16f
                    setTextColor(0xFFFFFFFF.toInt())
                    gravity = Gravity.CENTER
                }
                val tvLbl = TextView(this@ScanPreviewActivity).apply {
                    text = label
                    textSize = 11f
                    setTextColor(0xFFD4D4D8.toInt())
                    gravity = Gravity.CENTER
                }
                addView(tvIcon)
                addView(tvLbl)
            }
        }

        bottomToolbar.addView(makeBottomTool("📷", "Retake") {
            isRetakeMode = true
            launchScannerForMore(pageLimit = 1)
        })

        bottomToolbar.addView(makeBottomTool("➕", "Add") {
            isRetakeMode = false
            launchScannerForMore(pageLimit = 15)
        })

        bottomToolbar.addView(makeBottomTool("↺", "Left") {
            rotateCurrentPageLeft()
        })

        bottomToolbar.addView(makeBottomTool("✂️", "Crop") {
            openManualCropForCurrentPage()
        })

        bottomToolbar.addView(makeBottomTool("🔏", "Mohar/Sign") {
            openStampAndSignMenu()
        })

        root.addView(bottomToolbar)
        setContentView(root)

        val uriStrings = ArrayList<String>()
        intent.getStringArrayListExtra("SCAN_URIS")?.let { uriStrings.addAll(it) }

        if (uriStrings.isEmpty()) {
            if (intent.action == Intent.ACTION_SEND) {
                val singleUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                }
                singleUri?.let { uriStrings.add(it.toString()) }
            } else if (intent.action == Intent.ACTION_SEND_MULTIPLE) {
                val multiUris = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
                }
                multiUris?.take(30)?.forEach { uriStrings.add(it.toString()) }
            }
        }

        if (uriStrings.isEmpty()) {
            Toast.makeText(this, "No valid image found", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        Thread {
            val loadedOrig = ArrayList<Bitmap>()
            val loadedEdit = ArrayList<Bitmap>()

            for (uriStr in uriStrings.take(30)) {
                val parsedUri = Uri.parse(uriStr)
                decodeSafeUri(parsedUri, 1600)?.let { bmp ->
                    loadedOrig.add(bmp)
                    // Default to Magic Color (index 3) just like OKEN Scanner
                    loadedEdit.add(applyOkenFilter(bmp, 3))
                }
            }

            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                tvLoadingBanner.visibility = View.GONE
                if (loadedOrig.isEmpty()) {
                    Toast.makeText(this, "Could not load photo, please try again", Toast.LENGTH_SHORT).show()
                    finish()
                } else {
                    originalBitmaps.addAll(loadedOrig)
                    editedBitmaps.addAll(loadedEdit)
                    repeat(loadedOrig.size) { pageFilterModes.add(3) }
                    updatePageDisplay()
                    rebuildFilterThumbnails()
                }
            }
        }.start()
    }

    private fun decodeSafeUri(uri: Uri, maxDim: Int): Bitmap? {
        if (uri.scheme != "content") return null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (bounds.outWidth / sample > maxDim || bounds.outHeight / sample > maxDim) {
                sample *= 2
            }
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        } catch (_: Exception) {
            null
        }
    }

    private fun rebuildFilterThumbnails() {
        if (originalBitmaps.isEmpty()) return
        filterThumbRow.removeAllViews()

        val base = originalBitmaps[currentIndex]
        val thumbW = 130
        val thumbH = (base.height.toFloat() / base.width.toFloat().coerceAtLeast(1f) * thumbW)
            .toInt().coerceIn(110, 170)
        val tinySrc = Bitmap.createScaledBitmap(base, thumbW, thumbH, true)

        val selectedMode = pageFilterModes[currentIndex]
        val density = resources.displayMetrics.density
        val dp = { v: Int -> (v * density).toInt() }

        for (modeIdx in filterNames.indices) {
            val isSelected = (modeIdx == selectedMode)
            val card = MaterialCardView(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(74), LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    marginEnd = dp(8)
                }
                radius = 8f * density
                cardElevation = 0f
                setCardBackgroundColor(if (isSelected) 0xFF16A34A.toInt() else 0xFF3F3F46.toInt())
                strokeColor = if (isSelected) 0xFF22C55E.toInt() else 0xFF52525B.toInt()
                strokeWidth = (2f * density).toInt()
            }

            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
            }

            val ivThumb = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(68)
                )
                scaleType = ImageView.ScaleType.CENTER_CROP
                setImageBitmap(applyOkenFilter(tinySrc, modeIdx))
            }

            val tvName = TextView(this).apply {
                text = filterNames[modeIdx]
                textSize = 10f
                maxLines = 1
                gravity = Gravity.CENTER
                setTextColor(0xFFFFFFFF.toInt())
                setTypeface(null, if (isSelected) Typeface.BOLD else Typeface.NORMAL)
                setPadding(dp(2), dp(3), dp(2), dp(3))
            }

            col.addView(ivThumb)
            col.addView(tvName)
            card.addView(col)

            card.setOnClickListener {
                pageFilterModes[currentIndex] = modeIdx
                editedBitmaps[currentIndex] = applyOkenFilter(originalBitmaps[currentIndex], modeIdx)
                ivPreview.setImageBitmap(editedBitmaps[currentIndex])
                rebuildFilterThumbnails()
            }

            filterThumbRow.addView(card)
        }
    }

    // 0: No shadow, 1: Original, 2: Lighten, 3: Magic Color, 4: Doc Clear, 5: Grayscale
    private fun applyOkenFilter(src: Bitmap, mode: Int): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        if (mode == 1) {
            canvas.drawBitmap(src, 0f, 0f, paint)
            return out
        }

        val cm = ColorMatrix()
        var contrast = 1.0f
        var brightness = 0f

        when (mode) {
            0 -> { // No shadow (bright white paper + sharp ink)
                cm.setSaturation(1.1f)
                contrast = 1.48f
                brightness = 34f
            }
            2 -> { // Lighten
                cm.setSaturation(1.05f)
                contrast = 1.15f
                brightness = 28f
            }
            3 -> { // Magic Color (Vibrant HD text & colors)
                cm.setSaturation(1.32f)
                contrast = 1.38f
                brightness = 14f
            }
            4 -> { // Doc Clear (High contrast crisp document)
                cm.setSaturation(0.25f)
                contrast = 1.65f
                brightness = 22f
            }
            5 -> { // Grayscale / B&W
                cm.setSaturation(0f)
                contrast = 1.75f
                brightness = 18f
            }
        }

        val translate = (-.5f * contrast + .5f) * 255f + brightness
        val tone = ColorMatrix(
            floatArrayOf(
                contrast, 0f, 0f, 0f, translate,
                0f, contrast, 0f, 0f, translate,
                0f, 0f, contrast, 0f, translate,
                0f, 0f, 0f, 1f, 0f
            )
        )
        cm.postConcat(tone)
        paint.colorFilter = ColorMatrixColorFilter(cm)
        canvas.drawBitmap(src, 0f, 0f, paint)
        return out
    }

    private fun rotateCurrentPageLeft() {
        val m = Matrix().apply { postRotate(-90f) }
        val orig = originalBitmaps[currentIndex]
        val rotOrig = Bitmap.createBitmap(orig, 0, 0, orig.width, orig.height, m, true)
        originalBitmaps[currentIndex] = rotOrig
        editedBitmaps[currentIndex] = applyOkenFilter(rotOrig, pageFilterModes[currentIndex])
        ivPreview.setImageBitmap(editedBitmaps[currentIndex])
        rebuildFilterThumbnails()
    }

    private fun openManualCropForCurrentPage() {
        val currentOrig = originalBitmaps[currentIndex]
        val density = resources.displayMetrics.density
        val dp = { v: Int -> (v * density).toInt() }

        val frame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(360)
            )
            setBackgroundColor(0xFF0F172A.toInt())
        }

        val cropView = PageCropView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setSource(currentOrig)
        }
        frame.addView(cropView)

        AlertDialog.Builder(this)
            .setTitle("Crop Page (Drag Orange Corners)")
            .setView(frame)
            .setPositiveButton("✓ Apply Crop") { _, _ ->
                val cropped = cropView.getCropped()
                originalBitmaps[currentIndex] = cropped
                editedBitmaps[currentIndex] = applyOkenFilter(cropped, pageFilterModes[currentIndex])
                ivPreview.setImageBitmap(editedBitmaps[currentIndex])
                rebuildFilterThumbnails()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun launchScannerForMore(pageLimit: Int) {
        val options = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setPageLimit(pageLimit)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_BASE)
            .build()

        GmsDocumentScanning.getClient(options).getStartScanIntent(this)
            .addOnSuccessListener { intentSender ->
                extraScannerLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
            }
    }

    private fun updatePageDisplay() {
        if (editedBitmaps.isEmpty()) return
        ivPreview.setImageBitmap(editedBitmaps[currentIndex])
        tvPageIndicator.text = "${currentIndex + 1}/${editedBitmaps.size}"
    }

    private fun getSecureProjectsRoot(): File {
        val dir = File(filesDir, "DocMitra/Projects")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun getSecureVaultAssetsDir(): File {
        val dir = File(filesDir, "DocMitra/Stamps")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun sanitizeFileName(raw: String, fallback: String): String {
        val cleaned = raw.replace("[^a-zA-Z0-9\\u0900-\\u097F_\\- ]".toRegex(), "")
            .replace("..", "")
            .trim()
            .take(50)
        return cleaned.ifEmpty { fallback }
    }

    private fun openStampAndSignMenu() {
        val assetsDir = getSecureVaultAssetsDir()
        val savedMoharFile = File(assetsDir, "saved_mohar.png")
        val savedSignFile = File(assetsDir, "saved_sign.png")

        val options = ArrayList<String>()
        options.add("🔏 Create Custom Mohar / Office Seal")
        if (savedMoharFile.exists()) options.add("📌 Use Saved Mohar")
        options.add("✍️ Draw Digital Signature (E-Sign)")
        if (savedSignFile.exists()) options.add("🖊️ Use Saved Signature")
        options.add("📋 'SELF ATTESTED' + Today's Date")
        options.add("⚖️ 'CERTIFIED TRUE COPY' Stamp")

        AlertDialog.Builder(this)
            .setTitle("Add Mohar / Stamp / Signature")
            .setItems(options.toTypedArray()) { _, which ->
                val choice = options[which]
                when {
                    choice.contains("Create Custom Mohar") -> promptCreateCustomMohar(savedMoharFile)
                    choice.contains("Use Saved Mohar") -> {
                        BitmapFactory.decodeFile(savedMoharFile.absolutePath)?.let { placeStampOnOverlay(it) }
                    }
                    choice.contains("Draw Digital Signature") -> promptDrawSignature(savedSignFile)
                    choice.contains("Use Saved Signature") -> {
                        BitmapFactory.decodeFile(savedSignFile.absolutePath)?.let { placeStampOnOverlay(it) }
                    }
                    choice.contains("SELF ATTESTED") -> {
                        val dateStr = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date())
                        placeStampOnOverlay(generateBoxStampBitmap("SELF ATTESTED", "Date: $dateStr", Color.rgb(18, 56, 168)))
                    }
                    choice.contains("CERTIFIED TRUE COPY") -> {
                        val dateStr = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date())
                        placeStampOnOverlay(generateBoxStampBitmap("CERTIFIED TRUE COPY", "Verified • $dateStr", Color.rgb(185, 28, 28)))
                    }
                }
            }
            .show()
    }

    private fun promptCreateCustomMohar(saveFile: File) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 24, 40, 10)
        }
        val etLine1 = EditText(this).apply { hint = "Top Line (e.g. ADV. VICKY TIWARI)" }
        val etLine2 = EditText(this).apply { hint = "Middle / Designation (e.g. ADVOCATE)" }
        val etLine3 = EditText(this).apply { hint = "Bottom / Place (e.g. HIGH COURT LUCKNOW)" }
        layout.addView(etLine1)
        layout.addView(etLine2)
        layout.addView(etLine3)

        AlertDialog.Builder(this)
            .setTitle("Design Your Mohar (Seal)")
            .setView(layout)
            .setPositiveButton("Round Mohar (Blue)") { _, _ ->
                val bmp = generateRoundMoharBitmap(
                    etLine1.text.toString().trim().ifEmpty { "AUTHORIZED SEAL" },
                    etLine2.text.toString().trim().ifEmpty { "VERIFIED" },
                    etLine3.text.toString().trim().ifEmpty { "INDIA" },
                    Color.rgb(20, 62, 175)
                )
                FileOutputStream(saveFile).use { out -> bmp.compress(Bitmap.CompressFormat.PNG, 100, out) }
                placeStampOnOverlay(bmp)
            }
            .setNeutralButton("Box Mohar (Blue)") { _, _ ->
                val l2 = listOf(etLine2.text.toString().trim(), etLine3.text.toString().trim())
                    .filter { it.isNotEmpty() }.joinToString(" | ")
                val bmp = generateBoxStampBitmap(
                    etLine1.text.toString().trim().ifEmpty { "AUTHORIZED SEAL" },
                    l2,
                    Color.rgb(20, 62, 175)
                )
                FileOutputStream(saveFile).use { out -> bmp.compress(Bitmap.CompressFormat.PNG, 100, out) }
                placeStampOnOverlay(bmp)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun promptDrawSignature(saveFile: File) {
        val pad = SignatureDrawView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (180 * resources.displayMetrics.density).toInt()
            )
        }
        AlertDialog.Builder(this)
            .setTitle("Sign with Finger Below")
            .setView(pad)
            .setPositiveButton("Apply & Save Sign") { _, _ ->
                val bmp = pad.exportSignatureBitmap()
                if (bmp != null) {
                    FileOutputStream(saveFile).use { out -> bmp.compress(Bitmap.CompressFormat.PNG, 100, out) }
                    placeStampOnOverlay(bmp)
                }
            }
            .setNeutralButton("Clear") { _, _ -> promptDrawSignature(saveFile) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun placeStampOnOverlay(stampBmp: Bitmap) {
        overlayView.applyStampBitmap(stampBmp)
        stampControlsBar.visibility = View.VISIBLE
        Toast.makeText(this, "Drag Mohar/Sign anywhere, then tap '✓ Fix on Page'", Toast.LENGTH_SHORT).show()
    }

    private fun burnStampOntoCurrentPage() {
        val stampBmp = overlayView.activeStamp ?: return
        val basePage = editedBitmaps[currentIndex]

        val viewW = ivPreview.width.toFloat()
        val viewH = ivPreview.height.toFloat()
        if (viewW <= 0f || viewH <= 0f) return

        val scale = min(viewW / basePage.width.toFloat(), viewH / basePage.height.toFloat())
        val displayedW = basePage.width * scale
        val displayedH = basePage.height * scale
        val offsetX = (viewW - displayedW) / 2f
        val offsetY = (viewH - displayedH) / 2f

        val targetLeft = ((overlayView.stampX - offsetX) / scale)
        val targetTop = ((overlayView.stampY - offsetY) / scale)
        val targetW = ((stampBmp.width * overlayView.stampScale) / scale)
        val targetH = ((stampBmp.height * overlayView.stampScale) / scale)

        val merged = basePage.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(merged)
        canvas.drawBitmap(
            stampBmp,
            null,
            RectF(targetLeft, targetTop, targetLeft + targetW, targetTop + targetH),
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        )

        editedBitmaps[currentIndex] = merged
        originalBitmaps[currentIndex] = merged
        ivPreview.setImageBitmap(merged)
        overlayView.clearStamp()
        stampControlsBar.visibility = View.GONE
        Toast.makeText(this, "Fixed onto page!", Toast.LENGTH_SHORT).show()
    }

    private fun generateBoxStampBitmap(title: String, subtitle: String, inkColor: Int): Bitmap {
        val w = 520
        val h = 170
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = inkColor
            style = Paint.Style.STROKE
            strokeWidth = 7f
        }
        canvas.drawRoundRect(RectF(8f, 8f, w - 8f, h - 8f), 14f, 14f, borderPaint)

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = inkColor
            textSize = 36f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = inkColor
            textSize = 23f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        if (subtitle.isEmpty()) canvas.drawText(title, w / 2f, h / 2f + 12f, titlePaint)
        else {
            canvas.drawText(title, w / 2f, 74f, titlePaint)
            canvas.drawText(subtitle, w / 2f, 124f, subPaint)
        }
        return bmp
    }

    private fun generateRoundMoharBitmap(topText: String, centerText: String, bottomText: String, inkColor: Int): Bitmap {
        val size = 380
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val cx = size / 2f
        val cy = size / 2f

        val outerRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = inkColor; style = Paint.Style.STROKE; strokeWidth = 8f }
        val innerRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = inkColor; style = Paint.Style.STROKE; strokeWidth = 3.5f }
        canvas.drawCircle(cx, cy, 174f, outerRing)
        canvas.drawCircle(cx, cy, 162f, innerRing)
        canvas.drawCircle(cx, cy, 108f, innerRing)

        val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = inkColor
            textSize = 24f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val topArc = Path().apply { addArc(RectF(cx - 132f, cy - 132f, cx + 132f, cy + 132f), 190f, 160f) }
        canvas.drawTextOnPath(topText.uppercase(Locale.getDefault()), topArc, 0f, 0f, arcPaint)

        val bottomArc = Path().apply { addArc(RectF(cx - 138f, cy - 138f, cx + 138f, cy + 138f), 165f, -150f) }
        canvas.drawTextOnPath(bottomText.uppercase(Locale.getDefault()), bottomArc, 0f, 0f, arcPaint)

        val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = inkColor
            textSize = 27f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("★ $centerText ★", cx, cy + 9f, centerPaint)
        return bmp
    }

    private fun saveProjectAndFinish(shareImmediately: Boolean) {
        try {
            if (overlayView.activeStamp != null) burnStampOntoCurrentPage()
            saveCopiesToGalleryQuietly()

            val projectDir: File
            if (validatedTargetFolder != null) {
                projectDir = validatedTargetFolder!!
            } else {
                val fallback = "Doc_${SimpleDateFormat("ddMM_HHmm", Locale.getDefault()).format(Date())}"
                val cleanName = sanitizeFileName(etDocTitle.text.toString(), fallback)
                projectDir = File(getSecureProjectsRoot(), cleanName)
                if (!projectDir.exists()) projectDir.mkdirs()
            }

            val existingPages = projectDir.listFiles { f ->
                f.isFile && f.extension.equals("jpg", ignoreCase = true)
            }?.sortedBy { it.name } ?: emptyList()

            var nextNum = existingPages.size + 1
            for (bmp in editedBitmaps) {
                var pageFile = File(projectDir, String.format(Locale.US, "page_%04d.jpg", nextNum))
                while (pageFile.exists()) {
                    nextNum++
                    pageFile = File(projectDir, String.format(Locale.US, "page_%04d.jpg", nextNum))
                }
                FileOutputStream(pageFile).use { out -> bmp.compress(Bitmap.CompressFormat.JPEG, 95, out) }
                nextNum++
            }
            projectDir.setLastModified(System.currentTimeMillis())

            Toast.makeText(this, "Saved HD in '${projectDir.name}'!", Toast.LENGTH_SHORT).show()

            if (shareImmediately) {
                val allProjectPages = projectDir.listFiles { f ->
                    f.isFile && f.extension.equals("jpg", ignoreCase = true)
                }?.sortedBy { it.name } ?: emptyList()

                val cachePdf = File(cacheDir, "${projectDir.name}.pdf")
                val pdfDoc = PdfDocument()
                allProjectPages.forEachIndexed { idx, imgFile ->
                    val b = BitmapFactory.decodeFile(imgFile.absolutePath)
                    if (b != null) {
                        val info = PdfDocument.PageInfo.Builder(b.width, b.height, idx + 1).create()
                        val page = pdfDoc.startPage(info)
                        page.canvas.drawBitmap(b, 0f, 0f, null)
                        pdfDoc.finishPage(page)
                    }
                }
                FileOutputStream(cachePdf).use { out -> pdfDoc.writeTo(out) }
                pdfDoc.close()

                val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", cachePdf)
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/pdf"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(shareIntent, "Share HD PDF"))
            } else {
                val homeIntent = Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
                startActivity(homeIntent)
                finish()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Save error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveCopiesToGalleryQuietly() {
        try {
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            for (i in editedBitmaps.indices) {
                val fileName = "DocMitra_${stamp}_P${i + 1}.jpg"
                val bmp = editedBitmaps[i]
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                        put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/DocMitra")
                    }
                    val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    uri?.let { contentResolver.openOutputStream(it)?.use { out -> bmp.compress(Bitmap.CompressFormat.JPEG, 94, out) } }
                } else {
                    val picDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "DocMitra")
                    if (!picDir.exists()) picDir.mkdirs()
                    FileOutputStream(File(picDir, fileName)).use { out -> bmp.compress(Bitmap.CompressFormat.JPEG, 94, out) }
                }
            }
        } catch (_: Exception) {}
    }

    private class StampOverlayView(context: Context) : View(context) {
        var activeStamp: Bitmap? = null
        var stampX = 120f
        var stampY = 260f
        var stampScale = 0.65f
        private var lastTouchX = 0f
        private var lastTouchY = 0f
        private var isDragging = false

        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF16A34A.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }

        fun applyStampBitmap(bmp: Bitmap) {
            activeStamp = bmp
            val viewW = if (width > 0) width.toFloat() else 800f
            val viewH = if (height > 0) height.toFloat() else 1000f
            stampScale = (viewW * 0.42f / bmp.width.coerceAtLeast(1)).coerceIn(0.25f, 1.2f)
            stampX = (viewW - bmp.width * stampScale) / 2f
            stampY = (viewH - bmp.height * stampScale) / 2f
            invalidate()
        }

        fun adjustScale(delta: Float) {
            stampScale = (stampScale + delta).coerceIn(0.2f, 2.0f)
            invalidate()
        }

        fun clearStamp() {
            activeStamp = null
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val bmp = activeStamp ?: return
            val dest = RectF(stampX, stampY, stampX + bmp.width * stampScale, stampY + bmp.height * stampScale)
            canvas.drawBitmap(bmp, null, dest, null)
            canvas.drawRect(dest, borderPaint)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            val bmp = activeStamp ?: return false
            val w = bmp.width * stampScale
            val h = bmp.height * stampScale
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    if (event.x >= stampX - 30f && event.x <= stampX + w + 30f &&
                        event.y >= stampY - 30f && event.y <= stampY + h + 30f) {
                        isDragging = true
                        lastTouchX = event.x
                        lastTouchY = event.y
                        return true
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    if (isDragging) {
                        stampX += event.x - lastTouchX
                        stampY += event.y - lastTouchY
                        lastTouchX = event.x
                        lastTouchY = event.y
                        invalidate()
                        return true
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> isDragging = false
            }
            return false
        }
    }

    private class SignatureDrawView(context: Context) : View(context) {
        private val signPath = Path()
        private var hasStrokes = false
        private val signPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(15, 45, 150)
            style = Paint.Style.STROKE
            strokeWidth = 9f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        init { setBackgroundColor(0xFFF8FAFC.toInt()) }
        override fun onDraw(canvas: Canvas) { super.onDraw(canvas); canvas.drawPath(signPath, signPaint) }
        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> { signPath.moveTo(event.x, event.y); hasStrokes = true; invalidate(); return true }
                MotionEvent.ACTION_MOVE -> { signPath.lineTo(event.x, event.y); invalidate(); return true }
            }
            return true
        }
        fun exportSignatureBitmap(): Bitmap? {
            if (!hasStrokes || width <= 0 || height <= 0) return null
            val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            Canvas(bmp).drawPath(signPath, signPaint)
            return bmp
        }
    }

    private class PageCropView(context: Context) : View(context) {
        private var src: Bitmap? = null
        private val imgRect = RectF()
        private val cropRect = RectF()
        private var handle = 0
        private var lastX = 0f
        private var lastY = 0f

        private val shade = Paint().apply { color = 0x88000000.toInt(); style = Paint.Style.FILL }
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF16A34A.toInt(); style = Paint.Style.STROKE; strokeWidth = 5f }
        private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFEA580C.toInt(); style = Paint.Style.FILL }

        fun setSource(b: Bitmap) {
            src = b
            calcRects()
            invalidate()
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            calcRects()
        }

        private fun calcRects() {
            val b = src ?: return
            if (width <= 0 || height <= 0) return
            val s = min(width.toFloat() / b.width.toFloat(), height.toFloat() / b.height.toFloat())
            val dw = b.width * s
            val dh = b.height * s
            val l = (width - dw) / 2f
            val t = (height - dh) / 2f
            imgRect.set(l, t, l + dw, t + dh)
            cropRect.set(l + dw * 0.08f, t + dh * 0.08f, l + dw * 0.92f, t + dh * 0.92f)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val b = src ?: return
            canvas.drawBitmap(b, null, imgRect, null)
            canvas.drawRect(imgRect.left, imgRect.top, imgRect.right, cropRect.top, shade)
            canvas.drawRect(imgRect.left, cropRect.bottom, imgRect.right, imgRect.bottom, shade)
            canvas.drawRect(imgRect.left, cropRect.top, cropRect.left, cropRect.bottom, shade)
            canvas.drawRect(cropRect.right, cropRect.top, imgRect.right, cropRect.bottom, shade)
            canvas.drawRect(cropRect, line)
            for ((cx, cy) in listOf(
                Pair(cropRect.left, cropRect.top),
                Pair(cropRect.right, cropRect.top),
                Pair(cropRect.right, cropRect.bottom),
                Pair(cropRect.left, cropRect.bottom)
            )) {
                canvas.drawCircle(cx, cy, 18f, dot)
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (src == null) return false
            val x = event.x
            val y = event.y
            val r = 60f
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    handle = when {
                        abs(x - cropRect.left) < r && abs(y - cropRect.top) < r -> 1
                        abs(x - cropRect.right) < r && abs(y - cropRect.top) < r -> 2
                        abs(x - cropRect.right) < r && abs(y - cropRect.bottom) < r -> 3
                        abs(x - cropRect.left) < r && abs(y - cropRect.bottom) < r -> 4
                        cropRect.contains(x, y) -> 5
                        else -> 0
                    }
                    lastX = x
                    lastY = y
                    return handle != 0
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = x - lastX
                    val dy = y - lastY
                    when (handle) {
                        1 -> {
                            cropRect.left = (cropRect.left + dx).coerceIn(imgRect.left, cropRect.right - 80f)
                            cropRect.top = (cropRect.top + dy).coerceIn(imgRect.top, cropRect.bottom - 80f)
                        }
                        2 -> {
                            cropRect.right = (cropRect.right + dx).coerceIn(cropRect.left + 80f, imgRect.right)
                            cropRect.top = (cropRect.top + dy).coerceIn(imgRect.top, cropRect.bottom - 80f)
                        }
                        3 -> {
                            cropRect.right = (cropRect.right + dx).coerceIn(cropRect.left + 80f, imgRect.right)
                            cropRect.bottom = (cropRect.bottom + dy).coerceIn(cropRect.top + 80f, imgRect.bottom)
                        }
                        4 -> {
                            cropRect.left = (cropRect.left + dx).coerceIn(imgRect.left, cropRect.right - 80f)
                            cropRect.bottom = (cropRect.bottom + dy).coerceIn(cropRect.top + 80f, imgRect.bottom)
                        }
                        5 -> {
                            val w = cropRect.width()
                            val h = cropRect.height()
                            val nl = (cropRect.left + dx).coerceIn(imgRect.left, imgRect.right - w)
                            val nt = (cropRect.top + dy).coerceIn(imgRect.top, imgRect.bottom - h)
                            cropRect.set(nl, nt, nl + w, nt + h)
                        }
                    }
                    lastX = x
                    lastY = y
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> handle = 0
            }
            return false
        }

        fun getCropped(): Bitmap {
            val b = src ?: return Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
            if (imgRect.width() <= 0f || imgRect.height() <= 0f) return b
            val sx = b.width.toFloat() / imgRect.width()
            val sy = b.height.toFloat() / imgRect.height()
            val l = ((cropRect.left - imgRect.left) * sx).toInt().coerceIn(0, b.width - 10)
            val t = ((cropRect.top - imgRect.top) * sy).toInt().coerceIn(0, b.height - 10)
            val cw = (cropRect.width() * sx).toInt().coerceIn(10, b.width - l)
            val ch = (cropRect.height() * sy).toInt().coerceIn(10, b.height - t)
            return Bitmap.createBitmap(b, l, t, cw, ch)
        }
    }
}
