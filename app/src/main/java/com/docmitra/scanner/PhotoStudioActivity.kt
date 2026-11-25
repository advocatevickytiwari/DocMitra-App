package com.docmitra.scanner

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PhotoStudioActivity : AppCompatActivity() {

    private lateinit var ivPreview: ImageView
    private lateinit var tvSizeInfo: TextView
    private lateinit var etName: EditText
    private lateinit var etDate: EditText
    private lateinit var etCustomKb: EditText

    private var baseBitmap: Bitmap? = null
    private var workingBitmap: Bitmap? = null
    private var finalBytes: ByteArray? = null
    private var originalSizeKb: Int = 0

    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { loadSelectedImage(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_photo_studio)

        ivPreview = findViewById(R.id.ivPhotoPreview)
        tvSizeInfo = findViewById(R.id.tvSizeInfo)
        etName = findViewById(R.id.etCandidateName)
        etDate = findViewById(R.id.etPhotoDate)
        etCustomKb = findViewById(R.id.etCustomKb)

        val today = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date())
        etDate.setText("DOP: $today")

        findViewById<ImageButton>(R.id.btnBackPhoto).setOnClickListener { finish() }
        findViewById<MaterialButton>(R.id.btnPickImage).setOnClickListener {
            pickImageLauncher.launch("image/*")
        }

        findViewById<MaterialButton>(R.id.btnApplyExamStrip).setOnClickListener {
            applyNameDateStrip()
        }

        findViewById<MaterialButton>(R.id.btn20Kb).setOnClickListener { compressToTargetKb(20) }
        findViewById<MaterialButton>(R.id.btn50Kb).setOnClickListener { compressToTargetKb(50) }
        findViewById<MaterialButton>(R.id.btn100Kb).setOnClickListener { compressToTargetKb(100) }
        findViewById<MaterialButton>(R.id.btnCompressCustom).setOnClickListener {
            val target = etCustomKb.text.toString().toIntOrNull()
            if (target != null && target >= 5) {
                compressToTargetKb(target)
            } else {
                Toast.makeText(this, "Enter valid KB (minimum 5 KB)", Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<MaterialButton>(R.id.btnSavePhotoGallery).setOnClickListener {
            saveOutputToGallery()
        }

        // Open gallery picker automatically on launch
        pickImageLauncher.launch("image/*")
    }

    private fun loadSelectedImage(uri: Uri) {
        try {
            val rawBytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return
            originalSizeKb = rawBytes.size / 1024
            val bmp = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size) ?: return
            baseBitmap = bmp
            workingBitmap = bmp
            finalBytes = rawBytes
            ivPreview.setImageBitmap(bmp)
            tvSizeInfo.text = "Original: $originalSizeKb KB  →  Output: $originalSizeKb KB"
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to load image", Toast.LENGTH_SHORT).show()
        }
    }

    private fun applyNameDateStrip() {
        val src = baseBitmap ?: run {
            Toast.makeText(this, "Please select a photo first", Toast.LENGTH_SHORT).show()
            return
        }
        val nameText = etName.text.toString().trim().uppercase(Locale.getDefault())
        val dateText = etDate.text.toString().trim()

        if (nameText.isEmpty() && dateText.isEmpty()) {
            Toast.makeText(this, "Enter Name or Date first", Toast.LENGTH_SHORT).show()
            return
        }

        val stripHeight = (src.height * 0.18f).toInt().coerceAtLeast(70)
        val combined = Bitmap.createBitmap(src.width, src.height + stripHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(combined)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(src, 0f, 0f, null)

        val borderPaint = Paint().apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = (src.width * 0.008f).coerceAtLeast(2f)
        }
        canvas.drawRect(0f, src.height.toFloat(), src.width.toFloat(), combined.height.toFloat(), borderPaint)

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

        val centerX = src.width / 2f
        if (nameText.isNotEmpty() && dateText.isNotEmpty()) {
            textPaint.textSize = (stripHeight * 0.34f)
            canvas.drawText(nameText, centerX, src.height + stripHeight * 0.44f, textPaint)
            textPaint.textSize = (stripHeight * 0.30f)
            canvas.drawText(dateText, centerX, src.height + stripHeight * 0.84f, textPaint)
        } else {
            val single = if (nameText.isNotEmpty()) nameText else dateText
            textPaint.textSize = (stripHeight * 0.38f)
            canvas.drawText(single, centerX, src.height + stripHeight * 0.62f, textPaint)
        }

        workingBitmap = combined
        val baos = ByteArrayOutputStream()
        combined.compress(Bitmap.CompressFormat.JPEG, 95, baos)
        finalBytes = baos.toByteArray()
        ivPreview.setImageBitmap(combined)
        tvSizeInfo.text = "Original: $originalSizeKb KB  →  Output: ${finalBytes!!.size / 1024} KB"
        Toast.makeText(this, "Name & Date strip applied!", Toast.LENGTH_SHORT).show()
    }

    private fun compressToTargetKb(targetKb: Int) {
        var bmp = workingBitmap ?: run {
            Toast.makeText(this, "Please select a photo first", Toast.LENGTH_SHORT).show()
            return
        }

        val targetBytes = targetKb * 1024
        var outputBytes: ByteArray

        // Step 1: Try quality reduction first
        var minQ = 10
        var maxQ = 98
        var bestBytes: ByteArray? = null

        while (minQ <= maxQ) {
            val midQ = (minQ + maxQ) / 2
            val baos = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, midQ, baos)
            val data = baos.toByteArray()
            if (data.size <= targetBytes) {
                bestBytes = data
                minQ = midQ + 1
            } else {
                maxQ = midQ - 1
            }
        }

        // Step 2: If even Q=10 is larger than targetKb, downscale dimensions smoothly
        while (bestBytes == null && bmp.width > 120 && bmp.height > 120) {
            bmp = Bitmap.createScaledBitmap(bmp, (bmp.width * 0.80).toInt(), (bmp.height * 0.80).toInt(), true)
            val baos = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 75, baos)
            val data = baos.toByteArray()
            if (data.size <= targetBytes) {
                bestBytes = data
            }
        }

        outputBytes = bestBytes ?: run {
            val baos = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 50, baos)
            baos.toByteArray()
        }

        finalBytes = outputBytes
        val previewBmp = BitmapFactory.decodeByteArray(outputBytes, 0, outputBytes.size)
        ivPreview.setImageBitmap(previewBmp)
        val outKb = (outputBytes.size / 1024).coerceAtLeast(1)
        tvSizeInfo.text = "Original: $originalSizeKb KB  →  Output: $outKb KB"
        Toast.makeText(this, "Compressed to $outKb KB!", Toast.LENGTH_SHORT).show()
    }

    private fun saveOutputToGallery() {
        val bytes = finalBytes ?: run {
            Toast.makeText(this, "Select a photo first", Toast.LENGTH_SHORT).show()
            return
        }
        val fileName = "DocMitra_Photo_${System.currentTimeMillis()}.jpg"
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/DocMitra")
                }
                val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                uri?.let { contentResolver.openOutputStream(it)?.use { out -> out.write(bytes) } }
            } else {
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "DocMitra")
                if (!dir.exists()) dir.mkdirs()
                FileOutputStream(File(dir, fileName)).use { out -> out.write(bytes) }
            }
            Toast.makeText(this, "Saved to Gallery (Pictures/DocMitra)!", Toast.LENGTH_LONG).show()
            finish()
        } catch (e: Exception) {
            Toast.makeText(this, "Save failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}
