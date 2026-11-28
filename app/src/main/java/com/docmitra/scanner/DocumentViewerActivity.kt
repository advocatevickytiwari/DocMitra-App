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
            setPadding(dp(10), dp(10), dp(10), dp(24
