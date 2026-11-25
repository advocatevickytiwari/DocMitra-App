package com.docmitra.scanner

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.google.android.material.button.MaterialButton
import java.io.File
import java.io.FileOutputStream

class PdfIdStudioActivity : AppCompatActivity() {

    private var frontBitmap: Bitmap? = null
    private var backBitmap: Bitmap? = null
    private var multiUris: List<Uri> = emptyList()

    private val pickFrontLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            frontBitmap = loadBitmapFromUri(it)
            findViewById<ImageView>(R.id.ivFrontSide).setImageBitmap(frontBitmap)
        }
    }

    private val pickBackLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            backBitmap = loadBitmapFromUri(it)
            findViewById<ImageView>(R.id.ivBackSide).setImageBitmap(backBitmap)
        }
    }

    private val pickMultiLauncher = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) {
            multiUris = uris
            findViewById<TextView>(R.id.tvSelectedImagesCount).text =
                "${uris.size} Photos Selected — Ready to Convert"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pdf_id_studio)

        findViewById<ImageButton>(R.id.btnBackPdfId).setOnClickListener { finish() }

        findViewById<MaterialButton>(R.id.btnSelectFront).setOnClickListener {
            pickFrontLauncher.launch("image/*")
        }
        findViewById<MaterialButton>(R.id.btnSelectBack).setOnClickListener {
            pickBackLauncher.launch("image/*")
        }
        findViewById<MaterialButton>(R.id.btnMergeIdPdf).setOnClickListener {
            mergeIdCardToPdf()
        }

        findViewById<MaterialButton>(R.id.btnPickMultiImages).setOnClickListener {
            pickMultiLauncher.launch("image/*")
        }
        findViewById<MaterialButton>(R.id.btnCreateMultiPdf).setOnClickListener {
            convertMultiImagesToPdf()
        }
    }

    private fun loadBitmapFromUri(uri: Uri): Bitmap? {
        return try {
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
        } catch (e: Exception) {
            null
        }
    }

    private fun mergeIdCardToPdf() {
        val front = frontBitmap
        val back = backBitmap
        if (front == null || back == null) {
            Toast.makeText(this, "Please select both Front and Back sides", Toast.LENGTH_SHORT).show()
            return
        }

        // Standard A4 canvas (1240 x 1754 px at 150 DPI)
        val a4Width = 1240
        val a4Height = 1754
        val pageBitmap = Bitmap.createBitmap(a4Width, a4Height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(pageBitmap)
        canvas.drawColor(Color.WHITE)

        val cardWidth = 900
        val cardHeight = 560
        val leftX = (a4Width - cardWidth) / 2f

        val scaledFront = Bitmap.createScaledBitmap(front, cardWidth, cardHeight, true)
        val scaledBack = Bitmap.createScaledBitmap(back, cardWidth, cardHeight, true)

        canvas.drawBitmap(scaledFront, leftX, 220f, null)
        canvas.drawBitmap(scaledBack, leftX, 920f, null)

        val borderPaint = Paint().apply {
            color = Color.LTGRAY
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }
        canvas.drawRect(leftX, 220f, leftX + cardWidth, 220f + cardHeight, borderPaint)
        canvas.drawRect(leftX, 920f, leftX + cardWidth, 920f + cardHeight, borderPaint)

        saveBitmapsAsPdf(listOf(pageBitmap), "DocMitra_ID_${System.currentTimeMillis()}.pdf")
    }

    private fun convertMultiImagesToPdf() {
        if (multiUris.isEmpty()) {
            Toast.makeText(this, "Select at least 1 photo first", Toast.LENGTH_SHORT).show()
            return
        }
        val bitmaps = mutableListOf<Bitmap>()
        for (uri in multiUris) {
            loadBitmapFromUri(uri)?.let { bitmaps.add(it) }
        }
        if (bitmaps.isNotEmpty()) {
            saveBitmapsAsPdf(bitmaps, "DocMitra_Docs_${System.currentTimeMillis()}.pdf")
        }
    }

    private fun saveBitmapsAsPdf(bitmaps: List<Bitmap>, fileName: String) {
        val pdfDocument = PdfDocument()
        try {
            bitmaps.forEachIndexed { idx, bmp ->
                val pageInfo = PdfDocument.PageInfo.Builder(bmp.width, bmp.height, idx + 1).create()
                val page = pdfDocument.startPage(pageInfo)
                page.canvas.drawBitmap(bmp, 0f, 0f, null)
                pdfDocument.finishPage(page)
            }

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
            showShareDialog(internalFile)
        } catch (e: Exception) {
            pdfDocument.close()
            Toast.makeText(this, "PDF Creation Failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showShareDialog(file: File) {
        val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
        AlertDialog.Builder(this)
            .setTitle("PDF Ready")
            .setMessage("Saved to Documents/DocMitra/${file.name}")
            .setPositiveButton("Share PDF") { _, _ ->
                val share = Intent(Intent.ACTION_SEND).apply {
                    type = "application/pdf"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(share, "Share PDF"))
                finish()
            }
            .setNegativeButton("Done") { _, _ -> finish() }
            .show()
    }
}
