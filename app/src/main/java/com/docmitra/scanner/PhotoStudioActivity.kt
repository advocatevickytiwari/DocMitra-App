package com.docmitra.scanner

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.google.android.material.button.MaterialButton
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min

class PhotoStudioActivity : AppCompatActivity() {

    private var mode = "STAMP_SIGN"
    private var baseBitmap: Bitmap? = null
    private var originalSizeKb: Int = 0
    private var compressedBytes: ByteArray? = null
    private var tempCamFile: File? = null

    private lateinit var ivTargetDoc: ImageView
    private lateinit var stampOverlay: MovableStampView
    private lateinit var stampSizeBar: LinearLayout
    private lateinit var etDocName: EditText
    private var tvCompressStatus: TextView? = null
    private var sbQualityRef: SeekBar? = null

    private val cameraPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) openDirectCamera()
        else Toast.makeText(this, "Camera permission required", Toast.LENGTH_SHORT).show()
    }

    private val cameraCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { ok ->
        val f = tempCamFile
        if (ok && f != null && f.exists() && f.length() > 0) {
            val origKb = (f.length() / 1024L).toInt().coerceAtLeast(1)
            val bmp = decodeScaledFile(f, 1800)
            f.delete()
            if (bmp != null) {
                originalSizeKb = origKb
                setLoadedBitmap(bmp)
            }
        }
    }

    private val galleryImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null && uri.scheme == "content") {
            val origBytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
            val origKb = ((origBytes?.size ?: 0) / 1024).coerceAtLeast(1)
            decodeScaledUri(uri, 1800)?.let {
                originalSizeKb = origKb
                setLoadedBitmap(it)
            }
        }
    }

    private val pdfFirstPageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null && uri.scheme == "content") {
            renderPdfPageForStamping(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )

        mode = intent.getStringExtra("MODE") ?: "STAMP_SIGN"

        val density = resources.displayMetrics.density
        val dp = { v: Int -> (v * density).toInt() }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(if (mode == "COMPRESS") 0xFFF1F5F9.toInt() else 0xFF0F172A.toInt())
        }

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

        val defaultTitle = if (mode == "COMPRESS") {
            "Photo_${SimpleDateFormat("ddMM_HHmm", Locale.getDefault()).format(Date())}"
        } else {
            "Signed_${SimpleDateFormat("ddMM_HHmm", Locale.getDefault()).format(Date())}"
        }

        etDocName = EditText(this).apply {
            setText(defaultTitle)
            hint = "Enter file name"
            textSize = 14f
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFFCBD5E1.toInt())
            setBackgroundColor(0x22FFFFFF)
            setPadding(dp(10), dp(7), dp(10), dp(7))
            isSingleLine = true
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(8)
            }
        }

        val btnPickDoc = makeCleanButton("+ Select File", 0xFFEA580C.toInt(), 0xFFFFFFFF.toInt(), 12f, dp).apply {
            layoutParams = LinearLayout.LayoutParams(dp(104), dp(38))
            setOnClickListener { showDocumentSourceDialog() }
        }

        topBar.addView(btnBack)
        topBar.addView(etDocName)
        topBar.addView(btnPickDoc)
        root.addView(topBar)

        if (mode == "COMPRESS") {
            buildCompressModeUi(root, dp)
        } else {
            buildStampAndSignStudioUi(root, dp)
        }

        setContentView(root)
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

    // ==================== 1. SIGNATURE & CUSTOM STAMP STUDIO ====================

    private fun buildStampAndSignStudioUi(root: LinearLayout, dp: (Int) -> Int) {
        val stageFrame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            ).apply { setMargins(dp(10), dp(8), dp(10), dp(8)) }
            setBackgroundColor(0xFF1E293B.toInt())
        }

        ivTargetDoc = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            scaleType = ImageView.ScaleType.FIT_CENTER
        }

        stampOverlay = MovableStampView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        stageFrame.addView(ivTargetDoc)
        stageFrame.addView(stampOverlay)
        root.addView(stageFrame)

        stampSizeBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xFF1E293B.toInt())
            setPadding(dp(8), dp(6), dp(8), dp(6))
            visibility = View.GONE
        }

        val btnSmaller = makeCleanButton("Size -", 0xFF334155.toInt(), 0xFFFFFFFF.toInt(), 11.5f, dp).apply {
            layoutParams = LinearLayout.LayoutParams(dp(64), dp(38))
            setOnClickListener { stampOverlay.changeScale(-0.15f) }
        }

        val btnBigger = makeCleanButton("Size +", 0xFF334155.toInt(), 0xFFFFFFFF.toInt(), 11.5f, dp).apply {
            layoutParams = LinearLayout.LayoutParams(dp(64), dp(38)).apply { marginStart = dp(6) }
            setOnClickListener { stampOverlay.changeScale(0.15f) }
        }

        val btnRemoveStamp = makeCleanButton("Remove", 0xFF450A0A.toInt(), 0xFFFCA5A5.toInt(), 11.5f, dp).apply {
            layoutParams = LinearLayout.LayoutParams(dp(72), dp(38)).apply { marginStart = dp(6) }
            setOnClickListener {
                stampOverlay.clear()
                stampSizeBar.visibility = View.GONE
            }
        }

        val btnFixOnPaper = makeCleanButton("✓ Fix on Paper", 0xFFEA580C.toInt(), 0xFFFFFFFF.toInt(), 12f, dp).apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(38), 1f).apply { marginStart = dp(6) }
            setOnClickListener { fixStampOntoPaper() }
        }

        stampSizeBar.addView(btnSmaller)
        stampSizeBar.addView(btnBigger)
        stampSizeBar.addView(btnRemoveStamp)
        stampSizeBar.addView(btnFixOnPaper)
        root.addView(stampSizeBar)

        val bottomPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFFFFFFF.toInt())
            setPadding(dp(10), dp(10), dp(10), dp(12))
        }

        val toolsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        }

        val btnCustomMohar = makeCleanButton("🔏 Mohar", 0xFF14532D.toInt(), 0xFFFFFFFF.toInt(), 12f, dp).apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(5) }
            setOnClickListener { showMoharMenu() }
        }

        val btnDigitalSign = makeCleanButton("✍️ Signature", 0xFF1E3A8A.toInt(), 0xFFFFFFFF.toInt(), 12f, dp).apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(44), 1.15f).apply { marginEnd = dp(5) }
            setOnClickListener { showSignatureMenu() }
        }

        val btnSelfAttested = makeCleanButton("📋 Self Attest", 0xFFE2E8F0.toInt(), 0xFF0F172A.toInt(), 11.5f, dp).apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(44), 1.05f)
            setOnClickListener {
                val dateStr = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date())
                val bmp = createBoxStamp("SELF ATTESTED", "Date: $dateStr", Color.rgb(18, 56, 168))
                placeStamp(bmp)
            }
        }

        toolsRow.addView(btnCustomMohar)
        toolsRow.addView(btnDigitalSign)
        toolsRow.addView(btnSelfAttested)
        bottomPanel.addView(toolsRow)

        val saveRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val btnSavePaper = makeCleanButton("✓ Save (App + Gallery)", 0xFF14532D.toInt(), 0xFFFFFFFF.toInt(), 12.5f, dp).apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(46), 1.25f).apply { marginEnd = dp(8) }
            setOnClickListener { saveStampedDocument(sharePdf = false) }
        }

        val btnSharePdf = makeCleanButton("Share PDF", 0xFFEA580C.toInt(), 0xFFFFFFFF.toInt(), 13f, dp).apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(46), 0.95f)
            setOnClickListener { saveStampedDocument(sharePdf = true) }
        }

        saveRow.addView(btnSavePaper)
        saveRow.addView(btnSharePdf)
        bottomPanel.addView(saveRow)

        root.addView(bottomPanel)

        val blankSheet = Bitmap.createBitmap(1240, 1754, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
        }
        setLoadedBitmap(blankSheet)
    }

    // ==================== 2. PHOTO & IMAGE COMPRESS (KB) STUDIO ====================

    private fun buildCompressModeUi(root: LinearLayout, dp: (Int) -> Int) {
        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(20))
        }

        val btnSelectBig = makeCleanButton("📷  Select Photo (Camera / Gallery)", 0xFFEA580C.toInt(), 0xFFFFFFFF.toInt(), 13.5f, dp).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(46)).apply {
                bottomMargin = dp(10)
            }
            setOnClickListener { showDocumentSourceDialog() }
        }
        body.addView(btnSelectBig)

        ivTargetDoc = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(230)).apply {
                bottomMargin = dp(10)
            }
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(0xFFFFFFFF.toInt())
            setImageResource(android.R.drawable.ic_menu_gallery)
        }
        body.addView(ivTargetDoc)

        val statusTv = TextView(this).apply {
            text = "Step 1: Select a photo above to compress"
            textSize = 13.5f
            setTextColor(0xFF14532D.toInt())
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setBackgroundColor(0xFFDCFCE7.toInt())
            setPadding(dp(10), dp(10), dp(10), dp(10))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(10)
            }
        }
        tvCompressStatus = statusTv
        body.addView(statusTv)

        // Quick 1-Tap Target Size Buttons (20KB, 50KB, 100KB, 200KB)
        val presetRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(10)
            }
        }

        val targets = listOf(20, 50, 100, 200)
        targets.forEachIndexed { idx, targetKb ->
            val b = makeCleanButton("${targetKb} KB", 0xFF334155.toInt(), 0xFFFFFFFF.toInt(), 11.5f, dp).apply {
                layoutParams = LinearLayout.LayoutParams(0, dp(38), 1f).apply {
                    if (idx < targets.lastIndex) marginEnd = dp(5)
                }
                setOnClickListener { compressToExactTargetKb(targetKb) }
            }
            presetRow.addView(b)
        }
        body.addView(presetRow)

        val sliderLabel = TextView(this).apply {
            text = "Manual Quality Slider (Drag Left for Smaller KB):"
            textSize = 12f
            setTextColor(0xFF475569.toInt())
            setPadding(0, 0, 0, dp(4))
        }
        body.addView(sliderLabel)

        val sbQuality = SeekBar(this).apply {
            max = 95
            progress = 55
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(14)
            }
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (baseBitmap != null) {
                        recalculateCompressedPreview(progress.coerceIn(10, 95))
                    }
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        sbQualityRef = sbQuality
        body.addView(sbQuality)

        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val btnSaveCompressed = makeCleanButton("✓ Save Photo", 0xFF14532D.toInt(), 0xFFFFFFFF.toInt(), 13f, dp).apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(8) }
            setOnClickListener {
                val bytes = compressedBytes
                if (bytes == null || baseBitmap == null) {
                    Toast.makeText(this@PhotoStudioActivity, "Please select a photo first", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val kb = (bytes.size / 1024).coerceAtLeast(1)
                val name = "${sanitizeSimple(etDocName.text.toString(), "Compressed")}_${kb}KB.jpg"
                saveRawJpegBytesToGallery(bytes, name)
                Toast.makeText(this@PhotoStudioActivity, "Saved ($kb KB) to Gallery!", Toast.LENGTH_LONG).show()
            }
        }

        val btnShareCompressed = makeCleanButton("Share Photo", 0xFFEA580C.toInt(), 0xFFFFFFFF.toInt(), 13f, dp).apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f)
            setOnClickListener {
                val bytes = compressedBytes
                if (bytes == null || baseBitmap == null) {
                    Toast.makeText(this@PhotoStudioActivity, "Please select a photo first", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val kb = (bytes.size / 1024).coerceAtLeast(1)
                val name = "${sanitizeSimple(etDocName.text.toString(), "Compressed")}_${kb}KB.jpg"
                val cacheImg = File(cacheDir, name)
                FileOutputStream(cacheImg).use { it.write(bytes) }
                val uri = FileProvider.getUriForFile(this@PhotoStudioActivity, "${packageName}.fileprovider", cacheImg)
                val share = Intent(Intent.ACTION_SEND).apply {
                    type = "image/jpeg"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(share, "Share Compressed Image"))
            }
        }

        actionRow.addView(btnSaveCompressed)
        actionRow.addView(btnShareCompressed)
        body.addView(actionRow)

        scroll.addView(body)
        root.addView(scroll)
    }

    private fun recalculateCompressedPreview(quality: Int) {
        val bmp = baseBitmap ?: return
        val baos = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, quality, baos)
        val bytes = baos.toByteArray()
        compressedBytes = bytes
        val newKb = (bytes.size / 1024).coerceAtLeast(1)
        tvCompressStatus?.text = "Original: ${originalSizeKb} KB  →  New Size: ${newKb} KB (Quality $quality%)"
    }

    private fun compressToExactTargetKb(targetKb: Int) {
        val bmp = baseBitmap ?: run {
            Toast.makeText(this, "Please select a photo first", Toast.LENGTH_SHORT).show()
            return
        }
        var working = bmp
        var bestBytes: ByteArray? = null
        var bestQuality = 50

        // Try quality steps first, then downscale if image resolution is huge
        for (scaleStep in 0..3) {
            for (q in 90 downTo 15 step 5) {
                val baos = ByteArrayOutputStream()
                working.compress(Bitmap.CompressFormat.JPEG, q, baos)
                val arr = baos.toByteArray()
                bestBytes = arr
                bestQuality = q
                if (arr.size / 1024 <= targetKb) {
                    compressedBytes = arr
                    sbQualityRef?.progress = bestQuality
                    val finalKb = (arr.size / 1024).coerceAtLeast(1)
                    tvCompressStatus?.text = "Original: ${originalSizeKb} KB  →  New Size: ${finalKb} KB (Target ≤ ${targetKb} KB)"
                    ivTargetDoc.setImageBitmap(BitmapFactory.decodeByteArray(arr, 0, arr.size))
                    return
                }
            }
            val nextW = (working.width * 0.72f).toInt().coerceAtLeast(240)
            val nextH = (working.height * 0.72f).toInt().coerceAtLeast(240)
            working = Bitmap.createScaledBitmap(working, nextW, nextH, true)
        }

        bestBytes?.let { arr ->
            compressedBytes = arr
            val finalKb = (arr.size / 1024).coerceAtLeast(1)
            tvCompressStatus?.text = "Original: ${originalSizeKb} KB  →  New Size: ${finalKb} KB"
        }
    }

    private fun saveRawJpegBytesToGallery(bytes: ByteArray, fileName: String) {
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
        } catch (_: Exception) {}
    }

    // ==================== SHARED DIALOGS & HELPERS ====================

    private fun showCleanOptionSheet(title: String, options: List<String>, onSelect: (Int) -> Unit) {
        val density = resources.displayMetrics.density
        val dp = { v: Int -> (v * density).toInt() }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(16))
        }

        var dialog: AlertDialog? = null
        options.forEachIndexed { idx, label ->
            val btn = MaterialButton(this).apply {
                text = label
                textSize = 14f
                isAllCaps = false
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setTextColor(0xFF0F172A.toInt())
                setBackgroundColor(0xFFF1F5F9.toInt())
                setPadding(dp(14), dp(12), dp(14), dp(12))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(8) }
                setOnClickListener {
                    dialog?.dismiss()
                    onSelect(idx)
                }
            }
            container.addView(btn)
        }

        dialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setView(container)
            .setNegativeButton("Cancel", null)
            .create()
        dialog.show()
    }

    private fun showDocumentSourceDialog() {
        val items = if (mode == "COMPRESS") {
            listOf("📷  Take Photo with Camera", "🖼  Pick Photo from Gallery")
        } else {
            listOf("📷  Take Photo with Camera", "🖼  Pick Photo from Gallery", "📄  Pick PDF Page to Stamp")
        }
        showCleanOptionSheet("Select Document", items) { which ->
            when (which) {
                0 -> {
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                        openDirectCamera()
                    } else {
                        cameraPermLauncher.launch(Manifest.permission.CAMERA)
                    }
                }
                1 -> galleryImageLauncher.launch("image/*")
                2 -> pdfFirstPageLauncher.launch("application/pdf")
            }
        }
    }

    private fun openDirectCamera() {
        try {
            val f = File(cacheDir, "studio_cam_${System.currentTimeMillis()}.jpg")
            tempCamFile = f
            val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", f)
            cameraCaptureLauncher.launch(uri)
        } catch (e: Exception) {
            Toast.makeText(this, "Camera error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun renderPdfPageForStamping(uri: Uri) {
        try {
            val pfd = contentResolver.openFileDescriptor(uri, "r") ?: return
            val renderer = PdfRenderer(pfd)
            if (renderer.pageCount > 0) {
                val page = renderer.openPage(0)
                val bmp = Bitmap.createBitmap(page.width * 2, page.height * 2, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(Color.WHITE)
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                page.close()
                setLoadedBitmap(bmp)
            }
            renderer.close()
            pfd.close()
        } catch (e: Exception) {
            Toast.makeText(this, "PDF load error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setLoadedBitmap(bmp: Bitmap) {
        baseBitmap = bmp
        ivTargetDoc.setImageBitmap(bmp)
        if (mode == "COMPRESS") {
            val q = sbQualityRef?.progress?.coerceIn(10, 95) ?: 55
            recalculateCompressedPreview(q)
        }
    }

    private fun getStampsDir(): File {
        val dir = File(filesDir, "DocMitra/Stamps")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun showMoharMenu() {
        val savedMohar = File(getStampsDir(), "saved_mohar.png")
        val opts = ArrayList<String>()
        if (savedMohar.exists()) opts.add("📌  Use Saved Mohar")
        opts.add("🔏  Create New Round / Box Mohar")
        opts.add("⚖️  'CERTIFIED TRUE COPY' Stamp")

        showCleanOptionSheet("Mohar / Office Seal", opts) { which ->
            val sel = opts[which]
            when {
                sel.contains("Use Saved") -> {
                    BitmapFactory.decodeFile(savedMohar.absolutePath)?.let { placeStamp(it) }
                }
                sel.contains("Create New") -> promptNewMohar(savedMohar)
                sel.contains("CERTIFIED") -> {
                    val dateStr = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date())
                    placeStamp(createBoxStamp("CERTIFIED TRUE COPY", "Verified • $dateStr", Color.rgb(185, 28, 28)))
                }
            }
        }
    }

    private fun promptNewMohar(saveFile: File) {
        val density = resources.displayMetrics.density
        val dp = { v: Int -> (v * density).toInt() }

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(8))
        }
        val et1 = EditText(this).apply {
            hint = "Top Line (e.g. ADV. VICKY TIWARI)"
            isSingleLine = true
        }
        val et2 = EditText(this).apply {
            hint = "Center Title (e.g. ADVOCATE)"
            isSingleLine = true
        }
        val et3 = EditText(this).apply {
            hint = "Bottom Line (e.g. HIGH COURT LUCKNOW)"
            isSingleLine = true
        }
        box.addView(et1)
        box.addView(et2)
        box.addView(et3)

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, 0)
        }

        var dlg: AlertDialog? = null

        val btnRound = makeCleanButton("Round Mohar", 0xFF1E3A8A.toInt(), 0xFFFFFFFF.toInt(), 12.5f, dp).apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginEnd = dp(6) }
            setOnClickListener {
                val bmp = createRoundMohar(
                    et1.text.toString().trim().ifEmpty { "AUTHORIZED SEAL" },
                    et2.text.toString().trim().ifEmpty { "VERIFIED" },
                    et3.text.toString().trim().ifEmpty { "INDIA" },
                    Color.rgb(20, 62, 175)
                )
                FileOutputStream(saveFile).use { out -> bmp.compress(Bitmap.CompressFormat.PNG, 100, out) }
                dlg?.dismiss()
                placeStamp(bmp)
            }
        }

        val btnBox = makeCleanButton("Box Mohar", 0xFF14532D.toInt(), 0xFFFFFFFF.toInt(), 12.5f, dp).apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(42), 1f)
            setOnClickListener {
                val sub = listOf(et2.text.toString().trim(), et3.text.toString().trim())
                    .filter { it.isNotEmpty() }.joinToString(" | ")
                val bmp = createBoxStamp(
                    et1.text.toString().trim().ifEmpty { "AUTHORIZED SEAL" },
                    sub,
                    Color.rgb(20, 62, 175)
                )
                FileOutputStream(saveFile).use { out -> bmp.compress(Bitmap.CompressFormat.PNG, 100, out) }
                dlg?.dismiss()
                placeStamp(bmp)
            }
        }

        btnRow.addView(btnRound)
        btnRow.addView(btnBox)
        box.addView(btnRow)

        dlg = AlertDialog.Builder(this)
            .setTitle("Create Custom Mohar")
            .setView(box)
            .setNegativeButton("Cancel", null)
            .create()
        dlg.show()
    }

    private fun showSignatureMenu() {
        val savedSign = File(getStampsDir(), "saved_sign.png")
        val opts = ArrayList<String>()
        if (savedSign.exists()) opts.add("🖊️  Use Saved Signature")
        opts.add("✍️  Draw New Signature")

        showCleanOptionSheet("Digital Signature", opts) { which ->
            if (opts[which].contains("Use Saved")) {
                BitmapFactory.decodeFile(savedSign.absolutePath)?.let { placeStamp(it) }
            } else {
                promptDrawSign(savedSign)
            }
        }
    }

    private fun promptDrawSign(saveFile: File) {
        val density = resources.displayMetrics.density
        val dp = { v: Int -> (v * density).toInt() }

        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(8))
        }

        val pad = FingerSignPad(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(190)
            )
        }

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, 0)
        }

        var dlg: AlertDialog? = null

        val btnClear = makeCleanButton("Clear Pad", 0xFFE2E8F0.toInt(), 0xFF0F172A.toInt(), 12.5f, dp).apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginEnd = dp(6) }
            setOnClickListener { pad.clearPad() }
        }

        val btnApply = makeCleanButton("✓ Save & Apply", 0xFF14532D.toInt(), 0xFFFFFFFF.toInt(), 12.5f, dp).apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(42), 1.3f)
            setOnClickListener {
                val bmp = pad.exportSignBitmap()
                if (bmp != null) {
                    FileOutputStream(saveFile).use { out -> bmp.compress(Bitmap.CompressFormat.PNG, 100, out) }
                    dlg?.dismiss()
                    placeStamp(bmp)
                } else {
                    Toast.makeText(this@PhotoStudioActivity, "Please sign on pad first", Toast.LENGTH_SHORT).show()
                }
            }
        }

        btnRow.addView(btnClear)
        btnRow.addView(btnApply)
        wrapper.addView(pad)
        wrapper.addView(btnRow)

        dlg = AlertDialog.Builder(this)
            .setTitle("Sign with Finger")
            .setView(wrapper)
            .setNegativeButton("Cancel", null)
            .create()
        dlg.show()
    }

    private fun placeStamp(bmp: Bitmap) {
        stampOverlay.applyStamp(bmp)
        stampSizeBar.visibility = View.VISIBLE
        Toast.makeText(this, "Drag Mohar/Sign to position, then tap '✓ Fix on Paper'", Toast.LENGTH_SHORT).show()
    }

    private fun fixStampOntoPaper() {
        val stamp = stampOverlay.currentStamp ?: return
        val doc = baseBitmap ?: return

        val viewW = ivTargetDoc.width.toFloat()
        val viewH = ivTargetDoc.height.toFloat()
        if (viewW <= 0f || viewH <= 0f) return

        val scale = min(viewW / doc.width.toFloat(), viewH / doc.height.toFloat())
        val dispW = doc.width * scale
        val dispH = doc.height * scale
        val offX = (viewW - dispW) / 2f
        val offY = (viewH - dispH) / 2f

        val left = (stampOverlay.posX - offX) / scale
        val top = (stampOverlay.posY - offY) / scale
        val w = (stamp.width * stampOverlay.scaleVal) / scale
        val h = (stamp.height * stampOverlay.scaleVal) / scale

        val merged = doc.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(merged)
        canvas.drawBitmap(stamp, null, RectF(left, top, left + w, top + h), Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))

        setLoadedBitmap(merged)
        stampOverlay.clear()
        stampSizeBar.visibility = View.GONE
        Toast.makeText(this, "Fixed onto paper!", Toast.LENGTH_SHORT).show()
    }

    private fun saveStampedDocument(sharePdf: Boolean) {
        if (stampOverlay.currentStamp != null) fixStampOntoPaper()
        val finalBmp = baseBitmap ?: return

        try {
            val cleanName = sanitizeSimple(
                etDocName.text.toString(),
                "Signed_${SimpleDateFormat("ddMM_HHmm", Locale.getDefault()).format(Date())}"
            )

            val projDir = File(filesDir, "DocMitra/Projects/$cleanName")
            if (!projDir.exists()) projDir.mkdirs()
            val pageFile = File(projDir, "page_0001.jpg")
            FileOutputStream(pageFile).use { out -> finalBmp.compress(Bitmap.CompressFormat.JPEG, 95, out) }
            projDir.setLastModified(System.currentTimeMillis())

            saveToGallery(finalBmp, "$cleanName.jpg")

            if (sharePdf) {
                val pdfFile = File(cacheDir, "$cleanName.pdf")
                val pdfDoc = PdfDocument()
                val info = PdfDocument.PageInfo.Builder(finalBmp.width, finalBmp.height, 1).create()
                val p = pdfDoc.startPage(info)
                p.canvas.drawBitmap(finalBmp, 0f, 0f, null)
                pdfDoc.finishPage(p)
                FileOutputStream(pdfFile).use { out -> pdfDoc.writeTo(out) }
                pdfDoc.close()

                val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", pdfFile)
                val share = Intent(Intent.ACTION_SEND).apply {
                    type = "application/pdf"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(share, "Share Signed PDF"))
            } else {
                Toast.makeText(this, "Saved '$cleanName' to App & Gallery!", Toast.LENGTH_LONG).show()
                finish()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Save error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun sanitizeSimple(raw: String, fallback: String): String {
        return raw.trim()
            .replace("[^a-zA-Z0-9\\u0900-\\u097F_\\- ]".toRegex(), "")
            .replace("..", "")
            .trim()
            .take(50)
            .ifEmpty { fallback }
    }

    private fun createBoxStamp(title: String, sub: String, ink: Int): Bitmap {
        val w = 560
        val h = 180
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val pBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ink
            style = Paint.Style.STROKE
            strokeWidth = 7f
        }
        c.drawRoundRect(RectF(8f, 8f, w - 8f, h - 8f), 14f, 14f, pBorder)

        val titleSize = if (title.length > 20) 27f else 34f
        val pTitle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ink
            textSize = titleSize
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val subSize = if (sub.length > 28) 18f else 22f
        val pSub = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ink
            textSize = subSize
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        if (sub.isEmpty()) c.drawText(title, w / 2f, h / 2f + 12f, pTitle)
        else {
            c.drawText(title, w / 2f, 76f, pTitle)
            c.drawText(sub, w / 2f, 128f, pSub)
        }
        return bmp
    }

    private fun createRoundMohar(topTxt: String, midTxt: String, botTxt: String, ink: Int): Bitmap {
        val size = 420
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val cx = size / 2f
        val cy = size / 2f
        val pOuter = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; style = Paint.Style.STROKE; strokeWidth = 8f }
        val pInner = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; style = Paint.Style.STROKE; strokeWidth = 3.5f }
        c.drawCircle(cx, cy, 192f, pOuter)
        c.drawCircle(cx, cy, 178f, pInner)
        c.drawCircle(cx, cy, 118f, pInner)

        val topSize = if (topTxt.length > 18) 20f else 24f
        val pArcTop = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ink
            textSize = topSize
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val topArc = Path().apply { addArc(RectF(cx - 146f, cy - 146f, cx + 146f, cy + 146f), 190f, 160f) }
        c.drawTextOnPath(topTxt.uppercase(Locale.getDefault()), topArc, 0f, 0f, pArcTop)

        val botSize = if (botTxt.length > 18) 19f else 23f
        val pArcBot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ink
            textSize = botSize
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val botArc = Path().apply { addArc(RectF(cx - 152f, cy - 152f, cx + 152f, cy + 152f), 165f, -150f) }
        c.drawTextOnPath(botTxt.uppercase(Locale.getDefault()), botArc, 0f, 0f, pArcBot)

        val midSize = if (midTxt.length > 12) 22f else 26f
        val pMid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ink
            textSize = midSize
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        c.drawText("★ $midTxt ★", cx, cy + 9f, pMid)
        return bmp
    }

    private fun decodeScaledFile(file: File, maxDim: Int): Bitmap? {
        return try {
            val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, b)
            var s = 1
            while (b.outWidth / s > maxDim || b.outHeight / s > maxDim) s *= 2
            BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = s })
        } catch (_: Exception) { null }
    }

    private fun decodeScaledUri(uri: Uri, maxDim: Int): Bitmap? {
        return try {
            val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, b) }
            var s = 1
            while (b.outWidth / s > maxDim || b.outHeight / s > maxDim) s *= 2
            contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = s })
            }
        } catch (_: Exception) { null }
    }

    private fun saveToGallery(bmp: Bitmap, name: String) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/DocMitra")
                }
                val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                uri?.let { contentResolver.openOutputStream(it)?.use { out -> bmp.compress(Bitmap.CompressFormat.JPEG, 95, out) } }
            } else {
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "DocMitra")
                if (!dir.exists()) dir.mkdirs()
                FileOutputStream(File(dir, name)).use { out -> bmp.compress(Bitmap.CompressFormat.JPEG, 95, out) }
            }
        } catch (_: Exception) {}
    }

    private class MovableStampView(context: Context) : View(context) {
        var currentStamp: Bitmap? = null
        var posX = 120f
        var posY = 260f
        var scaleVal = 0.65f
        private var lastX = 0f
        private var lastY = 0f
        private var dragging = false
        private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFEA580C.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }

        fun applyStamp(bmp: Bitmap) {
            currentStamp = bmp
            val vw = if (width > 0) width.toFloat() else 800f
            val vh = if (height > 0) height.toFloat() else 1000f
            scaleVal = (vw * 0.42f / bmp.width.coerceAtLeast(1)).coerceIn(0.25f, 1.2f)
            posX = (vw - bmp.width * scaleVal) / 2f
            posY = (vh - bmp.height * scaleVal) / 2f
            invalidate()
        }

        fun changeScale(delta: Float) {
            scaleVal = (scaleVal + delta).coerceIn(0.2f, 2.0f)
            invalidate()
        }

        fun clear() {
            currentStamp = null
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val bmp = currentStamp ?: return
            val dest = RectF(posX, posY, posX + bmp.width * scaleVal, posY + bmp.height * scaleVal)
            canvas.drawBitmap(bmp, null, dest, null)
            canvas.drawRect(dest, boxPaint)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            val bmp = currentStamp ?: return false
            val w = bmp.width * scaleVal
            val h = bmp.height * scaleVal
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    if (event.x >= posX - 30f && event.x <= posX + w + 30f &&
                        event.y >= posY - 30f && event.y <= posY + h + 30f) {
                        dragging = true
                        lastX = event.x
                        lastY = event.y
                        return true
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    if (dragging) {
                        posX += event.x - lastX
                        posY += event.y - lastY
                        lastX = event.x
                        lastY = event.y
                        invalidate()
                        return true
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragging = false
            }
            return false
        }
    }

    private class FingerSignPad(context: Context) : View(context) {
        private val signPath = Path()
        private var drawn = false
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(15, 45, 150)
            style = Paint.Style.STROKE
            strokeWidth = 9f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        init { setBackgroundColor(0xFFF8FAFC.toInt()) }
        fun clearPad() {
            signPath.reset()
            drawn = false
            invalidate()
        }
        override fun onDraw(canvas: Canvas) { super.onDraw(canvas); canvas.drawPath(signPath, paint) }
        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> { signPath.moveTo(event.x, event.y); drawn = true; invalidate(); return true }
                MotionEvent.ACTION_MOVE -> { signPath.lineTo(event.x, event.y); invalidate(); return true }
            }
            return true
        }
        fun exportSignBitmap(): Bitmap? {
            if (!drawn || width <= 0 || height <= 0) return null
            val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            Canvas(bmp).drawPath(signPath, paint)
            return bmp
        }
    }
}
