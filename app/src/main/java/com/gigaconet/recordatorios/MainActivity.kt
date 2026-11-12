package com.gigaconet.recordatorios

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton

class MainActivity : AppCompatActivity() {

    private lateinit var container: LinearLayout
    private lateinit var emptyView: View
    private lateinit var banner: View
    private lateinit var bannerText: TextView
    private lateinit var bannerButton: MaterialButton

    private val foregroundLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        updateBanner()
        if (GeofenceHelper.hasFine(this) && !GeofenceHelper.hasBackground(this)) {
            explainBackground()
        }
    }

    private val backgroundLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        updateBanner()
        GeofenceHelper.syncAll(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        Notifier.ensureChannel(this)

        container = findViewById(R.id.places_container)
        emptyView = findViewById(R.id.empty_view)
        banner = findViewById(R.id.banner)
        bannerText = findViewById(R.id.banner_text)
        bannerButton = findViewById(R.id.banner_button)

        findViewById<ExtendedFloatingActionButton>(R.id.fab_add).setOnClickListener {
            startActivity(Intent(this, MapPickerActivity::class.java))
        }
        findViewById<MaterialButton>(R.id.battery_button).setOnClickListener { openAppSettings() }

        if (!GeofenceHelper.hasFine(this)) askForeground()
    }

    override fun onResume() {
        super.onResume()
        render()
        updateBanner()
        GeofenceHelper.syncAll(this)
    }

    // ---------- Permisos ----------

    private fun askForeground() {
        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms += Manifest.permission.POST_NOTIFICATIONS
        }
        foregroundLauncher.launch(perms.toTypedArray())
    }

    private fun explainBackground() {
        AlertDialog.Builder(this)
            .setTitle("Un permiso más")
            .setMessage(
                "Para avisarte con la app cerrada, en la siguiente pantalla elige " +
                    "\"Permitir todo el tiempo\" en Ubicación."
            )
            .setPositiveButton("Continuar") { _, _ ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    backgroundLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                }
            }
            .setNegativeButton("Ahora no", null)
            .show()
    }

    private fun updateBanner() {
        when {
            !GeofenceHelper.hasFine(this) -> showBanner(
                "La app necesita tu ubicación para avisarte al llegar a un lugar.",
                "Permitir ubicación"
            ) { askForeground() }

            !GeofenceHelper.hasBackground(this) -> showBanner(
                "Falta elegir \"Permitir todo el tiempo\" en Ubicación. Sin eso solo avisa con la app abierta.",
                "Abrir ajustes"
            ) { openAppSettings() }

            !GeofenceHelper.hasNotifications(this) -> showBanner(
                "Las notificaciones están apagadas, así que no verás los avisos.",
                "Activar"
            ) { openAppSettings() }

            else -> banner.visibility = View.GONE
        }
    }

    private fun showBanner(text: String, action: String, onClick: () -> Unit) {
        banner.visibility = View.VISIBLE
        bannerText.text = text
        bannerButton.text = action
        bannerButton.setOnClickListener { onClick() }
    }

    private fun openAppSettings() {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
        )
    }

    // ---------- Lista de lugares ----------

    private fun render() {
        val places = Store.load(this)
        container.removeAllViews()
        emptyView.visibility = if (places.isEmpty()) View.VISIBLE else View.GONE

        // Primero los lugares con cosas pendientes
        places.sortedWith(compareBy({ it.pending.isEmpty() }, { it.name.lowercase() })).forEach { place ->
            val card = LayoutInflater.from(this).inflate(R.layout.item_place, container, false)
            card.findViewById<TextView>(R.id.place_name).text = place.name
            val pendingCount = place.pending.size
            card.findViewById<TextView>(R.id.place_info).text = buildString {
                append("Aviso a ${place.radius.toInt()} m · ")
                append(
                    when (pendingCount) {
                        0 -> "todo listo"
                        1 -> "1 pendiente"
                        else -> "$pendingCount pendientes"
                    }
                )
            }

            val notesBox = card.findViewById<LinearLayout>(R.id.notes_container)
            place.notes.forEach { note ->
                val cb = CheckBox(this).apply {
                    text = note.text
                    isChecked = note.done
                    alpha = if (note.done) 0.5f else 1f
                    textSize = 16f
                    setOnCheckedChangeListener { _, checked ->
                        Store.update(this@MainActivity) { list ->
                            list.find { it.id == place.id }?.notes?.find { it.id == note.id }?.done = checked
                        }
                        render()
                        GeofenceHelper.syncAll(this@MainActivity)
                    }
                    setOnLongClickListener { confirmDeleteNote(place, note); true }
                }
                notesBox.addView(cb)
            }

            card.findViewById<MaterialButton>(R.id.btn_add_note).setOnClickListener { addNote(place) }
            card.findViewById<MaterialButton>(R.id.btn_clear_done).apply {
                visibility = if (place.notes.any { it.done }) View.VISIBLE else View.GONE
                setOnClickListener { clearDone(place) }
            }
            card.findViewById<MaterialButton>(R.id.btn_delete_place).setOnClickListener { confirmDeletePlace(place) }

            container.addView(card)
        }
    }

    private fun addNote(place: Place) {
        val input = EditText(this).apply {
            hint = "Ej: comprar cable UTP"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val wrap = FrameLayout(this).apply { setPadding(pad, pad / 2, pad, 0); addView(input) }

        AlertDialog.Builder(this)
            .setTitle("Nueva nota en ${place.name}")
            .setView(wrap)
            .setPositiveButton("Guardar") { _, _ ->
                val text = input.text.toString().trim()
                if (text.isNotEmpty()) {
                    Store.update(this) { list ->
                        list.find { it.id == place.id }?.notes?.add(Note(text = text))
                    }
                    render()
                    GeofenceHelper.syncAll(this)
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
        input.requestFocus()
    }

    private fun clearDone(place: Place) {
        Store.update(this) { list ->
            list.find { it.id == place.id }?.notes?.removeAll { it.done }
        }
        render()
    }

    private fun confirmDeleteNote(place: Place, note: Note) {
        AlertDialog.Builder(this)
            .setTitle("¿Borrar esta nota?")
            .setMessage(note.text)
            .setPositiveButton("Borrar") { _, _ ->
                Store.update(this) { list ->
                    list.find { it.id == place.id }?.notes?.removeAll { it.id == note.id }
                }
                render()
                GeofenceHelper.syncAll(this)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun confirmDeletePlace(place: Place) {
        AlertDialog.Builder(this)
            .setTitle("¿Borrar ${place.name}?")
            .setMessage("Se borran el lugar y sus ${place.notes.size} notas. No se puede deshacer.")
            .setPositiveButton("Borrar") { _, _ ->
                Store.update(this) { list -> list.removeAll { it.id == place.id } }
                render()
                GeofenceHelper.syncAll(this)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }
}
