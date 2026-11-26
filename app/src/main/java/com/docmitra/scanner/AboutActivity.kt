package com.docmitra.scanner

import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat

class AboutActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val density = resources.displayMetrics.density
        val prefs = getSharedPreferences("docmitra_settings", Context.MODE_PRIVATE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFFFFFFF.toInt())
        }

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((14 * density).toInt(), (14 * density).toInt(), (16 * density).toInt(), (14 * density).toInt())
        }

        val backBtn = TextView(this).apply {
            text = "←"
            textSize = 22f
            setTextColor(0xFF0F172A.toInt())
            setPadding((6 * density).toInt(), 0, (16 * density).toInt(), 0)
            setOnClickListener { finish() }
        }

        val titleTv = TextView(this).apply {
            text = "About"
            textSize = 19f
            setTextColor(0xFF0F172A.toInt())
            setTypeface(null, android.graphics.Typeface.BOLD)
        }

        topBar.addView(backBtn)
        topBar.addView(titleTv)
        root.addView(topBar)

        val subHeader = TextView(this).apply {
            text = "Terms of Service and Privacy Policy"
            textSize = 13f
            setTextColor(0xFF475569.toInt())
            setTypeface(null, android.graphics.Typeface.BOLD)
            setBackgroundColor(0xFFF1F5F9.toInt())
            setPadding((18 * density).toInt(), (10 * density).toInt(), (18 * density).toInt(), (10 * density).toInt())
        }
        root.addView(subHeader)

        fun createPolicyRow(label: String, onClick: () -> Unit): TextView {
            return TextView(this).apply {
                text = label
                textSize = 15f
                setTextColor(0xFF0F172A.toInt())
                setTypeface(null, android.graphics.Typeface.BOLD)
                setPadding((18 * density).toInt(), (18 * density).toInt(), (18 * density).toInt(), (18 * density).toInt())
                setOnClickListener { onClick() }
            }
        }

        fun createDivider(): View {
            return View(this).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
                setBackgroundColor(0xFFF1F5F9.toInt())
            }
        }

        root.addView(createPolicyRow("Terms of Service") {
            showLegalDialog(
                "Terms of Service",
                "DocMitra is a 100% offline document scanning and PDF utility studio. All document scans, photo edits, and ID Vault records are processed locally on your device. Users are responsible for complying with applicable laws when scanning, merging, or sharing official documents."
            )
        })
        root.addView(createDivider())

        root.addView(createPolicyRow("Privacy Policy") {
            showLegalDialog(
                "Privacy Policy",
                "Your privacy comes first. DocMitra does NOT upload your documents, photos, or ID cards to any external cloud server. Camera and Storage permissions are used strictly on-device to scan documents and save PDFs/JPGs to your local storage or gallery."
            )
        })
        root.addView(createDivider())

        root.addView(createPolicyRow("Children's Online Privacy Protection Guidelines") {
            showLegalDialog(
                "Children's Online Privacy Protection Guidelines",
                "DocMitra operates completely offline and does not knowingly collect, store, or transmit personal data from children under 13 years of age."
            )
        })
        root.addView(createDivider())

        val notifRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((18 * density).toInt(), (16 * density).toInt(), (18 * density).toInt(), (16 * density).toInt())
        }

        val notifLabel = TextView(this).apply {
            text = "Update Notifications"
            textSize = 15f
            setTextColor(0xFF0F172A.toInt())
            setTypeface(null, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val notifSwitch = SwitchCompat(this).apply {
            isChecked = prefs.getBoolean("update_notifications", true)
            setOnCheckedChangeListener { _, isChecked ->
                prefs.edit().putBoolean("update_notifications", isChecked).apply()
                Toast.makeText(
                    this@AboutActivity,
                    if (isChecked) "Update notifications enabled" else "Update notifications disabled",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        notifRow.addView(notifLabel)
        notifRow.addView(notifSwitch)
        root.addView(notifRow)
        root.addView(createDivider())

        val versionTv = TextView(this).apply {
            text = "DocMitra Offline Studio • Version 1.0\n100% On-Device Secure Processing"
            textSize = 12f
            setTextColor(0xFF94A3B8.toInt())
            gravity = Gravity.CENTER
            setPadding((18 * density).toInt(), (32 * density).toInt(), (18 * density).toInt(), (18 * density).toInt())
        }
        root.addView(versionTv)

        val scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)
    }

    private fun showLegalDialog(title: String, body: String) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(body)
            .setPositiveButton("OK", null)
            .show()
    }
}
