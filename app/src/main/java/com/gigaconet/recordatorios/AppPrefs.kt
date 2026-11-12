package com.gigaconet.recordatorios

import android.app.Application
import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

/** Sonidos propios de la app (en res/raw). "sistema" usa el tono normal del teléfono. */
enum class AvisoSound(val key: String, val label: String, val rawRes: Int?) {
    CAMPANITA("campanita", "Campanita", R.raw.aviso_campanita),
    BURBUJA("burbuja", "Burbuja", R.raw.aviso_burbuja),
    SUAVE("suave", "Suave", R.raw.aviso_suave),
    SISTEMA("sistema", "Tono del teléfono", null);

    companion object {
        fun from(key: String?) = entries.firstOrNull { it.key == key } ?: CAMPANITA
    }
}

/** Ajustes del usuario. */
object AppPrefs {
    const val THEME_SYSTEM = 0
    const val THEME_LIGHT = 1
    const val THEME_DARK = 2

    private fun p(ctx: Context) = ctx.getSharedPreferences("ajustes", Context.MODE_PRIVATE)

    fun theme(ctx: Context) = p(ctx).getInt("theme", THEME_SYSTEM)
    fun setTheme(ctx: Context, t: Int) {
        p(ctx).edit().putInt("theme", t).apply()
        applyTheme(t)
    }

    fun applyTheme(t: Int) = AppCompatDelegate.setDefaultNightMode(
        when (t) {
            THEME_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            THEME_DARK -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
    )

    fun defaultRadius(ctx: Context) = p(ctx).getFloat("radius", 150f)
    fun setDefaultRadius(ctx: Context, r: Float) = p(ctx).edit().putFloat("radius", r).apply()

    fun sound(ctx: Context) = AvisoSound.from(p(ctx).getString("sound", null))
    fun setSound(ctx: Context, s: AvisoSound) = p(ctx).edit().putString("sound", s.key).apply()
}

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        AppPrefs.applyTheme(AppPrefs.theme(this))
    }
}
