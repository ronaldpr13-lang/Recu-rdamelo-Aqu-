package com.gigaconet.recordatorios

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import kotlin.math.roundToInt

/**
 * Escoger el lugar: el pin queda fijo en el centro y mueves el mapa debajo.
 * La dirección se llena sola; solo hace falta escribir la nota.
 */
class MapPickerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PLACE_ID = "place_id"
        private const val MIN_RADIUS = 30f
        private const val MAX_RADIUS = 2000f
        private const val STEP = 10f
    }

    private lateinit var map: MapView
    private lateinit var addressText: TextView
    private lateinit var radiusLabel: TextView
    private lateinit var slider: Slider
    private lateinit var nameInput: TextInputEditText
    private lateinit var noteInput: TextInputEditText
    private lateinit var noteLayout: TextInputLayout
    private lateinit var searchInput: TextInputEditText
    private lateinit var triggerGroup: MaterialButtonToggleGroup
    private lateinit var circle: Polygon
    private lateinit var gpsText: TextView
    private var myDot: MyLocationNewOverlay? = null

    private var editing: Place? = null
    private var radius = 200f
    private var lastAddress = ""
    private var geocodeTicket = 0
    private var userMovedMap = false
    private val handler = Handler(Looper.getMainLooper())
    private val geocodeRunnable = Runnable { lookupAddress() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // OpenStreetMap pide identificar la app que descarga el mapa
        Configuration.getInstance().load(this, getSharedPreferences("osmdroid", MODE_PRIVATE))
        Configuration.getInstance().userAgentValue = packageName

        setContentView(R.layout.activity_map)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val all = Store.load(this)
        editing = intent.getStringExtra(EXTRA_PLACE_ID)?.let { id -> all.find { it.id == id } }
        val ed = editing
        title = if (ed != null) "Editar lugar" else "Nuevo recordatorio"

        map = findViewById(R.id.map)
        addressText = findViewById(R.id.address_text)
        radiusLabel = findViewById(R.id.radius_label)
        slider = findViewById(R.id.radius_slider)
        nameInput = findViewById(R.id.name_input)
        noteInput = findViewById(R.id.note_input)
        noteLayout = findViewById(R.id.note_layout)
        searchInput = findViewById(R.id.search_input)
        triggerGroup = findViewById(R.id.trigger_group)
        gpsText = findViewById(R.id.gps_text)

        setupMap(all)
        setupControls()

        if (ed != null) {
            findViewById<View>(R.id.new_note_section).visibility = View.GONE
            nameInput.setText(ed.name)
            lastAddress = ed.address
            addressText.text = ed.address.ifBlank { ed.name }
            setRadius(ed.radius)
            map.controller.setZoom(17.0)
            map.controller.setCenter(GeoPoint(ed.lat, ed.lng))
            findViewById<MaterialButton>(R.id.btn_save).text = "Guardar cambios"
        } else {
            setRadius(200f)
            map.controller.setZoom(8.5)
            map.controller.setCenter(GeoPoint(18.75, -70.2)) // República Dominicana
            centerOnUser(quiet = true)
        }
        drawCircle()
        handler.postDelayed(geocodeRunnable, 300)
    }

    private fun setupMap(all: List<Place>) {
        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)
        map.isTilesScaledToDpi = true
        map.minZoomLevel = 5.0
        map.maxZoomLevel = 19.5
        map.zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)

        // Tus otros lugares, para que los veas mientras escoges
        all.filter { it.id != editing?.id }.forEach { p ->
            map.overlays.add(Polygon(map).apply {
                setPoints(Polygon.pointsAsCircle(GeoPoint(p.lat, p.lng), p.radius.toDouble()))
                fillPaint.color = ContextCompat.getColor(this@MapPickerActivity, R.color.other_fill)
                outlinePaint.color = ContextCompat.getColor(this@MapPickerActivity, R.color.other_stroke)
                outlinePaint.strokeWidth = 3f
                setOnClickListener { _, _, _ -> false }
            })
            map.overlays.add(Marker(map).apply {
                position = GeoPoint(p.lat, p.lng)
                title = p.name
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                setOnMarkerClickListener { m, _ ->
                    Toast.makeText(this@MapPickerActivity, m.title, Toast.LENGTH_SHORT).show()
                    true
                }
            })
        }

        circle = Polygon(map).apply {
            fillPaint.color = ContextCompat.getColor(this@MapPickerActivity, R.color.zone_fill)
            outlinePaint.color = ContextCompat.getColor(this@MapPickerActivity, R.color.zone_stroke)
            outlinePaint.strokeWidth = 4f
            setOnClickListener { _, _, _ -> false }
        }
        map.overlays.add(circle)

        // Punto azul: dónde estás tú ahora mismo
        if (GeofenceHelper.hasFine(this)) {
            myDot = MyLocationNewOverlay(GpsMyLocationProvider(this), map).also { map.overlays.add(it) }
        }

        // Tocar el mapa lleva el pin a ese punto
        map.overlays.add(0, MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                userMovedMap = true
                map.controller.animateTo(p)
                return true
            }
            override fun longPressHelper(p: GeoPoint): Boolean = false
        }))

        map.addMapListener(object : MapListener {
            override fun onScroll(event: ScrollEvent?): Boolean { onCenterMoved(); return false }
            override fun onZoom(event: ZoomEvent?): Boolean { onCenterMoved(); return false }
        })
        map.setOnTouchListener { _, _ -> userMovedMap = true; false }
    }

    private fun setupControls() {
        slider.addOnChangeListener { _, value, _ ->
            radius = value
            showRadius()
            drawCircle()
        }

        findViewById<FloatingActionButton>(R.id.fab_location).setOnClickListener {
            centerOnUser(quiet = false)
        }

        val chips = findViewById<ChipGroup>(R.id.name_chips)
        for (i in 0 until chips.childCount) {
            val chip = chips.getChildAt(i) as? Chip ?: continue
            chip.setOnClickListener {
                nameInput.setText(chip.text)
                nameInput.setSelection(chip.text.length)
            }
        }

        searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) { doSearch(); true } else false
        }
        findViewById<TextInputLayout>(R.id.search_layout).setEndIconOnClickListener { doSearch() }

        findViewById<MaterialButton>(R.id.btn_save).setOnClickListener { save() }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish(); return true
    }

    override fun onResume() { super.onResume(); map.onResume(); myDot?.enableMyLocation() }
    override fun onPause() { myDot?.disableMyLocation(); super.onPause(); map.onPause() }
    override fun onDestroy() { handler.removeCallbacksAndMessages(null); super.onDestroy() }

    // ---------- Mapa ----------

    private fun center(): GeoPoint {
        val c = map.mapCenter
        return GeoPoint(c.latitude, c.longitude)
    }

    private fun onCenterMoved() {
        drawCircle()
        handler.removeCallbacks(geocodeRunnable)
        handler.postDelayed(geocodeRunnable, 700)
    }

    private fun drawCircle() {
        circle.setPoints(Polygon.pointsAsCircle(center(), radius.toDouble()))
        map.invalidate()
    }

    private fun setRadius(r: Float) {
        val snapped = (((r - MIN_RADIUS) / STEP).roundToInt() * STEP + MIN_RADIUS).coerceIn(MIN_RADIUS, MAX_RADIUS)
        radius = snapped
        slider.value = snapped
        showRadius()
    }

    private fun showRadius() {
        val how = when {
            radius <= 60 -> "justo en el lugar"
            radius <= 150 -> "casi en la puerta"
            radius <= 300 -> "una o dos cuadras antes"
            radius <= 700 -> "bastante antes, bueno en motor o carro"
            else -> "desde lejos"
        }
        radiusLabel.text = "Avisarme a ${Geo.distanceText(radius)} ($how)"
    }

    private fun goTo(lat: Double, lng: Double, animate: Boolean) {
        val p = GeoPoint(lat, lng)
        if (map.zoomLevelDouble < 16.0) map.controller.setZoom(17.5)
        if (animate) map.controller.animateTo(p) else map.controller.setCenter(p)
        onCenterMoved()
    }

    @SuppressLint("MissingPermission")
    private fun centerOnUser(quiet: Boolean) {
        if (!GeofenceHelper.hasFine(this)) {
            if (!quiet) Toast.makeText(this, "Primero permite la ubicación en la pantalla principal", Toast.LENGTH_LONG).show()
            return
        }
        if (!quiet) userMovedMap = false
        gpsText.visibility = View.VISIBLE
        gpsText.text = "Buscando tu ubicación exacta…"
        val client = LocationServices.getFusedLocationProviderClient(this)
        // La última posición solo si es de hace menos de 1 minuto; luego la exacta del GPS
        client.lastLocation.addOnSuccessListener { loc ->
            if (loc != null && !userMovedMap && System.currentTimeMillis() - loc.time < 60_000) {
                goTo(loc.latitude, loc.longitude, animate = false)
            }
        }
        client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, CancellationTokenSource().token)
            .addOnSuccessListener { loc ->
                if (loc != null) {
                    val acc = loc.accuracy.toInt()
                    gpsText.text = if (acc <= 20) "GPS preciso: ±$acc m"
                    else "GPS poco preciso: ±$acc m. Sal al aire libre o espera unos segundos y toca otra vez el botón de ubicación."
                }
                when {
                    loc != null && !userMovedMap -> goTo(loc.latitude, loc.longitude, animate = true)
                    loc == null && !quiet -> Toast.makeText(
                        this, "No encontré tu ubicación. Revisa que el GPS esté encendido.", Toast.LENGTH_LONG
                    ).show()
                }
            }
    }

    private fun lookupAddress() {
        val ticket = ++geocodeTicket
        val c = center()
        Geo.reverse(this, c.latitude, c.longitude) { addr ->
            if (ticket != geocodeTicket || isFinishing) return@reverse
            lastAddress = addr ?: ""
            addressText.text = addr ?: "Punto marcado en el mapa"
        }
    }

    private fun doSearch() {
        val q = searchInput.text?.toString()?.trim().orEmpty()
        if (q.isEmpty()) return
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(searchInput.windowToken, 0)
        searchInput.clearFocus()
        Geo.search(this, q) { results ->
            if (isFinishing) return@search
            userMovedMap = true
            when {
                results.isEmpty() -> Toast.makeText(
                    this, "No encontré \"$q\". Prueba con otro nombre o mueve el mapa con el dedo.", Toast.LENGTH_LONG
                ).show()
                results.size == 1 -> goTo(results[0].latitude, results[0].longitude, animate = true)
                else -> MaterialAlertDialogBuilder(this)
                    .setTitle("¿Cuál de estos?")
                    .setItems(results.map { Geo.format(it) }.toTypedArray()) { _, i ->
                        goTo(results[i].latitude, results[i].longitude, animate = true)
                    }
                    .setNegativeButton("Cancelar", null)
                    .show()
            }
        }
    }

    // ---------- Guardar ----------

    private fun suggestedName(): String =
        lastAddress.substringBefore(",").trim().ifEmpty { "Mi lugar" }

    private fun save() {
        val c = center()
        val typedName = nameInput.text?.toString()?.trim().orEmpty()
        val ed = editing

        if (ed != null) {
            Store.update(this) { list ->
                list.find { it.id == ed.id }?.let { p ->
                    p.lat = c.latitude
                    p.lng = c.longitude
                    p.radius = radius
                    p.name = typedName.ifEmpty { suggestedName() }
                    if (lastAddress.isNotEmpty()) p.address = lastAddress
                }
            }
            GeofenceHelper.syncAll(this)
            TrackerService.refresh(this)
            Toast.makeText(this, "Lugar actualizado", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val note = noteInput.text?.toString()?.trim().orEmpty()
        if (note.isEmpty()) {
            noteLayout.error = "Escribe qué tienes que hacer o buscar aquí"
            noteInput.requestFocus()
            return
        }
        noteLayout.error = null
        val trigger = if (triggerGroup.checkedButtonId == R.id.btn_leave) Trigger.LEAVE else Trigger.ARRIVE

        var savedIn = ""
        Store.update(this) { list ->
            // Si ya tienes un lugar en ese mismo punto, la nota se agrega ahí
            val near = list.minByOrNull { it.distanceTo(c.latitude, c.longitude) }
                ?.takeIf {
                    it.distanceTo(c.latitude, c.longitude) <= 40f &&
                        (typedName.isEmpty() || typedName.equals(it.name, ignoreCase = true))
                }
            if (near != null) {
                near.notes.add(Note(text = note, trigger = trigger))
                savedIn = near.name
            } else {
                val name = typedName.ifEmpty { suggestedName() }
                list.add(
                    Place(
                        name = name, address = lastAddress,
                        lat = c.latitude, lng = c.longitude, radius = radius,
                        notes = mutableListOf(Note(text = note, trigger = trigger))
                    )
                )
                savedIn = name
            }
        }
        GeofenceHelper.syncAll(this)
        TrackerService.refresh(this)
        val msg = if (trigger == Trigger.LEAVE) "Listo. Te aviso cuando salgas de $savedIn"
        else "Listo. Te aviso la próxima vez que llegues a $savedIn"
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        finish()
    }
}
