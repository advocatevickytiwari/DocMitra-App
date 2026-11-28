package com.docmitra.scanner

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.WindowManager
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
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.min

class DocumentViewerActivity : AppCompatActivity() {

    private lateinit var projectDir: File
    private lateinit var pagesContainer: LinearLayout
    private lateinit var tvHeaderTitle: TextView
    @Volatile private var isOperationRunning = false
    private val workerExecutor = Executors.newSingleThreadExecutor()

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
                    putExtra("TARGET_PROJECT_DIR", projectDir.absolutePath)
                }
                startActivity(studioIntent)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Security Shield: FLAG_SECURE
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

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xFF14532D.toInt())
            setPadding(dp(10), dp(10), dp(12), dp(10))
        }

        val btnBack = MaterialButton(this).apply {
            text = "←"
            textSize = 16f
            minWidth = dp(40)
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0x00000000)
            setOnClickListener { finish() }
        }

        tvHeaderTitle = TextView(this).apply {
            text = projectDir.name
            textSize = 17f
            setTextColor(0xFFFFFFFF.toInt())
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val btnAddPage = MaterialButton(this).apply {
            text = "+ Add Page"
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xFFEA580C.toInt())
            setOnClickListener { startAddPageScanner() }
        }

        topBar.addView(btnBack)
        topBar.addView(tvHeaderTitle)
        topBar.addView(btnAddPage)
        root.addView(topBar)

        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        pagesContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(24))
        }
        scrollView.addView(pagesContainer)
        root.addView(scrollView)

        val bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xFFFFFFFF.toInt())
            setPadding(dp(12), dp(10), dp(12), dp(12))
        }

        val btnSharePdf = MaterialButton(this).apply {
            text = "Share HD PDF"
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xFF14532D.toInt())
            layoutParams = LinearLayout.LayoutParams(0, dp(46), 1.2f).apply {
                marginEnd = dp(8)
            }
            setOnClickListener { exportAndSharePdfAsync() }
        }

        val btnShareJpg = MaterialButton(this).apply {
            text = "Share Images"
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xFFEA580C.toInt())
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
                return MaterialButton(this).apply {
                    text = label
                    textSize = 11f
                    minWidth = 0
                    setPadding(dp(8), 0, dp(8), 0)
                    setBackgroundColor(bg)
                    setTextColor(fg)
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
            .setPageLimit(20)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_BASE)
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
        Toast.makeText(this, "Creating HD PDF...", Toast.LENGTH_SHORT).show()

        workerExecutor.execute {
            try {
                val cachePdf = File(cacheDir, "${projectDir.name}.pdf")
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
                    isOperationRunning = false
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
