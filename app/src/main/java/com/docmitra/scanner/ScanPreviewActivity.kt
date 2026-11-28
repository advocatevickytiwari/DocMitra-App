package com.docmitra.scanner

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
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
import android.view.View
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
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
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

class ScanPreviewActivity : AppCompatActivity() {

    private lateinit var ivPreview: ImageView
    private lateinit var overlayView: StampOverlayView
    private lateinit var tvPageIndicator: TextView
    private lateinit var etDocTitle: EditText
    private lateinit var stampControlsBar: LinearLayout
    private lateinit var adjustBar: LinearLayout
    private lateinit var tvLoadingBanner: TextView
    private lateinit var filterThumbRow: LinearLayout
    private lateinit var cbApplyToAll: CheckBox
    private lateinit var sbBrightness: SeekBar
    private lateinit var sbContrast: SeekBar

    private val originalBitmaps = ArrayList<Bitmap>()
    private val editedBitmaps = ArrayList<Bitmap>()
    private val pageFilterModes = ArrayList<Int>()
    private val pageBrightness = ArrayList<Float>()
    private val pageContrast = ArrayList<Float>()

    private var currentIndex = 0
    private var isRetakeMode = false
    private var validatedTargetFolder: File? = null

    private val filterNames = listOf(
        "No shadow",
        "Original",
        "Lighten",
        "Magic Color",
        "Doc Clear",
        "Grayscale"
    )

    private val extraScannerLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val scanResult = GmsDocumentScanningResult.fromActivityResultIntent(result.data)
            val pages = scanResult?.pages
            if (!pages.isNullOrEmpty()) {
                Thread {
                    val newOrig = ArrayList<Bitmap>()
                    val newEdit = ArrayList<Bitmap>()
                    for (p in pages) {
                        decodeSafeUri(p.imageUri, 1600)?.let { bmp ->
                            newOrig.add(bmp)
                            newEdit.add(renderFilteredPage(bmp, 3, 0f, 1f))
                        }
                    }
                    runOnUiThread {
                        if (isFinishing || isDestroyed || newOrig.isEmpty()) return@runOnUiThread
                        if (isRetakeMode && originalBitmaps.isNotEmpty()) {
                            originalBitmaps[currentIndex] = newOrig[0]
                            editedBitmaps[currentIndex] = newEdit[0]
                            pageFilterModes[currentIndex] = 3
                            pageBrightness[currentIndex] = 0f
                            pageContrast[currentIndex] = 1f
                        } else {
                            for (i in newOrig.indices) {
                                originalBitmaps.add(newOrig[i])
                                editedBitmaps.add(newEdit[i])
                                pageFilterModes.add(3)
                                pageBrightness.add(0f)
                                pageContrast.add(1f)
                            }
                            currentIndex = originalBitmaps.size - 1
                        }
                        syncSlidersToCurrentPage()
                        updatePageDisplay()
                        rebuildFilterThumbnails()
                    }
                }.start()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )

        val secureBaseDir = getSecureProjectsRoot()
        val rawFolderPath = intent.getStringExtra("TARGET_PROJECT_DIR")
        if (!rawFolderPath.isNullOrBlank()) {
            try {
                val candidate = File(rawFolderPath).canonicalFile
                val canonicalRoot = secureBaseDir.canonicalFile
                if (candidate.parentFile == canonicalRoot && candidate.isDirectory) {
                    validatedTargetFolder = candidate
                }
            } catch (_: Exception) {
                validatedTargetFolder = null
            }
        }

        val density = resources.displayMetrics.density
        val dp = { v: Int -> (v * density).toInt() }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF18181B.toInt())
        }

        // Top Bar
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xFF18181B.toInt())
            setPadding(dp(8), dp(8), dp(10), dp(6))
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

        val defaultDocName = validatedTargetFolder?.name
            ?: "Doc_${SimpleDateFormat("ddMM_HHmm", Locale.getDefault()).format(Date())}"

        etDocTitle = EditText(this).apply {
            setText(defaultDocName)
            hint = "Document Name"
            textSize = 14f
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFFA1A1AA.toInt())
            setBackgroundColor(0x22FFFFFF)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            isSingleLine = true
            isEnabled = (validatedTargetFolder == null)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(8)
            }
        }

        val btnShareQuick = makeZeroClipButton("PDF", 0xFFEA580C.toInt(), 0xFFFFFFFF.toInt(), 12f, dp).apply {
            layoutParams = LinearLayout.LayoutParams(dp(56), dp(38)).apply { marginEnd = dp(6) }
            setOnClickListener {
                if (editedBitmaps.isNotEmpty()) saveProjectAndFinish(shareImmediately = true)
            }
        }

        val btnDone = makeZeroClipButton("Done", 0xFF16A34A.toInt(), 0xFFFFFFFF.toInt(), 13f, dp).apply {
            layoutParams = LinearLayout.LayoutParams(dp(68), dp(38))
            setOnClickListener {
                if (editedBitmaps.isNotEmpty()) saveProjectAndFinish(shareImmediately = false)
            }
        }

        topBar.addView(btnBack)
        topBar.addView(etDocTitle)
        topBar.addView(btnShareQuick)
        topBar.addView(btnDone)
        root.addView(topBar)

        // Center Document Stage
        val stageFrame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            ).apply { setMargins(dp(10), dp(2), dp(10), dp(2)) }
            setBackgroundColor(0xFF18181B.toInt())
        }

        ivPreview = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            scaleType = ImageView.ScaleType.FIT_CENTER
        }

        overlayView = StampOverlayView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        tvLoadingBanner = TextView(this).apply {
            text = "Loading HD Preview..."
            textSize = 15f
            setTextColor(0xFFFFFFFF.toInt())
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setBackgroundColor(0xCC18181B.toInt())
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        stageFrame.addView(ivPreview)
        stageFrame.addView(overlayView)
        stageFrame.addView(tvLoadingBanner)
        root.addView(stageFrame)

        // Page Switcher (< 1/1 >) + Apply to all pages Checkbox (OKEN Style)
        val navAndApplyRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(2), dp(12), dp(4))
        }

        val btnPrev = makeZeroClipButton("<", 0xFF27272A.toInt(), 0xFFFFFFFF.toInt(), 13f, dp).apply {
            layoutParams = LinearLayout.LayoutParams(dp(34), dp(30))
            setOnClickListener {
                if (currentIndex > 0) {
                    currentIndex--
                    syncSlidersToCurrentPage()
                    updatePageDisplay()
                    rebuildFilterThumbnails()
                }
            }
        }

        tvPageIndicator = TextView(this).apply {
            text = "1/1"
            textSize = 12.5f
            setTextColor(0xFFFFFFFF.toInt())
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(10), 0, dp(10), 0)
        }

        val btnNext = makeZeroClipButton(">", 0xFF27272A.toInt(), 0xFFFFFFFF.toInt(), 13f, dp).apply {
            layoutParams = LinearLayout.LayoutParams(dp(34), dp(30))
            setOnClickListener {
                if (currentIndex < editedBitmaps.size - 1) {
                    currentIndex++
                    syncSlidersToCurrentPage()
                    updatePageDisplay()
                    rebuildFilterThumbnails()
                }
            }
        }

        val spacer = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        }

        cbApplyToAll = CheckBox(this).apply {
            text = "Apply to all pages"
            textSize = 11.5f
            setTextColor(0xFFE4E4E7.toInt())
            buttonTintList = android.content.res.ColorStateList.valueOf(0xFF22C55E.toInt())
        }

        navAndApplyRow.addView(btnPrev)
        navAndApplyRow.addView(tvPageIndicator)
        navAndApplyRow.addView(btnNext)
        navAndApplyRow.addView(spacer)
        navAndApplyRow.addView(cbApplyToAll)
        root.addView(navAndApplyRow)

        // Brightness & Contrast Adjust Bar (Hidden until 'Adjust' tapped)
        adjustBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF27272A.toInt())
            setPadding(dp(12), dp(6), dp(12), dp(6))
            visibility = View.GONE
        }

        val brightRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val tvB = TextView(this).apply {
            text = "Bright"
            textSize = 11f
            setTextColor(0xFFFFFFFF.toInt())
            layoutParams = LinearLayout.LayoutParams(dp(54), LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        sbBrightness = SeekBar(this).apply {
            max = 100
            progress = 50
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    if (fromUser && originalBitmaps.isNotEmpty()) {
                        applyCurrentToneAdjustments()
                    }
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        brightRow.addView(tvB)
        brightRow.addView(sbBrightness)

        val contrastRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, 0)
        }
        val tvC = TextView(this).apply {
            text = "Contrast"
            textSize = 11f
            setTextColor(0xFFFFFFFF.toInt())
            layoutParams = LinearLayout.LayoutParams(dp(54), LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        sbContrast = SeekBar(this).apply {
            max = 100
            progress = 50
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    if (fromUser && originalBitmaps.isNotEmpty()) {
                        applyCurrentToneAdjustments()
                    }
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        contrastRow.addView(tvC)
        contrastRow.addView(sbContrast)

        adjustBar.addView(brightRow)
        adjustBar.addView(contrastRow)
        root.addView(adjustBar)

        // Stamp Controls Bar
        stampControlsBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xFF27272A.toInt())
            setPadding(dp(8), dp(5), dp(8), dp(5))
            visibility = View.GONE
        }

        val btnStampSmaller = makeZeroClipButton("Size -", 0xFF3F3F46.toInt(), 0xFFFFFFFF.toInt(), 11f, dp).apply {
            layoutParams = LinearLayout.LayoutParams(dp(60), dp(36))
            setOnClickListener { overlayView.adjustScale(-0.15f) }
        }

        val btnStampBigger = makeZeroClipButton("Size +", 0xFF3F3F46.toInt(), 0xFFFFFFFF.toInt(), 11f, dp).apply {
            layoutParams = LinearLayout.LayoutParams(dp(60), dp(36)).apply { marginStart = dp(6) }
            setOnClickListener { overlayView.adjustScale(0.15f) }
        }

        val btnClearStamp = makeZeroClipButton("Remove", 0xFF450A0A.toInt(), 0xFFFCA5A5.toInt(), 11f, dp).apply {
            layoutParams = LinearLayout.LayoutParams(dp(68), dp(36)).apply { marginStart = dp(6) }
            setOnClickListener {
                overlayView.clearStamp()
                stampControlsBar.visibility = View.GONE
            }
        }

        val btnFixStamp = makeZeroClipButton("✓ Fix on Page", 0xFF16A34A.toInt(), 0xFFFFFFFF.toInt(), 12f, dp).apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(36), 1f).apply { marginStart = dp(6) }
            setOnClickListener { burnStampOntoCurrentPage() }
        }

        stampControlsBar.addView(btnStampSmaller)
        stampControlsBar.addView(btnStampBigger)
        stampControlsBar.addView(btnClearStamp)
        stampControlsBar.addView(btnFixStamp)
        root.addView(stampControlsBar)

        // OKEN-Style Horizontal Live Filter Thumbnail Strip
        val filterScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setBackgroundColor(0xFF27272A.toInt())
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }

        filterThumbRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        filterScroll.addView(filterThumbRow)
        root.addView(filterScroll)

        // OKEN-Style Bottom 6-Action Toolbar
        val bottomToolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xFF18181B.toInt())
            setPadding(dp(2), dp(6), dp(2), dp(8))
        }

        fun makeBottomTool(icon: String, label: String, onClick: () -> Unit): LinearLayout {
            return LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener { if (editedBitmaps.isNotEmpty()) onClick() }

                val tvIcon = TextView(this@ScanPreviewActivity).apply {
                    text = icon
                    textSize = 15f
                    setTextColor(0xFFFFFFFF.toInt())
                    gravity = Gravity.CENTER
                }
                val tvLbl = TextView(this@ScanPreviewActivity).apply {
                    text = label
                    textSize = 10f
                    maxLines = 1
                    setTextColor(0xFFD4D4D8.toInt())
                    gravity = Gravity.CENTER
                }
                addView(tvIcon)
                addView(tvLbl)
            }
        }

        bottomToolbar.addView(makeBottomTool("📷", "Retake") {
            isRetakeMode = true
            launchScannerForMore(pageLimit = 1)
        })

        bottomToolbar.addView(makeBottomTool("➕", "Add") {
            isRetakeMode = false
            launchScannerForMore(pageLimit = 20)
        })

        bottomToolbar.addView(makeBottomTool("↺", "Left") {
            rotateCurrentPageLeft()
        })

        bottomToolbar.addView(makeBottomTool("✂️", "Crop") {
            openPerspectiveWarpCropDialog()
        })

        bottomToolbar.addView(makeBottomTool("☀️", "Adjust") {
            adjustBar.visibility = if (adjustBar.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        })

        bottomToolbar.addView(makeBottomTool("🔏", "Mohar") {
            openStampAndSignMenu()
        })

        root.addView(bottomToolbar)
        setContentView(root)

        val uriStrings = ArrayList<String>()
        intent.getStringArrayListExtra("SCAN_URIS")?.let { uriStrings.addAll(it) }

        if (uriStrings.isEmpty()) {
            if (intent.action == Intent.ACTION_SEND) {
                val singleUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                }
                singleUri?.let { uriStrings.add(it.toString()) }
            } else if (intent.action == Intent.ACTION_SEND_MULTIPLE) {
                val multiUris = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
                }
                multiUris?.take(30)?.forEach { uriStrings.add(it.toString()) }
            }
        }

        if (uriStrings.isEmpty()) {
            Toast.makeText(this, "No valid image found", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        Thread {
            val loadedOrig = ArrayList<Bitmap>()
            val loadedEdit = ArrayList<Bitmap>()

            for (uriStr in uriStrings.take(30)) {
                val parsedUri = Uri.parse(uriStr)
                decodeSafeUri(parsedUri, 1600)?.let { bmp ->
                    loadedOrig.add(bmp)
                    loadedEdit.add(renderFilteredPage(bmp, 3, 0f, 1f))
                }
            }

            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                tvLoadingBanner.visibility = View.GONE
                if (loadedOrig.isEmpty()) {
                    Toast.makeText(this, "Could not load photo, please try again", Toast.LENGTH_SHORT).show()
                    finish()
                } else {
                    originalBitmaps.addAll(loadedOrig)
                    editedBitmaps.addAll(loadedEdit)
                    repeat(loadedOrig.size) {
                        pageFilterModes.add(3)
                        pageBrightness.add(0f)
                        pageContrast.add(1f)
                    }
                    updatePageDisplay()
                    rebuildFilterThumbnails()
                }
            }
        }.start()
    }

    private fun makeZeroClipButton(
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
            setPadding(dp(4), 0, dp(4), 0)
            setTypeface(null, Typeface.BOLD)
            setTextColor(txtColor)
            setBackgroundColor(bgColor)
        }
    }

    private fun decodeSafeUri(uri: Uri, maxDim: Int): Bitmap? {
        if (uri.scheme != "content") return null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (bounds.outWidth / sample > maxDim || bounds.outHeight / sample > maxDim) {
                sample *= 2
            }
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        } catch (_: Exception) {
            null
        }
    }

    private fun syncSlidersToCurrentPage() {
        if (pageBrightness.isEmpty()) return
        val b = pageBrightness[currentIndex] // -60..+60 -> 0..100
        val c = pageContrast[currentIndex]   // 0.6..1.5 -> 0..100
        sbBrightness.progress = (((b + 60f) / 120f) * 100f).toInt().coerceIn(0, 100)
        sbContrast.progress = (((c - 0.6f) / 0.9f) * 100f).toInt().coerceIn(0, 100)
    }

    private fun applyCurrentToneAdjustments() {
        val extraBright = ((sbBrightness.progress - 50) / 50f) * 60f
        val extraContrast = 1f + ((sbContrast.progress - 50) / 50f) * 0.45f

        if (cbApplyToAll.isChecked) {
            for (i in originalBitmaps.indices) {
                pageBrightness[i] = extraBright
                pageContrast[i] = extraContrast
                editedBitmaps[i] = renderFilteredPage(originalBitmaps[i], pageFilterModes[i], extraBright, extraContrast)
            }
        } else {
            pageBrightness[currentIndex] = extraBright
            pageContrast[currentIndex] = extraContrast
            editedBitmaps[currentIndex] = renderFilteredPage(originalBitmaps[currentIndex], pageFilterModes[currentIndex], extraBright, extraContrast)
        }
        ivPreview.setImageBitmap(editedBitmaps[currentIndex])
    }

    private fun rebuildFilterThumbnails() {
        if (originalBitmaps.isEmpty()) return
        filterThumbRow.removeAllViews()

        val base = originalBitmaps[currentIndex]
        val thumbW = 130
        val thumbH = (base.height.toFloat() / base.width.toFloat().coerceAtLeast(1f) * thumbW)
            .toInt().coerceIn(110, 170)
        val tinySrc = Bitmap.createScaledBitmap(base, thumbW, thumbH, true)

        val selectedMode = pageFilterModes[currentIndex]
        val density = resources.displayMetrics.density
        val dp = { v: Int -> (v * density).toInt() }

        for (modeIdx in filterNames.indices) {
            val isSelected = (modeIdx == selectedMode)
            val card = MaterialCardView(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(74), LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    marginEnd = dp(8)
                }
                radius = 8f * density
                cardElevation = 0f
                setCardBackgroundColor(if (isSelected) 0xFF16A34A.toInt() else 0xFF3F3F46.toInt())
                strokeColor = if (isSelected) 0xFF22C55E.toInt() else 0xFF52525B.toInt()
                strokeWidth = (2f * density).toInt()
            }

            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
            }

            val ivThumb = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(64)
                )
                scaleType = ImageView.ScaleType.CENTER_CROP
                setImageBitmap(renderFilteredPage(tinySrc, modeIdx, 0f, 1f))
            }

            val tvName = TextView(this).apply {
                text = filterNames[modeIdx]
                textSize = 10f
                maxLines = 1
                gravity = Gravity.CENTER
                setTextColor(0xFFFFFFFF.toInt())
                setTypeface(null, if (isSelected) Typeface.BOLD else Typeface.NORMAL)
                setPadding(dp(2), dp(3), dp(2), dp(3))
            }

            col.addView(ivThumb)
            col.addView(tvName)
            card.addView(col)

            card.setOnClickListener {
                if (cbApplyToAll.isChecked) {
                    for (i in originalBitmaps.indices) {
                        pageFilterModes[i] = modeIdx
                        editedBitmaps[i] = renderFilteredPage(
                            originalBitmaps[i],
                            modeIdx,
                            pageBrightness[i],
                            pageContrast[i]
                        )
                    }
                    Toast.makeText(this, "Applied '${filterNames[modeIdx]}' to all pages", Toast.LENGTH_SHORT).show()
                } else {
                    pageFilterModes[currentIndex] = modeIdx
                    editedBitmaps[currentIndex] = renderFilteredPage(
                        originalBitmaps[currentIndex],
                        modeIdx,
                        pageBrightness[currentIndex],
                        pageContrast[currentIndex]
                    )
                }
                ivPreview.setImageBitmap(editedBitmaps[currentIndex])
                rebuildFilterThumbnails()
            }

            filterThumbRow.addView(card)
        }
    }

    private fun renderFilteredPage(src: Bitmap, mode: Int, extraBright: Float, extraContrast: Float): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        val cm = ColorMatrix()
        var baseContrast = 1.0f
        var baseBrightness = 0f

        when (mode) {
            0 -> { // No shadow
                cm.setSaturation(1.1f)
                baseContrast = 1.48f
                baseBrightness = 34f
            }
            1 -> { // Original
                cm.setSaturation(1.0f)
                baseContrast = 1.0f
                baseBrightness = 0f
            }
            2 -> { // Lighten
                cm.setSaturation(1.05f)
                baseContrast = 1.15f
                baseBrightness = 28f
            }
            3 -> { // Magic Color
                cm.setSaturation(1.32f)
                baseContrast = 1.38f
                baseBrightness = 14f
            }
            4 -> { // Doc Clear
                cm.setSaturation(0.25f)
                baseContrast = 1.65f
                baseBrightness = 22f
            }
            5 -> { // Grayscale
                cm.setSaturation(0f)
                baseContrast = 1.75f
                baseBrightness = 18f
            }
        }

        val finalContrast = (baseContrast * extraContrast).coerceIn(0.4f, 2.8f)
        val finalBrightness = baseBrightness + extraBright
        val translate = (-.5f * finalContrast + .5f) * 255f + finalBrightness

        val tone = ColorMatrix(
            floatArrayOf(
                finalContrast, 0f, 0f, 0f, translate,
                0f, finalContrast, 0f, 0f, translate,
                0f, 0f, finalContrast, 0f, translate,
                0f, 0f, 0f, 1f, 0f
            )
        )
        cm.postConcat(tone)
        paint.colorFilter = ColorMatrixColorFilter(cm)
        canvas.drawBitmap(src, 0f, 0f, paint)
        return out
    }

    private fun rotateCurrentPageLeft() {
        val m = Matrix().apply { postRotate(-90f) }
        val orig = originalBitmaps[currentIndex]
        val rotOrig = Bitmap.createBitmap(orig, 0, 0, orig.width, orig.height, m, true)
        originalBitmaps[currentIndex] = rotOrig
        editedBitmaps[currentIndex] = renderFilteredPage(
            rotOrig,
            pageFilterModes[currentIndex],
            pageBrightness[currentIndex],
            pageContrast[currentIndex]
        )
        ivPreview.setImageBitmap(editedBitmaps[currentIndex])
        rebuildFilterThumbnails()
    }

    // 4-Corner Perspective Warp + Magnifier Loupe (Straightens tilted documents cleanly)
    private fun openPerspectiveWarpCropDialog() {
        val currentOrig = originalBitmaps[currentIndex]
        val density = resources.displayMetrics.density
        val dp = { v: Int -> (v * density).toInt() }

        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF0F172A.toInt())
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }

        val tipTv = TextView(this).apply {
            text = "Drag any corner (Magnifier zoom appears above finger)"
            textSize = 11.5f
            setTextColor(0xFFE2E8F0.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(6))
        }

        val cropView = PerspectiveMagnifierCropView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(360)
            )
            setSourceBitmap(currentOrig)
        }

        var dlg: AlertDialog? = null

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, 0)
        }

        val btnReset = makeZeroClipButton("Reset Corners", 0xFF334155.toInt(), 0xFFFFFFFF.toInt(), 12f, dp).apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginEnd = dp(6) }
            setOnClickListener { cropView.resetCorners() }
        }

        val btnApply = makeZeroClipButton("✓ Straighten & Apply", 0xFF16A34A.toInt(), 0xFFFFFFFF.toInt(), 12.5f, dp).apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(42), 1.3f)
            setOnClickListener {
                val warped = cropView.extractWarpedFlatBitmap()
                originalBitmaps[currentIndex] = warped
                editedBitmaps[currentIndex] = renderFilteredPage(
                    warped,
                    pageFilterModes[currentIndex],
                    pageBrightness[currentIndex],
                    pageContrast[currentIndex]
                )
                ivPreview.setImageBitmap(editedBitmaps[currentIndex])
                rebuildFilterThumbnails()
                dlg?.dismiss()
            }
        }

        btnRow.addView(btnReset)
        btnRow.addView(btnApply)
        wrapper.addView(tipTv)
        wrapper.addView(cropView)
        wrapper.addView(btnRow)

        dlg = AlertDialog.Builder(this)
            .setTitle("Perspective Auto-Straighten Crop")
            .setView(wrapper)
            .setNegativeButton("Cancel", null)
            .create()
        dlg.show()
    }

    private fun launchScannerForMore(pageLimit: Int) {
        val options = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setPageLimit(pageLimit)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build()

        GmsDocumentScanning.getClient(options).getStartScanIntent(this)
            .addOnSuccessListener { intentSender ->
                extraScannerLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
            }
    }

    private fun updatePageDisplay() {
        if (editedBitmaps.isEmpty()) return
        ivPreview.setImageBitmap(editedBitmaps[currentIndex])
        tvPageIndicator.text = "${currentIndex + 1}/${editedBitmaps.size}"
    }

    private fun getSecureProjectsRoot(): File {
        val dir = File(filesDir, "DocMitra/Projects")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun getSecureVaultAssetsDir(): File {
        val dir = File(filesDir, "DocMitra/Stamps")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun sanitizeFileName(raw: String, fallback: String): String {
        val cleaned = raw.replace("[^a-zA-Z0-9\\u0900-\\u097F_\\- ]".toRegex(), "")
            .replace("..", "")
            .trim()
            .take(50)
        return cleaned.ifEmpty { fallback }
    }

    private fun openStampAndSignMenu() {
        val assetsDir = getSecureVaultAssetsDir()
        val savedMoharFile = File(assetsDir, "saved_mohar.png")
        val savedSignFile = File(assetsDir, "saved_sign.png")

        val options = ArrayList<String>()
        options.add("🔏 Create Custom Mohar / Seal")
        if (savedMoharFile.exists()) options.add("📌 Use Saved Mohar")
        options.add("✍️ Draw Digital Signature")
        if (savedSignFile.exists()) options.add("🖊️ Use Saved Signature")
        options.add("📋 'SELF ATTESTED' + Date")
        options.add("⚖️ 'CERTIFIED TRUE COPY'")

        AlertDialog.Builder(this)
            .setTitle("Add Mohar / Signature")
            .setItems(options.toTypedArray()) { _, which ->
                val choice = options[which]
                when {
                    choice.contains("Create Custom Mohar") -> promptCreateCustomMohar(savedMoharFile)
                    choice.contains("Use Saved Mohar") -> {
                        BitmapFactory.decodeFile(savedMoharFile.absolutePath)?.let { placeStampOnOverlay(it) }
                    }
                    choice.contains("Draw Digital Signature") -> promptDrawSignature(savedSignFile)
                    choice.contains("Use Saved Signature") -> {
                        BitmapFactory.decodeFile(savedSignFile.absolutePath)?.let { placeStampOnOverlay(it) }
                    }
                    choice.contains("SELF ATTESTED") -> {
                        val dateStr = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date())
                        placeStampOnOverlay(generateBoxStampBitmap("SELF ATTESTED", "Date: $dateStr", Color.rgb(18, 56, 168)))
                    }
                    choice.contains("CERTIFIED TRUE COPY") -> {
                        val dateStr = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date())
                        placeStampOnOverlay(generateBoxStampBitmap("CERTIFIED TRUE COPY", "Verified • $dateStr", Color.rgb(185, 28, 28)))
                    }
                }
            }
            .show()
    }

    private fun promptCreateCustomMohar(saveFile: File) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 24, 40, 10)
        }
        val etLine1 = EditText(this).apply { hint = "Top Line (e.g. ADV. VICKY TIWARI)"; isSingleLine = true }
        val etLine2 = EditText(this).apply { hint = "Middle / Title (e.g. ADVOCATE)"; isSingleLine = true }
        val etLine3 = EditText(this).apply { hint = "Bottom / Place (e.g. HIGH COURT LUCKNOW)"; isSingleLine = true }
        layout.addView(etLine1)
        layout.addView(etLine2)
        layout.addView(etLine3)

        AlertDialog.Builder(this)
            .setTitle("Design Your Mohar")
            .setView(layout)
            .setPositiveButton("Round Mohar") { _, _ ->
                val bmp = generateRoundMoharBitmap(
                    etLine1.text.toString().trim().ifEmpty { "AUTHORIZED SEAL" },
                    etLine2.text.toString().trim().ifEmpty { "VERIFIED" },
                    etLine3.text.toString().trim().ifEmpty { "INDIA" },
                    Color.rgb(20, 62, 175)
                )
                FileOutputStream(saveFile).use { out -> bmp.compress(Bitmap.CompressFormat.PNG, 100, out) }
                placeStampOnOverlay(bmp)
            }
            .setNeutralButton("Box Mohar") { _, _ ->
                val l2 = listOf(etLine2.text.toString().trim(), etLine3.text.toString().trim())
                    .filter { it.isNotEmpty() }.joinToString(" | ")
                val bmp = generateBoxStampBitmap(
                    etLine1.text.toString().trim().ifEmpty { "AUTHORIZED SEAL" },
                    l2,
                    Color.rgb(20, 62, 175)
                )
                FileOutputStream(saveFile).use { out -> bmp.compress(Bitmap.CompressFormat.PNG, 100, out) }
                placeStampOnOverlay(bmp)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun promptDrawSignature(saveFile: File) {
        val pad = SignatureDrawView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (180 * resources.displayMetrics.density).toInt()
            )
        }
        AlertDialog.Builder(this)
            .setTitle("Sign with Finger")
            .setView(pad)
            .setPositiveButton("Apply & Save") { _, _ ->
                val bmp = pad.exportSignatureBitmap()
                if (bmp != null) {
                    FileOutputStream(saveFile).use { out -> bmp.compress(Bitmap.CompressFormat.PNG, 100, out) }
                    placeStampOnOverlay(bmp)
                }
            }
            .setNeutralButton("Clear") { _, _ -> promptDrawSignature(saveFile) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun placeStampOnOverlay(stampBmp: Bitmap) {
        overlayView.applyStampBitmap(stampBmp)
        stampControlsBar.visibility = View.VISIBLE
        Toast.makeText(this, "Drag Mohar/Sign anywhere, then tap '✓ Fix on Page'", Toast.LENGTH_SHORT).show()
    }

    private fun burnStampOntoCurrentPage() {
        val stampBmp = overlayView.activeStamp ?: return
        val basePage = editedBitmaps[currentIndex]

        val viewW = ivPreview.width.toFloat()
        val viewH = ivPreview.height.toFloat()
        if (viewW <= 0f || viewH <= 0f) return

        val scale = min(viewW / basePage.width.toFloat(), viewH / basePage.height.toFloat())
        val displayedW = basePage.width * scale
        val displayedH = basePage.height * scale
        val offsetX = (viewW - displayedW) / 2f
        val offsetY = (viewH - displayedH) / 2f

        val targetLeft = ((overlayView.stampX - offsetX) / scale)
        val targetTop = ((overlayView.stampY - offsetY) / scale)
        val targetW = ((stampBmp.width * overlayView.stampScale) / scale)
        val targetH = ((stampBmp.height * overlayView.stampScale) / scale)

        val merged = basePage.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(merged)
        canvas.drawBitmap(
            stampBmp,
            null,
            RectF(targetLeft, targetTop, targetLeft + targetW, targetTop + targetH),
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        )

        editedBitmaps[currentIndex] = merged
        originalBitmaps[currentIndex] = merged
        ivPreview.setImageBitmap(merged)
        overlayView.clearStamp()
        stampControlsBar.visibility = View.GONE
        Toast.makeText(this, "Fixed onto page!", Toast.LENGTH_SHORT).show()
    }

    private fun generateBoxStampBitmap(title: String, subtitle: String, inkColor: Int): Bitmap {
        val w = 560
        val h = 180
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = inkColor
            style = Paint.Style.STROKE
            strokeWidth = 7f
        }
        canvas.drawRoundRect(RectF(8f, 8f, w - 8f, h - 8f), 14f, 14f, borderPaint)

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = inkColor
            textSize = if (title.length > 20) 27f else 34f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = inkColor
            textSize = if (subtitle.length > 28) 18f else 22f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        if (subtitle.isEmpty()) canvas.drawText(title, w / 2f, h / 2f + 12f, titlePaint)
        else {
            canvas.drawText(title, w / 2f, 76f, titlePaint)
            canvas.drawText(subtitle, w / 2f, 128f, subPaint)
        }
        return bmp
    }

    private fun generateRoundMoharBitmap(topText: String, centerText: String, bottomText: String, inkColor: Int): Bitmap {
        val size = 420
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val cx = size / 2f
        val cy = size / 2f

        val outerRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = inkColor; style = Paint.Style.STROKE; strokeWidth = 8f }
        val innerRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = inkColor; style = Paint.Style.STROKE; strokeWidth = 3.5f }
        canvas.drawCircle(cx, cy, 192f, outerRing)
        canvas.drawCircle(cx, cy, 178f, innerRing)
        canvas.drawCircle(cx, cy, 118f, innerRing)

        val arcPaintTop = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = inkColor
            textSize = if (topText.length > 18) 20f else 24f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val topArc = Path().apply { addArc(RectF(cx - 146f, cy - 146f, cx + 146f, cy + 146f), 190f, 160f) }
        canvas.drawTextOnPath(topText.uppercase(Locale.getDefault()), topArc, 0f, 0f, arcPaintTop)

        val arcPaintBot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = inkColor
            textSize = if (bottomText.length > 18) 19f else 23f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val bottomArc = Path().apply { addArc(RectF(cx - 152f, cy - 152f, cx + 152f, cy + 152f), 165f, -150f) }
        canvas.drawTextOnPath(bottomText.uppercase(Locale.getDefault()), bottomArc, 0f, 0f, arcPaintBot)

        val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = inkColor
            textSize = if (centerText.length > 12) 22f else 26f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("★ $centerText ★", cx, cy + 9f, centerPaint)
        return bmp
    }

    private fun saveProjectAndFinish(shareImmediately: Boolean) {
        try {
            if (overlayView.activeStamp != null) burnStampOntoCurrentPage()
            saveCopiesToGalleryQuietly()

            val projectDir: File
            if (validatedTargetFolder != null) {
                projectDir = validatedTargetFolder!!
            } else {
                val fallback = "Doc_${SimpleDateFormat("ddMM_HHmm", Locale.getDefault()).format(Date())}"
                val cleanName = sanitizeFileName(etDocTitle.text.toString(), fallback)
                projectDir = File(getSecureProjectsRoot(), cleanName)
                if (!projectDir.exists()) projectDir.mkdirs()
            }

            val existingPages = projectDir.listFiles { f ->
                f.isFile && f.extension.equals("jpg", ignoreCase = true)
            }?.sortedBy { it.name } ?: emptyList()

            var nextNum = existingPages.size + 1
            for (bmp in editedBitmaps) {
                var pageFile = File(projectDir, String.format(Locale.US, "page_%04d.jpg", nextNum))
                while (pageFile.exists()) {
                    nextNum++
                    pageFile = File(projectDir, String.format(Locale.US, "page_%04d.jpg", nextNum))
                }
                FileOutputStream(pageFile).use { out -> bmp.compress(Bitmap.CompressFormat.JPEG, 95, out) }
                nextNum++
            }
            projectDir.setLastModified(System.currentTimeMillis())

            Toast.makeText(this, "Saved HD in '${projectDir.name}'!", Toast.LENGTH_SHORT).show()

            if (shareImmediately) {
                val allProjectPages = projectDir.listFiles { f ->
                    f.isFile && f.extension.equals("jpg", ignoreCase = true)
                }?.sortedBy { it.name } ?: emptyList()

                val cachePdf = File(cacheDir, "${projectDir.name}.pdf")
                val pdfDoc = PdfDocument()
                allProjectPages.forEachIndexed { idx, imgFile ->
                    val b = BitmapFactory.decodeFile(imgFile.absolutePath)
                    if (b != null) {
                        val info = PdfDocument.PageInfo.Builder(b.width, b.height, idx + 1).create()
                        val page = pdfDoc.startPage(info)
                        page.canvas.drawBitmap(b, 0f, 0f, null)
                        pdfDoc.finishPage(page)
                    }
                }
                FileOutputStream(cachePdf).use { out -> pdfDoc.writeTo(out) }
                pdfDoc.close()

                val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", cachePdf)
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/pdf"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(shareIntent, "Share HD PDF"))
            } else {
                val homeIntent = Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
                startActivity(homeIntent)
                finish()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Save error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveCopiesToGalleryQuietly() {
        try {
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            for (i in editedBitmaps.indices) {
                val fileName = "DocMitra_${stamp}_P${i + 1}.jpg"
                val bmp = editedBitmaps[i]
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                        put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/DocMitra")
                    }
                    val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    uri?.let { contentResolver.openOutputStream(it)?.use { out -> bmp.compress(Bitmap.CompressFormat.JPEG, 94, out) } }
                } else {
                    val picDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "DocMitra")
                    if (!picDir.exists()) picDir.mkdirs()
                    FileOutputStream(File(picDir, fileName)).use { out -> bmp.compress(Bitmap.CompressFormat.JPEG, 94, out) }
                }
            }
        } catch (_: Exception) {}
    }

    // ==================== PERSPECTIVE WARP + MAGNIFIER LOUPE VIEW ====================

    private class PerspectiveMagnifierCropView(context: Context) : View(context) {
        private var srcBmp: Bitmap? = null
        private val imgRect = RectF()
        private val corners = Array(4) { PointF() } // 0:TL, 1:TR, 2:BR, 3:BL
        private var activeCorner = -1
        private var touchX = 0f
        private var touchY = 0f

        private val shadePaint = Paint().apply {
            color = 0x77000000.toInt()
            style = Paint.Style.FILL
        }
        private val quadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF22C55E.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 5f
        }
        private val cornerFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFEA580C.toInt()
            style = Paint.Style.FILL
        }
        private val cornerRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 4f
        }

        fun setSourceBitmap(b: Bitmap) {
            srcBmp = b
            resetCorners()
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            resetCorners()
        }

        fun resetCorners() {
            val b = srcBmp ?: return
            if (width <= 0 || height <= 0) return
            val s = min(width.toFloat() / b.width.toFloat(), height.toFloat() / b.height.toFloat())
            val dw = b.width * s
            val dh = b.height * s
            val l = (width - dw) / 2f
            val t = (height - dh) / 2f
            imgRect.set(l, t, l + dw, t + dh)

            val padX = dw * 0.07f
            val padY = dh * 0.07f
            corners[0].set(l + padX, t + padY)
            corners[1].set(l + dw - padX, t + padY)
            corners[2].set(l + dw - padX, t + dh - padY)
            corners[3].set(l + padX, t + dh - padY)
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val b = srcBmp ?: return
            canvas.drawBitmap(b, null, imgRect, null)

            // Draw 4-point quadrilateral border
            val path = Path().apply {
                moveTo(corners[0].x, corners[0].y)
                lineTo(corners[1].x, corners[1].y)
                lineTo(corners[2].x, corners[2].y)
                lineTo(corners[3].x, corners[3].y)
                close()
            }
            canvas.drawPath(path, quadPaint)

            // Draw 4 corner handles + 4 mid-edge dots
            for (i in 0..3) {
                canvas.drawCircle(corners[i].x, corners[i].y, 19f, cornerFill)
                canvas.drawCircle(corners[i].x, corners[i].y, 19f, cornerRing)

                val next = corners[(i + 1) % 4]
                val mx = (corners[i].x + next.x) / 2f
                val my = (corners[i].y + next.y) / 2f
                canvas.drawCircle(mx, my, 10f, cornerRing)
            }

            // OKEN-style Magnifier Zoom Loupe in top corner when dragging a handle
            if (activeCorner != -1 && imgRect.width() > 0f && imgRect.height() > 0f) {
                val loupeR = 95f
                val loupeCx = if (touchX < width / 2f) width - loupeR - 24f else loupeR + 24f
                val loupeCy = loupeR + 24f

                val sx = ((touchX - imgRect.left) / imgRect.width()) * b.width
                val sy = ((touchY - imgRect.top) / imgRect.height()) * b.height

                canvas.save()
                val clipCircle = Path().apply { addCircle(loupeCx, loupeCy, loupeR, Path.Direction.CW) }
                canvas.clipPath(clipCircle)
                canvas.drawColor(Color.BLACK)

                val zoomMatrix = Matrix().apply {
                    postTranslate(-sx, -sy)
                    postScale(2.2f, 2.2f)
                    postTranslate(loupeCx, loupeCy)
                }
                canvas.drawBitmap(b, zoomMatrix, null)

                // Crosshair inside magnifier
                val crossPaint = Paint().apply { color = 0xFFEA580C.toInt(); strokeWidth = 3f }
                canvas.drawLine(loupeCx - 24f, loupeCy, loupeCx + 24f, loupeCy, crossPaint)
                canvas.drawLine(loupeCx, loupeCy - 24f, loupeCx, loupeCy + 24f, crossPaint)
                canvas.restore()

                canvas.drawCircle(loupeCx, loupeCy, loupeR, cornerRing)
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (srcBmp == null) return false
            val x = event.x.coerceIn(imgRect.left, imgRect.right)
            val y = event.y.coerceIn(imgRect.top, imgRect.bottom)

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    var bestIdx = -1
                    var bestDist = 75f
                    for (i in 0..3) {
                        val d = hypot((x - corners[i].x).toDouble(), (y - corners[i].y).toDouble()).toFloat()
                        if (d < bestDist) {
                            bestDist = d
                            bestIdx = i
                        }
                    }
                    activeCorner = bestIdx
                    touchX = x
                    touchY = y
                    invalidate()
                    return activeCorner != -1
                }
                MotionEvent.ACTION_MOVE -> {
                    if (activeCorner != -1) {
                        corners[activeCorner].set(x, y)
                        touchX = x
                        touchY = y
                        invalidate()
                        return true
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    activeCorner = -1
                    invalidate()
                }
            }
            return false
        }

        fun extractWarpedFlatBitmap(): Bitmap {
            val b = srcBmp ?: return Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
            if (imgRect.width() <= 0f || imgRect.height() <= 0f) return b

            val scaleX = b.width.toFloat() / imgRect.width()
            val scaleY = b.height.toFloat() / imgRect.height()

            val srcPts = floatArrayOf(
                (corners[0].x - imgRect.left) * scaleX, (corners[0].y - imgRect.top) * scaleY,
                (corners[1].x - imgRect.left) * scaleX, (corners[1].y - imgRect.top) * scaleY,
                (corners[2].x - imgRect.left) * scaleX, (corners[2].y - imgRect.top) * scaleY,
                (corners[3].x - imgRect.left) * scaleX, (corners[3].y - imgRect.top) * scaleY
            )

            val topW = hypot((srcPts[2] - srcPts[0]).toDouble(), (srcPts[3] - srcPts[1]).toDouble()).toFloat()
            val botW = hypot((srcPts[4] - srcPts[6]).toDouble(), (srcPts[5] - srcPts[7]).toDouble()).toFloat()
            val leftH = hypot((srcPts[6] - srcPts[0]).toDouble(), (srcPts[7] - srcPts[1]).toDouble()).toFloat()
            val rightH = hypot((srcPts[4] - srcPts[2]).toDouble(), (srcPts[5] - srcPts[3]).toDouble()).toFloat()

            val outW = max(topW, botW).toInt().coerceIn(200, 2400)
            val outH = max(leftH, rightH).toInt().coerceIn(200, 3200)

            val dstPts = floatArrayOf(
                0f, 0f,
                outW.toFloat(), 0f,
                outW.toFloat(), outH.toFloat(),
                0f, outH.toFloat()
            )

            val warpMatrix = Matrix()
            warpMatrix.setPolyToPoly(srcPts, 0, dstPts, 0, 4)

            val result = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(result)
            canvas.drawColor(Color.WHITE)
            canvas.drawBitmap(b, warpMatrix, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
            return result
        }
    }

    private class StampOverlayView(context: Context) : View(context) {
        var activeStamp: Bitmap? = null
        var stampX = 120f
        var stampY = 260f
        var stampScale = 0.65f
        private var lastTouchX = 0f
        private var lastTouchY = 0f
        private var isDragging = false

        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF16A34A.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }

        fun applyStampBitmap(bmp: Bitmap) {
            activeStamp = bmp
            val viewW = if (width > 0) width.toFloat() else 800f
            val viewH = if (height > 0) height.toFloat() else 1000f
            stampScale = (viewW * 0.42f / bmp.width.coerceAtLeast(1)).coerceIn(0.25f, 1.2f)
            stampX = (viewW - bmp.width * stampScale) / 2f
            stampY = (viewH - bmp.height * stampScale) / 2f
            invalidate()
        }

        fun adjustScale(delta: Float) {
            stampScale = (stampScale + delta).coerceIn(0.2f, 2.0f)
            invalidate()
        }

        fun clearStamp() {
            activeStamp = null
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val bmp = activeStamp ?: return
            val dest = RectF(stampX, stampY, stampX + bmp.width * stampScale, stampY + bmp.height * stampScale)
            canvas.drawBitmap(bmp, null, dest, null)
            canvas.drawRect(dest, borderPaint)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            val bmp = activeStamp ?: return false
            val w = bmp.width * stampScale
            val h = bmp.height * stampScale
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    if (event.x >= stampX - 30f && event.x <= stampX + w + 30f &&
                        event.y >= stampY - 30f && event.y <= stampY + h + 30f) {
                        isDragging = true
                        lastTouchX = event.x
                        lastTouchY = event.y
                        return true
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    if (isDragging) {
                        stampX += event.x - lastTouchX
                        stampY += event.y - lastTouchY
                        lastTouchX = event.x
                        lastTouchY = event.y
                        invalidate()
                        return true
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> isDragging = false
            }
            return false
        }
    }

    private class SignatureDrawView(context: Context) : View(context) {
        private val signPath = Path()
        private var hasStrokes = false
        private val signPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(15, 45, 150)
            style = Paint.Style.STROKE
            strokeWidth = 9f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        init { setBackgroundColor(0xFFF8FAFC.toInt()) }
        override fun onDraw(canvas: Canvas) { super.onDraw(canvas); canvas.drawPath(signPath, signPaint) }
        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> { signPath.moveTo(event.x, event.y); hasStrokes = true; invalidate(); return true }
                MotionEvent.ACTION_MOVE -> { signPath.lineTo(event.x, event.y); invalidate(); return true }
            }
            return true
        }
        fun exportSignatureBitmap(): Bitmap? {
            if (!hasStrokes || width <= 0 || height <= 0) return null
            val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            Canvas(bmp).drawPath(signPath, signPaint)
            return bmp
        }
    }
}
