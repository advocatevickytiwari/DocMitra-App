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
    private lateinit var tvPageIndicator: TextView
    private lateinit var seekBrightness: SeekBar
    private lateinit var seekContrast: SeekBar

    private val originalBitmaps = ArrayList<Bitmap>()
    private val editedBitmaps = ArrayList<Bitmap>()
    private var currentIndex = 0
    private var currentFilterMode = 1
    private var validatedAppendFile: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Security Fix 1: Prevent background apps & Recent Apps preview from capturing sensitive documents
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )

        val secureBaseDir = getSecureStorageDir()

        // Security Fix 2: Strict Path Traversal Guard — only allow appending to files strictly inside DocMitra secure folder
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

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xFF14532D.toInt())
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }

        val btnBack = MaterialButton(this).apply {
            text = "← Back"
            textSize = 13f
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0x00000000)
            setOnClickListener { finish() }
        }

        tvPageIndicator = TextView(this).apply {
            text = "Page 1 of 1"
            textSize = 15f
            setTextColor(0xFFFFFFFF.toInt())
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val btnPrevPage = MaterialButton(this).apply {
            text = "◀"
            minWidth = dp(40)
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0x22FFFFFF)
        }

        val btnNextPage = MaterialButton(this).apply {
            text = "▶"
            minWidth = dp(40)
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0x22FFFFFF)
        }

        topBar.addView(btnBack)
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
                setMargins(dp(12), dp(12), dp(12), dp(12))
            }
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(0xFF1E293B.toInt())
        }
        root.addView(ivPreview)

        val controlsPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFFFFFFF.toInt())
            setPadding(dp(14), dp(12), dp(14), dp(14))
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
                setPadding(0, dp(6), 0, dp(2))
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

        val (brightRow, sbBright) = makeSliderRow("Whiteness", 60)
        val (contrastRow, sbContrast) = makeSliderRow("Ink Depth", 65)
        seekBrightness = sbBright
        seekContrast = sbContrast

        controlsPanel.addView(brightRow)
        controlsPanel.addView(contrastRow)

        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, 0)
        }

        fun makeActionBtn(label: String, bgColor: Int): MaterialButton {
            return MaterialButton(this).apply {
                text = label
                textSize = 12f
                setTextColor(0xFFFFFFFF.toInt())
                setBackgroundColor(bgColor)
                layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f).apply {
                    marginEnd = dp(6)
                }
            }
        }

        val btnSaveInApp = makeActionBtn(
            if (validatedAppendFile != null) "Add to File" else "Save App",
            0xFF14532D.toInt()
        )
        val btnSaveGallery = makeActionBtn("Gallery", 0xFFEA580C.toInt())
        val btnSharePdf = makeActionBtn("Share PDF", 0xFF1E293B.toInt())

        actionRow.addView(btnSaveInApp)
        actionRow.addView(btnSaveGallery)
        actionRow.addView(btnSharePdf)
        controlsPanel.addView(actionRow)

        root.addView(controlsPanel)
        setContentView(root)

        // Security Fix 3: Safe URI extraction with strict scheme check (only content:// allowed, blocks file:// hijacking)
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
                if (parsedUri.scheme != "content") continue // Block local file:// theft attempts

                // Downsample huge images safely to prevent OutOfMemory DoS crash
                val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                contentResolver.openInputStream(parsedUri)?.use { stream ->
                    BitmapFactory.decodeStream(stream, null, boundsOpts)
                }
                var sampleSize = 1
                while (boundsOpts.outWidth / sampleSize > 2200 || boundsOpts.outHeight / sampleSize > 2200) {
                    sampleSize *= 2
                }

                val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
                contentResolver.openInputStream(parsedUri)?.use { stream ->
                    val bmp = BitmapFactory.decodeStream(stream, null, decodeOpts)
                    if (bmp != null) {
                        originalBitmaps.add(bmp)
                        editedBitmaps.add(processDocumentPro(bmp, 60, 65, 1))
                    }
                }
            } catch (_: SecurityException) {
                // Ignore unauthorized URI access
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        if (originalBitmaps.isEmpty()) {
            Toast.makeText(this, "No valid image found to scan", Toast.LENGTH_SHORT).show()
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
            Toast.makeText(this, "Original Photo", Toast.LENGTH_SHORT).show()
        }

        btnFilterMagic.setOnClickListener {
            currentFilterMode = 1
            seekBrightness.progress = 60
            seekContrast.progress = 65
            applyCurrentAdjustments()
            Toast.makeText(this, "Magic HD Color (Shadow Removed)", Toast.LENGTH_SHORT).show()
        }

        btnFilterClear.setOnClickListener {
            currentFilterMode = 2
            seekBrightness.progress = 65
            seekContrast.progress = 75
            applyCurrentAdjustments()
            Toast.makeText(this, "Doc Clear (Ultra Sharp Words)", Toast.LENGTH_SHORT).show()
        }

        btnFilterBw.setOnClickListener {
            currentFilterMode = 3
            seekBrightness.progress = 62
            seekContrast.progress = 80
            applyCurrentAdjustments()
            Toast.makeText(this, "B&W Xerox (Pure White & Black)", Toast.LENGTH_SHORT).show()
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

        btnSaveInApp.setOnClickListener {
            if (validatedAppendFile != null) {
                appendPagesToExistingPdf(validatedAppendFile!!, shareImmediately = false)
            } else {
                promptNameAndSave(shareImmediately = false)
            }
        }

        btnSaveGallery.setOnClickListener {
            saveAllImagesToGallery()
        }

        btnSharePdf.setOnClickListener {
            if (validatedAppendFile != null) {
                appendPagesToExistingPdf(validatedAppendFile!!, shareImmediately = true)
            } else {
                promptNameAndSave(shareImmediately = true)
            }
        }
    }

    // Security Fix 4: Store all documents inside internal app sandbox (filesDir) so USB/PC & rogue apps cannot read them
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
        tvPageIndicator.text = "Page ${currentIndex + 1} of ${editedBitmaps.size}"
    }

    private fun applyCurrentAdjustments() {
        val base = originalBitmaps[currentIndex]
        editedBitmaps[currentIndex] = processDocumentPro(
            base,
            seekBrightness.progress,
            seekContrast.progress,
            currentFilterMode
        )
        ivPreview.setImageBitmap(editedBitmaps[currentIndex])
    }

    private fun processDocumentPro(src: Bitmap, whitenessProgress: Int, inkProgress: Int, mode: Int): Bitmap {
        if (mode == 0 && whitenessProgress in 48..52 && inkProgress in 48..52) {
            return src.copy(Bitmap.Config.ARGB_8888, true)
        }

        if (mode == 0) {
            val brightness = (whitenessProgress - 50) * 2.4f
            val contrast = 0.5f + (inkProgress / 50f) * 0.9f
            val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(out)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            val translate = (-.5f * contrast + .5f) * 255f + brightness
            val cm = ColorMatrix(
                floatArrayOf(
                    contrast, 0f, 0f, 0f, translate,
                    0f, contrast, 0f, 0f, translate,
                    0f, 0f, contrast, 0f, translate,
                    0f, 0f, 0f, 1f, 0f
                )
            )
            paint.colorFilter = ColorMatrixColorFilter(cm)
            canvas.drawBitmap(src, 0f, 0f, paint)
            return out
        }

        val w = src.width
        val h = src.height

        val bgW = max(16, w / 24)
        val bgH = max(16, h / 24)
        val small = Bitmap.createScaledBitmap(src, bgW, bgH, true)
        val smallPixels = IntArray(bgW * bgH)
        small.getPixels(smallPixels, 0, bgW, 0, 0, bgW, bgH)

        for (i in smallPixels.indices) {
            val c = smallPixels[i]
            val r = max(135, (c shr 16) and 0xFF)
            val g = max(135, (c shr 8) and 0xFF)
            val b = max(135, c and 0xFF)
            smallPixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        small.setPixels(smallPixels, 0, bgW, 0, 0, bgW, bgH)
        val bgMap = Bitmap.createScaledBitmap(small, w, h, true)

        val srcPixels = IntArray(w * h)
        val bgPixels = IntArray(w * h)
        val normPixels = IntArray(w * h)
        src.getPixels(srcPixels, 0, w, 0, 0, w, h)
        bgMap.getPixels(bgPixels, 0, w, 0, 0, w, h)

        val whiteCut = (236 - (whitenessProgress - 50) * 0.65f).toInt().coerceIn(185, 248)
        val inkGain = 1.15f + (inkProgress / 100f) * 1.45f

        for (i in srcPixels.indices) {
            val sc = srcPixels[i]
            val bc = bgPixels[i]

            val sr = (sc shr 16) and 0xFF
            val sg = (sc shr 8) and 0xFF
            val sb = sc and 0xFF

            val br = max(120, (bc shr 16) and 0xFF)
            val bg = max(120, (bc shr 8) and 0xFF)
            val bb = max(120, bc and 0xFF)

            var nr = ((sr * 255) / br).coerceIn(0, 255)
            var ng = ((sg * 255) / bg).coerceIn(0, 255)
            var nb = ((sb * 255) / bb).coerceIn(0, 255)

            fun enhanceChannel(v: Int): Int {
                if (v >= whiteCut) return 255
                val ratio = v.toFloat() / whiteCut.toFloat()
                val curved = Math.pow(ratio.toDouble(), inkGain.toDouble()).toFloat()
                return (curved * 255f).toInt().coerceIn(0, 255)
            }

            nr = enhanceChannel(nr)
            ng = enhanceChannel(ng)
            nb = enhanceChannel(nb)

            when (mode) {
                1 -> {
                    val maxC = max(nr, max(ng, nb))
                    val minC = min(nr, min(ng, nb))
                    if (maxC - minC > 22) {
                        val avg = (nr + ng + nb) / 3
                        nr = (avg + ((nr - avg) * 1.35f)).toInt().coerceIn(0, 255)
                        ng = (avg + ((ng - avg) * 1.35f)).toInt().coerceIn(0, 255)
                        nb = (avg + ((nb - avg) * 1.35f)).toInt().coerceIn(0, 255)
                    }
                }
                2 -> {
                    val lum = (nr * 77 + ng * 150 + nb * 29) shr 8
                    val crisp = when {
                        lum > 218 -> 255
                        lum < 145 -> (lum * 0.55f).toInt()
                        else -> lum
                    }
                    nr = crisp
                    ng = crisp
                    nb = crisp
                }
                3 -> {
                    val lum = (nr * 77 + ng * 150 + nb * 29) shr 8
                    val threshold = (175 + (whitenessProgress - 50) * 0.6f).toInt().coerceIn(130, 215)
                    val bw = if (lum < threshold) 0 else 255
                    nr = bw
                    ng = bw
                    nb = bw
                }
            }

            normPixels[i] = (0xFF shl 24) or (nr shl 16) or (ng shl 8) or nb
        }

        val outPixels = IntArray(w * h)
        val sharpStrength = when (mode) {
            1 -> 0.85f
            2 -> 1.25f
            else -> 0.45f
        }

        for (y in 1 until h - 1) {
            val row = y * w
            for (x in 1 until w - 1) {
                val idx = row + x
                val c = normPixels[idx]
                val t = normPixels[idx - w]
                val b = normPixels[idx + w]
                val l = normPixels[idx - 1]
                val r = normPixels[idx + 1]

                val rC = (c shr 16) and 0xFF
                val gC = (c shr 8) and 0xFF
                val bC = c and 0xFF

                val rAvg = (((t shr 16) and 0xFF) + ((b shr 16) and 0xFF) + ((l shr 16) and 0xFF) + ((r shr 16) and 0xFF)) shr 2
                val gAvg = (((t shr 8) and 0xFF) + ((b shr 8) and 0xFF) + ((l shr 8) and 0xFF) + ((r shr 8) and 0xFF)) shr 2
                val bAvg = ((t and 0xFF) + (b and 0xFF) + (l and 0xFF) + (r and 0xFF)) shr 2

                val rFinal = (rC + ((rC - rAvg) * sharpStrength)).toInt().coerceIn(0, 255)
                val gFinal = (gC + ((gC - gAvg) * sharpStrength)).toInt().coerceIn(0, 255)
                val bFinal = (bC + ((bC - bAvg) * sharpStrength)).toInt().coerceIn(0, 255)

                outPixels[idx] = (0xFF shl 24) or (rFinal shl 16) or (gFinal shl 8) or bFinal
            }
        }

        for (x in 0 until w) {
            outPixels[x] = normPixels[x]
            outPixels[(h - 1) * w + x] = normPixels[(h - 1) * w + x]
        }
        for (y in 0 until h) {
            outPixels[y * w] = normPixels[y * w]
            outPixels[y * w + (w - 1)] = normPixels[y * w + (w - 1)]
        }

        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        result.setPixels(outPixels, 0, w, 0, 0, w, h)
        return result
    }

    private fun promptNameAndSave(shareImmediately: Boolean) {
        val defaultName = "Doc_${SimpleDateFormat("ddMM_HHmm", Locale.getDefault()).format(Date())}"
        val input = EditText(this).apply {
            setText(defaultName)
            hint = "Enter person or file name (e.g. Pawan, Ram Singh)"
            setSelection(text.length)
        }

        AlertDialog.Builder(this)
            .setTitle("Save Document As")
            .setView(input)
            .setPositiveButton("Save (Can Add More Later)") { _, _ ->
                val cleanName = sanitizeFileName(input.text.toString(), defaultName)
                val appDir = getSecureStorageDir()

                val pdfFile = File(appDir, "$cleanName.pdf")
                createPdfFromBitmaps(editedBitmaps, pdfFile)
                Toast.makeText(this, "Saved securely in '$cleanName'!", Toast.LENGTH_LONG).show()
                if (shareImmediately) {
                    shareFile(pdfFile, "application/pdf")
                } else {
                    finish()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun appendPagesToExistingPdf(targetPdfFile: File, shareImmediately: Boolean) {
        try {
            val combinedBitmaps = ArrayList<Bitmap>()

            if (targetPdfFile.exists()) {
                val pfd = ParcelFileDescriptor.open(targetPdfFile, ParcelFileDescriptor.MODE_READ_ONLY)
                val renderer = PdfRenderer(pfd)
                for (i in 0 until renderer.pageCount) {
                    val page = renderer.openPage(i)
                    val bmp = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(0xFFFFFFFF.toInt())
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    page.close()
                    combinedBitmaps.add(bmp)
                }
                renderer.close()
                pfd.close()
            }

            combinedBitmaps.addAll(editedBitmaps)
            createPdfFromBitmaps(combinedBitmaps, targetPdfFile)

            Toast.makeText(
                this,
                "Added ${editedBitmaps.size} photo(s) to '${targetPdfFile.nameWithoutExtension}' (Total: ${combinedBitmaps.size} pages)!",
                Toast.LENGTH_LONG
            ).show()

            if (shareImmediately) {
                shareFile(targetPdfFile, "application/pdf")
            } else {
                finish()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Error adding pages: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun saveAllImagesToGallery() {
        try {
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val appDir = getSecureStorageDir()

            for (i in editedBitmaps.indices) {
                val fileName = "DocMitra_${timeStamp}_P${i + 1}.jpg"
                val bmp = editedBitmaps[i]

                val appFile = File(appDir, fileName)
                FileOutputStream(appFile).use { out -> bmp.compress(Bitmap.CompressFormat.JPEG, 96, out) }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                        put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/DocMitra")
                    }
                    val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    uri?.let { contentResolver.openOutputStream(it)?.use { out -> bmp.compress(Bitmap.CompressFormat.JPEG, 96, out) } }
                } else {
                    val picDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "DocMitra")
                    if (!picDir.exists()) picDir.mkdirs()
                    FileOutputStream(File(picDir, fileName)).use { out -> bmp.compress(Bitmap.CompressFormat.JPEG, 96, out) }
                }
            }
            Toast.makeText(this, "Saved to Phone Gallery & App!", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Save failed: ${e.message}", Toast.LENGTH_SHORT).show()
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

    private fun shareFile(file: File, mimeType: String) {
        val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Share Document"))
    }
}
