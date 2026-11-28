package com.docmitra.scanner

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.WindowManager
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
import androidx.core.content.ContextCompat
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
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private var isHindi = false
    private var currentSearchQuery = ""
    private var targetProjectFolderForScan: String? = null
    private var tempDirectCamFile: File? = null
    @Volatile private var isBusyProcessing = false

    private val bgExecutor = Executors.newSingleThreadExecutor()

    private lateinit var recentDocsContainer: LinearLayout
    private lateinit var tvAllDocsCount: TextView
    private lateinit var viewDocsTab: LinearLayout
    private lateinit var viewToolsTab: ScrollView
    private lateinit var ivTabDocs: ImageView
    private lateinit var tvTabDocs: TextView
    private lateinit var ivTabTools: ImageView
    private lateinit var tvTabTools: TextView

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            launchDirectSystemCamera()
        } else {
            Toast.makeText(this, "Camera permission denied. Please allow Camera in Settings.", Toast.LENGTH_LONG).show()
        }
    }

    private val directCameraLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        val file = tempDirectCamFile
        if (success && file != null && file.exists() && file.length() > 0) {
            val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
            openPreviewStudioWithUris(arrayListOf(uri.toString()))
        } else {
            targetProjectFolderForScan = null
        }
    }

    private val galleryMultiLauncher = registerForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (!uris.isNullOrEmpty()) {
            val copiedUris = ArrayList<String>()
            for ((idx, srcUri) in uris.take(20).withIndex()) {
                copyUriToSafeCacheFile(srcUri, "gal_${System.currentTimeMillis()}_$idx.jpg")?.let {
                    copiedUris.add(it.toString())
                }
            }
            if (copiedUris.isNotEmpty()) {
                openPreviewStudioWithUris(copiedUris)
            } else {
                targetProjectFolderForScan = null
            }
        } else {
            targetProjectFolderForScan = null
        }
    }

    private val scannerLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val scanResult = GmsDocumentScanningResult.fromActivityResultIntent(result.data)
            val pages = scanResult?.pages
            if (!pages.isNullOrEmpty()) {
                val safeUriList = ArrayList<String>()
                for ((idx, page) in pages.withIndex()) {
                    val persistedUri = copyUriToSafeCacheFile(
                        page.imageUri,
                        "scan_${System.currentTimeMillis()}_$idx.jpg"
                    )
                    if (persistedUri != null) {
                        safeUriList.add(persistedUri.toString())
                    }
                }
                if (safeUriList.isNotEmpty()) {
                    openPreviewStudioWithUris(safeUriList)
                } else {
                    targetProjectFolderForScan = null
                    Toast.makeText(this, "Could not read scanned page, please try Camera option.", Toast.LENGTH_SHORT).show()
                }
            } else {
                targetProjectFolderForScan = null
            }
        } else {
            targetProjectFolderForScan = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Security Shield: FLAG_SECURE
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )

        setContentView(R.layout.activity_main)

        recentDocsContainer = findViewById(R.id.recentDocsContainer)
        tvAllDocsCount = findViewById(R.id.tvAllDocsCount)
        viewDocsTab = findViewById(R.id.viewDocsTab)
        viewToolsTab = findViewById(R.id.viewToolsTab)
        ivTabDocs = findViewById(R.id.ivTabDocs)
        tvTabDocs = findViewById(R.id.tvTabDocs)
        ivTabTools = findViewById(R.id.ivTabTools)
        tvTabTools = findViewById(R.id.tvTabTools)

        bgExecutor.execute {
            shredTemporaryCacheFiles()
            migrateAllLegacyDocsToProjectFolders()
            runOnUiThread {
                if (!isFinishing && !isDestroyed) loadProjectFolders()
            }
        }

        findViewById<MaterialButton>(R.id.btnLangToggle).setOnClickListener {
            isHindi = !isHindi
            updateLanguage()
        }

        findViewById<MaterialButton>(R.id.btnAboutInfo).setOnClickListener {
            startActivity(Intent(this, AboutActivity::class.java))
        }

        findViewById<MaterialCardView>(R.id.cardCamScan).setOnClickListener {
            if (isBusyProcessing) return@setOnClickListener
            targetProjectFolderForScan = null
            showScanSourceChooser()
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
                loadProjectFolders()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        setupUtilityCards()
        switchTab(showDocs = true)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        switchTab(showDocs = true)
        loadProjectFolders()
    }

    override fun onResume() {
        super.onResume()
        loadProjectFolders()
    }

    override fun onDestroy() {
        super.onDestroy()
        bgExecutor.shutdownNow()
    }

    private fun showScanSourceChooser() {
        val options = arrayOf(
            "📄 Smart Auto-Crop Scanner",
            "📷 Direct Phone Camera",
            "🖼 Choose from Gallery"
        )
        AlertDialog.Builder(this)
            .setTitle("Scan Document")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> startSmartMlKitScanner()
                    1 -> checkPermissionAndLaunchCamera()
                    2 -> galleryMultiLauncher.launch("image/*")
                }
            }
            .show()
    }

    private fun checkPermissionAndLaunchCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            launchDirectSystemCamera()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun launchDirectSystemCamera() {
        try {
            val file = File(cacheDir, "cam_scan_${System.currentTimeMillis()}.jpg")
            tempDirectCamFile = file
            val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
            directCameraLauncher.launch(uri)
        } catch (e: Exception) {
            Toast.makeText(this, "Camera open error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startSmartMlKitScanner() {
        val options = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setPageLimit(20)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_BASE)
            .build()

        GmsDocumentScanning.getClient(options).getStartScanIntent(this)
            .addOnSuccessListener { intentSender ->
                scannerLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
            }
            .addOnFailureListener {
                checkPermissionAndLaunchCamera()
            }
    }

    private fun copyUriToSafeCacheFile(sourceUri: Uri, targetName: String): Uri? {
        return try {
            val outFile = File(cacheDir, targetName)
            contentResolver.openInputStream(sourceUri)?.use { input ->
                FileOutputStream(outFile).use { output ->
                    input.copyTo(output)
                }
            }
            if (outFile.exists() && outFile.length() > 0) {
                FileProvider.getUriForFile(this, "${packageName}.fileprovider", outFile)
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun openPreviewStudioWithUris(uriList: ArrayList<String>) {
        val studioIntent = Intent(this, ScanPreviewActivity::class.java).apply {
            putStringArrayListExtra("SCAN_URIS", uriList)
            putExtra("TARGET_PROJECT_DIR", targetProjectFolderForScan)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        targetProjectFolderForScan = null
        startActivity(studioIntent)
    }

    private fun getSecureProjectsRoot(): File {
        val dir = File(filesDir, "DocMitra/Projects")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun shredTemporaryCacheFiles() {
        try {
            val cutoff = System.currentTimeMillis() - (30 * 60 * 1000L)
            cacheDir.listFiles()?.forEach { file ->
                if (file.isFile && (file.extension.equals("pdf", true) || file.extension.equals("jpg", true))) {
                    if (file.lastModified() < cutoff) {
                        file.delete()
                    }
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun migrateAllLegacyDocsToProjectFolders() {
        val projectsRoot = getSecureProjectsRoot()
        val legacyDirs = listOf(
            File(filesDir, "DocMitra"),
            File(getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "DocMitra")
        )

        for (legacyDir in legacyDirs) {
            if (!legacyDir.exists() || !legacyDir.isDirectory) continue
            legacyDir.listFiles()?.forEach { file ->
                if (!file.isFile) return@forEach
                try {
                    val projName = file.nameWithoutExtension
                        .replace("[^a-zA-Z0-9\\u0900-\\u097F_\\- ]".toRegex(), "")
                        .trim()
                        .ifEmpty { "Migrated_Doc" }
                    val projDir = File(projectsRoot, projName)
                    if (!projDir.exists()) projDir.mkdirs()

                    if (file.extension.equals("pdf", ignoreCase = true)) {
                        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                        val renderer = PdfRenderer(pfd)
                        for (i in 0 until renderer.pageCount) {
                            val page = renderer.openPage(i)
                            val bmp = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
                            bmp.eraseColor(0xFFFFFFFF.toInt())
                            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            page.close()
                            val outJpg = File(projDir, String.format(Locale.US, "page_%04d.jpg", i + 1))
                            FileOutputStream(outJpg).use { out -> bmp.compress(Bitmap.CompressFormat.JPEG, 95, out) }
                            bmp.recycle()
                        }
                        renderer.close()
                        pfd.close()
                        file.delete()
                    } else if (file.extension.equals("jpg", ignoreCase = true)) {
                        val outJpg = File(projDir, "page_0001.jpg")
                        if (!outJpg.exists()) file.copyTo(outJpg)
                        file.delete()
                    }
                } catch (_: Exception) {
                }
            }
        }
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

    private fun loadProjectFolders() {
        recentDocsContainer.removeAllViews()
        val root = getSecureProjectsRoot()

        val allProjects = root.listFiles { file ->
            file.isDirectory && (file.listFiles { f -> f.isFile && f.extension.equals("jpg", ignoreCase = true) }?.isNotEmpty() == true)
        }?.sortedByDescending { it.lastModified() } ?: emptyList()

        val filteredProjects = if (currentSearchQuery.isEmpty()) {
            allProjects
        } else {
            allProjects.filter { it.name.contains(currentSearchQuery, ignoreCase = true) }
        }

        tvAllDocsCount.text = if (isHindi) "सभी फाइलें (${filteredProjects.size})" else "All docs (${filteredProjects.size})"

        if (filteredProjects.isEmpty()) {
            val emptyTv = TextView(this).apply {
                text = if (currentSearchQuery.isNotEmpty()) {
                    "No matching document found."
                } else if (isHindi) {
                    "नीचे ऑरेंज कैमरा बटन दबाएं → Camera या Gallery चुनें → मोहर/साइन लगाएं और '✓ Save HD' दबाएं।"
                } else {
                    "Tap the orange Camera button below to choose Camera or Gallery, add Mohar/Sign, and Save HD."
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
        for (projDir in filteredProjects) {
            val pages = projDir.listFiles { f ->
                f.isFile && f.extension.equals("jpg", ignoreCase = true)
            }?.sortedBy { it.name } ?: continue

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
                setPadding((10 * density).toInt(), (10 * density).toInt(), (6 * density).toInt(), (10 * density).toInt())
            }

            val thumbCard = MaterialCardView(this).apply {
                layoutParams = LinearLayout.LayoutParams((52 * density).toInt(), (64 * density).toInt())
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
            val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
            val thumbBmp = BitmapFactory.decodeFile(pages.first().absolutePath, opts)
            if (thumbBmp != null) {
                thumbIv.setImageBitmap(thumbBmp)
            } else {
                thumbIv.setImageResource(android.R.drawable.ic_menu_report_image)
            }
            thumbCard.addView(thumbIv)

            val infoCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = (10 * density).toInt()
                }
            }

            val titleTv = TextView(this).apply {
                text = projDir.name
                setTextColor(0xFF0F172A.toInt())
                textSize = 15f
                maxLines = 1
                setTypeface(null, Typeface.BOLD)
            }

            val dateStr = SimpleDateFormat("dd/MM/yy HH:mm", Locale.getDefault()).format(Date(projDir.lastModified()))
            val badgeText = "${pages.size}${if (pages.size == 1) "Page" else "Pages"}"

            val subTv = TextView(this).apply {
                text = "$dateStr • $badgeText • HD"
                setTextColor(0xFF14532D.toInt())
                textSize = 11f
                setPadding(0, (3 * density).toInt(), 0, 0)
            }

            infoCol.addView(titleTv)
            infoCol.addView(subTv)

            val shareBtn = MaterialButton(this).apply {
                text = "Share"
                textSize = 11f
                minWidth = 0
                setPadding((8 * density).toInt(), 0, (8 * density).toInt(), 0)
                setBackgroundColor(0xFFF0FDF4.toInt())
                setTextColor(0xFF14532D.toInt())
                setOnClickListener { shareProjectAsPdfAsync(projDir, pages) }
            }

            val moreBtn = MaterialButton(this).apply {
                text = "⋮"
                textSize = 18f
                minWidth = 0
                setPadding((8 * density).toInt(), 0, (8 * density).toInt(), 0)
                setBackgroundColor(0x00000000)
                setTextColor(0xFF475569.toInt())
                setOnClickListener { showProjectOptions(projDir, pages) }
            }

            row.addView(thumbCard)
            row.addView(infoCol)
            row.addView(shareBtn)
            row.addView(moreBtn)
            card.addView(row)
            card.setOnClickListener {
                val viewerIntent = Intent(this, DocumentViewerActivity::class.java).apply {
                    putExtra("PROJECT_DIR_PATH", projDir.absolutePath)
                }
                startActivity(viewerIntent)
            }

            recentDocsContainer.addView(card)
        }
    }

    private fun showProjectOptions(projDir: File, pages: List<File>) {
        val options = arrayOf(
            "Open Full-Screen (Zoom & Reorder)",
            "+ Add More Photos",
            "Rename (e.g. Pawan / Ram Singh)",
            "Share HD PDF",
            "Delete File"
        )

        AlertDialog.Builder(this)
            .setTitle(projDir.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        startActivity(Intent(this, DocumentViewerActivity::class.java).apply {
                            putExtra("PROJECT_DIR_PATH", projDir.absolutePath)
                        })
                    }
                    1 -> {
                        targetProjectFolderForScan = projDir.absolutePath
                        showScanSourceChooser()
                    }
                    2 -> promptRenameProject(projDir)
                    3 -> shareProjectAsPdfAsync(projDir, pages)
                    4 -> {
                        projDir.deleteRecursively()
                        Toast.makeText(this, "Deleted", Toast.LENGTH_SHORT).show()
                        loadProjectFolders()
                    }
                }
            }
            .show()
    }

    private fun promptRenameProject(projDir: File) {
        val input = EditText(this).apply {
            setText(projDir.name)
            hint = "Enter name (e.g. Pawan, Ram Singh)"
            setSelection(text.length)
        }
        AlertDialog.Builder(this)
            .setTitle("Rename File")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val newName = input.text.toString().trim()
                    .replace("[^a-zA-Z0-9\\u0900-\\u097F_\\- ]".toRegex(), "")
                    .replace("..", "")
                    .trim()
                    .take(50)
                if (newName.isNotEmpty()) {
                    val canonicalRoot = getSecureProjectsRoot().canonicalFile
                    val dest = File(canonicalRoot, newName).canonicalFile
                    if (dest.parentFile == canonicalRoot && !dest.exists() && projDir.renameTo(dest)) {
                        Toast.makeText(this, "Saved as '$newName'", Toast.LENGTH_SHORT).show()
                        loadProjectFolders()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun shareProjectAsPdfAsync(projDir: File, pages: List<File>) {
        if (isBusyProcessing) return
        isBusyProcessing = true
        Toast.makeText(this, "Building HD PDF...", Toast.LENGTH_SHORT).show()

        bgExecutor.execute {
            try {
                val cachePdf = File(cacheDir, "${projDir.name}.pdf")
                val pdfDoc = PdfDocument()
                pages.forEachIndexed { idx, imgFile ->
                    val bmp = BitmapFactory.decodeFile(imgFile.absolutePath)
                    if (bmp != null) {
                        val info = PdfDocument.PageInfo.Builder(bmp.width, bmp.height, idx + 1).create()
                        val page = pdfDoc.startPage(info)
                        page.canvas.drawBitmap(bmp, 0f, 0f, null)
                        pdfDoc.finishPage(page)
                        bmp.recycle()
                    }
                }
                FileOutputStream(cachePdf).use { out -> pdfDoc.writeTo(out) }
                pdfDoc.close()

                runOnUiThread {
                    isBusyProcessing = false
                    if (!isFinishing && !isDestroyed) {
                        val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", cachePdf)
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "application/pdf"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        startActivity(Intent.createChooser(intent, "Share HD PDF"))
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    isBusyProcessing = false
                    Toast.makeText(this, "Share error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun setupUtilityCards() {
        findViewById<MaterialCardView>(R.id.cardPhotoCompress).setOnClickListener {
            startActivity(Intent(this, PhotoStudioActivity::class.java).apply { putExtra("MODE", "COMPRESS") })
        }
        findViewById<MaterialCardView>(R.id.cardExamPhoto).setOnClickListener {
            startActivity(Intent(this, PhotoStudioActivity::class.java).apply { putExtra("MODE", "STAMP_SIGN") })
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
            if (isHindi) "मोहर व हस्ताक्षर\n(Stamp & Sign)" else "Signature &\nCustom Stamp"
        findViewById<TextView>(R.id.tvTool3).text =
            if (isHindi) "फोटो से PDF\nबनाएं" else "Image to PDF\nMaker"
        findViewById<TextView>(R.id.tvTool4).text =
            if (isHindi) "आधार/ID दोनों\nसाइड एक पेज पर" else "ID Card Front\n& Back Merger"
        findViewById<TextView>(R.id.tvTool5).text =
            if (isHindi) "PDF कंप्रेस,\nमर्ज व लॉक" else "PDF Compress,\nMerge & Lock"
        findViewById<TextView>(R.id.tvTool6).text =
            if (isHindi) "मेरा ID वॉल्ट\n(फिंगरप्रिंट)" else "My ID Vault\n(Fingerprint)"
        loadProjectFolders()
    }
}
