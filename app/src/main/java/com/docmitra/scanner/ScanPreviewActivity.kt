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
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
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
    private lateinit var etFileName: EditText
    private var originalBitmaps = mutableListOf<Bitmap>()
    private var filteredBitmaps = mutableListOf<Bitmap>()
    private var currentFilter = 1 // Default: HD Magic Clear

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_scan_preview)

        ivPreview = findViewById(R.id.ivPreview)
        etFileName = findViewById(R.id.etFileName)
        val tvPageCount = findViewById<TextView>(R.id.tvPageCount)

        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        etFileName.setText("DocMitra_$timeStamp")

        val uriStrings = intent.getStringArrayListExtra("SCAN_URIS") ?: arrayListOf()
        for (uriStr in uriStrings) {
            try {
                val uri = Uri.parse(uriStr)
                contentResolver.openInputStream(uri)?.use { stream ->
                    val opts = BitmapFactory.Options().apply {
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                    }
                    BitmapFactory.decodeStream(stream, null, opts)?.let { bmp ->
                        originalBitmaps.add(bmp)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        if (originalBitmaps.isEmpty()) {
            Toast.makeText(this, "No image captured", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        tvPageCount.text = "${originalBitmaps.size} Page${if (originalBitmaps.size > 1) "s" else ""}"

        // Automatically apply HD Clear & Bright enhancement on capture
        applyFilterToAll(1)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }

        val btnOrig = findViewById<MaterialButton>(R.id.btnFilterOriginal)
        val btnMagic = findViewById<MaterialButton>(R.id.btnFilterMagic)
        val btnBw = findViewById<MaterialButton>(R.id.btnFilterBW)
        val btnSharp = findViewById<MaterialButton>(R.id.btnFilterSharp)
        val btnLight = findViewById<MaterialButton>(R.id.btnFilterLight)

        btnOrig.text = "Original"
        btnMagic.text = "HD Clear"
        btnBw.text = "Docs B&W"
        btnSharp.text = "Ultra Sharp"
        btnLight.text = "Super Bright"

        btnOrig.setOnClickListener { applyFilterToAll(0) }
        btnMagic.setOnClickListener { applyFilterToAll(1) }
        btnBw.setOnClickListener { applyFilterToAll(2) }
        btnSharp.setOnClickListener { applyFilterToAll(3) }
        btnLight.setOnClickListener { applyFilterToAll(4) }

        val btnSaveJpg = findViewById<MaterialButton>(R.id.btnSaveJpg)
        val btnSavePdf = findViewById<MaterialButton>(R.id.btnSavePdf)

        btnSaveJpg.text = "Save Photo in App"
        btnSavePdf.text = "Save PDF in App"

        btnSaveJpg.setOnClickListener { saveJpgInAppFirst() }
        btnSavePdf.setOnClickListener { savePdfInAppFirst() }
    }

    private fun applyFilterToAll(filterType: Int) {
        currentFilter = filterType
        filteredBitmaps.clear()
        for (bmp in originalBitmaps) {
            filteredBitmaps.add(enhanceDocumentHd(bmp, filterType))
        }
        ivPreview.setImageBitmap(filteredBitmaps[0])
        highlightActiveFilter(filterType)
    }

    private fun highlightActiveFilter(active: Int) {
        val buttons = listOf(
            findViewById<MaterialButton>(R.id.btnFilterOriginal),
            findViewById<MaterialButton>(R.id.btnFilterMagic),
            findViewById<MaterialButton>(R.id.btnFilterBW),
            findViewById<MaterialButton>(R.id.btnFilterSharp),
            findViewById<MaterialButton>(R.id.btnFilterLight)
        )
        buttons.forEachIndexed { index, btn ->
            if (index == active) {
                btn.setBackgroundColor(0xFF2563EB.toInt())
                btn.setTextColor(0xFFFFFFFF.toInt())
            } else {
                btn.setBackgroundColor(0xFF334155.toInt())
                btn.setTextColor(0xFFCBD5E1.toInt())
            }
        }
    }

    // HD Document Enhancement: Boosts paper whiteness, text sharpness & clarity
    private fun enhanceDocumentHd(src: Bitmap, type: Int): Bitmap {
        if (type == 0) return src
        val result = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val cm = ColorMatrix()

        when (type) {
            1 -> { // HD Clear (Auto-Enhance): Bright white paper + crisp dark ink + rich color
                val sat = ColorMatrix().apply { setSaturation(1.18f) }
                val contrast = 1.42f
                val brightness = 24f
                val tone = ColorMatrix(floatArrayOf(
                    contrast, 0f, 0f, 0f, brightness,
                    0f, contrast, 0f, 0f, brightness,
                    0f, 0f, contrast, 0f, brightness,
                    0f, 0f, 0f, 1f, 0f
                ))
                cm.postConcat(sat)
                cm.postConcat(tone)
            }
            2 -> { // Docs B&W: Pure white background + deep black text
                val bw = ColorMatrix().apply { setSaturation(0f) }
                val contrast = 1.75f
                val brightness = 30f
                val tone = ColorMatrix(floatArrayOf(
                    contrast, 0f, 0f, 0f, brightness,
                    0f, contrast, 0f, 0f, brightness,
                    0f, 0f, contrast, 0f, brightness,
                    0f, 0f, 0f, 1f, 0f
                ))
                cm.postConcat(bw)
                cm.postConcat(tone)
            }
            3 -> { // Ultra Sharp: High definition text edge boost
                val contrast = 1.60f
                val brightness = 16f
                cm.set(floatArrayOf(
                    contrast, 0f, 0f, 0f, brightness,
                    0f, contrast, 0f, 0f, brightness,
                    0f, 0f, contrast, 0f, brightness,
                    0f, 0f, 0f, 1f, 0f
                ))
            }
            4 -> { // Super Bright: Removes dark shadows completely
                val contrast = 1.25f
                val brightness = 46f
                cm.set(floatArrayOf(
                    contrast, 0f, 0f, 0f, brightness,
                    0f, contrast, 0f, 0f, brightness,
                    0f, 0f, contrast, 0f, brightness,
                    0f, 0f, 0f, 1f, 0f
                ))
            }
        }

        paint.colorFilter = ColorMatrixColorFilter(cm)
        canvas.drawBitmap(src, 0f, 0f, paint)
        return result
    }

    private fun getCleanName(): String {
        val raw = etFileName.text.toString().trim().replace(Regex("[^a-zA-Z0-9_-]"), "_")
        return if (raw.isEmpty()) "DocMitra_${System.currentTimeMillis()}" else raw
    }

    private fun getAppStorageDir(): File {
        val dir = File(getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "DocMitra")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    // Step 1: Save HD Photo inside App first, then offer Gallery export
    private fun saveJpgInAppFirst() {
        val baseName = getCleanName()
        val appDir = getAppStorageDir()
        val savedFiles = mutableListOf<File>()
        try {
            filteredBitmaps.forEachIndexed { idx, bmp ->
                val fileName = if (filteredBitmaps.size == 1) "$baseName.jpg" else "${baseName}_p${idx + 1}.jpg"
                val file = File(appDir, fileName)
                FileOutputStream(file).use { out ->
                    bmp.compress(Bitmap.CompressFormat.JPEG, 96, out)
                }
                savedFiles.add(file)
            }
            showSavedOptionsDialog(savedFiles, isPdf = false)
        } catch (e: Exception) {
            Toast.makeText(this, "Save failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // Step 1: Save HD PDF inside App first, then offer Gallery/Downloads export
    private fun savePdfInAppFirst() {
        val fileName = "${getCleanName()}.pdf"
        val appDir = getAppStorageDir()
        val pdfFile = File(appDir, fileName)
        val pdfDocument = PdfDocument()
        try {
            filteredBitmaps.forEachIndexed { idx, bmp ->
                val pageInfo = PdfDocument.PageInfo.Builder(bmp.width, bmp.height, idx + 1).create()
                val page = pdfDocument.startPage(pageInfo)
                page.canvas.drawBitmap(bmp, 0f, 0f, null)
                pdfDocument.finishPage(page)
            }
            FileOutputStream(pdfFile).use { out -> pdfDocument.writeTo(out) }
            pdfDocument.close()
            showSavedOptionsDialog(listOf(pdfFile), isPdf = true)
        } catch (e: Exception) {
            pdfDocument.close()
            Toast.makeText(this, "PDF Error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // Step 2: After saving in App, give user option to Save to Gallery & Share
    private fun showSavedOptionsDialog(files: List<File>, isPdf: Boolean) {
        val primaryFile = files.first()
        val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", primaryFile)

        AlertDialog.Builder(this)
            .setTitle("Saved in DocMitra App ✓")
            .setMessage("यह डॉक्यूमेंट ऐप में सुरक्षित सेव हो गया है। क्या आप इसे फोन की Gallery / Downloads में भी सेव करना चाहते हैं?")
            .setPositiveButton("Save to Gallery") { _, _ ->
                exportToPhoneGallery()
                finish()
            }
            .setNeutralButton("Share") { _, _ ->
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = if (isPdf) "application/pdf" else "image/jpeg"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(shareIntent, "Share Document"))
                finish()
            }
            .setNegativeButton("Done (Keep in App)") { _, _ ->
                finish()
            }
            .setCancelable(false)
            .show()
    }

    private fun exportToPhoneGallery() {
        val baseName = getCleanName()
        try {
            filteredBitmaps.forEachIndexed { idx, bmp ->
                val fileName = if (filteredBitmaps.size == 1) "$baseName.jpg" else "${baseName}_p${idx + 1}.jpg"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                        put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/DocMitra")
                    }
                    val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    uri?.let { contentResolver.openOutputStream(it)?.use { out -> bmp.compress(Bitmap.CompressFormat.JPEG, 96, out) } }
                } else {
                    val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "DocMitra")
                    if (!dir.exists()) dir.mkdirs()
                    FileOutputStream(File(dir, fileName)).use { out -> bmp.compress(Bitmap.CompressFormat.JPEG, 96, out) }
                }
            }
            Toast.makeText(this, "Saved to Gallery (Pictures/DocMitra)!", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Gallery export error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}
