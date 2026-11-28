package com.docmitra.scanner

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
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
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.content.FileProvider
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

class DocumentViewerActivity : AppCompatActivity() {

    private lateinit var projectDir: File
    private lateinit var pagesContainer: LinearLayout
    private lateinit var tvHeaderTitle: TextView
    private lateinit var btnPageSize: MaterialButton
    private lateinit var btnPdfQuality: MaterialButton
    private lateinit var btnViewMode: MaterialButton

    private var pageSizeMode = 0 // 0 = Auto Fit, 1 = A4 (1240x1754), 2 = Legal (1240x2040)
    private var pdfQualityMode = 0 // 0 = High HD (92%), 1 = Medium (70%), 2 = Small KB (45%)
    private var isGridView = false
    private var activeWatermarkText = ""

    @Volatile private var isOperationRunning = false
    private val workerExecutor = Executors.newSingleThreadExecutor()

    private val scannerLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val scanResult = GmsDocumentScanningResult.fromActivityResultIntent(result.data)
            val pages = scanResult?.pages
            if (!pages.isNullOrEmpty()) {
                val safeUriList = ArrayList<String>()
                for ((idx, page) in pages.withIndex()) {
                    try {
                        val outFile = File(cacheDir, "add_page_${System.currentTimeMillis()}_$idx.jpg")
                        contentResolver.openInputStream(page.imageUri)?.use { input ->
                            FileOutputStream(outFile).use { output -> input.copyTo(output) }
                        }
                        if (outFile.exists() && outFile.length() > 0) {
                            val persistedUri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", outFile)
                            safeUriList.add(persistedUri.toString())
                        }
                    } catch (_: Exception) {
                    }
                }
                if (safeUriList.isNotEmpty()) {
                    val studioIntent = Intent(this, ScanPreviewActivity::class.java).apply {
                        putStringArrayListExtra("SCAN_URIS", safeUriList)
                        putExtra("TARGET_PROJECT_DIR", projectDir.absolutePath)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(studioIntent)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )

        val secureRoot = File(filesDir, "DocMitra/Projects").canonicalFile
        val rawPath = intent.getStringExtra("PROJECT_DIR_PATH")
        if (rawPath.isNullOrBlank()) {
            finish()
            return
        }

        try {
            val candidate = File(rawPath).canonicalFile
            if (candidate.parentFile != secureRoot || !candidate.isDirectory) {
                finish()
                return
            }
            projectDir = candidate
        } catch (_: Exception) {
            finish()
            return
        }

        val density = resources.displayMetrics.density
        val dp = { v: Int -> (v * density).toInt() }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFF1F5F9.toInt())
        }

        // Top Bar
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xFF14532D.toInt())
            setPadding(dp(8), dp(8), dp(10), dp(8))
        }

        val btnBack = MaterialButton(this).apply {
            text = "←"
            textSize = 18f
            minWidth = dp(40)
            minimumWidth = dp(40)
            insetTop = 0
            insetBottom = 0
            setPadding(0, 0, 0, 0)
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0x00000000)
            setOnClickListener { finish() }
        }

        tvHeaderTitle = TextView(this).apply {
            text = projectDir.name
            textSize = 16f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(0xFFFFFFFF.toInt())
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(6)
            }
        }

        val btnAddPage = makeCleanButton("+ Add Page", 0xFFEA580C.toInt(), 0xFFFFFFFF.toInt(), 12f, dp).apply {
            layoutParams = LinearLayout.LayoutParams(dp(92), dp(36))
            setOnClickListener { startAddPageScanner() }
        }

        topBar.addView(btnBack)
        topBar.addView(tvHeaderTitle)
        topBar.addView(btnAddPage)
        root.addView(topBar)

        // OKEN-Style PDF Settings Strip (Page Size | Quality | Watermark | Grid/List)
        val settingsBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xFFFFFFFF.toInt())
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }

        btnPageSize = makeCleanButton("Size: Auto", 0xFFF1F5F9.toInt(), 0xFF0F172A.toInt(), 11f, dp).apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(34), 1f).apply { marginEnd = dp(4) }
            setOnClickListener {
                pageSizeMode = (pageSizeMode + 1) % 3
                text = when (pageSizeMode) {
                    1 -> "Size: A4"
                    2 -> "Size: Legal"
                    else -> "Size: Auto"
                }
            }
        }

        btnPdfQuality = makeCleanButton("Qual: HD", 0xFFF1F5F9.toInt(), 0xFF14532D.toInt(), 11f, dp).apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(34), 1f).apply { marginEnd = dp(4) }
            setOnClickListener {
                pdfQualityMode = (pdfQualityMode + 1) % 3
                text = when (pdfQualityMode) {
                    1 -> "Qual: Med"
                    2 -> "Qual: Low"
                    else -> "Qual: HD"
                }
            }
        }

        val btnWatermark = makeCleanButton("© Watermark", 0xFFF1F5F9.toInt(), 0xFF1E3A8A.toInt(), 11f, dp).apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(34), 1.15f).apply { marginEnd = dp(4) }
            setOnClickListener { promptWatermarkSetup() }
        }

        btnViewMode = makeCleanButton("⊞ Grid", 0xFFE2E8F0.toInt(), 0xFF0F172A.toInt(), 11f, dp).apply {
            layoutParams = LinearLayout.LayoutParams(dp(64), dp(34))
            setOnClickListener {
                isGridView = !isGridView
                text = if (isGridView) "☰ List" else "⊞ Grid"
                renderProjectPages()
            }
        }

        settingsBar.addView(btnPageSize)
        settingsBar.addView(btnPdfQuality)
        settingsBar.addView(btnWatermark)
        settingsBar.addView(btnViewMode)
        root.addView(settingsBar)

        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        pagesContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(24))
        }
        scrollView.addView(pagesContainer)
        root.addView(scrollView)

        // Bottom Export Bar
        val bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xFFFFFFFF.toInt())
            setPadding(dp(10), dp(8), dp(10), dp(10))
        }

        val btnSharePdf = makeCleanButton("Share PDF", 0xFF14532D.toInt(), 0xFFFFFFFF.toInt(), 13f, dp).apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(46), 1.2f).apply {
                marginEnd = dp(8)
            }
            setOnClickListener { exportAndSharePdfAsync() }
        }

        val btnShareJpg = makeCleanButton("Share JPGs", 0xFFEA580C.toInt(), 0xFFFFFFFF.toInt(), 12.5f, dp).apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(46), 1f)
            setOnClickListener { shareAllAsImages() }
        }

        bottomBar.addView(btnSharePdf)
        bottomBar.addView(btnShareJpg)
        root.addView(bottomBar)

        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        renderProjectPages()
    }

    override fun onDestroy() {
        super.onDestroy()
        workerExecutor.shutdownNow()
    }

    private fun makeCleanButton(
        label: String,
        bgColor: Int,
        txtColor: Int,
        txtSize: Float,
        dp: (Int) -> Int
    ): MaterialButton {
        return MaterialButton(this).apply {
            text = label
            textSize = txtSize
            isAllCaps = false
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            minWidth = 0
            minimumWidth = 0
            insetTop = 0
            insetBottom = 0
            setPadding(dp(6), 0, dp(6), 0)
            setTypeface(null, Typeface.BOLD)
            setTextColor(txtColor)
            setBackgroundColor(bgColor)
        }
    }

    private fun promptWatermarkSetup() {
        val input = EditText(this).apply {
            setText(activeWatermarkText)
            hint = "e.g. CONFIDENTIAL / ADV. VICKY TIWARI"
            isSingleLine = true
        }
        AlertDialog.Builder(this)
            .setTitle("PDF Watermark (All Pages)")
            .setView(input)
            .setPositiveButton("Apply") { _, _ ->
                activeWatermarkText = input.text.toString().trim()
                if (activeWatermarkText.isNotEmpty()) {
                    Toast.makeText(this, "Watermark '$activeWatermarkText' active for PDF!", Toast.LENGTH_SHORT).show()
                }
            }
            .setNeutralButton("Remove") { _, _ ->
                activeWatermarkText = ""
                Toast.makeText(this, "Watermark removed", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun getSortedPageFiles(): List<File> {
        return projectDir.listFiles { f ->
            f.isFile && f.extension.equals("jpg", ignoreCase = true)
        }?.sortedBy { it.name } ?: emptyList()
    }

    private fun renderProjectPages() {
        pagesContainer.removeAllViews()
        val pages = getSortedPageFiles()
        tvHeaderTitle.text = "${projectDir.name} (${pages.size}p)"

        if (pages.isEmpty()) {
            finish()
            return
        }

        val density = resources.displayMetrics.density
        val dp = { v: Int -> (v * density).toInt() }

        if (isGridView) {
            var i = 0
            while (i < pages.size) {
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = dp(10) }
                }

                for (colIdx in 0..1) {
                    val pageIdx = i + colIdx
                    if (pageIdx < pages.size) {
                        val file = pages[pageIdx]
                        val cellCard = MaterialCardView(this).apply {
                            layoutParams = LinearLayout.LayoutParams(0, dp(220), 1f).apply {
                                if (colIdx == 0) marginEnd = dp(5) else marginStart = dp(5)
                            }
                            radius = 10f * density
                            cardElevation = 2f * density
                            setCardBackgroundColor(0xFFFFFFFF.toInt())
                        }
                        val col = LinearLayout(this).apply {
                            orientation = LinearLayout.VERTICAL
                            setPadding(dp(6), dp(6), dp(6), dp(6))
                        }
                        val iv = ImageView(this).apply {
                            layoutParams = LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                0,
                                1f
                            )
                            scaleType = ImageView.ScaleType.FIT_CENTER
                            val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
                            setImageBitmap(BitmapFactory.decodeFile(file.absolutePath, opts))
                        }
                        val badge = TextView(this).apply {
                            text = "Page ${pageIdx + 1}"
                            textSize = 11.5f
                            gravity = Gravity.CENTER
                            setTypeface(null, Typeface.BOLD)
                            setTextColor(0xFF14532D.toInt())
                            setPadding(0, dp(4), 0, 0)
                        }
                        col.addView(iv)
                        col.addView(badge)
                        cellCard.addView(col)
                        cellCard.setOnClickListener { openFullScreenPinchZoom(file, pageIdx + 1) }
                        row.addView(cellCard)
                    } else {
                        val emptySpace = LinearLayout(this).apply {
                            layoutParams = LinearLayout.LayoutParams(0, 1, 1f).apply { marginStart = dp(5) }
                        }
                        row.addView(emptySpace)
                    }
                }
                pagesContainer.addView(row)
                i += 2
            }
            return
        }

        pages.forEachIndexed { index, pageFile ->
            val card = MaterialCardView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(14) }
                radius = 12f * density
                cardElevation = 2f * density
                setCardBackgroundColor(0xFFFFFFFF.toInt())
            }

            val cardCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(10), dp(8), dp(10), dp(10))
            }

            val ctrlRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 0, 0, dp(6))
            }

            val tvNum = TextView(this).apply {
                text = "Page ${index + 1}"
                textSize = 13f
                setTextColor(0xFF14532D.toInt())
                setTypeface(null, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            fun miniBtn(label: String, bg: Int, fg: Int, onClick: () -> Unit): MaterialButton {
                return makeCleanButton(label, bg, fg, 11f, dp).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        dp(34)
                    ).apply { marginStart = dp(4) }
                    setOnClickListener {
                        if (!isOperationRunning) onClick()
                    }
                }
            }

            ctrlRow.addView(tvNum)

            if (index > 0) {
                ctrlRow.addView(miniBtn("↑", 0xFFE2E8F0.toInt(), 0xFF0F172A.toInt()) {
                    swapPages(pages[index], pages[index - 1])
                })
            }

            if (index < pages.size - 1) {
                ctrlRow.addView(miniBtn("↓", 0xFFE2E8F0.toInt(), 0xFF0F172A.toInt()) {
                    swapPages(pages[index], pages[index + 1])
                })
            }

            ctrlRow.addView(miniBtn("↻", 0xFFE2E8F0.toInt(), 0xFF14532D.toInt()) {
                rotatePageFileAsync(pageFile)
            })

            ctrlRow.addView(miniBtn("🔏 Mohar", 0xFFDCFCE7.toInt(), 0xFF14532D.toInt()) {
                openPageInStampStudio(pageFile)
            })

            if (pages.size > 1) {
                ctrlRow.addView(miniBtn("🗑", 0xFFFEE2E2.toInt(), 0xFFDC2626.toInt()) {
                    pageFile.delete()
                    normalizePageFileNames()
                    renderProjectPages()
                })
            }

            val ivPage = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                adjustViewBounds = true
                setBackgroundColor(0xFFF8FAFC.toInt())
                setOnClickListener { openFullScreenPinchZoom(pageFile, index + 1) }
            }

            val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
            val previewBmp = BitmapFactory.decodeFile(pageFile.absolutePath, opts)
            ivPage.setImageBitmap(previewBmp)

            val hintTv = TextView(this).apply {
                text = "Tap page to Pinch-Zoom"
                textSize = 10.5f
                setTextColor(0xFF64748B.toInt())
                gravity = Gravity.END
                setPadding(0, dp(4), 0, 0)
            }

            cardCol.addView(ctrlRow)
            cardCol.addView(ivPage)
            cardCol.addView(hintTv)
            card.addView(cardCol)
            pagesContainer.addView(card)
        }
    }

    private fun openFullScreenPinchZoom(pageFile: File, pageNum: Int) {
        val fullBmp = BitmapFactory.decodeFile(pageFile.absolutePath) ?: return
        val zoomView = PinchZoomImageView(this).apply {
            setImageBitmap(fullBmp)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (480 * resources.displayMetrics.density).toInt()
            )
        }

        AlertDialog.Builder(this)
            .setTitle("${projectDir.name} - Page $pageNum (Pinch to Zoom)")
            .setView(zoomView)
            .setPositiveButton("Save to Gallery") { _, _ -> saveSinglePageToGallery(pageFile) }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun openPageInStampStudio(pageFile: File) {
        try {
            val tempCopy = File(cacheDir, "stamp_edit_${System.currentTimeMillis()}.jpg")
            pageFile.copyTo(tempCopy, overwrite = true)
            val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", tempCopy)
            pageFile.delete()
            normalizePageFileNames()
            val intent = Intent(this, ScanPreviewActivity::class.java).apply {
                putStringArrayListExtra("SCAN_URIS", arrayListOf(uri.toString()))
                putExtra("TARGET_PROJECT_DIR", projectDir.absolutePath)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Error opening editor: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun swapPages(fileA: File, fileB: File) {
        val temp = File(projectDir, "temp_swap.jpg")
        if (fileA.renameTo(temp)) {
            fileB.renameTo(fileA)
            temp.renameTo(fileB)
            renderProjectPages()
        }
    }

    private fun normalizePageFileNames() {
        val current = getSortedPageFiles()
        current.forEachIndexed { idx, file ->
            val target = File(projectDir, String.format(Locale.US, "page_%04d.jpg", idx + 1))
            if (file.name != target.name) {
                file.renameTo(target)
            }
        }
    }

    private fun rotatePageFileAsync(file: File) {
        if (isOperationRunning) return
        isOperationRunning = true
        workerExecutor.execute {
            try {
                val bmp = BitmapFactory.decodeFile(file.absolutePath)
                if (bmp != null) {
                    val matrix = Matrix().apply { postRotate(90f) }
                    val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
                    FileOutputStream(file).use { out ->
                        rotated.compress(Bitmap.CompressFormat.JPEG, 95, out)
                    }
                    bmp.recycle()
                    rotated.recycle()
                    projectDir.setLastModified(System.currentTimeMillis())
                }
                runOnUiThread {
                    isOperationRunning = false
                    if (!isFinishing && !isDestroyed) renderProjectPages()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    isOperationRunning = false
                    Toast.makeText(this, "Rotate failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun startAddPageScanner() {
        val options = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setPageLimit(25)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build()

        GmsDocumentScanning.getClient(options).getStartScanIntent(this)
            .addOnSuccessListener { intentSender ->
                scannerLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
            }
    }

    private fun exportAndSharePdfAsync() {
        if (isOperationRunning) return
        val pages = getSortedPageFiles()
        if (pages.isEmpty()) return

        isOperationRunning = true
        Toast.makeText(this, "Building PDF...", Toast.LENGTH_SHORT).show()

        workerExecutor.execute {
            try {
                val jpegQuality = when (pdfQualityMode) {
                    1 -> 68
                    2 -> 42
                    else -> 92
                }
                val scaleLimit = when (pdfQualityMode) {
                    1 -> 1240
                    2 -> 900
                    else -> 1800
                }

                val cachePdf = File(cacheDir, "${projectDir.name}.pdf")
                val pdfDoc = PdfDocument()

                pages.forEachIndexed { idx, imgFile ->
                    val raw = BitmapFactory.decodeFile(imgFile.absolutePath) ?: return@forEachIndexed
                    val maxDim = max(raw.width, raw.height).coerceAtLeast(1)
                    val scaleDown = min(1f, scaleLimit.toFloat() / maxDim.toFloat())
                    val w = (raw.width * scaleDown).toInt().coerceAtLeast(300)
                    val h = (raw.height * scaleDown).toInt().coerceAtLeast(400)
                    val resized = if (scaleDown < 0.99f) Bitmap.createScaledBitmap(raw, w, h, true) else raw

                    val baos = ByteArrayOutputStream()
                    resized.compress(Bitmap.CompressFormat.JPEG, jpegQuality, baos)
                    val compressedBmp = BitmapFactory.decodeByteArray(baos.toByteArray(), 0, baos.size()) ?: resized

                    val pageW: Int
                    val pageH: Int
                    when (pageSizeMode) {
                        1 -> { pageW = 1240; pageH = 1754 } // A4
                        2 -> { pageW = 1240; pageH = 2040 } // Legal
                        else -> { pageW = compressedBmp.width; pageH = compressedBmp.height }
                    }

                    val info = PdfDocument.PageInfo.Builder(pageW, pageH, idx + 1).create()
                    val page = pdfDoc.startPage(info)
                    val canvas = page.canvas
                    canvas.drawColor(Color.WHITE)

                    if (pageSizeMode == 0) {
                        canvas.drawBitmap(compressedBmp, 0f, 0f, null)
                    } else {
                        val fitScale = min(pageW.toFloat() / compressedBmp.width, pageH.toFloat() / compressedBmp.height)
                        val dw = compressedBmp.width * fitScale
                        val dh = compressedBmp.height * fitScale
                        val left = (pageW - dw) / 2f
                        val top = (pageH - dh) / 2f
                        canvas.drawBitmap(compressedBmp, null, RectF(left, top, left + dw, top + dh), null)
                    }

                    if (activeWatermarkText.isNotEmpty()) {
                        val wmPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            color = Color.argb(48, 30, 41, 59)
                            textSize = (pageW / 14f).coerceIn(36f, 84f)
                            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                            textAlign = Paint.Align.CENTER
                        }
                        canvas.save()
                        canvas.rotate(-35f, pageW / 2f, pageH / 2f)
                        canvas.drawText(activeWatermarkText, pageW / 2f, pageH / 2f, wmPaint)
                        canvas.restore()
                    }

                    pdfDoc.finishPage(page)
                    if (resized !== raw) resized.recycle()
                    if (compressedBmp !== resized) compressedBmp.recycle()
                    raw.recycle()
                }

                FileOutputStream(cachePdf).use { out -> pdfDoc.writeTo(out) }
                pdfDoc.close()

                val finalKb = (cachePdf.length() / 1024L).toInt().coerceAtLeast(1)

                runOnUiThread {
                    isOperationRunning = false
                    if (!isFinishing && !isDestroyed) {
                        Toast.makeText(this, "PDF Ready (${finalKb} KB)", Toast.LENGTH_SHORT).show()
                        val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", cachePdf)
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "application/pdf"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        startActivity(Intent.createChooser(intent, "Share PDF (${finalKb} KB)"))
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    isOperationRunning = false
                    Toast.makeText(this, "PDF export error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun shareAllAsImages() {
        try {
            val pages = getSortedPageFiles()
            if (pages.isEmpty()) return

            val uris = ArrayList<Uri>()
            for (f in pages) {
                uris.add(FileProvider.getUriForFile(this, "${packageName}.fileprovider", f))
            }
            val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "image/jpeg"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Share Images"))
        } catch (e: Exception) {
            Toast.makeText(this, "Share error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveSinglePageToGallery(pageFile: File) {
        try {
            val bmp = BitmapFactory.decodeFile(pageFile.absolutePath) ?: return
            val imgName = "${projectDir.name}_${pageFile.name}"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, imgName)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/DocMitra")
                }
                val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                uri?.let { contentResolver.openOutputStream(it)?.use { out -> bmp.compress(Bitmap.CompressFormat.JPEG, 96, out) } }
            } else {
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "DocMitra")
                if (!dir.exists()) dir.mkdirs()
                FileOutputStream(File(dir, imgName)).use { out -> bmp.compress(Bitmap.CompressFormat.JPEG, 96, out) }
            }
            bmp.recycle()
            Toast.makeText(this, "Saved to Gallery!", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Save error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private class PinchZoomImageView(context: Context) : AppCompatImageView(context) {
        private val matrixZoom = Matrix()
        private var currentScale = 1f
        private var lastX = 0f
        private var lastY = 0f
        private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val factor = detector.scaleFactor
                val nextScale = (currentScale * factor).coerceIn(1f, 5f)
                val appliedFactor = nextScale / currentScale
                currentScale = nextScale
                matrixZoom.postScale(appliedFactor, appliedFactor, detector.focusX, detector.focusY)
                imageMatrix = matrixZoom
                return true
            }
        })

        init {
            scaleType = ScaleType.MATRIX
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            val d = drawable ?: return
            if (d.intrinsicWidth <= 0 || d.intrinsicHeight <= 0) return
            val scale = min(w.toFloat() / d.intrinsicWidth.toFloat(), h.toFloat() / d.intrinsicHeight.toFloat())
            val dx = (w - d.intrinsicWidth * scale) / 2f
            val dy = (h - d.intrinsicHeight * scale) / 2f
            matrixZoom.reset()
            matrixZoom.postScale(scale, scale)
            matrixZoom.postTranslate(dx, dy)
            imageMatrix = matrixZoom
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            scaleDetector.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = event.x
                    lastY = event.y
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!scaleDetector.isInProgress && currentScale > 1.05f) {
                        matrixZoom.postTranslate(event.x - lastX, event.y - lastY)
                        imageMatrix = matrixZoom
                    }
                    lastX = event.x
                    lastY = event.y
                }
            }
            return true
        }
    }
}
