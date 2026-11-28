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
import android.graphics.Matrix
import android.graphics.Paint
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
import com.google.android.material.card.MaterialCardView
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min

class PdfIdStudioActivity : AppCompatActivity() {

    private var mode = "ID_MERGE"
    private var frontBitmap: Bitmap? = null
    private var backBitmap: Bitmap? = null
    private var activeIdSide = 1 // 1 = Front, 2 = Back
    private var isSideBySide = false
    private var tempCameraFile: File? = null
    private var cameraRequestPurpose = "ID_CARD"

    private lateinit var ivFrontPreview: ImageView
    private lateinit var ivBackPreview: ImageView
    private lateinit var ivMergedPreview: ImageView
    private lateinit var etFileName: EditText

    private val selectedImages = ArrayList<Bitmap>()
    private var selectedPdfUri: Uri? = null

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            launchDirectCameraInternal()
        } else {
            Toast.makeText(this, "Camera permission required to take photo", Toast.LENGTH_LONG).show()
        }
    }

    private val directCameraLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        val camFile = tempCameraFile
        if (success && camFile != null && camFile.exists() && camFile.length() > 0) {
            val rawBmp = decodeSafeFileBitmap(camFile, 2000)
            camFile.delete()
            if (rawBmp != null) {
                if (cameraRequestPurpose == "IMG_TO_PDF") {
                    selectedImages.add(rawBmp)
                    ivMergedPreview.setImageBitmap(rawBmp)
                    Toast.makeText(this, "Total ${selectedImages.size} photo(s) added", Toast.LENGTH_SHORT).show()
                } else {
                    openInteractiveCropDialog(rawBmp)
                }
            }
        }
    }

    private val singleGalleryPicker = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null && uri.scheme == "content") {
            val rawBmp = decodeSafeUriBitmap(uri, 2000)
            if (rawBmp != null) {
                openInteractiveCropDialog(rawBmp)
            }
        }
    }

    private val multiImagePicker = registerForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (!uris.isNullOrEmpty()) {
            for (u in uris.take(30)) {
                decodeSafeUriBitmap(u, 1800)?.let { selectedImages.add(it) }
            }
            if (selectedImages.isNotEmpty()) {
                ivMergedPreview.setImageBitmap(selectedImages.last())
                Toast.makeText(this, "Total ${selectedImages.size} photo(s) ready", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private val pdfPicker = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null && uri.scheme == "content") {
            selectedPdfUri = uri
            Toast.makeText(this, "PDF selected! Ready to compress.", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )

        mode = intent.getStringExtra("MODE") ?: "ID_MERGE"

        val density = resources.displayMetrics.density
        val dp = { v: Int -> (v * density).toInt() }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFF1F5F9.toInt())
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

        val titleText = when (mode) {
            "IMG_TO_PDF" -> "Image to PDF Maker"
            "PDF_TOOLS" -> "PDF Compress & Lock"
            else -> "ID Card Front & Back Merger"
        }

        val tvTitle = TextView(this).apply {
            text = titleText
            textSize = 16f
            setTextColor(0xFFFFFFFF.toInt())
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        topBar.addView(btnBack)
        topBar.addView(tvTitle)
        root.addView(topBar)

        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(20))
        }

        val defaultName = when (mode) {
            "IMG_TO_PDF" -> "PDF_${SimpleDateFormat("ddMM_HHmm", Locale.getDefault()).format(Date())}"
            "PDF_TOOLS" -> "Compressed_${SimpleDateFormat("ddMM_HHmm", Locale.getDefault()).format(Date())}"
            else -> "ID_Card_${SimpleDateFormat("ddMM_HHmm", Locale.getDefault()).format(Date())}"
        }

        etFileName = EditText(this).apply {
            setText(defaultName)
            hint = "Enter file name (e.g. Pawan_ID)"
            textSize = 14f
            setTextColor(0xFF0F172A.toInt())
            setBackgroundColor(0xFFFFFFFF.toInt())
            setPadding(dp(12), dp(10), dp(12), dp(10))
            isSingleLine = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }
        }
        body.addView(etFileName)

        if (mode == "ID_MERGE") {
            buildIdMergerUi(body, dp, density)
        } else if (mode == "IMG_TO_PDF") {
            buildImageToPdfUi(body, dp)
        } else {
            buildPdfToolsUi(body, dp)
        }

        scrollView.addView(body)
        root.addView(scrollView)
        setContentView(root)
    }

    private fun buildIdMergerUi(body: LinearLayout, dp: (Int) -> Int, density: Float) {
        val sidesRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        }

        fun createSideCard(label: String, sideNum: Int): Pair<MaterialCardView, ImageView> {
            val card = MaterialCardView(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, dp(145), 1f).apply {
                    if (sideNum == 1) marginEnd = dp(6) else marginStart = dp(6)
                }
                radius = 12f * density
                cardElevation = 2f * density
                setCardBackgroundColor(0xFFFFFFFF.toInt())
                strokeColor = 0xFF14532D.toInt()
                strokeWidth = (1.5f * density).toInt()
            }

            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(8), dp(8), dp(8))
            }

            val tvHeader = TextView(this).apply {
                text = label
                textSize = 12.5f
                setTypeface(null, Typeface.BOLD)
                setTextColor(0xFF14532D.toInt())
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, dp(4))
            }

            val iv = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                )
                scaleType = ImageView.ScaleType.FIT_CENTER
                setBackgroundColor(0xFFF8FAFC.toInt())
                setImageResource(android.R.drawable.ic_menu_add)
            }

            val tvTapHint = TextView(this).apply {
                text = "Tap to Add / Crop"
                textSize = 11f
                setTextColor(0xFFEA580C.toInt())
                setTypeface(null, Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(0, dp(4), 0, 0)
            }

            col.addView(tvHeader)
            col.addView(iv)
            col.addView(tvTapHint)
            card.addView(col)

            card.setOnClickListener {
                activeIdSide = sideNum
                showIdSideSourcePopup()
            }
            return Pair(card, iv)
        }

        val (frontCard, ivF) = createSideCard("1. FRONT SIDE", 1)
        val (backCard, ivB) = createSideCard("2. BACK SIDE", 2)
        ivFrontPreview = ivF
        ivBackPreview = ivB

        sidesRow.addView(frontCard)
        sidesRow.addView(backCard)
        body.addView(sidesRow)

        val layoutRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        }

        val btnTopBottom = MaterialButton(this).apply {
            text = "Top & Bottom (A4)"
            textSize = 11.5f
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xFF14532D.toInt())
            layoutParams = LinearLayout.LayoutParams(0, dp(38), 1f).apply { marginEnd = dp(6) }
            setOnClickListener {
                isSideBySide = false
                refreshIdMergedPreview()
            }
        }

        val btnSideBySide = MaterialButton(this).apply {
            text = "Side by Side (A4)"
            textSize = 11.5f
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xFF475569.toInt())
            layoutParams = LinearLayout.LayoutParams(0, dp(38), 1f)
            setOnClickListener {
                isSideBySide = true
                refreshIdMergedPreview()
            }
        }

        layoutRow.addView(btnTopBottom)
        layoutRow.addView(btnSideBySide)
        body.addView(layoutRow)

        ivMergedPreview = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(320)
            ).apply { bottomMargin = dp(10) }
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(0xFFFFFFFF.toInt())
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        body.addView(ivMergedPreview)

        val saveRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val btnSaveGalleryAndApp = MaterialButton(this).apply {
            text = "✓ Save Photo (Gallery)"
            textSize = 12.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xFF14532D.toInt())
            layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(6) }
            setOnClickListener { saveMergedIdCard(sharePdfAfter = false) }
        }

        val btnSharePdf = MaterialButton(this).apply {
            text = "Share PDF"
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xFFEA580C.toInt())
            layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f)
            setOnClickListener { saveMergedIdCard(sharePdfAfter = true) }
        }

        saveRow.addView(btnSaveGalleryAndApp)
        saveRow.addView(btnSharePdf)
        body.addView(saveRow)

        refreshIdMergedPreview()
    }

    private fun showIdSideSourcePopup() {
        val existing = if (activeIdSide == 1) frontBitmap else backBitmap
        val options = if (existing != null) {
            arrayOf("📷 Take Photo with Camera", "🖼 Choose from Gallery", "✂️ Re-Crop Current Photo")
        } else {
            arrayOf("📷 Take Photo with Camera", "🖼 Choose from Gallery")
        }

        AlertDialog.Builder(this)
            .setTitle(if (activeIdSide == 1) "FRONT Side Options" else "BACK Side Options")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        cameraRequestPurpose = "ID_CARD"
                        checkCameraPermissionAndOpen()
                    }
                    1 -> singleGalleryPicker.launch("image/*")
                    2 -> existing?.let { openInteractiveCropDialog(it) }
                }
            }
            .show()
    }

    private fun checkCameraPermissionAndOpen() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            launchDirectCameraInternal()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun launchDirectCameraInternal() {
        try {
            val file = File(cacheDir, "id_cam_${System.currentTimeMillis()}.jpg")
            tempCameraFile = file
            val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
            directCameraLauncher.launch(uri)
        } catch (e: Exception) {
            Toast.makeText(this, "Camera error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openInteractiveCropDialog(sourceBmp: Bitmap) {
        var workingBmp = sourceBmp
        val density = resources.displayMetrics.density
        val dp = { v: Int -> (v * density).toInt() }

        val dialogLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF0F172A.toInt())
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }

        val hintTv = TextView(this).apply {
            text = "Drag orange corners to crop ID card cleanly"
            textSize = 12f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(6))
        }

        val cropFrame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(340)
            )
        }

        val cropEditorView = InteractiveCropView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setupBitmap(workingBmp)
        }
        cropFrame.addView(cropEditorView)

        val btnRotateCrop = MaterialButton(this).apply {
            text = "↻ Rotate 90°"
            textSize = 12f
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xFF334155.toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(40)
            ).apply { topMargin = dp(8) }
            setOnClickListener {
                val m = Matrix().apply { postRotate(90f) }
                workingBmp = Bitmap.createBitmap(workingBmp, 0, 0, workingBmp.width, workingBmp.height, m, true)
                cropEditorView.setupBitmap(workingBmp)
            }
        }

        dialogLayout.addView(hintTv)
        dialogLayout.addView(cropFrame)
        dialogLayout.addView(btnRotateCrop)

        AlertDialog.Builder(this)
            .setTitle(if (activeIdSide == 1) "Crop FRONT Side" else "Crop BACK Side")
            .setView(dialogLayout)
            .setCancelable(false)
            .setPositiveButton("✓ Crop & Use") { _, _ ->
                val cropped = cropEditorView.extractCroppedBitmap()
                if (activeIdSide == 1) {
                    frontBitmap = cropped
                    ivFrontPreview.setImageBitmap(cropped)
                } else {
                    backBitmap = cropped
                    ivBackPreview.setImageBitmap(cropped)
                }
                refreshIdMergedPreview()
            }
            .setNeutralButton("Use Full") { _, _ ->
                if (activeIdSide == 1) {
                    frontBitmap = workingBmp
                    ivFrontPreview.setImageBitmap(workingBmp)
                } else {
                    backBitmap = workingBmp
                    ivBackPreview.setImageBitmap(workingBmp)
                }
                refreshIdMergedPreview()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun drawBitmapFitInsideSlot(canvas: Canvas, bmp: Bitmap, slot: RectF, paint: Paint) {
        val imgRatio = bmp.width.toFloat() / bmp.height.toFloat().coerceAtLeast(1f)
        val slotRatio = slot.width() / slot.height().coerceAtLeast(1f)

        val drawW: Float
        val drawH: Float
        if (imgRatio > slotRatio) {
            drawW = slot.width()
            drawH = drawW / imgRatio
        } else {
            drawH = slot.height()
            drawW = drawH * imgRatio
        }

        val left = slot.left + (slot.width() - drawW) / 2f
        val top = slot.top + (slot.height() - drawH) / 2f
        val dest = RectF(left, top, left + drawW, top + drawH)

        canvas.drawBitmap(bmp, null, dest, paint)

        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(148, 163, 184)
            style = Paint.Style.STROKE
            strokeWidth = 2.5f
        }
        canvas.drawRect(dest, borderPaint)
    }

    private fun renderA4IdSheetBitmap(): Bitmap {
        val pageW = 1240
        val pageH = 1754
        val sheet = Bitmap.createBitmap(pageW, pageH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(sheet)
        canvas.drawColor(Color.WHITE)

        val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(226, 232, 240)
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }
        val placeholderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(100, 116, 139)
            textSize = 28f
            textAlign = Paint.Align.CENTER
        }
        val imgPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        if (!isSideBySide) {
            val maxSlotW = 900f
            val maxSlotH = 570f
            val left = (pageW - maxSlotW) / 2f
            val top1 = 180f
            val top2 = 910f

            val slot1 = RectF(left, top1, left + maxSlotW, top1 + maxSlotH)
            val slot2 = RectF(left, top2, left + maxSlotW, top2 + maxSlotH)

            if (frontBitmap != null) {
                drawBitmapFitInsideSlot(canvas, frontBitmap!!, slot1, imgPaint)
            } else {
                canvas.drawRect(slot1, guidePaint)
                canvas.drawText("1. FRONT SIDE (Tap Box 1 Above)", pageW / 2f, top1 + maxSlotH / 2f, placeholderPaint)
            }

            if (backBitmap != null) {
                drawBitmapFitInsideSlot(canvas, backBitmap!!, slot2, imgPaint)
            } else {
                canvas.drawRect(slot2, guidePaint)
                canvas.drawText("2. BACK SIDE (Tap Box 2 Above)", pageW / 2f, top2 + maxSlotH / 2f, placeholderPaint)
            }
        } else {
            val maxSlotW = 540f
            val maxSlotH = 345f
            val top = 260f
            val left1 = 60f
            val left2 = 640f

            val slot1 = RectF(left1, top, left1 + maxSlotW, top + maxSlotH)
            val slot2 = RectF(left2, top, left2 + maxSlotW, top + maxSlotH)

            if (frontBitmap != null) {
                drawBitmapFitInsideSlot(canvas, frontBitmap!!, slot1, imgPaint)
            } else {
                canvas.drawRect(slot1, guidePaint)
                canvas.drawText("1. FRONT", left1 + maxSlotW / 2f, top + maxSlotH / 2f, placeholderPaint)
            }

            if (backBitmap != null) {
                drawBitmapFitInsideSlot(canvas, backBitmap!!, slot2, imgPaint)
            } else {
                canvas.drawRect(slot2, guidePaint)
                canvas.drawText("2. BACK", left2 + maxSlotW / 2f, top + maxSlotH / 2f, placeholderPaint)
            }
        }

        return sheet
    }

    private fun refreshIdMergedPreview() {
        ivMergedPreview.setImageBitmap(renderA4IdSheetBitmap())
    }

    private fun saveMergedIdCard(sharePdfAfter: Boolean) {
        if (frontBitmap == null && backBitmap == null) {
            Toast.makeText(this, "Pehle Front ya Back photo lein", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val a4Sheet = renderA4IdSheetBitmap()
            val cleanName = sanitizeName(
                etFileName.text.toString(),
                "ID_${SimpleDateFormat("ddMM_HHmm", Locale.getDefault()).format(Date())}"
            )

            val projDir = File(filesDir, "DocMitra/Projects/$cleanName")
            if (!projDir.exists()) projDir.mkdirs()
            val pageFile = File(projDir, "page_0001.jpg")
            FileOutputStream(pageFile).use { out -> a4Sheet.compress(Bitmap.CompressFormat.JPEG, 95, out) }
            projDir.setLastModified(System.currentTimeMillis())

            saveBitmapToGallery(a4Sheet, "$cleanName.jpg")

            if (sharePdfAfter) {
                val pdfFile = File(cacheDir, "$cleanName.pdf")
                val pdfDoc = PdfDocument()
                val info = PdfDocument.PageInfo.Builder(a4Sheet.width, a4Sheet.height, 1).create()
                val page = pdfDoc.startPage(info)
                page.canvas.drawBitmap(a4Sheet, 0f, 0f, null)
                pdfDoc.finishPage(page)
                FileOutputStream(pdfFile).use { out -> pdfDoc.writeTo(out) }
                pdfDoc.close()

                sharePdfFile(pdfFile)
            } else {
                Toast.makeText(this, "Saved '$cleanName' to Gallery & App!", Toast.LENGTH_LONG).show()
                finish()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Save failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun buildImageToPdfUi(body: LinearLayout, dp: (Int) -> Int) {
        val btnAddPhotos = MaterialButton(this).apply {
            text = "+ Add Photos (Camera / Gallery)"
            textSize = 14f
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xFFEA580C.toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(48)
            ).apply { bottomMargin = dp(12) }
            setOnClickListener {
                AlertDialog.Builder(this@PdfIdStudioActivity)
                    .setTitle("Add Photos to PDF")
                    .setItems(arrayOf("📷 Take Photo with Camera", "🖼 Choose from Gallery")) { _, which ->
                        if (which == 0) {
                            cameraRequestPurpose = "IMG_TO_PDF"
                            checkCameraPermissionAndOpen()
                        } else {
                            multiImagePicker.launch("image/*")
                        }
                    }
                    .show()
            }
        }
        body.addView(btnAddPhotos)

        ivMergedPreview = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(320)
            ).apply { bottomMargin = dp(12) }
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(0xFFFFFFFF.toInt())
        }
        body.addView(ivMergedPreview)

        val btnCreate = MaterialButton(this).apply {
            text = "Create & Share PDF"
            textSize = 14f
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xFF14532D.toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(48)
            )
            setOnClickListener {
                if (selectedImages.isEmpty()) {
                    Toast.makeText(this@PdfIdStudioActivity, "Please add photos first", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val cleanName = sanitizeName(etFileName.text.toString(), "DocMitra_PDF")
                val projDir = File(filesDir, "DocMitra/Projects/$cleanName")
                if (!projDir.exists()) projDir.mkdirs()

                val pdfFile = File(cacheDir, "$cleanName.pdf")
                val pdfDoc = PdfDocument()
                selectedImages.forEachIndexed { idx, bmp ->
                    val pageFile = File(projDir, String.format(Locale.US, "page_%04d.jpg", idx + 1))
                    FileOutputStream(pageFile).use { out -> bmp.compress(Bitmap.CompressFormat.JPEG, 92, out) }

                    val info = PdfDocument.PageInfo.Builder(bmp.width, bmp.height, idx + 1).create()
                    val p = pdfDoc.startPage(info)
                    p.canvas.drawBitmap(bmp, 0f, 0f, null)
                    pdfDoc.finishPage(p)
                }
                FileOutputStream(pdfFile).use { out -> pdfDoc.writeTo(out) }
                pdfDoc.close()
                sharePdfFile(pdfFile)
            }
        }
        body.addView(btnCreate)
    }

    private fun buildPdfToolsUi(body: LinearLayout, dp: (Int) -> Int) {
        val btnPickPdf = MaterialButton(this).apply {
            text = "Select PDF File to Compress"
            textSize = 14f
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xFFEA580C.toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(48)
            ).apply { bottomMargin = dp(12) }
            setOnClickListener { pdfPicker.launch("application/pdf") }
        }
        body.addView(btnPickPdf)

        val btnCompress = MaterialButton(this).apply {
            text = "Compress & Share PDF"
            textSize = 14f
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xFF14532D.toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(48)
            )
            setOnClickListener { compressAndSharePdf() }
        }
        body.addView(btnCompress)
    }

    private fun compressAndSharePdf() {
        val uri = selectedPdfUri
        if (uri == null || uri.scheme != "content") {
            Toast.makeText(this, "Please select a PDF first", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val pfd: ParcelFileDescriptor = contentResolver.openFileDescriptor(uri, "r") ?: return
            val renderer = PdfRenderer(pfd)
            val cleanName = sanitizeName(etFileName.text.toString(), "Compressed_Doc")
            val outPdf = File(cacheDir, "$cleanName.pdf")
            val pdfDoc = PdfDocument()

            for (i in 0 until renderer.pageCount) {
                val page = renderer.openPage(i)
                val targetW = (page.width * 0.72f).toInt().coerceAtLeast(600)
                val targetH = ((page.height.toFloat() / page.width.toFloat()) * targetW).toInt().coerceAtLeast(800)
                val bmp = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(Color.WHITE)
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                page.close()

                val info = PdfDocument.PageInfo.Builder(targetW, targetH, i + 1).create()
                val p = pdfDoc.startPage(info)
                p.canvas.drawBitmap(bmp, 0f, 0f, null)
                pdfDoc.finishPage(p)
            }
            renderer.close()
            pfd.close()

            FileOutputStream(outPdf).use { out -> pdfDoc.writeTo(out) }
            pdfDoc.close()
            sharePdfFile(outPdf)
        } catch (e: Exception) {
            Toast.makeText(this, "Compress error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun decodeSafeFileBitmap(file: File, maxDim: Int): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            var sample = 1
            while (bounds.outWidth / sample > maxDim || bounds.outHeight / sample > maxDim) {
                sample *= 2
            }
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            BitmapFactory.decodeFile(file.absolutePath, opts)
        } catch (_: Exception) {
            null
        }
    }

    private fun decodeSafeUriBitmap(uri: Uri, maxDim: Int): Bitmap? {
        if (uri.scheme != "content") return null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (bounds.outWidth / sample > maxDim || bounds.outHeight / sample > maxDim) {
                sample *= 2
            }
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        } catch (_: Exception) {
            null
        }
    }

    private fun sanitizeName(raw: String, fallback: String): String {
        return raw.replace("[^a-zA-Z0-9\\u0900-\\u097F_\\- ]".toRegex(), "")
            .replace("..", "")
            .trim()
            .take(50)
            .ifEmpty { fallback }
    }

    private fun saveBitmapToGallery(bmp: Bitmap, fileName: String) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/DocMitra")
                }
                val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                uri?.let { contentResolver.openOutputStream(it)?.use { out -> bmp.compress(Bitmap.CompressFormat.JPEG, 95, out) } }
            } else {
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "DocMitra")
                if (!dir.exists()) dir.mkdirs()
                FileOutputStream(File(dir, fileName)).use { out -> bmp.compress(Bitmap.CompressFormat.JPEG, 95, out) }
            }
        } catch (_: Exception) {
        }
    }

    private fun sharePdfFile(file: File) {
        val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Share PDF"))
    }

    private class InteractiveCropView(context: Context) : View(context) {
        private var srcBmp: Bitmap? = null
        private val imgRect = RectF()
        private val cropRect = RectF()
        private var activeHandle = 0
        private var lastX = 0f
        private var lastY = 0f

        private val shadePaint = Paint().apply {
            color = 0x88000000.toInt()
            style = Paint.Style.FILL
        }
        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFEA580C.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 5f
        }
        private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFEA580C.toInt()
            style = Paint.Style.FILL
        }
        private val handleRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 4f
        }

        fun setupBitmap(bmp: Bitmap) {
            srcBmp = bmp
            recalculateRects()
            invalidate()
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            recalculateRects()
        }

        private fun recalculateRects() {
            val bmp = srcBmp ?: return
            if (width <= 0 || height <= 0) return

            val scale = min(width.toFloat() / bmp.width.toFloat(), height.toFloat() / bmp.height.toFloat())
            val dispW = bmp.width * scale
            val dispH = bmp.height * scale
            val left = (width - dispW) / 2f
            val top = (height - dispH) / 2f
            imgRect.set(left, top, left + dispW, top + dispH)

            val padX = dispW * 0.12f
            val padY = dispH * 0.18f
            cropRect.set(left + padX, top + padY, left + dispW - padX, top + dispH - padY)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val bmp = srcBmp ?: return
            canvas.drawBitmap(bmp, null, imgRect, null)

            canvas.drawRect(imgRect.left, imgRect.top, imgRect.right, cropRect.top, shadePaint)
            canvas.drawRect(imgRect.left, cropRect.bottom, imgRect.right, imgRect.bottom, shadePaint)
            canvas.drawRect(imgRect.left, cropRect.top, cropRect.left, cropRect.bottom, shadePaint)
            canvas.drawRect(cropRect.right, cropRect.top, imgRect.right, cropRect.bottom, shadePaint)

            canvas.drawRect(cropRect, borderPaint)
            val corners = listOf(
                Pair(cropRect.left, cropRect.top),
                Pair(cropRect.right, cropRect.top),
                Pair(cropRect.right, cropRect.bottom),
                Pair(cropRect.left, cropRect.bottom)
            )
            for ((cx, cy) in corners) {
                canvas.drawCircle(cx, cy, 20f, handlePaint)
                canvas.drawCircle(cx, cy, 20f, handleRingPaint)
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (srcBmp == null) return false
            val x = event.x
            val y = event.y
            val hitR = 65f

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    activeHandle = when {
                        abs(x - cropRect.left) < hitR && abs(y - cropRect.top) < hitR -> 1
                        abs(x - cropRect.right) < hitR && abs(y - cropRect.top) < hitR -> 2
                        abs(x - cropRect.right) < hitR && abs(y - cropRect.bottom) < hitR -> 3
                        abs(x - cropRect.left) < hitR && abs(y - cropRect.bottom) < hitR -> 4
                        cropRect.contains(x, y) -> 5
                        else -> 0
                    }
                    lastX = x
                    lastY = y
                    return activeHandle != 0
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = x - lastX
                    val dy = y - lastY
                    val minSize = 80f
                    when (activeHandle) {
                        1 -> {
                            cropRect.left = (cropRect.left + dx).coerceIn(imgRect.left, cropRect.right - minSize)
                            cropRect.top = (cropRect.top + dy).coerceIn(imgRect.top, cropRect.bottom - minSize)
                        }
                        2 -> {
                            cropRect.right = (cropRect.right + dx).coerceIn(cropRect.left + minSize, imgRect.right)
                            cropRect.top = (cropRect.top + dy).coerceIn(imgRect.top, cropRect.bottom - minSize)
                        }
                        3 -> {
                            cropRect.right = (cropRect.right + dx).coerceIn(cropRect.left + minSize, imgRect.right)
                            cropRect.bottom = (cropRect.bottom + dy).coerceIn(cropRect.top + minSize, imgRect.bottom)
                        }
                        4 -> {
                            cropRect.left = (cropRect.left + dx).coerceIn(imgRect.left, cropRect.right - minSize)
                            cropRect.bottom = (cropRect.bottom + dy).coerceIn(cropRect.top + minSize, imgRect.bottom)
                        }
                        5 -> {
                            val w = cropRect.width()
                            val h = cropRect.height()
                            val newL = (cropRect.left + dx).coerceIn(imgRect.left, imgRect.right - w)
                            val newT = (cropRect.top + dy).coerceIn(imgRect.top, imgRect.bottom - h)
                            cropRect.set(newL, newT, newL + w, newT + h)
                        }
                    }
                    lastX = x
                    lastY = y
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    activeHandle = 0
                }
            }
            return false
        }

        fun extractCroppedBitmap(): Bitmap {
            val bmp = srcBmp ?: return Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
            if (imgRect.width() <= 0f || imgRect.height() <= 0f) return bmp

            val scaleX = bmp.width.toFloat() / imgRect.width()
            val scaleY = bmp.height.toFloat() / imgRect.height()

            val left = ((cropRect.left - imgRect.left) * scaleX).toInt().coerceIn(0, bmp.width - 10)
            val top = ((cropRect.top - imgRect.top) * scaleY).toInt().coerceIn(0, bmp.height - 10)
            val cropW = (cropRect.width() * scaleX).toInt().coerceIn(10, bmp.width - left)
            val cropH = (cropRect.height() * scaleY).toInt().coerceIn(10, bmp.height - top)

            return Bitmap.createBitmap(bmp, left, top, cropW, cropH)
        }
    }
}
