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
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
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

        // Hardware-level Safe Area Insets
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, windowInsets ->
            val insets = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(insets.left, insets.top, insets.right, insets.bottom)
            WindowInsetsCompat.CONSUMED
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
            setPadding(dp(10), dp(6), dp(10), dp(6))
            isSingleLine = true
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(8)
            }
        }

        val btnPickDoc = MaterialButton(this).apply {
            text = "+ Open File"
            textSize = 12f
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
            setPadding(dp(10), dp(6), dp(10), dp(6))
            visibility = View.GONE
        }

        val btnSmaller = MaterialButton(this).apply {
            text = "Size -"
            textSize = 11.5f
            minWidth = 0
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xFF334155.toInt())
            setOnClickListener { stampOverlay.changeScale(-0.15f) }
        }

        val btnBigger = MaterialButton(this).apply {
            text = "Size +"
            textSize = 11.5f
            minWidth = 0
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xFF334155.toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(6) }
            setOnClickListener { stampOverlay.changeScale(0.15f) }
        }

        val btnRemoveStamp = MaterialButton(this).apply {
            text = "Remove"
            textSize = 11f
            minWidth = 0
            setTextColor(0xFFFCA5A5.toInt())
            setBackgroundColor(0x00000000)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(6) }
            setOnClickListener {
                stampOverlay.clear()
                stampSizeBar.visibility = View.GONE
            }
        }

        val btnFixOnPaper = MaterialButton(this).apply {
            text = "✓ Fix on Paper"
            textSize = 12f
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xFFEA580C.toInt())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(8)
            }
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
            setPadding(dp(12), dp(10), dp(12), dp(12))
        }

        val toolsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        }

        val btnCustomMohar = MaterialButton(this).apply {
            text = "🔏 Mohar (Seal)"
            textSize = 11.5f
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xFF14532D.toInt())
            layoutParams = LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginEnd = dp(5) }
            setOnClickListener { showMoharMenu() }
        }

        val btnDigitalSign = MaterialButton(this).apply {
            text = "✍️ Signature"
            textSize = 11.5f
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xFF1E3A8A.toInt())
            layoutParams = LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginEnd = dp(5) }
            setOnClickListener { showSignatureMenu() }
        }

        val btnSelfAttested = MaterialButton(this).apply {
            text = "📋 Self Attested"
            textSize = 11f
            setTextColor(0xFF0F172A.toInt())
            setBackgroundColor(0xFFE2E8F0.toInt())
            layoutParams = LinearLayout.LayoutParams(0, dp(42), 1f)
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

        val btnSavePaper = MaterialButton(this).apply {
            text = "✓ Save (App + Gallery)"
            textSize = 12.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xFF14532D.toInt())
            layoutParams = LinearLayout.LayoutParams(0, dp(46), 1.2f).apply { marginEnd = dp(8) }
            setOnClickListener { saveStampedDocument(sharePdf = false) }
        }

        val btnSharePdf = MaterialButton(this).apply {
            text = "Share PDF"
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xFFEA580C.toInt())
            layoutParams = LinearLayout.LayoutParams(0, dp(46), 1f)
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
        Toast.makeText(this, "Tap '+ Open File' to load photo/PDF, or create Mohar/Sign below!", Toast.LENGTH_LONG).show()
    }

    private fun showDocumentSourceDialog() {
        val items = if (mode == "COMPRESS") {
            arrayOf("📷 Take Photo with Camera", "🖼 Pick Photo from Gallery")
        } else {
            arrayOf("📷 Take Photo with Camera", "🖼 Pick Photo from Gallery", "📄 Pick PDF Page to Stamp")
        }
        AlertDialog.Builder(this)
            .setTitle("Select Document")
            .setItems(items) { _, which ->
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
            .show()
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
        if (savedMohar.exists()) opts.add("📌 Use Saved Mohar")
        opts.add("🔏 Create New Round / Box Mohar")
        opts.add("⚖️ 'CERTIFIED TRUE COPY' Stamp")

        AlertDialog.Builder(this)
            .setTitle("Mohar / Office Seal")
            .setItems(opts.toTypedArray()) { _, which ->
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
            .show()
    }

    private fun promptNewMohar(saveFile: File) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 24, 40, 10)
        }
        val et1 = EditText(this).apply { hint = "Top Text (e.g. ADV. VICKY TIWARI)" }
        val et2 = EditText(this).apply { hint = "Center Title (e.g. ADVOCATE)" }
        val et3 = EditText(this).apply { hint = "Bottom Text (e.g. HIGH COURT LUCKNOW)" }
        box.addView(et1)
        box.addView(et2)
        box.addView(et3)

        AlertDialog.Builder(this)
            .setTitle("Create Custom Mohar")
            .setView(box)
            .setPositiveButton("Round Mohar (Blue)") { _, _ ->
                val bmp = createRoundMohar(
                    et1.text.toString().trim().ifEmpty { "AUTHORIZED SEAL" },
                    et2.text.toString().trim().ifEmpty { "VERIFIED" },
                    et3.text.toString().trim().ifEmpty { "INDIA" },
                    Color.rgb(20, 62, 175)
                )
                FileOutputStream(saveFile).use { out -> bmp.compress(Bitmap.CompressFormat.PNG, 100, out) }
                placeStamp(bmp)
            }
            .setNeutralButton("Box Mohar (Blue)") { _, _ ->
                val sub = listOf(et2.text.toString().trim(), et3.text.toString().trim())
                    .filter { it.isNotEmpty() }.joinToString(" | ")
                val bmp = createBoxStamp(
                    et1.text.toString().trim().ifEmpty { "AUTHORIZED SEAL" },
                    sub,
                    Color.rgb(20, 62, 175)
                )
                FileOutputStream(saveFile).use { out -> bmp.compress(Bitmap.CompressFormat.PNG, 100, out) }
                placeStamp(bmp)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showSignatureMenu() {
        val savedSign = File(getStampsDir(), "saved_sign.png")
        val opts = ArrayList<String>()
        if (savedSign.exists()) opts.add("🖊️ Use Saved Signature")
        opts.add("✍️ Draw New Signature")

        AlertDialog.Builder(this)
            .setTitle("Digital Signature")
            .setItems(opts.toTypedArray()) { _, which ->
                if (opts[which].contains("Use Saved")) {
                    BitmapFactory.decodeFile(savedSign.absolutePath)?.let { placeStamp(it) }
                } else {
                    promptDrawSign(savedSign)
                }
            }
            .show()
    }

    private fun promptDrawSign(saveFile: File) {
        val pad = FingerSignPad(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (180 * resources.displayMetrics.density).toInt()
            )
        }
        AlertDialog.Builder(this)
            .setTitle("Sign with Finger")
            .setView(pad)
            .setPositiveButton("Save & Apply") { _, _ ->
                val bmp = pad.exportSignBitmap()
                if (bmp != null) {
                    FileOutputStream(saveFile).use { out -> bmp.compress(Bitmap.CompressFormat.PNG, 100, out) }
                    placeStamp(bmp)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
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

        val btnSaveCompressed = MaterialButton(this).apply {
            text = "Save Compressed Photo to Gallery"
            textSize = 14f
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xFF14532D.toInt())
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
        val w = 520
        val h = 170
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val pBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ink
            style = Paint.Style.STROKE
            strokeWidth = 7f
        }
        c.drawRoundRect(RectF(8f, 8f, w - 8f, h - 8f), 14f, 14f, pBorder)
        val pTitle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ink
            textSize = 35f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val pSub = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ink
            textSize = 22f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        if (sub.isEmpty()) c.drawText(title, w / 2f, h / 2f + 12f, pTitle)
        else {
            c.drawText(title, w / 2f, 74f, pTitle)
            c.drawText(sub, w / 2f, 124f, pSub)
        }
        return bmp
    }

    private fun createRoundMohar(topTxt: String, midTxt: String, botTxt: String, ink: Int): Bitmap {
        val size = 380
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val cx = size / 2f
        val cy = size / 2f
        val pOuter = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; style = Paint.Style.STROKE; strokeWidth = 8f }
        val pInner = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; style = Paint.Style.STROKE; strokeWidth = 3.5f }
        c.drawCircle(cx, cy, 174f, pOuter)
        c.drawCircle(cx, cy, 162f, pInner)
        c.drawCircle(cx, cy, 108f, pInner)

        val pArc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ink
            textSize = 24f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val topArc = Path().apply { addArc(RectF(cx - 132f, cy - 132f, cx + 132f, cy + 132f), 190f, 160f) }
        c.drawTextOnPath(topTxt.uppercase(Locale.getDefault()), topArc, 0f, 0f, pArc)

        val botArc = Path().apply { addArc(RectF(cx - 138f, cy - 138f, cx + 138f, cy + 138f), 165f, -150f) }
        c.drawTextOnPath(botTxt.uppercase(Locale.getDefault()), botArc, 0f, 0f, pArc)

        val pMid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ink
            textSize = 26f
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
