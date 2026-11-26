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
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.Gravity
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

class ScanPreviewActivity : AppCompatActivity() {

    private lateinit var ivPreview: ImageView
    private lateinit var tvPageIndicator: TextView
    private lateinit var seekBrightness: SeekBar
    private lateinit var seekContrast: SeekBar

    private val originalBitmaps = ArrayList<Bitmap>()
    private val editedBitmaps = ArrayList<Bitmap>()
    private var currentIndex = 0
    private var currentFilterMode = 1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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
                setPadding(dp(4), 0, dp(4), 0)
            }
        }

        val btnFilterOriginal = makeFilterBtn("Original", 0xFF64748B.toInt())
        val btnFilterMagic = makeFilterBtn("Magic HD", 0xFFEA580C.toInt())
        val btnFilterGray = makeFilterBtn("Sharp Text", 0xFF14532D.toInt())
        val btnFilterBw = makeFilterBtn("B&W Print", 0xFF0F172A.toInt())

        filterRow.addView(btnFilterOriginal)
        filterRow.addView(btnFilterMagic)
        filterRow.addView(btnFilterGray)
        filterRow.addView(btnFilterBw)
        controlsPanel.addView(filterRow)

        fun makeSliderRow(title: String): Pair<LinearLayout, SeekBar> {
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
                progress = 62
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            row.addView(tv)
            row.addView(sb)
            return Pair(row, sb)
        }

        val (brightRow, sbBright) = makeSliderRow("Brightness")
        val (contrastRow, sbContrast) = makeSliderRow("Sharpness")
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

        val btnSaveInApp = makeActionBtn("Save App", 0xFF14532D.toInt())
        val btnSaveGallery = makeActionBtn("Gallery", 0xFFEA580C.toInt())
        val btnSharePdf = makeActionBtn("Share PDF", 0xFF1E293B.toInt())

        actionRow.addView(btnSaveInApp)
        actionRow.addView(btnSaveGallery)
        actionRow.addView(btnSharePdf)
        controlsPanel.addView(actionRow)

        root.addView(controlsPanel)
        setContentView(root)

        val uriStrings = intent.getStringArrayListExtra("SCAN_URIS") ?: arrayListOf()
        for (uriStr in uriStrings) {
            try {
                contentResolver.openInputStream(Uri.parse(uriStr))?.use { stream ->
                    val bmp = BitmapFactory.decodeStream(stream)
                    if (bmp != null) {
                        originalBitmaps.add(bmp)
                        editedBitmaps.add(applyMagicCrispEngine(bmp, 1.25f, 12f, 1))
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        if (originalBitmaps.isEmpty()) {
            Toast.makeText(this, "No scanned pages found", Toast.LENGTH_SHORT).show()
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
            Toast.makeText(this, "Original Mode", Toast.LENGTH_SHORT).show()
        }

        btnFilterMagic.setOnClickListener {
            currentFilterMode = 1
            seekBrightness.progress = 64
            seekContrast.progress = 68
            applyCurrentAdjustments()
            Toast.makeText(this, "Magic Crisp HD (Clear Words)", Toast.LENGTH_SHORT).show()
        }

        btnFilterGray.setOnClickListener {
            currentFilterMode = 2
            seekBrightness.progress = 60
            seekContrast.progress = 72
            applyCurrentAdjustments()
            Toast.makeText(this, "Super Sharp Text Mode", Toast.LENGTH_SHORT).show()
        }

        btnFilterBw.setOnClickListener {
            currentFilterMode = 3
            seekBrightness.progress = 65
            seekContrast.progress = 85
            applyCurrentAdjustments()
            Toast.makeText(this, "B&W Clear Print Mode", Toast.LENGTH_SHORT).show()
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
            showSaveDialog(shareImmediately = false)
        }

        btnSaveGallery.setOnClickListener {
            saveAllImagesToGallery()
        }

        btnSharePdf.setOnClickListener {
            showSaveDialog(shareImmediately = true)
        }
    }

    private fun updatePageDisplay() {
        ivPreview.setImageBitmap(editedBitmaps[currentIndex])
        tvPageIndicator.text = "Page ${currentIndex + 1} of ${editedBitmaps.size}"
    }

    private fun applyCurrentAdjustments() {
        val brightness = (seekBrightness.progress - 50) * 2.2f
        val contrast = 0.5f + (seekContrast.progress / 50f) * 0.9f
        val base = originalBitmaps[currentIndex]
        editedBitmaps[currentIndex] = applyMagicCrispEngine(base, contrast, brightness, currentFilterMode)
        ivPreview.setImageBitmap(editedBitmaps[currentIndex])
    }

    private fun applyMagicCrispEngine(src: Bitmap, contrast: Float, brightness: Float, mode: Int): Bitmap {
        if (mode == 0 && contrast in 0.98f..1.02f && brightness in -2f..2f) {
            return src.copy(Bitmap.Config.ARGB_8888, true)
        }

        val toned = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(toned)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        val cm = ColorMatrix()
        when (mode) {
            1 -> cm.setSaturation(1.18f)
            2 -> cm.setSaturation(0.35f)
            3 -> cm.setSaturation(0f)
        }

        val scale = contrast
        val translate = (-.5f * scale + .5f) * 255f + brightness
        val contrastMatrix = ColorMatrix(
            floatArrayOf(
                scale, 0f, 0f, 0f, translate,
                0f, scale, 0f, 0f, translate,
                0f, 0f, scale, 0f, translate,
                0f, 0f, 0f, 1f, 0f
            )
        )
        cm.postConcat(contrastMatrix)
        paint.colorFilter = ColorMatrixColorFilter(cm)
        canvas.drawBitmap(src, 0f, 0f, paint)

        if (mode == 0) return toned

        return sharpenTextPixels(toned, if (mode == 1) 0.65f else 0.95f, mode == 3)
    }

    private fun sharpenTextPixels(bitmap: Bitmap, strength: Float, hardBw: Boolean): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        val outPixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        for (y in 1 until h - 1) {
            val rowOffset = y * w
            for (x in 1 until w - 1) {
                val idx = rowOffset + x
                val c = pixels[idx]
                val top = pixels[idx - w]
                val bottom = pixels[idx + w]
                val left = pixels[idx - 1]
                val right = pixels[idx + 1]

                val rC = (c shr 16) and 0xFF
                val gC = (c shr 8) and 0xFF
                val bC = c and 0xFF

                val rAvg = (((top shr 16) and 0xFF) + ((bottom shr 16) and 0xFF) + ((left shr 16) and 0xFF) + ((right shr 16) and 0xFF)) shr 2
                val gAvg = (((top shr 8) and 0xFF) + ((bottom shr 8) and 0xFF) + ((left shr 8) and 0xFF) + ((right shr 8) and 0xFF)) shr 2
                val bAvg = ((top and 0xFF) + (bottom and 0xFF) + (left and 0xFF) + (right and 0xFF)) shr 2

                var rNew = (rC + ((rC - rAvg) * strength)).toInt().coerceIn(0, 255)
                var gNew = (gC + ((gC - gAvg) * strength)).toInt().coerceIn(0, 255)
                var bNew = (bC + ((bC - bAvg) * strength)).toInt().coerceIn(0, 255)

                if (hardBw) {
                    val lum = (rNew * 77 + gNew * 150 + bNew * 29) shr 8
                    val bwVal = when {
                        lum < 128 -> (lum * 0.45f).toInt()
                        lum > 185 -> 255
                        else -> lum
                    }
                    rNew = bwVal
                    gNew = bwVal
                    bNew = bwVal
                } else {
                    if (rNew > 210 && gNew > 210 && bNew > 210) {
                        rNew = 255
                        gNew = 255
                        bNew = 255
                    }
                }

                outPixels[idx] = (0xFF shl 24) or (rNew shl 16) or (gNew shl 8) or bNew
            }
        }

        for (x in 0 until w) {
            outPixels[x] = pixels[x]
            outPixels[(h - 1) * w + x] = pixels[(h - 1) * w + x]
        }
        for (y in 0 until h) {
            outPixels[y * w] = pixels[y * w]
            outPixels[y * w + (w - 1)] = pixels[y * w + (w - 1)]
        }

        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        result.setPixels(outPixels, 0, w, 0, 0, w, h)
        return result
    }

    private fun showSaveDialog(shareImmediately: Boolean = false) {
        val options = arrayOf("Save as HD PDF (All Pages)", "Save as HD JPG Images")
        AlertDialog.Builder(this)
            .setTitle("Choose Format")
            .setItems(options) { _, which ->
                val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                val appDir = File(getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "DocMitra")
                if (!appDir.exists()) appDir.mkdirs()

                if (which == 0) {
                    val pdfFile = File(appDir, "DocMitra_$timeStamp.pdf")
                    createPdfFromBitmaps(editedBitmaps, pdfFile)
                    Toast.makeText(this, "PDF Saved in DocMitra App!", Toast.LENGTH_LONG).show()
                    if (shareImmediately) shareFile(pdfFile, "application/pdf")
                } else {
                    var lastFile: File? = null
                    for (i in editedBitmaps.indices) {
                        val imgFile = File(appDir, "DocMitra_${timeStamp}_P${i + 1}.jpg")
                        FileOutputStream(imgFile).use { out ->
                            editedBitmaps[i].compress(Bitmap.CompressFormat.JPEG, 96, out)
                        }
                        lastFile = imgFile
                    }
                    Toast.makeText(this, "${editedBitmaps.size} HD Image(s) Saved in App!", Toast.LENGTH_LONG).show()
                    if (shareImmediately && lastFile != null) shareFile(lastFile, "image/jpeg")
                }
            }
            .show()
    }

    private fun saveAllImagesToGallery() {
        try {
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val appDir = File(getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "DocMitra")
            if (!appDir.exists()) appDir.mkdirs()

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
