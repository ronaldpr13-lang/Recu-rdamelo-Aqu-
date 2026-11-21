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
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
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
        TrackerService.refresh(this)
        if (GeofenceHelper.hasFine(this) && !GeofenceHelper.hasBackground(this)) {
            explainBackground()
        }
    }

    private val activityLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { GeofenceHelper.syncAll(this) }

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

        findViewById<ExtendedFloatingActionButton>(R.id.fab_add).setOnClickListener { chooseKind() }
        findViewById<MaterialButton>(R.id.btn_settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        Chains.schedulePeriodic(this)
        if (!GeofenceHelper.hasFine(this)) askForeground()
        else if (!GeofenceHelper.hasActivity(this) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Para ahorrar batería: saber cuándo empiezas a moverte
            activityLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION)
        }
    }

    override fun onStart() {
        super.onStart()
        Chains.onUpdated = { if (!isFinishing) render() }
    }

    override fun onStop() {
        Chains.onUpdated = null
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        // Si hace mucho que no se buscan sucursales, buscar de nuevo
        if (System.currentTimeMillis() - Chains.lastSearchTime(this) > 6 * 3_600_000L &&
            Store.load(this).any { it.chain != null }
        ) Chains.refresh(this)
        render()
        updateBanner()
        refreshMyLocation()
        // Vuelve a crear las zonas si cambiaron los permisos (o la primera vez)
        val ok = GeofenceHelper.hasFine(this) && GeofenceHelper.hasBackground(this)
        if (ok != lastPermsOk) {
            lastPermsOk = ok
            GeofenceHelper.syncAll(this)
        }
        TrackerService.refresh(this)
    }

    private fun changed() {
        render()
        GeofenceHelper.syncAll(this)
        TrackerService.refresh(this)
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            perms += Manifest.permission.ACTIVITY_RECOGNITION
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

    @SuppressLint("MissingPermission")
    private fun refreshMyLocation() {
        if (!GeofenceHelper.hasFine(this)) return
        val client = LocationServices.getFusedLocationProviderClient(this)
        // Primero la última conocida si es reciente, luego una lectura fresca del GPS
        client.lastLocation.addOnSuccessListener { loc ->
            if (loc != null && !isFinishing && myLocation == null &&
                System.currentTimeMillis() - loc.time < 2 * 60_000
            ) {
                myLocation = loc
                render()
            }
        }
        client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, CancellationTokenSource().token)
            .addOnSuccessListener { loc ->
                if (loc != null && !isFinishing) {
                    myLocation = loc
                    render()
                }
            }
    }

    // ---------- Lista de lugares ----------

    private fun distanceTo(p: Place): Float? {
        val loc = myLocation ?: return null
        if (p.chain != null) {
            return Chains.branches(this, p.id)?.minOfOrNull {
                Place(name = "", lat = it.lat, lng = it.lng, radius = 0f).distanceTo(loc.latitude, loc.longitude)
            }
        }
        return p.distanceTo(loc.latitude, loc.longitude)
    }

    private fun render() {
        val places = Store.load(this)
        container.removeAllViews()
        val pendingAll = places.sumOf { it.pending.size }
        emptyView.visibility = if (places.isEmpty()) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.header_sub).text = when {
            places.isEmpty() -> "Tus recordatorios por lugar"
            pendingAll == 0 -> "Todo al día"
            pendingAll == 1 -> "1 cosa pendiente"
            else -> "$pendingAll cosas pendientes"
        }

        // Primero los que tienen pendientes; luego los más cercanos
        val sorted = places.sortedWith(
            compareBy<Place>({ it.pending.isEmpty() }, { distanceTo(it) ?: Float.MAX_VALUE }, { it.name.lowercase() })
        )

        sorted.forEach { place ->
            val card = LayoutInflater.from(this).inflate(R.layout.item_place, container, false)
            card.findViewById<TextView>(R.id.place_name).text = place.name

            val isChain = place.chain != null
            val addressView = card.findViewById<TextView>(R.id.place_address)
            val showAddress = isChain || (place.address.isNotBlank() && !place.address.startsWith(place.name))
            addressView.visibility = if (showAddress) View.VISIBLE else View.GONE
            addressView.text = if (isChain) "Cualquier sucursal" else place.address

            val pendingCount = place.pending.size
            card.findViewById<TextView>(R.id.place_info).text = buildList {
                if (isChain) {
                    val br = Chains.branches(this@MainActivity, place.id)
                    when {
                        br == null -> add("Buscando sucursales cerca…")
                        br.isEmpty() -> add("Sin sucursales a 8 km; reviso al moverte")
                        else -> {
                            val near = distanceTo(place)
                            add(
                                "${br.size} sucursales cerca" +
                                    (near?.let { " (la más cercana a ${Geo.distanceText(it)})" } ?: "")
                            )
                        }
                    }
                } else distanceTo(place)?.let { d ->
                    val acc = myLocation?.accuracy ?: 0f
                    add("Estás a ${Geo.distanceText(d)}" + if (acc > 20) " (GPS ±${acc.toInt()} m)" else "")
                }
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
            if (place.chain != null) {
                menu.add(0, 3, 0, "Cambiar distancia")
                menu.add(0, 4, 1, "Buscar sucursales de nuevo")
            } else {
                menu.add(0, 1, 0, "Editar lugar y distancia")
            }
            menu.add(0, 2, 2, if (place.chain != null) "Borrar aviso" else "Borrar lugar")
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> startActivity(
                        Intent(this@MainActivity, MapPickerActivity::class.java)
                            .putExtra(MapPickerActivity.EXTRA_PLACE_ID, place.id)
                    )
                    2 -> confirmDeletePlace(place)
                    3 -> pickChainRadius(place)
                    4 -> {
                        Toast.makeText(this@MainActivity, "Buscando sucursales cerca de ti…", Toast.LENGTH_SHORT).show()
                        Chains.refresh(this@MainActivity)
                    }
                }
                true
            }
            show()
        }
    }

    // ---------- Aviso por comercio (cualquier sucursal) ----------

    private fun chooseKind() {
        MaterialAlertDialogBuilder(this)
            .setTitle("¿Qué quieres recordar?")
            .setItems(
                arrayOf(
                    "En un lugar del mapa\nCasa, una tienda específica…",
                    "En cualquier sucursal\nBanco Reservas, farmacia, supermercado…"
                )
            ) { _, which ->
                if (which == 0) startActivity(Intent(this, MapPickerActivity::class.java))
                else addChain()
            }
            .show()
    }

    private fun addChain() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_chain, null)
        val name = view.findViewById<TextInputEditText>(R.id.chain_name)
        val note = view.findViewById<TextInputEditText>(R.id.chain_note)
        val radiusGroup = view.findViewById<ChipGroup>(R.id.chain_radius)
        val suggestions = view.findViewById<ChipGroup>(R.id.chain_suggestions)
        for (i in 0 until suggestions.childCount) {
            (suggestions.getChildAt(i) as? Chip)?.setOnClickListener { name.setText((it as Chip).text) }
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("Aviso por comercio")
            .setView(view)
            .setPositiveButton("Guardar") { _, _ ->
                val brand = name.text?.toString()?.trim().orEmpty()
                val text = note.text?.toString()?.trim().orEmpty()
                if (brand.isEmpty() || text.isEmpty()) {
                    Toast.makeText(this, "Escribe el comercio y qué tienes que hacer", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                val radius = when (radiusGroup.checkedChipId) {
                    R.id.cr_100 -> 100f
                    R.id.cr_400 -> 400f
                    R.id.cr_800 -> 800f
                    else -> 200f
                }
                Store.update(this) { list ->
                    val existing = list.find { it.chain != null && it.name.equals(brand, ignoreCase = true) }
                    if (existing != null) {
                        existing.notes.add(Note(text = text))
                        existing.radius = radius
                    } else {
                        list.add(
                            Place(
                                name = brand, lat = 0.0, lng = 0.0, radius = radius,
                                notes = mutableListOf(Note(text = text)), chain = brand
                            )
                        )
                    }
                }
                Toast.makeText(this, "Buscando sucursales de $brand cerca de ti…", Toast.LENGTH_LONG).show()
                Chains.refresh(this)
                changed()
            }
            .setNegativeButton("Cancelar", null)
            .show()
        name.requestFocus()
    }

    private fun pickChainRadius(place: Place) {
        val options = floatArrayOf(100f, 200f, 400f, 800f)
        var chosen = options.indexOfFirst { it == place.radius }.let { if (it < 0) 1 else it }
        MaterialAlertDialogBuilder(this)
            .setTitle("Avisar a menos de")
            .setSingleChoiceItems(options.map { Geo.distanceText(it) }.toTypedArray(), chosen) { _, i -> chosen = i }
            .setPositiveButton("Guardar") { _, _ ->
                Store.update(this) { list -> list.find { it.id == place.id }?.radius = options[chosen] }
                changed()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun addNote(place: Place) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_note, null)
        val input = view.findViewById<TextInputEditText>(R.id.dialog_note_input)
        val group = view.findViewById<ChipGroup>(R.id.dialog_trigger_group)
        // Si el lugar ya tiene notas "al salir" (ej: la casa), sugerir esa opción
        if (place.chain != null) {
            group.visibility = View.GONE // en un comercio solo se avisa al pasar cerca
        } else if (place.notes.isNotEmpty() && place.notes.all { it.trigger == Trigger.LEAVE }) {
            group.check(R.id.dlg_leave)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("Nueva nota en ${place.name}")
            .setView(view)
            .setPositiveButton("Guardar") { _, _ ->
                val text = input.text?.toString()?.trim().orEmpty()
                if (text.isNotEmpty()) {
                    val trigger = if (place.chain == null && group.checkedChipId == R.id.dlg_leave) Trigger.LEAVE else Trigger.ARRIVE
                    Store.update(this) { list ->
                        list.find { it.id == place.id }?.notes?.add(Note(text = text, trigger = trigger))
                    }
                    if (place.chain != null) Chains.refresh(this)
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
            .setMessage(
                if (place.chain != null) "Se borra este aviso y sus ${place.notes.size} notas. No se puede deshacer."
                else "Se borran el lugar y sus ${place.notes.size} notas. No se puede deshacer."
            )
            .setPositiveButton("Borrar") { _, _ ->
                Store.update(this) { list -> list.removeAll { it.id == place.id } }
                changed()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }
}
