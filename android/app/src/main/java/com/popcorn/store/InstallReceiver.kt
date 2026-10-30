package com.popcorn.store

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.widget.Toast
import java.io.File

/**
 * Receives the result of an install. When the install finishes (success, failure or cancel)
 * and the customer left "Delete the installer afterwards" on, the downloaded APK is removed.
 */
class InstallReceiver : BroadcastReceiver() {

    companion object {
        const val EXTRA_PATH = "path"
        const val EXTRA_DELETE = "delete"
        const val EXTRA_NAME = "name"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val name = intent.getStringExtra(EXTRA_NAME) ?: "App"

        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // Android wants the customer to tap "Install". Show that screen; we are not done yet.
                val confirm: Intent? =
                    if (Build.VERSION.SDK_INT >= 33)
                        intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                    else
                        @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (confirm != null) context.startActivity(confirm)
            }

            PackageInstaller.STATUS_SUCCESS -> {
                cleanup(intent)
                Toast.makeText(context, "$name installed", Toast.LENGTH_LONG).show()
            }

            else -> {
                cleanup(intent)
                Toast.makeText(context, "$name was not installed", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun cleanup(intent: Intent) {
        if (!intent.getBooleanExtra(EXTRA_DELETE, true)) return
        val path = intent.getStringExtra(EXTRA_PATH) ?: return
        File(path).delete()
    }
}
