package app.fernreader.library

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import app.fernreader.onboarding.StoragePermissionPort

/**
 * F-001 (C-008): all-files access on API 30+, READ_EXTERNAL_STORAGE on API 26–29. Construct during the activity's
 * initialisation (it registers an activity-result launcher). [onResult] runs after the API 26–29 dialog; on API
 * 30+ the user returns through onResume, where the activity checks [granted] again.
 */
class StorageAccess(private val activity: ComponentActivity, onResult: () -> Unit) : StoragePermissionPort {
    private val legacy = activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { onResult() }

    override fun granted(): Boolean = hasAccess(activity)

    companion object {
        /**
         * Instrumented tests only: the system kills the app (and the test running in it) whenever all-files access
         * changes, so routing tests replace the check instead of toggling the real permission.
         */
        @androidx.annotation.VisibleForTesting var grantedOverride: (() -> Boolean)? = null

        fun hasAccess(context: Context): Boolean = grantedOverride?.invoke() ?: if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
            else context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }

    override fun request() {
        if (Build.VERSION.SDK_INT < 30) { legacy.launch(Manifest.permission.READ_EXTERNAL_STORAGE); return }
        try {
            activity.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${activity.packageName}")))
        } catch (e: ActivityNotFoundException) { // some builds lack the per-app screen
            activity.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        }
    }
}
