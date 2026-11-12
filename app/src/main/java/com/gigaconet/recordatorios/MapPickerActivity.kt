package com.gigaconet.recordatorios

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.view.MotionEvent
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.material.button.MaterialButton
import android.content.res.Configuration as UiConfig
import android.widget.EditText
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
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
import org.osmdroid.views.overlay.TilesOverlay
import org.osmdroid.views.overlay.gestures.RotationGestureOverlay
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
    private lateinit var searchInput: EditText
    private lateinit var triggerGroup: ChipGroup
    private lateinit var nameChips: ChipGroup
    private lateinit var nameLayout: TextInputLayout
    private lateinit var radiusValue: TextView
    private lateinit var circle: Polygon
    private lateinit var gpsText: TextView
    private var myDot: MyLocationNewOverlay? = null

    private var editing: Place? = null
    private var fullMap = false
    private lateinit var northBtn: MaterialButton
    private val compassTick = object : Runnable {
        override fun run() {
            updateNorth()
            handler.postDelayed(this, 200)
        }
    }
    private lateinit var fullMapBack: OnBackPressedCallback
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
        findViewById<MaterialButton>(R.id.btn_back).setOnClickListener { finish() }

        val all = Store.load(this)
        editing = intent.getStringExtra(EXTRA_PLACE_ID)?.let { id -> all.find { it.id == id } }
        val ed = editing
        findViewById<TextView>(R.id.sheet_title).text = if (ed != null) "Editar lugar" else "Nuevo recordatorio"

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
        nameChips = findViewById(R.id.name_chips)
        nameLayout = findViewById(R.id.name_layout)
        radiusValue = findViewById(R.id.radius_value)

        setupMap(all)
        setupControls()

        if (ed != null) {
            findViewById<View>(R.id.new_note_section).visibility = View.GONE
            selectName(ed.name)
            lastAddress = ed.address
            addressText.text = ed.address.ifBlank { ed.name }
            setRadius(ed.radius)
            map.controller.setZoom(17.0)
            map.controller.setCenter(GeoPoint(ed.lat, ed.lng))
            findViewById<MaterialButton>(R.id.btn_save).text = "Guardar cambios"
        } else {
            setRadius(AppPrefs.defaultRadius(this))
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
        // Modo noche: mapa oscuro
        val night = (resources.configuration.uiMode and UiConfig.UI_MODE_NIGHT_MASK) == UiConfig.UI_MODE_NIGHT_YES
        if (night) map.overlayManager.tilesOverlay.setColorFilter(TilesOverlay.INVERT_COLORS)
        map.overlayManager.tilesOverlay.loadingBackgroundColor = ContextCompat.getColor(this, R.color.bg)
        map.overlayManager.tilesOverlay.loadingLineColor = ContextCompat.getColor(this, R.color.divider)

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
                icon = ContextCompat.getDrawable(this@MapPickerActivity, R.drawable.ic_pin)?.mutate()?.also {
                    it.setTint(ContextCompat.getColor(this@MapPickerActivity, R.color.text_soft))
                }
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
            myDot = MyLocationNewOverlay(GpsMyLocationProvider(this), map).also {
                val dot = blueDot()
                it.setDirectionArrow(dot, dot)
                it.setPersonAnchor(0.5f, 0.5f)
                it.setDirectionAnchor(0.5f, 0.5f)
                map.overlays.add(it)
            }
        }

        // Girar el mapa con dos dedos
        map.overlays.add(SoftRotation(map).apply { isEnabled = true })

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

        findViewById<MaterialButton>(R.id.fab_location).setOnClickListener {
            centerOnUser(quiet = false)
        }

        // Nombre: un toque en un atajo; "Otro…" abre el campo para escribir
        nameChips.setOnCheckedStateChangeListener { _, ids ->
            val other = ids.firstOrNull() == R.id.chip_other
            nameLayout.visibility = if (other) View.VISIBLE else View.GONE
            if (other) nameInput.requestFocus()
        }

        searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) { doSearch(); true } else false
        }
        findViewById<MaterialButton>(R.id.btn_search).setOnClickListener { doSearch() }

        // Brújula: vuelve a poner el norte arriba
        northBtn = findViewById(R.id.btn_north)
        northBtn.setOnClickListener {
            map.mapOrientation = 0f
            map.invalidate()
            updateNorth()
        }

        // Deslizar el panel hacia abajo = mapa completo
        val sheet = findViewById<View>(R.id.sheet)
        val dp = resources.displayMetrics.density
        var downY = 0f
        findViewById<View>(R.id.sheet_header).setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downY = e.rawY; true }
                MotionEvent.ACTION_MOVE -> {
                    sheet.translationY = (e.rawY - downY).coerceAtLeast(0f); true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val dy = e.rawY - downY
                    if (dy > 70 * dp) setFullMap(true)
                    else sheet.animate().translationY(0f).setDuration(150).start()
                    if (kotlin.math.abs(dy) < 8 * dp) v.performClick()
                    true
                }
                else -> false
            }
        }

        // Mapa a pantalla completa
        findViewById<MaterialButton>(R.id.fab_expand).setOnClickListener { setFullMap(!fullMap) }
        findViewById<MaterialButton>(R.id.btn_confirm_point).setOnClickListener { setFullMap(false) }
        fullMapBack = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() = setFullMap(false)
        }
        onBackPressedDispatcher.addCallback(this, fullMapBack)

        findViewById<MaterialButton>(R.id.btn_save).setOnClickListener { save() }
    }

    /** Esconde el panel para ver todo el mapa; "Usar este punto" lo vuelve a mostrar. */
    private fun setFullMap(on: Boolean) {
        fullMap = on
        fullMapBack.isEnabled = on
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(map.windowToken, 0)
        val sheet = findViewById<View>(R.id.sheet)
        val keep = center() // el mapa cambia de tamaño: mantener el mismo punto en el centro
        if (on) {
            sheet.animate().translationY(sheet.height.toFloat()).setDuration(200).withEndAction {
                sheet.visibility = View.GONE
                sheet.translationY = 0f
                map.post { map.controller.setCenter(keep); onCenterMoved() }
            }.start()
        } else {
            sheet.visibility = View.VISIBLE
            sheet.translationY = sheet.height.toFloat()
            sheet.animate().translationY(0f).setDuration(220).start()
            map.post { map.controller.setCenter(keep); onCenterMoved() }
        }
        findViewById<View>(R.id.btn_confirm_point).visibility = if (on) View.VISIBLE else View.GONE
        findViewById<MaterialButton>(R.id.fab_expand).apply {
            setIconResource(if (on) R.drawable.ic_fullscreen_exit else R.drawable.ic_fullscreen)
            contentDescription = if (on) "Volver al formulario" else "Ver mapa completo"
        }
        val dp = resources.displayMetrics.density
        // Subir los botones redondos para que no choquen con "Usar este punto"
        listOf(R.id.fab_location to 40, R.id.fab_expand to 104).forEach { (id, base) ->
            val v = findViewById<View>(id)
            (v.layoutParams as android.widget.FrameLayout.LayoutParams).bottomMargin =
                ((if (on) base + 64 else base) * dp).toInt()
            v.requestLayout()
        }
    }

    /** Punto azul con borde blanco para tu ubicación. */
    private fun blueDot(): Bitmap {
        val size = (24 * resources.displayMetrics.density).toInt()
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val r = size / 2f
        p.color = Color.argb(60, 47, 128, 237); c.drawCircle(r, r, r, p)
        p.color = Color.WHITE; c.drawCircle(r, r, r * 0.55f, p)
        p.color = Color.rgb(47, 128, 237); c.drawCircle(r, r, r * 0.4f, p)
        return b
    }

    /** Marca el atajo si el nombre coincide; si no, "Otro…" con el nombre escrito. */
    private fun selectName(name: String) {
        for (i in 0 until nameChips.childCount) {
            val chip = nameChips.getChildAt(i) as? Chip ?: continue
            if (chip.id != R.id.chip_other && chip.text.toString().equals(name, ignoreCase = true)) {
                nameChips.check(chip.id); return
            }
        }
        nameInput.setText(name)
        nameChips.check(R.id.chip_other)
    }

    private fun chosenName(): String {
        val id = nameChips.checkedChipId
        if (id == View.NO_ID) return ""
        if (id == R.id.chip_other) return nameInput.text?.toString()?.trim().orEmpty()
        return findViewById<Chip>(id).text.toString()
    }

    override fun onResume() {
        super.onResume(); map.onResume(); myDot?.enableMyLocation()
        handler.post(compassTick)
    }

    override fun onPause() {
        handler.removeCallbacks(compassTick)
        myDot?.disableMyLocation(); super.onPause(); map.onPause()
    }

    /** Muestra la brújula solo cuando el mapa está girado, apuntando al norte. */
    private fun updateNorth() {
        val o = ((map.mapOrientation % 360f) + 360f) % 360f
        val rotated = o > 1f && o < 359f
        northBtn.visibility = if (rotated) View.VISIBLE else View.GONE
        northBtn.rotation = -map.mapOrientation
    }
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
        radiusValue.text = Geo.distanceText(radius)
        radiusLabel.text = "Te aviso $how"
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
        val typedName = chosenName()
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
        val trigger = if (triggerGroup.checkedChipId == R.id.chip_leave) Trigger.LEAVE else Trigger.ARRIVE

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
