package com.ojas.permsense.ui

import android.content.Context
import android.content.res.Configuration
import com.ojas.permsense.engine.AuditEngine
import com.ojas.permsense.i18n.Lang
import java.util.Locale

/**
 * The language the person chose inside the app (English or Hindi), applied to a context. When no
 * choice has been made the phone's own language applies, which Android already handles.
 */
object Localized {

    fun wrap(base: Context): Context {
        val tag = AuditEngine.savedLanguageTag(base) ?: return base
        val locale = Locale.forLanguageTag(tag)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        return base.createConfigurationContext(config)
    }

    /** The language findings and reports are written in: the saved choice, else the phone's. */
    fun langOf(context: Context): Lang =
        Lang.fromTag(AuditEngine.savedLanguageTag(context) ?: Locale.getDefault().language)
}
