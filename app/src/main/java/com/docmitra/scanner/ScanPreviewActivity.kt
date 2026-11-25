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
    private var currentFilter = 1 // Default: Magic Color

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
                    BitmapFactory.decodeStream(stream)?.let { bmp ->
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
        applyFilterToAll(1) // Apply Magic Color automatically

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }

        findViewById<MaterialButton>(R.id.btnFilterOriginal).setOnClickListener { applyFilterToAll(0) }
        findViewById<MaterialButton>(R.id.btnFilterMagic).setOnClickListener { applyFilterToAll(1) }
        findViewById<MaterialButton>(R.id.btnFilterBW).setOnClickListener { applyFilterToAll(2) }
        findViewById<MaterialButton>(R.id.btnFilterSharp).setOnClickListener { applyFilterToAll(3) }
        findViewById<MaterialButton>(R.id.btnFilterLight).setOnClickListener { applyFilterToAll(4) }

        findViewById<MaterialButton>(R.id.btnSaveJpg).setOnClickListener { saveAsJpg() }
        findViewById<MaterialButton>(R.id.btnSavePdf).setOnClickListener { saveAsPdf() }
    }

    private fun applyFilterToAll(filterType: Int) {
        currentFilter = filterType
        filteredBitmaps.clear()
        for (bmp in originalBitmaps) {
            filteredBitmaps.add(processBitmap(bmp, filterType))
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

    private fun processBitmap(src: Bitmap, type: Int): Bitmap {
        if (type == 0) return src
        val result = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val paint = Paint()
        val cm = ColorMatrix()

        when (type) {
            1 -> { // Magic Color: High contrast + crisp white background
                val contrast = 1.35f
                val brightness = 18f
                cm.set(floatArrayOf(
                    contrast, 0f, 0f, 0f, brightness,
                    0f, contrast, 0f, 0f, brightness,
                    0f, 0f, contrast, 0f, brightness,
                    0f, 0f, 0f, 1f, 0f
                ))
            }
            2 -> { // Docs B&W: Clean grayscale + sharp contrast
                val bw = ColorMatrix()
                bw.setSaturation(0f)
                val contrast = 1.65f
                val translate = (-0.5f * contrast + 0.5f) * 255f + 25f
                val cMatrix = ColorMatrix(floatArrayOf(
                    contrast, 0f, 0f, 0f, translate,
                    0f, contrast, 0f, 0f, translate,
                    0f, 0f, contrast, 0f, translate,
                    0f, 0f, 0f, 1f, 0f
                ))
                cm.postConcat(bw)
                cm.postConcat(cMatrix)
            }
            3 -> { // Ultra Sharp: Extra text definition
                val contrast = 1.55f
                val brightness = 10f
                cm.set(floatArrayOf(
                    contrast, 0f, 0f, 0f, brightness,
                    0f, contrast, 0f, 0f, brightness,
                    0f, 0f, contrast, 0f, brightness,
                    0f, 0f, 0f, 1f, 0f
                ))
            }
            4 -> { // Brighten: Removes shadow
                val contrast = 1.15f
                val brightness = 42f
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

    private fun saveAsJpg() {
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
                    uri?.let { contentResolver.openOutputStream(it)?.use { out -> bmp.compress(Bitmap.CompressFormat.JPEG, 92, out) } }
                } else {
                    val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "DocMitra")
                    if (!dir.exists()) dir.mkdirs()
                    FileOutputStream(File(dir, fileName)).use { out -> bmp.compress(Bitmap.CompressFormat.JPEG, 92, out) }
                }
            }
            Toast.makeText(this, "Saved to Gallery (Pictures/DocMitra)!", Toast.LENGTH_LONG).show()
            finish()
        } catch (e: Exception) {
            Toast.makeText(this, "Save failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveAsPdf() {
        val fileName = "${getCleanName()}.pdf"
        val pdfDocument = PdfDocument()
        try {
            filteredBitmaps.forEachIndexed { idx, bmp ->
                val pageInfo = PdfDocument.PageInfo.Builder(bmp.width, bmp.height, idx + 1).create()
                val page = pdfDocument.startPage(pageInfo)
                page.canvas.drawBitmap(bmp, 0f, 0f, null)
                pdfDocument.finishPage(page)
            }

            // Save inside app's DocMitra folder AND public Documents/DocMitra
            val internalDir = File(getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "DocMitra")
            if (!internalDir.exists()) internalDir.mkdirs()
            val internalFile = File(internalDir, fileName)
            FileOutputStream(internalFile).use { out -> pdfDocument.writeTo(out) }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOCUMENTS + "/DocMitra")
                }
                val uri = contentResolver.insert(MediaStore.Files.getContentUri("external"), values)
                uri?.let { contentResolver.openOutputStream(it)?.use { out -> pdfDocument.writeTo(out) } }
            }

            pdfDocument.close()
            showSuccessDialog(internalFile)
        } catch (e: Exception) {
            pdfDocument.close()
            Toast.makeText(this, "PDF Error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showSuccessDialog(pdfFile: File) {
        val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", pdfFile)
        AlertDialog.Builder(this)
            .setTitle("PDF Saved Successfully")
            .setMessage("Saved to Documents/DocMitra/${pdfFile.name}")
            .setPositiveButton("Share PDF") { _, _ ->
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/pdf"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(shareIntent, "Share Document"))
                finish()
            }
            .setNegativeButton("Done") { _, _ -> finish() }
            .setCancelable(false)
            .show()
    }
}
