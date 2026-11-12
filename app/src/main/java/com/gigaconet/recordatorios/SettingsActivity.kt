package com.gigaconet.recordatorios

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider

class SettingsActivity : AppCompatActivity() {

    private lateinit var soundValue: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        findViewById<MaterialButton>(R.id.btn_back).setOnClickListener { finish() }

        // Tema
        val themeGroup = findViewById<ChipGroup>(R.id.theme_group)
        themeGroup.check(
            when (AppPrefs.theme(this)) {
                AppPrefs.THEME_LIGHT -> R.id.theme_light
                AppPrefs.THEME_DARK -> R.id.theme_dark
                else -> R.id.theme_system
            }
        )
        themeGroup.setOnCheckedStateChangeListener { _, ids ->
            val t = when (ids.firstOrNull()) {
                R.id.theme_light -> AppPrefs.THEME_LIGHT
                R.id.theme_dark -> AppPrefs.THEME_DARK
                else -> AppPrefs.THEME_SYSTEM
            }
            if (t != AppPrefs.theme(this)) AppPrefs.setTheme(this, t)
        }

        // Aviso al instante
        val instant = findViewById<MaterialSwitch>(R.id.instant_switch)
        instant.isChecked = TrackerService.isEnabled(this)
        instant.setOnCheckedChangeListener { _, on -> TrackerService.setEnabled(this, on) }
        findViewById<android.view.View>(R.id.row_instant).setOnClickListener { instant.toggle() }

        // Sonido
        soundValue = findViewById(R.id.sound_value)
        showSound()
        findViewById<android.view.View>(R.id.row_sound).setOnClickListener { pickSound() }
        findViewById<android.view.View>(R.id.row_test).setOnClickListener { testNotification() }

        // Distancia por defecto
        val radiusValue = findViewById<TextView>(R.id.radius_value)
        val slider = findViewById<Slider>(R.id.radius_slider)
        val r = AppPrefs.defaultRadius(this).coerceIn(30f, 1000f)
        slider.value = (Math.round(r / 10f) * 10f).coerceIn(30f, 1000f)
        radiusValue.text = Geo.distanceText(slider.value)
        slider.addOnChangeListener { _, value, fromUser ->
            radiusValue.text = Geo.distanceText(value)
            if (fromUser) AppPrefs.setDefaultRadius(this, value)
        }

        // Permisos
        findViewById<android.view.View>(R.id.row_perm_location).setOnClickListener { openAppSettings() }
        findViewById<android.view.View>(R.id.row_perm_notif).setOnClickListener { openAppSettings() }
        findViewById<android.view.View>(R.id.row_perm_battery).setOnClickListener { askBattery() }

        val version = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (e: Exception) { "" }
        findViewById<TextView>(R.id.version_text).text = "Recuérdame Aquí · versión $version"
    }

    override fun onResume() {
        super.onResume()
        findViewById<MaterialSwitch>(R.id.instant_switch).isChecked = TrackerService.isEnabled(this)
        setStatus(R.id.perm_location, GeofenceHelper.hasFine(this) && GeofenceHelper.hasBackground(this))
        setStatus(R.id.perm_notif, GeofenceHelper.hasNotifications(this))
        setStatus(R.id.perm_battery, ignoresBattery())
    }

    private fun setStatus(id: Int, ok: Boolean) {
        findViewById<TextView>(id).apply {
            text = if (ok) "Listo" else "Revisar"
            setTextColor(ContextCompat.getColor(this@SettingsActivity, if (ok) R.color.ok else R.color.warn))
        }
    }

    // ---------- Sonido ----------

    private fun showSound() {
        soundValue.text = AppPrefs.sound(this).label + if (AppPrefs.sound(this) != AvisoSound.SISTEMA) " · propio de la app" else ""
    }

    private fun pickSound() {
        val sounds = AvisoSound.entries
        var chosen = sounds.indexOf(AppPrefs.sound(this))
        MaterialAlertDialogBuilder(this)
            .setTitle("Sonido del aviso")
            .setSingleChoiceItems(sounds.map { it.label }.toTypedArray(), chosen) { _, i ->
                chosen = i
                Notifier.preview(this, sounds[i]) // se escucha al tocarlo
            }
            .setPositiveButton("Usar este") { _, _ ->
                AppPrefs.setSound(this, sounds[chosen])
                Notifier.ensureChannel(this)
                showSound()
            }
            .setNeutralButton("Más opciones") { _, _ -> openChannelSettings() }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    /** Ajustes de Android de este aviso: volumen, vibración, tono personalizado. */
    private fun openChannelSettings() {
        Notifier.ensureChannel(this)
        val intent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            .putExtra(Settings.EXTRA_CHANNEL_ID, Notifier.channelId(this))
        try { startActivity(intent) } catch (e: Exception) { openAppSettings() }
    }

    private fun testNotification() {
        if (!GeofenceHelper.hasNotifications(this)) {
            Toast.makeText(this, "Primero activa las notificaciones de la app", Toast.LENGTH_LONG).show()
            openAppSettings()
            return
        }
        Notifier.showTest(this)
    }

    // ---------- Permisos ----------

    private fun ignoresBattery(): Boolean =
        getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(packageName) == true

    @SuppressLint("BatteryLife")
    private fun askBattery() {
        if (ignoresBattery()) { openAppSettings(); return }
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            )
        } catch (e: Exception) {
            openAppSettings()
        }
    }

    private fun openAppSettings() {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
        )
    }
}
