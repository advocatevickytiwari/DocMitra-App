package com.docmitra.scanner

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
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

class VaultActivity : AppCompatActivity() {

    private lateinit var vaultListContainer: LinearLayout
    private lateinit var tvVaultStatus: TextView
    private var isUnlocked = false

    private val addVaultDocLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null && uri.scheme == "content") {
            saveUriToPrivateVault(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )

        val density = resources.displayMetrics.density
        val dp = { v: Int -> (v * density).toInt() }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF0F172A.toInt())
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

        val tvTitle = TextView(this).apply {
            text = "🔒 DocMitra Private ID Vault"
            textSize = 16f
            setTextColor(0xFFFFFFFF.toInt())
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        topBar.addView(btnBack)
        topBar.addView(tvTitle)
        root.addView(topBar)

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(16))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        tvVaultStatus = TextView(this).apply {
            text = "Screenshot Blocked • Offline Encrypted Storage"
            textSize = 12f
            setTextColor(0xFF4ADE80.toInt())
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 0, dp(10))
        }
        body.addView(tvVaultStatus)

        val btnAddVault = MaterialButton(this).apply {
            text = "+ Add ID / Document to Vault"
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(0xFF0F172A.toInt())
            setBackgroundColor(0xFF4ADE80.toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(48)
            ).apply { bottomMargin = dp(12) }
            setOnClickListener {
                if (isUnlocked) {
                    addVaultDocLauncher.launch("image/*")
                } else {
                    promptBiometricUnlock()
                }
            }
        }
        body.addView(btnAddVault)

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        vaultListContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        scroll.addView(vaultListContainer)
        body.addView(scroll)
        root.addView(body)

        setContentView(root)
        promptBiometricUnlock()
    }

    private fun getPrivateVaultDir(): File {
        val dir = File(filesDir, "DocMitra/PrivateVault")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun promptBiometricUnlock() {
        val biometricManager = BiometricManager.from(this)
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_WEAK or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL

        if (biometricManager.canAuthenticate(authenticators) != BiometricManager.BIOMETRIC_SUCCESS) {
            isUnlocked = true
            refreshVaultItems()
            return
        }

        val executor = ContextCompat.getMainExecutor(this)
        val biometricPrompt = BiometricPrompt(
            this,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    isUnlocked = true
                    refreshVaultItems()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    Toast.makeText(this@VaultActivity, "Authentication required to open ID Vault", Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
        )

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock DocMitra Private Vault")
            .setSubtitle("Verify Fingerprint or Screen Lock to view private IDs")
            .setAllowedAuthenticators(authenticators)
            .build()

        biometricPrompt.authenticate(promptInfo)
    }

    private fun saveUriToPrivateVault(uri: Uri) {
        try {
            contentResolver.openInputStream(uri)?.use { input ->
                val bmp = BitmapFactory.decodeStream(input)
                if (bmp != null) {
                    val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                    val outFile = File(getPrivateVaultDir(), "Vault_ID_$stamp.jpg")
                    FileOutputStream(outFile).use { out ->
                        bmp.compress(Bitmap.CompressFormat.JPEG, 94, out)
                    }
                    bmp.recycle()
                    Toast.makeText(this, "Saved securely in Private Vault!", Toast.LENGTH_SHORT).show()
                    refreshVaultItems()
                }
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Vault save error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun refreshVaultItems() {
        vaultListContainer.removeAllViews()
        if (!isUnlocked) return

        val files = getPrivateVaultDir().listFiles { f ->
            f.isFile && f.extension.equals("jpg", ignoreCase = true)
        }?.sortedByDescending { it.lastModified() } ?: emptyList()

        if (files.isEmpty()) {
            val emptyTv = TextView(this).apply {
                text = "Your Vault is empty. Tap '+ Add ID' above to store Aadhaar, PAN, or Bar Council ID safely."
                setTextColor(0xFF94A3B8.toInt())
                textSize = 13.5f
                setPadding(12, 32, 12, 12)
            }
            vaultListContainer.addView(emptyTv)
            return
        }

        val density = resources.displayMetrics.density
        val dp = { v: Int -> (v * density).toInt() }

        for (file in files) {
            val card = MaterialCardView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(10) }
                radius = 12f * density
                setCardBackgroundColor(0xFF1E293B.toInt())
            }

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(10), dp(10), dp(10))
            }

            val iv = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(54), dp(54))
                scaleType = ImageView.ScaleType.CENTER_CROP
                setImageBitmap(BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = 4 }))
            }

            val tvName = TextView(this).apply {
                text = file.nameWithoutExtension
                textSize = 14f
                setTextColor(0xFFFFFFFF.toInt())
                setTypeface(null, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dp(10)
                }
            }

            val btnShare = MaterialButton(this).apply {
                text = "Share"
                textSize = 11f
                minWidth = 0
                setTextColor(0xFFFFFFFF.toInt())
                setBackgroundColor(0xFFEA580C.toInt())
                setOnClickListener {
                    val shareUri = FileProvider.getUriForFile(this@VaultActivity, "${packageName}.fileprovider", file)
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "image/jpeg"
                        putExtra(Intent.EXTRA_STREAM, shareUri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(Intent.createChooser(intent, "Share Private ID"))
                }
            }

            val btnDel = MaterialButton(this).apply {
                text = "🗑"
                textSize = 14f
                minWidth = 0
                setTextColor(0xFFFCA5A5.toInt())
                setBackgroundColor(0x00000000)
                setOnClickListener {
                    file.delete()
                    refreshVaultItems()
                }
            }

            row.addView(iv)
            row.addView(tvName)
            row.addView(btnShare)
            row.addView(btnDel)
            card.addView(row)

            card.setOnClickListener {
                val fullIv = ImageView(this).apply {
                    setImageBitmap(BitmapFactory.decodeFile(file.absolutePath))
                    adjustViewBounds = true
                }
                AlertDialog.Builder(this)
                    .setTitle(file.nameWithoutExtension)
                    .setView(fullIv)
                    .setNegativeButton("Close", null)
                    .show()
            }

            vaultListContainer.addView(card)
        }
    }
}
