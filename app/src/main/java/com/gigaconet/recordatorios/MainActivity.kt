package com.gigaconet.recordatorios

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.location.Location
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.textfield.TextInputEditText

class MainActivity : AppCompatActivity() {

    private lateinit var container: LinearLayout
    private lateinit var emptyView: View
    private lateinit var banner: View
    private lateinit var bannerText: TextView
    private lateinit var bannerButton: MaterialButton

    private var myLocation: Location? = null
    private var lastPermsOk: Boolean? = null

    private val foregroundLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        updateBanner()
        refreshMyLocation()
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
        findViewById<MaterialButton>(R.id.test_button).setOnClickListener { testNotification() }
        findViewById<MaterialButton>(R.id.sound_button).setOnClickListener { openSoundSettings() }

        if (!GeofenceHelper.hasFine(this)) askForeground()
    }

    override fun onResume() {
        super.onResume()
        render()
        updateBanner()
        refreshMyLocation()
        // Vuelve a crear las zonas si cambiaron los permisos (o la primera vez)
        val ok = GeofenceHelper.hasFine(this) && GeofenceHelper.hasBackground(this)
        if (ok != lastPermsOk) {
            lastPermsOk = ok
            GeofenceHelper.syncAll(this)
        }
    }

    private fun changed() {
        render()
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
        MaterialAlertDialogBuilder(this)
            .setTitle("Un permiso más")
            .setMessage(
                "Para avisarte al llegar o al salir con la app cerrada, en la siguiente pantalla elige " +
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
                "La app necesita tu ubicación para avisarte al llegar o salir de un lugar.",
                "Permitir ubicación"
            ) { askForeground() }

            !GeofenceHelper.hasBackground(this) -> showBanner(
                "Falta elegir \"Permitir todo el tiempo\" en Ubicación. Sin eso no te puede avisar con la app cerrada.",
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

    private fun testNotification() {
        if (!GeofenceHelper.hasNotifications(this)) {
            Toast.makeText(this, "Primero activa las notificaciones de la app", Toast.LENGTH_LONG).show()
            openAppSettings()
            return
        }
        Notifier.showTest(this)
        Toast.makeText(this, "Así te llegará el aviso", Toast.LENGTH_SHORT).show()
    }

    /** Abre la pantalla de Android donde se escoge el sonido de estos avisos. */
    private fun openSoundSettings() {
        Notifier.ensureChannel(this)
        val intent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            .putExtra(Settings.EXTRA_CHANNEL_ID, Notifier.CHANNEL_ID)
        try {
            startActivity(intent)
            Toast.makeText(this, "Toca «Sonido» y escoge el que quieras", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            openAppSettings()
        }
    }

    @SuppressLint("MissingPermission")
    private fun refreshMyLocation() {
        if (!GeofenceHelper.hasFine(this)) return
        LocationServices.getFusedLocationProviderClient(this).lastLocation
            .addOnSuccessListener { loc ->
                if (loc != null && !isFinishing) {
                    myLocation = loc
                    render()
                }
            }
    }

    // ---------- Lista de lugares ----------

    private fun distanceTo(p: Place): Float? = myLocation?.let { p.distanceTo(it.latitude, it.longitude) }

    private fun render() {
        val places = Store.load(this)
        container.removeAllViews()
        emptyView.visibility = if (places.isEmpty()) View.VISIBLE else View.GONE

        // Primero los que tienen pendientes; luego los más cercanos
        val sorted = places.sortedWith(
            compareBy<Place>({ it.pending.isEmpty() }, { distanceTo(it) ?: Float.MAX_VALUE }, { it.name.lowercase() })
        )

        sorted.forEach { place ->
            val card = LayoutInflater.from(this).inflate(R.layout.item_place, container, false)
            card.findViewById<TextView>(R.id.place_name).text = place.name

            val addressView = card.findViewById<TextView>(R.id.place_address)
            val showAddress = place.address.isNotBlank() && !place.address.startsWith(place.name)
            addressView.visibility = if (showAddress) View.VISIBLE else View.GONE
            addressView.text = place.address

            val pendingCount = place.pending.size
            card.findViewById<TextView>(R.id.place_info).text = buildList {
                distanceTo(place)?.let { add("Estás a ${Geo.distanceText(it)}") }
                add("aviso a ${Geo.distanceText(place.radius)}")
                add(
                    when (pendingCount) {
                        0 -> "todo listo"
                        1 -> "1 pendiente"
                        else -> "$pendingCount pendientes"
                    }
                )
            }.joinToString(" · ")

            val notesBox = card.findViewById<LinearLayout>(R.id.notes_container)
            place.notes.sortedBy { it.done }.forEach { note -> notesBox.addView(noteRow(place, note)) }

            card.findViewById<MaterialButton>(R.id.btn_add_note).setOnClickListener { addNote(place) }
            card.findViewById<MaterialButton>(R.id.btn_clear_done).apply {
                visibility = if (place.notes.any { it.done }) View.VISIBLE else View.GONE
                setOnClickListener { clearDone(place) }
            }
            card.findViewById<MaterialButton>(R.id.btn_more).setOnClickListener { anchor -> showPlaceMenu(anchor, place) }

            container.addView(card)
        }
    }

    private fun noteRow(place: Place, note: Note): View {
        val row = LayoutInflater.from(this).inflate(R.layout.item_note, container, false)
        val cb = row.findViewById<CheckBox>(R.id.note_check)
        val tag = row.findViewById<TextView>(R.id.note_tag)

        cb.text = note.text
        cb.isChecked = note.done
        row.alpha = if (note.done) 0.5f else 1f
        cb.setOnCheckedChangeListener { _, checked ->
            Store.update(this) { list ->
                list.find { it.id == place.id }?.notes?.find { it.id == note.id }?.done = checked
            }
            changed()
        }
        cb.setOnLongClickListener { confirmDeleteNote(place, note); true }

        val leave = note.trigger == Trigger.LEAVE
        tag.text = if (leave) "Al salir" else "Al llegar"
        tag.setBackgroundResource(if (leave) R.drawable.bg_tag_leave else R.drawable.bg_tag_arrive)
        tag.setTextColor(ContextCompat.getColor(this, if (leave) R.color.tag_leave_fg else R.color.tag_arrive_fg))
        return row
    }

    private fun showPlaceMenu(anchor: View, place: Place) {
        PopupMenu(this, anchor).apply {
            menu.add(0, 1, 0, "Editar lugar y distancia")
            menu.add(0, 2, 1, "Borrar lugar")
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> startActivity(
                        Intent(this@MainActivity, MapPickerActivity::class.java)
                            .putExtra(MapPickerActivity.EXTRA_PLACE_ID, place.id)
                    )
                    2 -> confirmDeletePlace(place)
                }
                true
            }
            show()
        }
    }

    private fun addNote(place: Place) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_note, null)
        val input = view.findViewById<TextInputEditText>(R.id.dialog_note_input)
        val group = view.findViewById<MaterialButtonToggleGroup>(R.id.dialog_trigger_group)
        // Si el lugar ya tiene notas "al salir" (ej: la casa), sugerir esa opción
        if (place.notes.isNotEmpty() && place.notes.all { it.trigger == Trigger.LEAVE }) {
            group.check(R.id.dlg_leave)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("Nueva nota en ${place.name}")
            .setView(view)
            .setPositiveButton("Guardar") { _, _ ->
                val text = input.text?.toString()?.trim().orEmpty()
                if (text.isNotEmpty()) {
                    val trigger = if (group.checkedButtonId == R.id.dlg_leave) Trigger.LEAVE else Trigger.ARRIVE
                    Store.update(this) { list ->
                        list.find { it.id == place.id }?.notes?.add(Note(text = text, trigger = trigger))
                    }
                    changed()
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
        MaterialAlertDialogBuilder(this)
            .setTitle("¿Borrar esta nota?")
            .setMessage(note.text)
            .setPositiveButton("Borrar") { _, _ ->
                Store.update(this) { list ->
                    list.find { it.id == place.id }?.notes?.removeAll { it.id == note.id }
                }
                changed()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun confirmDeletePlace(place: Place) {
        MaterialAlertDialogBuilder(this)
            .setTitle("¿Borrar ${place.name}?")
            .setMessage("Se borran el lugar y sus ${place.notes.size} notas. No se puede deshacer.")
            .setPositiveButton("Borrar") { _, _ ->
                Store.update(this) { list -> list.removeAll { it.id == place.id } }
                changed()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }
}
