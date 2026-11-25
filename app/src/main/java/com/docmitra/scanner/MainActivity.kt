package com.docmitra.scanner

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.Gravity
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

class MainActivity : AppCompatActivity() {

    private var isHindi = false
    private lateinit var recentDocsContainer: LinearLayout

    private val scannerLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val scanResult = GmsDocumentScanningResult.fromActivityResultIntent(result.data)
            val pages = scanResult?.pages
            if (!pages.isNullOrEmpty()) {
                val uriList = ArrayList<String>()
                for (page in pages) {
                    uriList.add(page.imageUri.toString())
                }
                val studioIntent = Intent(this, ScanPreviewActivity::class.java).apply {
                    putStringArrayListExtra("SCAN_URIS", uriList)
                }
                startActivity(studioIntent)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        recentDocsContainer = findViewById(R.id.recentDocsContainer)
        findViewById<TextView>(R.id.tvRecentHeading).text = "SAVED DOCUMENTS IN APP"

        val btnLangToggle = findViewById<MaterialButton>(R.id.btnLangToggle)
        val cardCamScan = findViewById<MaterialCardView>(R.id.cardCamScan)

        btnLangToggle.setOnClickListener {
            isHindi = !isHindi
            updateLanguage()
        }

        cardCamScan.setOnClickListener {
            startDocumentScanner()
        }

        setupUtilityCards()
    }

    override fun onResume() {
        super.onResume()
        loadSavedDocuments()
    }

    private fun startDocumentScanner() {
        val options = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setPageLimit(20)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_BASE_WITH_FILTER)
            .build()

        val scanner = GmsDocumentScanning.getClient(options)
        scanner.getStartScanIntent(this)
            .addOnSuccessListener { intentSender ->
                scannerLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Scanner initializing, please try again: ${e.message}", Toast.LENGTH_LONG).show()
            }
    }

    private fun loadSavedDocuments() {
        recentDocsContainer.removeAllViews()
        val dir = File(getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "DocMitra")
        val files = dir.listFiles { file ->
            file.extension.equals("pdf", ignoreCase = true) || file.extension.equals("jpg", ignoreCase = true)
        }?.sortedByDescending { it.lastModified() }

        if (files.isNullOrEmpty()) {
            val emptyTv = TextView(this).apply {
                text = if (isHindi) "अभी ऐप में कोई डॉक्यूमेंट सेव नहीं है। ऊपर SCAN दबाकर शुरू करें।" else "No saved documents yet. Tap SCAN above to create & save your first document."
                setTextColor(0xFF94A3B8.toInt())
                textSize = 13f
                setPadding(8, 12, 8, 12)
            }
            recentDocsContainer.addView(emptyTv)
            return
        }

        for (file in files) {
            val isPdf = file.extension.equals("pdf", ignoreCase = true)
            val card = MaterialCardView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = 10 }
                radius = 12f * resources.displayMetrics.density
                cardElevation = 1f * resources.displayMetrics.density
                setCardBackgroundColor(0xFFFFFFFF.toInt())
                strokeColor = 0xFFE2E8F0.toInt()
                strokeWidth = (1f * resources.displayMetrics.density).toInt()
            }

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(30, 26, 30, 26)
            }

            val badgeTv = TextView(this).apply {
                text = if (isPdf) "PDF" else "JPG"
                setTextColor(if (isPdf) 0xFFDC2626.toInt() else 0xFF059669.toInt())
                setBackgroundColor(if (isPdf) 0xFFFEE2E2.toInt() else 0xFFD1FAE5.toInt())
                textSize = 11f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setPadding(18, 10, 18, 10)
            }

            val infoCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = 24
                }
            }

            val titleTv = TextView(this).apply {
                text = file.name
                setTextColor(0xFF0F172A.toInt())
                textSize = 14f
                setTypeface(null, android.graphics.Typeface.BOLD)
            }

            val dateStr = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()).format(Date(file.lastModified()))
            val sizeKb = (file.length() / 1024).coerceAtLeast(1)
            val subTv = TextView(this).apply {
                text = "$dateStr • ${sizeKb} KB"
                setTextColor(0xFF64748B.toInt())
                textSize = 11f
            }

            infoCol.addView(titleTv)
            infoCol.addView(subTv)

            val moreBtn = MaterialButton(this).apply {
                text = "⋮"
                textSize = 16f
                minWidth = 0
                setBackgroundColor(0xFFF1F5F9.toInt())
                setTextColor(0xFF0F172A.toInt())
                cornerRadius = (12f * resources.displayMetrics.density).toInt()
                setOnClickListener { showDocumentOptions(file, isPdf) }
            }

            row.addView(badgeTv)
            row.addView(infoCol)
            row.addView(moreBtn)
            card.addView(row)
            card.setOnClickListener { openDocument(file, isPdf) }

            recentDocsContainer.addView(card)
        }
    }

    private fun openDocument(file: File, isPdf: Boolean) {
        if (!isPdf) {
            val bmp = BitmapFactory.decodeFile(file.absolutePath) ?: return
            val iv = ImageView(this).apply {
                setImageBitmap(bmp)
                adjustViewBounds = true
                setPadding(16, 16, 16, 16)
            }
            AlertDialog.Builder(this)
                .setTitle(file.name)
                .setView(iv)
                .setPositiveButton("Save to Gallery") { _, _ -> saveImageFileToGallery(file) }
                .setNeutralButton("Share") { _, _ -> shareDocumentFile(file, false) }
                .setNegativeButton("Close", null)
                .show()
            return
        }

        try {
            val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/pdf")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Open PDF"))
        } catch (e: Exception) {
            Toast.makeText(this, "No PDF viewer installed", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showDocumentOptions(file: File, isPdf: Boolean) {
        val options = if (isPdf) {
            arrayOf("Open PDF", "Share PDF", "Delete from App")
        } else {
            arrayOf("View Photo", "Save to Gallery", "Share Photo", "Delete from App")
        }

        AlertDialog.Builder(this)
            .setTitle(file.name)
            .setItems(options) { _, which ->
                when (options[which]) {
                    "Open PDF", "View Photo" -> openDocument(file, isPdf)
                    "Save to Gallery" -> saveImageFileToGallery(file)
                    "Share PDF", "Share Photo" -> shareDocumentFile(file, isPdf)
                    "Delete from App" -> {
                        file.delete()
                        Toast.makeText(this, "Deleted", Toast.LENGTH_SHORT).show()
                        loadSavedDocuments()
                    }
                }
            }
            .show()
    }

    private fun saveImageFileToGallery(file: File) {
        try {
            val bmp = BitmapFactory.decodeFile(file.absolutePath) ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, file.name)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/DocMitra")
                }
                val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                uri?.let { contentResolver.openOutputStream(it)?.use { out -> bmp.compress(Bitmap.CompressFormat.JPEG, 96, out) } }
            } else {
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "DocMitra")
                if (!dir.exists()) dir.mkdirs()
                FileOutputStream(File(dir, file.name)).use { out -> bmp.compress(Bitmap.CompressFormat.JPEG, 96, out) }
            }
            Toast.makeText(this, "Saved to Gallery (Pictures/DocMitra)!", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Gallery save error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareDocumentFile(file: File, isPdf: Boolean) {
        val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = if (isPdf) "application/pdf" else "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Share Document"))
    }

    private fun setupUtilityCards() {
        findViewById<MaterialCardView>(R.id.cardPhotoCompress).setOnClickListener {
            startActivity(Intent(this, PhotoStudioActivity::class.java).apply {
                putExtra("MODE", "COMPRESS")
            })
        }
        findViewById<MaterialCardView>(R.id.cardExamPhoto).setOnClickListener {
            startActivity(Intent(this, PhotoStudioActivity::class.java).apply {
                putExtra("MODE", "EXAM")
            })
        }
        findViewById<MaterialCardView>(R.id.cardImgToPdf).setOnClickListener {
            startActivity(Intent(this, PdfIdStudioActivity::class.java).apply {
                putExtra("MODE", "IMG_TO_PDF")
            })
        }
        findViewById<MaterialCardView>(R.id.cardIdMerger).setOnClickListener {
            startActivity(Intent(this, PdfIdStudioActivity::class.java).apply {
                putExtra("MODE", "ID_MERGE")
            })
        }
        findViewById<MaterialCardView>(R.id.cardPdfTools).setOnClickListener {
            startActivity(Intent(this, PdfIdStudioActivity::class.java).apply {
                putExtra("MODE", "PDF_TOOLS")
            })
        }
        findViewById<MaterialCardView>(R.id.cardIdVault).setOnClickListener {
            startActivity(Intent(this, VaultActivity::class.java))
        }
    }

    private fun updateLanguage() {
        findViewById<TextView>(R.id.tvAppSubtitle).text =
            if (isHindi) "स्मार्ट ऑफलाइन डॉक्यूमेंट स्टूडियो" else "Smart Offline Document Studio"
        findViewById<TextView>(R.id.tvHeroTitle).text =
            if (isHindi) "स्मार्ट क्लियर कैम स्कैनर" else "Smart Clear Cam Scanner"
        findViewById<TextView>(R.id.tvHeroDesc).text =
            if (isHindi) "ऑटो-क्रॉप • HD फिल्टर • ऐप व गैलरी सेव" else "Auto-Crop • HD Filters • Save in App & Gallery"
        findViewById<TextView>(R.id.tvTool1).text =
            if (isHindi) "फोटो साइज कम\nकरें (KB)" else "Photo & Image\nCompress (KB)"
        findViewById<TextView>(R.id.tvTool2).text =
            if (isHindi) "एग्जाम फोटो व\nनाम/तारीख" else "Exam Photo &\nName / Date"
        findViewById<TextView>(R.id.tvTool3).text =
            if (isHindi) "फोटो से PDF\nबनाएं" else "Image to PDF\nMaker"
        findViewById<TextView>(R.id.tvTool4).text =
            if (isHindi) "आधार/ID दोनों\nसाइड एक पेज पर" else "ID Card Front\n& Back Merger"
        findViewById<TextView>(R.id.tvTool5).text =
            if (isHindi) "PDF कंप्रेस,\nमर्ज व लॉक" else "PDF Compress,\nMerge & Lock"
        findViewById<TextView>(R.id.tvTool6).text =
            if (isHindi) "मेरा ID वॉल्ट\n(फिंगरप्रिंट)" else "My ID Vault\n(Fingerprint)"
        loadSavedDocuments()
    }
}
