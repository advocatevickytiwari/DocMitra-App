package com.docmitra.scanner

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.view.Gravity
import android.view.WindowManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.google.android.material.button.MaterialButton
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ScanPreviewActivity : AppCompatActivity() {

    private lateinit var ivPreview: ImageView
    private lateinit var tvPageIndicator: TextView
    private lateinit var etDocTitle: EditText
    private lateinit var seekBrightness: SeekBar
    private lateinit var seekContrast: SeekBar

    private val originalBitmaps = ArrayList<Bitmap>()
    private val editedBitmaps = ArrayList<Bitmap>()
    private var currentIndex = 0
    private var currentFilterMode = 1 // 0=Original, 1=Magic HD, 2=Doc Clear, 3=B&W Xerox
    private var validatedAppendFile: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Security Shield: FLAG_SECURE
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )

        val secureBaseDir = getSecureStorageDir()

        // Path Traversal Guard
        val rawAppendPath = intent.getStringExtra("APPEND_TARGET_PATH")
        if (!rawAppendPath.isNullOrBlank()) {
            try {
                val candidate = File(rawAppendPath).canonicalFile
                val canonicalRoot = secureBaseDir.canonicalFile
                if (candidate.parentFile == canonicalRoot && candidate.extension.equals("pdf", ignoreCase = true)) {
                    validatedAppendFile = candidate
                }
            } catch (_: Exception) {
                validatedAppendFile = null
            }
        }

        val density = resources.displayMetrics.density
        val dp = { v: Int -> (v * density).toInt() }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF0F172A.toInt())
        }

        // Top Bar: Back | Editable File Name (OK Scanner Style) | Page Nav
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xFF14532D.toInt())
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }

        val btnBack = MaterialButton(this).apply {
            text = "✕"
            textSize = 15f
            minWidth = dp(40)
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0x00000000)
            setOnClickListener { finish() }
        }

        val defaultDocName = validatedAppendFile?.nameWithoutExtension
            ?: "Doc_${SimpleDateFormat("ddMM_HHmm", Locale.getDefault()).format(Date())}"

        etDocTitle = EditText(this).apply {
            setText(defaultDocName)
            hint = "Enter name (e.g. Pawan)"
            textSize = 14f
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFFCBD5E1.toInt())
            setBackgroundColor(0x22FFFFFF)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            isSingleLine = true
            isEnabled = (validatedAppendFile == null)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(8)
            }
        }

        tvPageIndicator = TextView(this).apply {
            text = "1/1"
            textSize = 13f
            setTextColor(0xFFFFFFFF.toInt())
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(dp(6), 0, dp(6), 0)
        }

        val btnPrevPage = MaterialButton(this).apply {
            text = "◀"
            minWidth = dp(36)
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0x22FFFFFF)
        }

        val btnNextPage = MaterialButton(this).apply {
            text = "▶"
            minWidth = dp(36)
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0x22FFFFFF)
        }

        topBar.addView(btnBack)
        topBar.addView(etDocTitle)
        topBar.addView(tvPageIndicator)
        topBar.addView(btnPrevPage)
        topBar.addView(btnNextPage)
        root.addView(topBar)

        ivPreview = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            ).apply {
                setMargins(dp(12), dp(10), dp(12), dp(10))
            }
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(0xFF1E293B.toInt())
        }
        root.addView(ivPreview)

        val controlsPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFFFFFFF.toInt())
            setPadding(dp(14), dp(10), dp(14), dp(14))
        }

        val filterRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        fun makeFilterBtn(label: String, bgColor: Int): MaterialButton {
            return MaterialButton(this).apply {
                text = label
                textSize = 11f
                setTextColor(0xFFFFFFFF.toInt())
                setBackgroundColor(bgColor)
                layoutParams = LinearLayout.LayoutParams(0, dp(38), 1f).apply {
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
                setPadding(0, dp(5), 0, dp(2))
            }
            val tv = TextView(this).apply {
                text = title
                textSize = 12f
                setTextColor(0xFF334155.toInt())
                setTypeface(null, android.graphics.Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(dp(76), LinearLayout.LayoutParams.WRAP_CONTENT)
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

        // One-Tap Action Row (OK Scanner Style: Instant Save + Share)
        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, 0)
        }

        val btnDoneSave = MaterialButton(this).apply {
            text = if (validatedAppendFile != null) "✓ Add & Save" else "✓ Done & Save"
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xFF14532D.toInt())
            layoutParams = LinearLayout.LayoutParams(0, dp(48), 1.4f).apply {
                marginEnd = dp(8)
            }
        }

        val btnSaveAndShare = MaterialButton(this).apply {
            text = "Share PDF"
            textSize = 13f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xFFEA580C.toInt())
            layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f)
        }

        actionRow.addView(btnDoneSave)
        actionRow.addView(btnSaveAndShare)
        controlsPanel.addView(actionRow)

        root.addView(controlsPanel)
        setContentView(root)

        // Safe URI loading (content:// only)
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
                multiUris?.take(20)?.forEach { uriStrings.add(it.toString()) }
            }
        }

        for (uriStr in uriStrings.take(20)) {
            try {
                val parsedUri = Uri.parse(uriStr)
                if (parsedUri.scheme != "content") continue

                val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                contentResolver.openInputStream(parsedUri)?.use { stream ->
                    BitmapFactory.decodeStream(stream, null, boundsOpts)
                }
                var sampleSize = 1
                while (boundsOpts.outWidth / sampleSize > 1800 || boundsOpts.outHeight / sampleSize > 1800) {
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

        // Instant 1-Tap Save (Auto-saves to App + Gallery & goes straight to Home list)
        btnDoneSave.setOnClickListener {
            executeInstantSaveAndFinish(shareImmediately = false)
        }

        btnSaveAndShare.setOnClickListener {
            executeInstantSaveAndFinish(shareImmediately = true)
        }
    }

    private fun getSecureStorageDir(): File {
        val dir = File(filesDir, "DocMitra")
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

    // Zero-Lag Hardware ColorMatrix Filter (Instant response with zero hanging)
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
            1 -> cm.setSaturation(1.25f) // Magic HD Color
            2 -> cm.setSaturation(0.15f) // Doc Clear Crisp
            3 -> cm.setSaturation(0f)    // B&W Xerox
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

    private fun executeInstantSaveAndFinish(shareImmediately: Boolean) {
        try {
            // Apply current filter to any untouched pages as well
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

            // Auto-save clean JPG copy to Phone Gallery (Pictures/DocMitra) in background
            saveCopiesToGalleryQuietly()

            val targetPdf: File
            if (validatedAppendFile != null) {
                targetPdf = validatedAppendFile!!
                val combined = ArrayList<Bitmap>()
                if (targetPdf.exists()) {
                    val pfd = ParcelFileDescriptor.open(targetPdf, ParcelFileDescriptor.MODE_READ_ONLY)
                    val renderer = PdfRenderer(pfd)
                    for (i in 0 until renderer.pageCount) {
                        val page = renderer.openPage(i)
                        val bmp = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(0xFFFFFFFF.toInt())
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        page.close()
                        combined.add(bmp)
                    }
                    renderer.close()
                    pfd.close()
                }
                combined.addAll(editedBitmaps)
                createPdfFromBitmaps(combined, targetPdf)
                Toast.makeText(this, "Added to '${targetPdf.nameWithoutExtension}'!", Toast.LENGTH_SHORT).show()
            } else {
                val fallback = "Doc_${SimpleDateFormat("ddMM_HHmm", Locale.getDefault()).format(Date())}"
                val cleanName = sanitizeFileName(etDocTitle.text.toString(), fallback)
                targetPdf = File(getSecureStorageDir(), "$cleanName.pdf")
                createPdfFromBitmaps(editedBitmaps, targetPdf)
                Toast.makeText(this, "Saved '$cleanName' (App + Gallery)!", Toast.LENGTH_SHORT).show()
            }

            if (shareImmediately) {
                val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", targetPdf)
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/pdf"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(shareIntent, "Share PDF"))
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

    private fun createPdfFromBitmaps(bitmaps: List<Bitmap>, outputFile: File) {
        val pdfDocument = PdfDocument()
        for (i in bitmaps.indices) {
            val bmp = bitmaps[i]
            val pageInfo = PdfDocument.PageInfo.Builder(bmp.width, bmp.height, i + 1).create()
            val page = pdfDocument.startPage(pageInfo)
            page.canvas.drawBitmap(bmp, 0f, 0f, null)
            pdfDocument.finishPage(page)
        }
        FileOutputStream(outputFile).use { out -> pdfDocument.writeTo(out) }
        pdfDocument.close()
    }
}
