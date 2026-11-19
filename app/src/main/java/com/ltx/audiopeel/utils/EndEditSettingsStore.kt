package com.ltx.audiopeel.utils

import android.content.Context
import androidx.core.content.edit
import com.ltx.audiopeel.EndEditKind
import com.ltx.audiopeel.EndEditOption
import com.ltx.audiopeel.EndEditSettings

/**
 * Persists the end edit settings across app restarts
 *
 * @param context context
 */
class EndEditSettingsStore(context: Context) {
    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Loads the saved settings, falling back to the defaults
     */
    fun load(): EndEditSettings = EndEditKind.entries.fold(EndEditSettings()) { settings, kind ->
        val default = settings[kind]
        val seconds = prefs.getInt(secondsKey(kind), EndEditOption.DEFAULT_SECONDS)
            .takeIf { it in EndEditOption.MIN_SECONDS..EndEditOption.MAX_SECONDS }
            ?: EndEditOption.DEFAULT_SECONDS
        settings.with(
            kind, EndEditOption(
                enabled = prefs.getBoolean(enabledKey(kind), default.enabled),
                secondsText = seconds.toString()
            )
        )
    }

    /**
     * Saves the settings; a length that isn't valid keeps the last valid one
     */
    fun save(settings: EndEditSettings) {
        prefs.edit {
            EndEditKind.entries.forEach { kind ->
                val option = settings[kind]
                putBoolean(enabledKey(kind), option.enabled)
                option.seconds?.let { putInt(secondsKey(kind), it) }
            }
        }
    }

    private fun enabledKey(kind: EndEditKind) = "${kind.name.lowercase()}_enabled"
    private fun secondsKey(kind: EndEditKind) = "${kind.name.lowercase()}_seconds"

    private companion object {
        const val PREFS_NAME = "end_edits"
    }
}
