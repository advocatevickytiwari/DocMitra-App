package com.docmitra.scanner

import android.app.Activity
import android.app.Application
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

class DocMitraApp : Application() {

    override fun onCreate() {
        super.onCreate()

        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                // 1. Global FLAG_SECURE on every screen
                activity.window.setFlags(
                    WindowManager.LayoutParams.FLAG_SECURE,
                    WindowManager.LayoutParams.FLAG_SECURE
                )

                // 2. Force non-overlapping dark green status bar & clean navigation bar
                activity.window.statusBarColor = 0xFF14532D.toInt()
                activity.window.navigationBarColor = 0xFF18181B.toInt()
                val controller = WindowInsetsControllerCompat(activity.window, activity.window.decorView)
                controller.isAppearanceLightStatusBars = false
            }

            override fun onActivityPostCreated(activity: Activity, savedInstanceState: Bundle?) {
                applyGuaranteedSafeFrame(activity)
            }

            override fun onActivityStarted(activity: Activity) {
                applyGuaranteedSafeFrame(activity)
            }

            override fun onActivityResumed(activity: Activity) {
                applyGuaranteedSafeFrame(activity)
            }

            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    private fun applyGuaranteedSafeFrame(activity: Activity) {
        val contentFrame = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        val rootChild = contentFrame.getChildAt(0) ?: return

        // Reset any old padding and bind live WindowInsets AFTER setContentView
        ViewCompat.setOnApplyWindowInsetsListener(contentFrame) { v, windowInsets ->
            val insets = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            // Fallback minimum top clearance (36dp) if OEM ROM reports 0 on edge-to-edge override
            val minTopPx = (36 * activity.resources.displayMetrics.density).toInt()
            val safeTop = if (insets.top > 0) insets.top else minTopPx

            v.setPadding(insets.left, safeTop, insets.right, insets.bottom)
            v.setBackgroundColor(0xFF14532D.toInt())
            rootChild.setPadding(0, 0, 0, 0)
            WindowInsetsCompat.CONSUMED
        }

        // Force Android system to immediately dispatch insets right now
        ViewCompat.requestApplyInsets(contentFrame)
    }
}
