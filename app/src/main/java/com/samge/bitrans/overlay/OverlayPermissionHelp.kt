package com.samge.bitrans.overlay

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/**
 * Xiaomi/HyperOS blocks the "Display over other apps" grant for sideloaded apps
 * (Security dialog: "App restricted from using the Display over other apps permission").
 *
 * Standard escape hatch: grant SYSTEM_ALERT_WINDOW via appops from adb:
 *   adb shell appops set com.samge.bitrans SYSTEM_ALERT_WINDOW allow
 * After that Settings.canDrawOverlays() returns true on MIUI/HyperOS and the
 * overlay works without touching the blocked UI toggle.
 *
 * In-app fallback guidance (no adb): MIUI-owned path is
 *   Settings -> Apps -> Manage apps -> BiTrans -> Other permissions
 *   -> "Display pop-up windows while running in the background" (后台弹出界面)
 * plus 家人的"锁定浮窗"…但最可靠仍是 appops。
 */
object OverlayPermissionHelp {
    const val PACKAGE = "com.samge.bitrans"
    const val ADB_COMMAND = "adb shell appops set $PACKAGE SYSTEM_ALERT_WINDOW allow"

    fun canDraw(ctx: Context): Boolean = Settings.canDrawOverlays(ctx)

    /** MIUI-specific settings page (best effort; null-safe on AOSP ROMs) */
    fun miuiIntent(): Intent = Intent("miui.intent.permission.APP_SETTINGS").apply {
        putExtra("extra_pkgname", PACKAGE)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun genericIntent(ctx: Context): Intent = Intent(
        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
        Uri.parse("package:" + ctx.packageName),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
