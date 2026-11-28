package com.docmitra.scanner

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
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
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.google.android.material.button.MaterialButton
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

class ScanPreviewActivity : AppCompatActivity() {

    private lateinit var ivPreview: ImageView
    private lateinit var overlayView: StampOverlayView
    private lateinit var tvPageIndicator: TextView
    private lateinit var etDocTitle: EditText
    private lateinit var seekBrightness: SeekBar
    private lateinit var seekContrast: SeekBar
    private lateinit var stampControlsBar: LinearLayout

    private val originalBitmaps = ArrayList<Bitmap>()
    private val editedBitmaps = ArrayList<Bitmap>()
    private var currentIndex = 0
    private var currentFilterMode = 1
    private var validatedTargetFolder: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Security Shield: FLAG_SECURE
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )

        val secureBaseDir = getSecureProjectsRoot()

        // Path Traversal Guard for Project Folder
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
            setBackgroundColor(0xFF0F172A.toInt())
        }

        // Top Bar
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xFF14532D.toInt())
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }

        val btnBack = MaterialButton(this).apply {
            text = "✕"
            textSize = 15f
            minWidth = dp(38)
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0x00000000)
            setOnClickListener { finish() }
        }

        val defaultDocName = validatedTargetFolder?.name
            ?: "Doc_${SimpleDateFormat("ddMM_HHmm", Locale.getDefault()).format(Date())}"

        etDocTitle = EditText(this).apply {
            setText(defaultDocName)
            hint = "Name (e.g. Pawan)"
            textSize = 14f
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFFCBD5E1.toInt())
            setBackgroundColor(0x22FFFFFF)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            isSingleLine = true
            isEnabled = (validatedTargetFolder == null)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(6)
            }
        }

        tvPageIndicator = TextView(this).apply {
            text = "1/1"
            textSize = 12f
            setTextColor(0xFFFFFFFF.toInt())
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(4), 0, dp(4), 0)
        }

        val btnPrevPage = MaterialButton(this).apply {
            text = "◀"
            minWidth = dp(34)
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0x22FFFFFF)
        }

        val btnNextPage = MaterialButton(this).apply {
            text = "▶"
            minWidth = dp(34)
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0x22FFFFFF)
        }

        topBar.addView(btnBack)
        topBar.addView(etDocTitle)
        topBar.addView(tvPageIndicator)
        topBar.addView(btnPrevPage)
        topBar.addView(btnNextPage)
        root.addView(topBar)

        // Preview Stage with Interactive Stamp/Sign Overlay
        val stageFrame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            ).apply {
                setMargins(dp(10), dp(8), dp(10), dp(8))
            }
            setBackgroundColor(0xFF1E293B.toInt())
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

        stageFrame.addView(ivPreview)
        stageFrame.addView(overlayView)
        root.addView(stageFrame)

        // Active Stamp Size/Apply Bar (Shown when a Stamp or Sign is placed)
        stampControlsBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xFF1E293B.toInt())
            setPadding(dp(10), dp(6), dp(10), dp(6))
            visibility = View.GONE
        }

        val btnStampSmaller = MaterialButton(this).apply {
            text = "A-"
            textSize = 12f
            minWidth = dp(42)
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xFF334155.toInt())
            setOnClickListener { overlayView.adjustScale(-0.15f) }
        }

        val btnStampBigger = MaterialButton(this).apply {
            text = "A+"
            textSize = 12f
            minWidth = dp(42)
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xFF334155.toInt())
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
            setBackgroundColor(0xFFEA580C.toInt())
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

        // Bottom Controls Panel
        val controlsPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFFFFFFF.toInt())
            setPadding(dp(12), dp(10), dp(12), dp(12))
        }

        val filterRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        fun makeFilterBtn(label: String, bgColor: Int): MaterialButton {
            return MaterialButton(this).apply {
                text = label
                textSize = 10.5f
                setTextColor(0xFFFFFFFF.toInt())
                setBackgroundColor(bgColor)
                layoutParams = LinearLayout.LayoutParams(0, dp(36), 1f).apply {
                    marginEnd = dp(4)
                }
                setPadding(dp(2), 0, dp(2), 0)
            }
        }

        val btnFilterOriginal = makeFilterBtn("Original", 0xFF64748B.toInt())
        val btnFilterMagic = makeFilterBtn("Magic HD", 0xFFEA580C.toInt())
        val btnFilterClear = makeFilterBtn("Doc Clear", 0xFF14532D.toInt())
        val btnFilterBw = makeFilterBtn("B&W Xerox", 0xFF0F172A.toInt())

        filterRow.addView(btnFilterOriginal)
        filterRow.addView(btnFilterMagic)
        filterRow.addView(btnFilterClear)
        filterRow.addView(btnFilterBw)
        controlsPanel.addView(filterRow)

        fun makeSliderRow(title: String, defaultVal: Int): Pair<LinearLayout, SeekBar> {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(4), 0, dp(2))
            }
            val tv = TextView(this).apply {
                text = title
                textSize = 11.5f
                setTextColor(0xFF334155.toInt())
                setTypeface(null, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(dp(72), LinearLayout.LayoutParams.WRAP_CONTENT)
            }
            val sb = SeekBar(this).apply {
                max = 100
                progress = defaultVal
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            row.addView(tv)
            row.addView(sb)
            return Pair(row, sb)
        }

        val (brightRow, sbBright) = makeSliderRow("Brightness", 58)
        val (contrastRow, sbContrast) = makeSliderRow("Clarity", 68)
        seekBrightness = sbBright
        seekContrast = sbContrast

        controlsPanel.addView(brightRow)
        controlsPanel.addView(contrastRow)

        // Action Row: Mohar/Sign | Done & Save | Share PDF
        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, 0)
        }

        val btnMoharSign = MaterialButton(this).apply {
            text = "🔏 Mohar / Sign"
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(0xFF14532D.toInt())
            setBackgroundColor(0xFFDCFCE7.toInt())
            layoutParams = LinearLayout.LayoutParams(0, dp(46), 1.1f).apply {
                marginEnd = dp(6)
            }
            setOnClickListener { openStampAndSignMenu() }
        }

        val btnDoneSave = MaterialButton(this).apply {
            text = if (validatedTargetFolder != null) "✓ Add HD" else "✓ Save HD"
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xFF14532D.toInt())
            layoutParams = LinearLayout.LayoutParams(0, dp(46), 1.1f).apply {
                marginEnd = dp(6)
            }
        }

        val btnSaveAndShare = MaterialButton(this).apply {
            text = "Share PDF"
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xFFEA580C.toInt())
            layoutParams = LinearLayout.LayoutParams(0, dp(46), 0.9f)
        }

        actionRow.addView(btnMoharSign)
        actionRow.addView(btnDoneSave)
        actionRow.addView(btnSaveAndShare)
        controlsPanel.addView(actionRow)

        root.addView(controlsPanel)
        setContentView(root)

        // Load Images Safely (content:// scheme only)
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

        for (uriStr in uriStrings.take(30)) {
            try {
                val parsedUri = Uri.parse(uriStr)
                if (parsedUri.scheme != "content") continue

                val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                contentResolver.openInputStream(parsedUri)?.use { stream ->
                    BitmapFactory.decodeStream(stream, null, boundsOpts)
                }
                var sampleSize = 1
                while (boundsOpts.outWidth / sampleSize > 2000 || boundsOpts.outHeight / sampleSize > 2000) {
                    sampleSize *= 2
                }

                val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
                contentResolver.openInputStream(parsedUri)?.use { stream ->
                    val bmp = BitmapFactory.decodeStream(stream, null, decodeOpts)
                    if (bmp != null) {
                        originalBitmaps.add(bmp)
                        editedBitmaps.add(applyFastHardwareFilter(bmp, 58, 68, 1))
                    }
                }
            } catch (_: Exception) {
            }
        }

        if (originalBitmaps.isEmpty()) {
            Toast.makeText(this, "No valid image found", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        updatePageDisplay()

        btnPrevPage.setOnClickListener {
            if (currentIndex > 0) {
                currentIndex--
                updatePageDisplay()
            }
        }

        btnNextPage.setOnClickListener {
            if (currentIndex < editedBitmaps.size - 1) {
                currentIndex++
                updatePageDisplay()
            }
        }

        btnFilterOriginal.setOnClickListener {
            currentFilterMode = 0
            seekBrightness.progress = 50
            seekContrast.progress = 50
            applyCurrentAdjustments()
        }

        btnFilterMagic.setOnClickListener {
            currentFilterMode = 1
            seekBrightness.progress = 58
            seekContrast.progress = 68
            applyCurrentAdjustments()
        }

        btnFilterClear.setOnClickListener {
            currentFilterMode = 2
            seekBrightness.progress = 62
            seekContrast.progress = 78
            applyCurrentAdjustments()
        }

        btnFilterBw.setOnClickListener {
            currentFilterMode = 3
            seekBrightness.progress = 65
            seekContrast.progress = 92
            applyCurrentAdjustments()
        }

        val sliderListener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) applyCurrentAdjustments()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        }
        seekBrightness.setOnSeekBarChangeListener(sliderListener)
        seekContrast.setOnSeekBarChangeListener(sliderListener)

        btnDoneSave.setOnClickListener {
            saveProjectFolderAndFinish(shareImmediately = false)
        }

        btnSaveAndShare.setOnClickListener {
            saveProjectFolderAndFinish(shareImmediately = true)
        }
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

    private fun updatePageDisplay() {
        ivPreview.setImageBitmap(editedBitmaps[currentIndex])
        tvPageIndicator.text = "${currentIndex + 1}/${editedBitmaps.size}"
    }

    private fun applyCurrentAdjustments() {
        val base = originalBitmaps[currentIndex]
        editedBitmaps[currentIndex] = applyFastHardwareFilter(
            base,
            seekBrightness.progress,
            seekContrast.progress,
            currentFilterMode
        )
        ivPreview.setImageBitmap(editedBitmaps[currentIndex])
    }

    private fun applyFastHardwareFilter(src: Bitmap, brightProg: Int, contrastProg: Int, mode: Int): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        if (mode == 0 && brightProg in 48..52 && contrastProg in 48..52) {
            canvas.drawBitmap(src, 0f, 0f, paint)
            return out
        }

        val cm = ColorMatrix()
        when (mode) {
            1 -> cm.setSaturation(1.25f)
            2 -> cm.setSaturation(0.15f)
            3 -> cm.setSaturation(0f)
        }

        val brightness = (brightProg - 50) * 2.4f
        val contrast = when (mode) {
            3 -> 1.6f + (contrastProg / 50f) * 1.4f
            2 -> 1.1f + (contrastProg / 50f) * 1.1f
            else -> 0.6f + (contrastProg / 50f) * 0.95f
        }
        val translate = (-.5f * contrast + .5f) * 255f + brightness

        val toneMatrix = ColorMatrix(
            floatArrayOf(
                contrast, 0f, 0f, 0f, translate,
                0f, contrast, 0f, 0f, translate,
                0f, 0f, contrast, 0f, translate,
                0f, 0f, 0f, 1f, 0f
            )
        )
        cm.postConcat(toneMatrix)
        paint.colorFilter = ColorMatrixColorFilter(cm)
        canvas.drawBitmap(src, 0f, 0f, paint)
        return out
    }

    // ==================== CUSTOM MOHAR / STAMP & E-SIGN ENGINE ====================

    private fun openStampAndSignMenu() {
        val assetsDir = getSecureVaultAssetsDir()
        val savedMoharFile = File(assetsDir, "saved_mohar.png")
        val savedSignFile = File(assetsDir, "saved_sign.png")

        val options = ArrayList<String>()
        options.add("🔏 Create Custom Mohar / Office Seal")
        if (savedMoharFile.exists()) {
            options.add("📌 Use Saved Mohar")
        }
        options.add("✍️ Draw Digital Signature (E-Sign)")
        if (savedSignFile.exists()) {
            options.add("🖊️ Use Saved Signature")
        }
        options.add("📋 'SELF ATTESTED' + Today's Date")
        options.add("⚖️ 'CERTIFIED TRUE COPY' Stamp")

        AlertDialog.Builder(this)
            .setTitle("Add Mohar / Stamp / Signature")
            .setItems(options.toTypedArray()) { _, which ->
                val choice = options[which]
                when {
                    choice.contains("Create Custom Mohar") -> promptCreateCustomMohar(savedMoharFile)
                    choice.contains("Use Saved Mohar") -> {
                        val bmp = BitmapFactory.decodeFile(savedMoharFile.absolutePath)
                        if (bmp != null) placeStampOnOverlay(bmp)
                    }
                    choice.contains("Draw Digital Signature") -> promptDrawSignature(savedSignFile)
                    choice.contains("Use Saved Signature") -> {
                        val bmp = BitmapFactory.decodeFile(savedSignFile.absolutePath)
                        if (bmp != null) placeStampOnOverlay(bmp)
                    }
                    choice.contains("SELF ATTESTED") -> {
                        val dateStr = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date())
                        val bmp = generateBoxStampBitmap("SELF ATTESTED", "Date: $dateStr", Color.rgb(18, 56, 168))
                        placeStampOnOverlay(bmp)
                    }
                    choice.contains("CERTIFIED TRUE COPY") -> {
                        val dateStr = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date())
                        val bmp = generateBoxStampBitmap("CERTIFIED TRUE COPY", "Verified • $dateStr", Color.rgb(185, 28, 28))
                        placeStampOnOverlay(bmp)
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
        val etLine1 = EditText(this).apply {
            hint = "Top Line (e.g. ADV. VICKY TIWARI)"
        }
        val etLine2 = EditText(this).apply {
            hint = "Middle / Designation (e.g. ADVOCATE)"
        }
        val etLine3 = EditText(this).apply {
            hint = "Bottom / Place (e.g. HIGH COURT LUCKNOW)"
        }
        layout.addView(etLine1)
        layout.addView(etLine2)
        layout.addView(etLine3)

        AlertDialog.Builder(this)
            .setTitle("Design Your Mohar (Seal)")
            .setView(layout)
            .setPositiveButton("Round Mohar (Blue)") { _, _ ->
                val l1 = etLine1.text.toString().trim().ifEmpty { "AUTHORIZED SEAL" }
                val l2 = etLine2.text.toString().trim().ifEmpty { "VERIFIED" }
                val l3 = etLine3.text.toString().trim().ifEmpty { "INDIA" }
                val bmp = generateRoundMoharBitmap(l1, l2, l3, Color.rgb(20, 62, 175))
                FileOutputStream(saveFile).use { out -> bmp.compress(Bitmap.CompressFormat.PNG, 100, out) }
                placeStampOnOverlay(bmp)
            }
            .setNeutralButton("Box Mohar (Blue)") { _, _ ->
                val l1 = etLine1.text.toString().trim().ifEmpty { "AUTHORIZED SEAL" }
                val l2 = listOf(etLine2.text.toString().trim(), etLine3.text.toString().trim())
                    .filter { it.isNotEmpty() }.joinToString(" | ")
                val bmp = generateBoxStampBitmap(l1, l2, Color.rgb(20, 62, 175))
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
        overlayView.setStampBitmap(stampBmp)
        stampControlsBar.visibility = View.VISIBLE
        Toast.makeText(this, "Drag Mohar/Sign anywhere on page, then tap '✓ Fix on Page'", Toast.LENGTH_LONG).show()
    }

    private fun burnStampOntoCurrentPage() {
        val stampBmp = overlayView.stampBitmap ?: return
        val basePage = editedBitmaps[currentIndex]

        // Map overlay coordinates precisely onto underlying page bitmap
        val viewW = ivPreview.width.toFloat()
        val viewH = ivPreview.height.toFloat()
        val bmpW = basePage.width.toFloat()
        val bmpH = basePage.height.toFloat()
        if (viewW <= 0f || viewH <= 0f) return

        val scale = min(viewW / bmpW, viewH / bmpH)
        val displayedW = bmpW * scale
        val displayedH = bmpH * scale
        val offsetX = (viewW - displayedW) / 2f
        val offsetY = (viewH - displayedH) / 2f

        val targetLeftOnBmp = ((overlayView.stampX - offsetX) / scale)
        val targetTopOnBmp = ((overlayView.stampY - offsetY) / scale)
        val targetWOnBmp = ((stampBmp.width * overlayView.stampScale) / scale)
        val targetHOnBmp = ((stampBmp.height * overlayView.stampScale) / scale)

        val merged = basePage.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(merged)
        val destRect = RectF(
            targetLeftOnBmp,
            targetTopOnBmp,
            targetLeftOnBmp + targetWOnBmp,
            targetTopOnBmp + targetHOnBmp
        )
        canvas.drawBitmap(stampBmp, null, destRect, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))

        editedBitmaps[currentIndex] = merged
        originalBitmaps[currentIndex] = merged
        ivPreview.setImageBitmap(merged)
        overlayView.clearStamp()
        stampControlsBar.visibility = View.GONE
        Toast.makeText(this, "Mohar / Sign fixed onto page!", Toast.LENGTH_SHORT).show()
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

        if (subtitle.isEmpty()) {
            canvas.drawText(title, w / 2f, h / 2f + 12f, titlePaint)
        } else {
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

        val outerRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = inkColor
            style = Paint.Style.STROKE
            strokeWidth = 8f
        }
        val innerRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = inkColor
            style = Paint.Style.STROKE
            strokeWidth = 3.5f
        }
        canvas.drawCircle(cx, cy, 174f, outerRing)
        canvas.drawCircle(cx, cy, 162f, innerRing)
        canvas.drawCircle(cx, cy, 108f, innerRing)

        val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = inkColor
            textSize = 24f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }

        val topArc = Path().apply {
            addArc(RectF(cx - 132f, cy - 132f, cx + 132f, cy + 132f), 190f, 160f)
        }
        canvas.drawTextOnPath(topText.uppercase(Locale.getDefault()), topArc, 0f, 0f, arcPaint)

        val bottomArc = Path().apply {
            addArc(RectF(cx - 138f, cy - 138f, cx + 138f, cy + 138f), 165f, -150f)
        }
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

    // ==================== SAVE TO HD PROJECT FOLDER ====================

    private fun saveProjectFolderAndFinish(shareImmediately: Boolean) {
        try {
            if (overlayView.stampBitmap != null) {
                burnStampOntoCurrentPage()
            }

            for (i in originalBitmaps.indices) {
                if (i != currentIndex) {
                    editedBitmaps[i] = applyFastHardwareFilter(
                        originalBitmaps[i],
                        seekBrightness.progress,
                        seekContrast.progress,
                        currentFilterMode
                    )
                }
            }

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
                FileOutputStream(pageFile).use { out ->
                    bmp.compress(Bitmap.CompressFormat.JPEG, 95, out)
                }
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
        } catch (_: Exception) {
        }
    }

    // Draggable Stamp/Signature Overlay View
    private class StampOverlayView(context: Context) : View(context) {
        var stampBitmap: Bitmap? = null
        var stampX = 120f
        var stampY = 260f
        var stampScale = 0.65f
        private var lastTouchX = 0f
        private var lastTouchY = 0f
        private var isDragging = false

        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFEA580C.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }

        fun setStampBitmap(bmp: Bitmap) {
            stampBitmap = bmp
            stampScale = (width * 0.42f / bmp.width.coerceAtLeast(1)).coerceIn(0.25f, 1.2f)
            stampX = (width - bmp.width * stampScale) / 2f
            stampY = (height - bmp.height * stampScale) / 2f
            invalidate()
        }

        fun adjustScale(delta: Float) {
            stampScale = (stampScale + delta).coerceIn(0.2f, 2.0f)
            invalidate()
        }

        fun clearStamp() {
            stampBitmap = null
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val bmp = stampBitmap ?: return
            val dest = RectF(
                stampX,
                stampY,
                stampX + bmp.width * stampScale,
                stampY + bmp.height * stampScale
            )
            canvas.drawBitmap(bmp, null, dest, null)
            canvas.drawRect(dest, borderPaint)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            val bmp = stampBitmap ?: return false
            val w = bmp.width * stampScale
            val h = bmp.height * stampScale

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    if (event.x in (stampX - 30f)..(stampX + w + 30f) &&
                        event.y in (stampY - 30f)..(stampY + h + 30f)
                    ) {
                        isDragging = true
                        lastTouchX = event.x
                        lastTouchY = event.y
                        return true
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    if (isDragging) {
                        stampX += (event.x - lastTouchX)
                        stampY += (event.y - lastTouchY)
                        lastTouchX = event.x
                        lastTouchY = event.y
                        invalidate()
                        return true
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    isDragging = false
                }
            }
            return false
        }
    }

    // Finger Signature Drawing Pad
    private class SignatureDrawView(context: Context) : View(context) {
        private val path = Path()
        private var hasStrokes = false
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(15, 45, 150)
            style = Paint.Style.STROKE
            strokeWidth = 9f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        init {
            setBackgroundColor(0xFFF8FAFC.toInt())
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            canvas.drawPath(path, paint)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    path.moveTo(event.x, event.y)
                    hasStrokes = true
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    path.lineTo(event.x, event.y)
                    invalidate()
                    return true
                }
            }
            return true
        }

        fun exportSignatureBitmap(): Bitmap? {
            if (!hasStrokes || width <= 0 || height <= 0) return null
            val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            canvas.drawPath(path, paint)
            return bmp
        }
    }
}
