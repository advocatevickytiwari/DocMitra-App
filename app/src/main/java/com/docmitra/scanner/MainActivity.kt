package com.docmitra.scanner

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.docmitra.scanner.databinding.ActivityMainBinding
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var isHindi = false

    private val scannerLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val scanResult = GmsDocumentScanningResult.fromActivityResultIntent(result.data)
            scanResult?.pdf?.let { pdf ->
                shareScannedPdf(pdf.uri)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupClicks()
    }

    private fun setupClicks() {
        // 1. Live Smart Clear Cam Scanner (ML Kit with Auto-Crop & Clear Filters)
        binding.cardCamScan.setOnClickListener {
            startClearDocumentScanner()
        }

        // 2. Language Toggle (English <-> Hindi)
        binding.btnLangToggle.setOnClickListener {
            isHindi = !isHindi
            updateLanguageUI()
        }

        // Upcoming Phase 2-4 Modules
        binding.cardPhotoCompress.setOnClickListener {
            showToast(if (isHindi) "Photo Compress अगले अपडेट में जुड़ रहा है" else "Photo Compress module ready in Phase 2")
        }
        binding.cardExamPhoto.setOnClickListener {
            showToast(if (isHindi) "Exam Photo Maker अगले अपडेट में जुड़ रहा है" else "Exam Photo Maker ready in Phase 2")
        }
        binding.cardImgToPdf.setOnClickListener {
            showToast(if (isHindi) "Image to PDF अगले अपडेट में जुड़ रहा है" else "Image to PDF ready in Phase 3")
        }
        binding.cardIdMerger.setOnClickListener {
            showToast(if (isHindi) "ID Card Merger अगले अपडेट में जुड़ रहा है" else "ID Card Merger ready in Phase 3")
        }
        binding.cardPdfTools.setOnClickListener {
            showToast(if (isHindi) "PDF Tools अगले अपडेट में जुड़ रहा है" else "PDF Tools ready in Phase 4")
        }
        binding.cardIdVault.setOnClickListener {
            showToast(if (isHindi) "ID Vault अगले अपडेट में जुड़ रहा है" else "ID Vault ready in Phase 4")
        }
    }

    private fun startClearDocumentScanner() {
        val options = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setPageLimit(20)
            .setResultFormats(
                GmsDocumentScannerOptions.RESULT_FORMAT_JPEG,
                GmsDocumentScannerOptions.RESULT_FORMAT_PDF
            )
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build()

        val scanner = GmsDocumentScanning.getClient(options)
        scanner.getStartScanIntent(this)
            .addOnSuccessListener { intentSender ->
                scannerLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
            }
            .addOnFailureListener { e ->
                showToast("Scanner Error: ${e.localizedMessage}")
            }
    }

    private fun shareScannedPdf(pdfUri: Uri) {
        try {
            val outFile = File(cacheDir, "DocMitra_Scan_${System.currentTimeMillis()}.pdf")
            contentResolver.openInputStream(pdfUri)?.use { input ->
                FileOutputStream(outFile).use { output ->
                    input.copyTo(output)
                }
            }
            val contentUri = FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider",
                outFile
            )
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, contentUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(shareIntent, "Share or Save Clear PDF"))
        } catch (e: Exception) {
            showToast("Error saving PDF: ${e.localizedMessage}")
        }
    }

    private fun updateLanguageUI() {
        if (isHindi) {
            binding.tvAppSubtitle.text = "100% ऑफलाइन • बिना वॉटरमार्क"
            binding.tvHeroTitle.text = "स्मार्ट क्लियर कैमरा स्कैनर"
            binding.tvHeroDesc.text = "ऑटो-क्रॉप + मैजिक क्लियर फिल्टर से साफ PDF बनाएं"
            binding.tvTool1.text = "फोटो और इमेज\nकंप्रेस (KB)"
            binding.tvTool2.text = "सरकारी फॉर्म फोटो\nऔर नाम/तारीख"
            binding.tvTool3.text = "फोटो से PDF\nबनाएं"
            binding.tvTool4.text = "ID कार्ड आगे-पीछे\nएक पेज पर"
            binding.tvTool5.text = "PDF कंप्रेस,\nमर्ज और लॉक"
            binding.tvTool6.text = "मेरा ID वॉल्ट\n(फिंगरप्रिंट लॉक)"
        } else {
            binding.tvAppSubtitle.text = "100% Offline • No Watermark"
            binding.tvHeroTitle.text = "Smart Clear Cam Scanner"
            binding.tvHeroDesc.text = "Auto-Crop + Magic Clear Filters to PDF/JPG"
            binding.tvTool1.text = "Photo & Image\nCompress (KB)"
            binding.tvTool2.text = "Exam Photo &\nName/Date"
            binding.tvTool3.text = "Image to PDF\nMaker"
            binding.tvTool4.text = "ID Card Front\n& Back Merger"
            binding.tvTool5.text = "PDF Compress,\nMerge & Lock"
            binding.tvTool6.text = "My ID Vault\n(Fingerprint)"
        }
    }

    private fun showToast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
