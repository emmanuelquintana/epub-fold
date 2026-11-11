package com.emmanuel.epubfold

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.crashlytics.FirebaseCrashlytics

/**
 * Informes de fallos con Firebase Crashlytics. Sólo funcionan cuando la app se compila
 * con Firebase; se pueden desactivar en Cuenta → Aplicación.
 */
object CrashReports {

    private fun sp(c: Context) = c.getSharedPreferences("reader", Context.MODE_PRIVATE)

    fun available(context: Context): Boolean =
        BuildConfig.HAS_FIREBASE && runCatching { FirebaseApp.getApps(context).isNotEmpty() }.getOrDefault(false)

    fun enabled(context: Context) = sp(context).getBoolean("crashReports", true)

    fun setEnabled(context: Context, on: Boolean) {
        sp(context).edit().putBoolean("crashReports", on).apply()
        apply(context)
    }

    fun apply(context: Context) {
        if (!available(context)) return
        runCatching { FirebaseCrashlytics.getInstance().setCrashlyticsCollectionEnabled(enabled(context)) }
    }

    /** Anota un error que no cerró la app pero conviene conocer. */
    fun log(context: Context, e: Throwable, where: String) {
        if (!available(context) || !enabled(context)) return
        runCatching {
            FirebaseCrashlytics.getInstance().apply {
                log(where)
                recordException(e)
            }
        }
    }
}
