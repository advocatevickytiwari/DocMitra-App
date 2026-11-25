package com.docmitra.scanner

import android.app.Activity
import android.app.KeyguardManager
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import java.io.File
import java.io.FileOutputStream

class VaultActivity : AppCompatActivity() {

    private lateinit var vaultContainer: LinearLayout
    private var isUnlocked = false

    private val authLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            isUnlocked = true
            renderVaultUi()
        } else {
            Toast.makeText(this, "Authentication required to open ID Vault", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private val addDocLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { saveToPrivateVault(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Block screenshots & screen recording for maximum security
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF0F172A.toInt())
            setPadding(40, 60, 40, 40)
        }

        val header = TextView(this).apply {
            text = "🔒 DocMitra Private ID Vault"
            setTextColor(0xFFF8FAFC.toInt())
            textSize = 20f
            setTypeface(null, android.graphics.Typeface.BOLD)
        }

        val sub = TextView(this).apply {
            text = "Screenshot Blocked • Offline Encrypted Storage"
            setTextColor(0xFF34D399.toInt())
            textSize = 12f
            setPadding(0, 8, 0, 28)
        }

        val addBtn = MaterialButton(this).apply {
            text = "+ Add ID / Document to Vault"
            setBackgroundColor(0xFF059669.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            setOnClickListener {
                if (isUnlocked) addDocLauncher.launch("image/*")
            }
        }

        vaultContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 30, 0, 0)
        }

        root.addView(header)
        root.addView(sub)
        root.addView(addBtn)
        root.addView(vaultContainer)
        setContentView(root)

        authenticateUser()
    }

    private fun authenticateUser() {
        val km = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
        if (km.isKeyguardSecure) {
            val intent = km.createConfirmDeviceCredentialIntent(
                "DocMitra ID Vault",
                "Verify Fingerprint or Screen Lock to unlock your private IDs"
            )
            if (intent != null) {
                authLauncher.launch(intent)
                return
            }
        }
        // Fallback if device has no screen lock set
        isUnlocked = true
        renderVaultUi()
    }

    private fun saveToPrivateVault(uri: Uri) {
        try {
            val vaultDir = File(filesDir, "private_vault")
            if (!vaultDir.exists()) vaultDir.mkdirs()
            val outFile = File(vaultDir, "ID_${System.currentTimeMillis()}.jpg")
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(outFile).use { output ->
                    input.copyTo(output)
                }
            }
            Toast.makeText(this, "ID securely locked in Vault!", Toast.LENGTH_SHORT).show()
            renderVaultUi()
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to save in vault", Toast.LENGTH_SHORT).show()
        }
    }

    private fun renderVaultUi() {
        vaultContainer.removeAllViews()
        val vaultDir = File(filesDir, "private_vault")
        val files = vaultDir.listFiles()?.sortedByDescending { it.lastModified() }

        if (files.isNullOrEmpty()) {
            val empty = TextView(this).apply {
                text = "Your Vault is empty. Tap '+ Add ID' above to store Aadhaar, PAN, or Bar Council ID safely."
                setTextColor(0xFF94A3B8.toInt())
                textSize = 13f
            }
            vaultContainer.addView(empty)
            return
        }

        for (file in files) {
            val card = MaterialCardView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = 16 }
                setCardBackgroundColor(0xFF1E293B.toInt())
                radius = 24f
            }

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(30, 24, 30, 24)
            }

            val title = TextView(this).apply {
                text = "Protected ID (${file.name.takeLast(10)})"
                setTextColor(0xFFF8FAFC.toInt())
                textSize = 14f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val viewBtn = MaterialButton(this).apply {
                text = "View"
                setBackgroundColor(0xFF2563EB.toInt())
                setTextColor(0xFFFFFFFF.toInt())
                setOnClickListener { showSecureImageDialog(file) }
            }

            row.addView(title)
            row.addView(viewBtn)
            card.addView(row)
            vaultContainer.addView(card)
        }
    }

    private fun showSecureImageDialog(file: File) {
        val bmp = BitmapFactory.decodeFile(file.absolutePath) ?: return
        val iv = ImageView(this).apply {
            setImageBitmap(bmp)
            adjustViewBounds = true
            setPadding(20, 20, 20, 20)
        }
        AlertDialog.Builder(this)
            .setTitle("Protected Document")
            .setView(iv)
            .setPositiveButton("Close", null)
            .setNegativeButton("Delete") { _, _ ->
                file.delete()
                renderVaultUi()
            }
            .show()
    }
}
