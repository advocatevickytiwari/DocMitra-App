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
    private var tempCamFile: File? = null

    private lateinit var ivTargetDoc: ImageView
    private lateinit var stampOverlay: MovableStampView
    private lateinit var stampSizeBar: LinearLayout
    private lateinit var etDocName: EditText
    private lateinit var tvCompressStatus: TextView

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
            val bmp = decodeScaledFile(f, 1800)
            f.delete()
            if (bmp != null) setLoadedBitmap(bmp)
        }
    }

    private val galleryImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null && uri.scheme == "content") {
            decodeScaledUri(uri, 1800)?.let { setLoadedBitmap(it) }
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

        val btnPickDoc = MaterialButton(this).apply {
            text = "+ Open File"
            textSize = 12f
            isAllCaps = false
            maxLines = 1
            insetTop = 0
            insetBottom = 0
            setPadding(dp(12), 0, dp(12), 0)
            setTypeface(null, Typeface.BOLD)
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xFFEA580C.toInt())
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

        // Stamp Scale & Fix Bar (Zero Clipping)
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

        // Bottom Studio Controls (Wide, non-clipped buttons)
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
            val cleanName = etDocName.text.toString().trim()
                .replace("[^a-zA-Z0-9\\u0900-\\u097F_\\- ]".toRegex(), "")
                .replace("..", "")
                .trim()
                .take(50)
                .ifEmpty { "Signed_${SimpleDateFormat("ddMM_HHmm", Locale.getDefault()).format(Date())}" }

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

    private fun buildCompressModeUi(root: LinearLayout, dp: (Int) -> Int) {
        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(20))
        }

        ivTargetDoc = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(280)).apply {
                bottomMargin = dp(10)
            }
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(0xFFFFFFFF.toInt())
        }
        body.addView(ivTargetDoc)

        tvCompressStatus = TextView(this).apply {
            text = "Tap '+ Open File' at top to select photo"
            textSize = 13f
            setTextColor(0xFF14532D.toInt())
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 0, dp(8))
        }
        body.addView(tvCompressStatus)

        val sbQuality = SeekBar(this).apply {
            max = 95
            progress = 60
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(12)
            }
        }
        body.addView(sbQuality)

        val btnSaveCompressed = makeCleanButton("Save Compressed Photo to Gallery", 0xFF14532D.toInt(), 0xFFFFFFFF.toInt(), 14f, dp).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48))
            setOnClickListener {
                val bmp = baseBitmap ?: return@setOnClickListener
                val q = sbQuality.progress.coerceIn(15, 95)
                val baos = ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.JPEG, q, baos)
                val kb = baos.size() / 1024
                val name = "${etDocName.text.toString().trim().ifEmpty { "Compressed" }}_${kb}KB.jpg"
                val compressedBmp = BitmapFactory.decodeByteArray(baos.toByteArray(), 0, baos.size())
                saveToGallery(compressedBmp, name)
                Toast.makeText(this@PhotoStudioActivity, "Saved ($kb KB) to Gallery!", Toast.LENGTH_LONG).show()
            }
        }
        body.addView(btnSaveCompressed)
        scroll.addView(body)
        root.addView(scroll)
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
            text
