package com.docmitra.scanner

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
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
    private var currentSearchQuery = ""
    private var appendTargetFilePath: String? = null // Non-null when adding pages to an existing file

    private lateinit var recentDocsContainer: LinearLayout
    private lateinit var tvAllDocsCount: TextView
    private lateinit var viewDocsTab: LinearLayout
    private lateinit var viewToolsTab: ScrollView
    private lateinit var ivTabDocs: ImageView
    private lateinit var tvTabDocs: TextView
    private lateinit var ivTabTools: ImageView
    private lateinit var tvTabTools: TextView

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
                    putExtra("APPEND_TARGET_PATH", appendTargetFilePath)
                }
                appendTargetFilePath = null
                startActivity(studioIntent)
            } else {
                appendTargetFilePath = null
            }
        } else {
            appendTargetFilePath = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        recentDocsContainer = findViewById(R.id.recentDocsContainer)
        tvAllDocsCount = findViewById(R.id.tvAllDocsCount)
        viewDocsTab = findViewById(R.id.viewDocsTab)
        viewToolsTab = findViewById(R.id.viewToolsTab)
        ivTabDocs = findViewById(R.id.ivTabDocs)
        tvTabDocs = findViewById(R.id.tvTabDocs)
        ivTabTools = findViewById(R.id.ivTabTools)
        tvTabTools = findViewById(R.id.tvTabTools)

        findViewById<MaterialButton>(R.id.btnLangToggle).setOnClickListener {
            isHindi = !isHindi
            updateLanguage()
        }

        findViewById<MaterialButton>(R.id.btnAboutInfo).setOnClickListener {
            startActivity(Intent(this, AboutActivity::class.java))
        }

        findViewById<MaterialCardView>(R.id.cardCamScan).setOnClickListener {
            appendTargetFilePath = null
            startDocumentScanner()
        }

        findViewById<LinearLayout>(R.id.navTabDocs).setOnClickListener {
            switchTab(showDocs = true)
        }

        findViewById<LinearLayout>(R.id.navTabTools).setOnClickListener {
            switchTab(showDocs = false)
        }

        findViewById<EditText>(R.id.etSearchDocs).addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                currentSearchQuery = s?.toString()?.trim() ?: ""
                loadSavedDocuments()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        setupUtilityCards()
        switchTab(showDocs = true)
    }

    override fun onResume() {
        super.onResume()
        loadSavedDocuments()
    }

    private fun switchTab(showDocs: Boolean) {
        if (showDocs) {
            viewDocsTab.visibility = View.VISIBLE
            viewToolsTab.visibility = View.GONE
            ivTabDocs.setColorFilter(0xFF14532D.toInt())
            tvTabDocs.setTextColor(0xFF14532D.toInt())
            ivTabTools.setColorFilter(0xFF64748B.toInt())
            tvTabTools.setTextColor(0xFF64748B.toInt())
        } else {
            viewDocsTab.visibility = View.GONE
            viewToolsTab.visibility = View.VISIBLE
            ivTabDocs.setColorFilter(0xFF64748B.toInt())
            tvTabDocs.setTextColor(0xFF64748B.toInt())
            ivTabTools.setColorFilter(0xFF14532D.toInt())
            tvTabTools.setTextColor(0xFF14532D.toInt())
        }
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
                appendTargetFilePath = null
                Toast.makeText(this, "Scanner initializing, please try again: ${e.message}", Toast.LENGTH_LONG).show()
            }
    }

    private fun loadSavedDocuments() {
        recentDocsContainer.removeAllViews()
        val dir = File(getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "DocMitra")
        if (!dir.exists()) dir.mkdirs()

        val allFiles = dir.listFiles { file ->
            file.isFile && (file.extension.equals("pdf", ignoreCase = true) || file.extension.equals("jpg", ignoreCase = true))
        }?.sortedByDescending { it.lastModified() } ?: emptyList()

        val filteredFiles = if (currentSearchQuery.isEmpty()) {
            allFiles
        } else {
            allFiles.filter { it.name.contains(currentSearchQuery, ignoreCase = true) }
        }

        tvAllDocsCount.text = if (isHindi) "सभी फाइलें (${filteredFiles.size})" else "All docs (${filteredFiles.size})"

        if (filteredFiles.isEmpty()) {
            val emptyTv = TextView(this).apply {
                text = if (currentSearchQuery.isNotEmpty()) {
                    "No matching document found."
                } else if (isHindi) {
                    "अभी कोई डॉक्यूमेंट सेव नहीं है। नीचे कैमरा बटन से फोटो खींचें, उसे व्यक्ति के नाम से सेव करें और बाद में उसी में और फोटो जोड़ते जाएं।"
                } else {
                    "No saved documents yet. Tap the center Camera button below to scan, name it (e.g. Pawan), and keep adding more photos inside it anytime."
                }
                setTextColor(0xFF64748B.toInt())
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding(24, 60, 24, 24)
            }
            recentDocsContainer.addView(emptyTv)
            return
        }

        val density = resources.displayMetrics.density
        for (file in filteredFiles) {
            val isPdf = file.extension.equals("pdf", ignoreCase = true)
            val card = MaterialCardView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = (10 * density).toInt() }
                radius = 12f * density
                cardElevation = 1f * density
                setCardBackgroundColor(0xFFFFFFFF.toInt())
                strokeColor = 0xFFE2E8F0.toInt()
                strokeWidth = (1f * density).toInt()
            }

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding((12 * density).toInt(), (10 * density).toInt(), (10 * density).toInt(), (10 * density).toInt())
            }

            val thumbCard = MaterialCardView(this).apply {
                layoutParams = LinearLayout.LayoutParams((54 * density).toInt(), (66 * density).toInt())
                radius = 8f * density
                cardElevation = 0f
                setCardBackgroundColor(0xFFF1F5F9.toInt())
                strokeColor = 0xFFCBD5E1.toInt()
                strokeWidth = (1f * density).toInt()
            }

            val thumbIv = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.MATCH_PARENT
                )
                scaleType = ImageView.ScaleType.CENTER_CROP
            }
            val thumbBmp = generateFileThumbnail(file, isPdf)
            if (thumbBmp != null) {
                thumbIv.setImageBitmap(thumbBmp)
            } else {
                thumbIv.setImageResource(android.R.drawable.ic_menu_report_image)
            }
            thumbCard.addView(thumbIv)

            val infoCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = (12 * density).toInt()
                }
            }

            val titleTv = TextView(this).apply {
                text = file.nameWithoutExtension
                setTextColor(0xFF0F172A.toInt())
                textSize = 15f
                maxLines = 1
                setTypeface(null, android.graphics.Typeface.BOLD)
            }

            val dateStr = SimpleDateFormat("dd/MM/yy HH:mm", Locale.getDefault()).format(Date(file.lastModified()))
            val sizeKb = (file.length() / 1024).coerceAtLeast(1)
            val pageCount = if (isPdf) getPdfPageCount(file) else 1
            val badgeText = "$pageCount ${if (pageCount == 1) "Page" else "Pages"}"

            val subTv = TextView(this).apply {
                text = "$dateStr  |  $badgeText  |  ${sizeKb} KB"
                setTextColor(0xFF14532D.toInt())
                textSize = 12f
                setPadding(0, (4 * density).toInt(), 0, 0)
            }

            infoCol.addView(titleTv)
            infoCol.addView(subTv)

            val moreBtn = MaterialButton(this).apply {
                text = "⋮"
                textSize = 18f
                minWidth = 0
                setBackgroundColor(0x00000000)
                setTextColor(0xFF475569.toInt())
                setOnClickListener { showDocumentOptions(file, isPdf) }
            }

            row.addView(thumbCard)
            row.addView(infoCol)
            row.addView(moreBtn)
            card.addView(row)
            card.setOnClickListener { openDocument(file, isPdf) }

            recentDocsContainer.addView(card)
        }
    }

    private fun generateFileThumbnail(file: File, isPdf: Boolean): Bitmap? {
        return try {
            if (!isPdf) {
                val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
                BitmapFactory.decodeFile(file.absolutePath, opts)
            } else {
                val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                val renderer = PdfRenderer(pfd)
                var bmp: Bitmap? = null
                if (renderer.pageCount > 0) {
                    val page = renderer.openPage(0)
                    bmp = Bitmap.createBitmap(160, 200, Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(0xFFFFFFFF.toInt())
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    page.close()
                }
                renderer.close()
                pfd.close()
                bmp
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun getPdfPageCount(file: File): Int {
        return try {
            val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = PdfRenderer(pfd)
            val count = renderer.pageCount
            renderer.close()
            pfd.close()
            count
        } catch (e: Exception) {
            1
        }
    }

    private fun openDocument(file: File, isPdf: Boolean) {
        if (!isPdf) {
            // Convert single JPG into a multi-page PDF seamlessly if user wants to view or add more photos
            val bmp = BitmapFactory.decodeFile(file.absolutePath) ?: return
            val iv = ImageView(this).apply {
                setImageBitmap(bmp)
                adjustViewBounds = true
                setPadding(16, 16, 16, 16)
            }
            AlertDialog.Builder(this)
                .setTitle(file.nameWithoutExtension)
                .setView(iv)
                .setPositiveButton("+ Add More Photos") { _, _ ->
                    val pdfTarget = convertJpgToPdfFile(file)
                    if (pdfTarget != null) {
                        appendTargetFilePath = pdfTarget.absolutePath
                        startDocumentScanner()
                    }
                }
                .setNeutralButton("Share") { _, _ -> shareDocumentFile(file, false) }
                .setNegativeButton("Close", null)
                .show()
            return
        }

        // Render all pages inside an in-app multi-page viewer with "+ Add More Photos" button
        try {
            val density = resources.displayMetrics.density
            val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = PdfRenderer(pfd)
            val totalPages = renderer.pageCount

            val container = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding((12 * density).toInt(), (8 * density).toInt(), (12 * density).toInt(), (12 * density).toInt())
            }

            for (i in 0 until totalPages) {
                val page = renderer.openPage(i)
                val targetW = 720
                val targetH = ((page.height.toFloat() / page.width.toFloat()) * targetW).toInt().coerceAtLeast(100)
                val bmp = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(0xFFFFFFFF.toInt())
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                page.close()

                val pageLabel = TextView(this).apply {
                    text = "Page ${i + 1} of $totalPages"
                    textSize = 12f
                    setTextColor(0xFF14532D.toInt())
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setPadding(0, (8 * density).toInt(), 0, (4 * density).toInt())
                }

                val iv = ImageView(this).apply {
                    setImageBitmap(bmp)
                    adjustViewBounds = true
                    setBackgroundColor(0xFFE2E8F0.toInt())
                    setPadding(2, 2, 2, 2)
                }

                container.addView(pageLabel)
                container.addView(iv)
            }

            renderer.close()
            pfd.close()

            val scrollView = ScrollView(this).apply { addView(container) }

            AlertDialog.Builder(this)
                .setTitle("${file.nameWithoutExtension} ($totalPages Pages)")
                .setView(scrollView)
                .setPositiveButton("+ Add More Photos") { _, _ ->
                    appendTargetFilePath = file.absolutePath
                    startDocumentScanner()
                }
                .setNeutralButton("Share") { _, _ -> shareDocumentFile(file, true) }
                .setNegativeButton("Close", null)
                .show()
        } catch (e: Exception) {
            Toast.makeText(this, "Could not open file: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun convertJpgToPdfFile(jpgFile: File): File? {
        return try {
            val bmp = BitmapFactory.decodeFile(jpgFile.absolutePath) ?: return null
            val pdfFile = File(jpgFile.parentFile, "${jpgFile.nameWithoutExtension}.pdf")
            val pdfDoc = PdfDocument()
            val pageInfo = PdfDocument.PageInfo.Builder(bmp.width, bmp.height, 1).create()
            val page = pdfDoc.startPage(pageInfo)
            page.canvas.drawBitmap(bmp, 0f, 0f, null)
            pdfDoc.finishPage(page)
            FileOutputStream(pdfFile).use { out -> pdfDoc.writeTo(out) }
            pdfDoc.close()
            jpgFile.delete()
            pdfFile
        } catch (e: Exception) {
            null
        }
    }

    private fun showDocumentOptions(file: File, isPdf: Boolean) {
        val options = if (isPdf) {
            arrayOf("+ Add More Photos", "Open & View All Pages", "Rename (e.g. Pawan / Ram Singh)", "Share File", "Delete")
        } else {
            arrayOf("+ Add More Photos", "View Photo", "Rename (e.g. Pawan / Ram Singh)", "Save to Gallery", "Share Photo", "Delete")
        }

        AlertDialog.Builder(this)
            .setTitle(file.nameWithoutExtension)
            .setItems(options) { _, which ->
                when (options[which]) {
                    "+ Add More Photos" -> {
                        val targetPdf = if (isPdf) file else convertJpgToPdfFile(file)
                        if (targetPdf != null) {
                            appendTargetFilePath = targetPdf.absolutePath
                            startDocumentScanner()
                        }
                    }
                    "Open & View All Pages", "View Photo" -> openDocument(file, isPdf)
                    "Rename (e.g. Pawan / Ram Singh)" -> promptRenameFile(file)
                    "Save to Gallery" -> saveImageFileToGallery(file)
                    "Share File", "Share Photo" -> shareDocumentFile(file, isPdf)
                    "Delete" -> {
                        file.delete()
                        Toast.makeText(this, "Deleted", Toast.LENGTH_SHORT).show()
                        loadSavedDocuments()
                    }
                }
            }
            .show()
    }

    private fun promptRenameFile(file: File) {
        val input = EditText(this).apply {
            setText(file.nameWithoutExtension)
            hint = "Enter name (e.g. Pawan, Ram Singh)"
            setSelection(text.length)
        }
        AlertDialog.Builder(this)
            .setTitle("Rename File")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val newName = input.text.toString().trim()
                    .replace("[^a-zA-Z0-9\\u0900-\\u097F_\\- ]".toRegex(), "").trim()
                if (newName.isNotEmpty()) {
                    val dest = File(file.parentFile, "$newName.${file.extension}")
                    if (file.renameTo(dest)) {
                        Toast.makeText(this, "Saved as '$newName'", Toast.LENGTH_SHORT).show()
                        loadSavedDocuments()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
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
            startActivity(Intent(this, PhotoStudioActivity::class.java).apply { putExtra("MODE", "COMPRESS") })
        }
        findViewById<MaterialCardView>(R.id.cardExamPhoto).setOnClickListener {
            startActivity(Intent(this, PhotoStudioActivity::class.java).apply { putExtra("MODE", "EXAM") })
        }
        findViewById<MaterialCardView>(R.id.cardImgToPdf).setOnClickListener {
            startActivity(Intent(this, PdfIdStudioActivity::class.java).apply { putExtra("MODE", "IMG_TO_PDF") })
        }
        findViewById<MaterialCardView>(R.id.cardIdMerger).setOnClickListener {
            startActivity(Intent(this, PdfIdStudioActivity::class.java).apply { putExtra("MODE", "ID_MERGE") })
        }
        findViewById<MaterialCardView>(R.id.cardPdfTools).setOnClickListener {
            startActivity(Intent(this, PdfIdStudioActivity::class.java).apply { putExtra("MODE", "PDF_TOOLS") })
        }
        findViewById<MaterialCardView>(R.id.cardIdVault).setOnClickListener {
            startActivity(Intent(this, VaultActivity::class.java))
        }
    }

    private fun updateLanguage() {
        tvTabDocs.text = if (isHindi) "फाइलें" else "Docs"
        tvTabTools.text = if (isHindi) "टूल्स" else "Tools"
        findViewById<TextView>(R.id.tvToolsHeading).text =
            if (isHindi) "सभी डॉक्यूमेंट और PDF टूल्स" else "ALL DOCUMENT & PDF TOOLS"
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
