package com.threadsyphon.android.util

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log

object BatteryHelper {
    private const val TAG = "BatteryHelper"

    /** Result of an OEM / settings deep-link attempt. */
    enum class OemOpenResult {
        /** Official Never sleeping apps list (user must tap + to add). */
        SamsungNeverSleeping,
        /** Generic Samsung battery / Device Care screen. */
        SamsungBattery,
        /** Fell back to APPLICATION_DETAILS_SETTINGS for our package. */
        AppDetails,
    }

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /** Request ignore-battery-optimizations for this package (user must confirm). */
    fun requestIgnoreBatteryOptimizations(context: Context) {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    fun openBatterySettings(context: Context) {
        val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    fun openAppDetails(context: Context) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /**
     * Open Samsung "Never sleeping apps" via the documented Device Care deeplink.
     *
     * Official API (Samsung Application Management):
     *   action = com.samsung.android.sm.ACTION_OPEN_CHECKABLE_LISTACTIVITY
     *   package = com.samsung.android.lool (or legacy com.samsung.android.sm)
     *   extra activity_type = 2  // never sleeping
     *
     * That screen lists apps already allowed. To ADD an app the user must tap
     * + (or Add), pick it, then confirm. There is no Intent that auto-adds or
     * opens the picker pre-selected. Some One UI builds omit sideloaded apps
     * from the Add (+) list entirely — then Never sleeping cannot enroll this
     * package; use App info → Battery → Unrestricted instead.
     *
     * Older code opened CheckableAppListActivity / BatteryActivity by class name
     * without activity_type=2, which often landed on the wrong list (or a view
     * with no +) so the app never appeared and could not be selected.
     */
    fun openOemBackgroundAllowlist(context: Context): OemOpenResult {
        // 1) Documented Never sleeping apps list (activity_type = 2)
        val neverSleepingPackages = listOf(
            "com.samsung.android.lool",
            "com.samsung.android.sm",
        )
        for (pkg in neverSleepingPackages) {
            val intent = Intent("com.samsung.android.sm.ACTION_OPEN_CHECKABLE_LISTACTIVITY").apply {
                setPackage(pkg)
                putExtra("activity_type", 2)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (tryStart(context, intent)) return OemOpenResult.SamsungNeverSleeping
        }

        // 2) Explicit CheckableAppListActivity with activity_type=2
        val checkableClasses = listOf(
            "com.samsung.android.lool" to "com.samsung.android.sm.battery.ui.usage.CheckableAppListActivity",
            "com.samsung.android.sm" to "com.samsung.android.sm.ui.battery.CheckableAppListActivity",
        )
        for ((pkg, cls) in checkableClasses) {
            val intent = Intent().apply {
                setClassName(pkg, cls)
                putExtra("activity_type", 2)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (tryStart(context, intent)) return OemOpenResult.SamsungNeverSleeping
        }

        // 3) Broader Samsung battery / Device Care screens (user navigates to Never sleeping)
        val batteryFallbacks = listOf(
            Intent("com.samsung.android.sm.ACTION_BATTERY").setPackage("com.samsung.android.lool"),
            Intent("com.samsung.android.sm.ACTION_BATTERY").setPackage("com.samsung.android.sm"),
            Intent().setClassName(
                "com.samsung.android.lool",
                "com.samsung.android.sm.battery.ui.BatteryActivity",
            ),
            Intent().setClassName(
                "com.samsung.android.sm",
                "com.samsung.android.sm.ui.battery.BatteryActivity",
            ),
        )
        for (raw in batteryFallbacks) {
            val intent = Intent(raw).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (tryStart(context, intent)) return OemOpenResult.SamsungBattery
        }

        openAppDetails(context)
        return OemOpenResult.AppDetails
    }

    private fun tryStart(context: Context, intent: Intent): Boolean {
        if (intent.resolveActivity(context.packageManager) == null) return false
        return try {
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.w(TAG, "OEM intent failed: ${e.message}")
            false
        }
    }

    fun canScheduleExactAlarms(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        return am.canScheduleExactAlarms()
    }

    fun openExactAlarmSettings(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (_: Exception) {
            openAppDetails(context)
        }
    }
}
