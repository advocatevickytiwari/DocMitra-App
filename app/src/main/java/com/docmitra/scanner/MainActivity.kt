package com.docmitra.scanner

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import java.io.File
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
        val files = dir.listFiles { file -> file.extension.equals("pdf", ignoreCase = true) }
            ?.sortedByDescending { it.lastModified() }

        if (files.isNullOrEmpty()) {
            val emptyTv = TextView(this).apply {
                text = if (isHindi) "अभी कोई PDF सेव नहीं है। ऊपर SCAN दबाकर शुरू करें।" else "No saved PDFs yet. Tap SCAN above to create your first document."
                setTextColor(0xFF94A3B8.toInt())
                textSize = 13f
                setPadding(8, 12, 8, 12)
            }
            recentDocsContainer.addView(emptyTv)
            return
        }

        for (file in files.take(10)) {
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
                setPadding(36, 30, 36, 30)
            }

            val infoCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val titleTv = TextView(this).apply {
                text = file.name
                setTextColor(0xFF0F172A.toInt())
                textSize = 14f
                setTypeface(null, android.graphics.Typeface.BOLD)
            }

            val dateStr = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()).format(Date(file.lastModified()))
            val sizeKb = file.length() / 1024
            val subTv = TextView(this).apply {
                text = "$dateStr • ${sizeKb} KB"
                setTextColor(0xFF64748B.toInt())
                textSize = 11f
            }

            infoCol.addView(titleTv)
            infoCol.addView(subTv)

            val shareBtn = MaterialButton(this).apply {
                text = if (isHindi) "शेयर" else "Share"
                textSize = 11f
                setBackgroundColor(0xFFEFF6FF.toInt())
                setTextColor(0xFF2563EB.toInt())
                cornerRadius = (16f * resources.displayMetrics.density).toInt()
                setOnClickListener { sharePdfFile(file) }
            }

            row.addView(infoCol)
            row.addView(shareBtn)
            card.addView(row)
            card.setOnClickListener { openPdfFile(file) }

            recentDocsContainer.addView(card)
        }
    }

    private fun openPdfFile(file: File) {
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

    private fun sharePdfFile(file: File) {
        val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Share PDF"))
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
            if (isHindi) "ऑटो-क्रॉप • लाइव फिल्टर • तुरंत PDF/JPG" else "Auto-Crop • Live Filters • Instant PDF/JPG"
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
