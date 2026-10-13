package dev.qnzapret

import android.content.Context

/** Последний результат явного consent/start; чтение не переподготавливает VPN. */
internal object VpnConsentStore {
    private const val PREFS = "vpn_consent"
    private const val KEY_GRANTED = "last_granted"

    fun read(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_GRANTED, false)

    fun record(context: Context, granted: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_GRANTED, granted).apply()
    }
}
