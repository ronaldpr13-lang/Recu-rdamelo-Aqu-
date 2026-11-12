package com.gigaconet.recordatorios

import android.annotation.SuppressLint
import android.os.Bundle
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.material.button.MaterialButton
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon

/** Pantalla para escoger el lugar en el mapa (OpenStreetMap, gratis). */
class MapPickerActivity : AppCompatActivity() {

    private lateinit var map: MapView
    private lateinit var hint: TextView
    private var chosen: GeoPoint? = null
    private var marker: Marker? = null
    private var circle: Polygon? = null
    private var radius = 200f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // OpenStreetMap pide identificar la app que descarga el mapa
        Configuration.getInstance().load(this, getSharedPreferences("osmdroid", MODE_PRIVATE))
        Configuration.getInstance().userAgentValue = packageName

        setContentView(R.layout.activity_map)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        hint = findViewById(R.id.map_hint)
        map = findViewById(R.id.map)
        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)
        map.zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
        map.controller.setZoom(8.5)
        map.controller.setCenter(GeoPoint(18.75, -70.2)) // República Dominicana

        map.overlays.add(MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                setPoint(p); return true
            }
            override fun longPressHelper(p: GeoPoint): Boolean = false
        }))

        findViewById<RadioGroup>(R.id.radius_group).setOnCheckedChangeListener { _, id ->
            radius = when (id) {
                R.id.r100 -> 100f
                R.id.r500 -> 500f
                else -> 200f
            }
            drawPoint()
        }

        findViewById<MaterialButton>(R.id.btn_here).setOnClickListener { useMyLocation(markIt = true) }
        findViewById<MaterialButton>(R.id.btn_save).setOnClickListener { save() }

        useMyLocation(markIt = false) // solo centra el mapa donde estás
    }

    override fun onSupportNavigateUp(): Boolean {
        finish(); return true
    }

    override fun onResume() { super.onResume(); map.onResume() }
    override fun onPause() { super.onPause(); map.onPause() }

    @SuppressLint("MissingPermission")
    private fun useMyLocation(markIt: Boolean) {
        if (!GeofenceHelper.hasFine(this)) {
            if (markIt) Toast.makeText(this, "Primero permite la ubicación en la pantalla principal", Toast.LENGTH_LONG).show()
            return
        }
        if (markIt) hint.text = "Buscando tu ubicación…"
        LocationServices.getFusedLocationProviderClient(this)
            .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, CancellationTokenSource().token)
            .addOnSuccessListener { loc ->
                if (loc == null) {
                    if (markIt) hint.text = "No se encontró tu ubicación. Revisa que el GPS esté encendido."
                    return@addOnSuccessListener
                }
                val p = GeoPoint(loc.latitude, loc.longitude)
                map.controller.setZoom(17.0)
                map.controller.animateTo(p)
                if (markIt) setPoint(p)
            }
            .addOnFailureListener {
                if (markIt) hint.text = "No se pudo leer el GPS. Toca el mapa para marcar el lugar."
            }
    }

    private fun setPoint(p: GeoPoint) {
        chosen = p
        hint.text = "Lugar marcado. Puedes tocar otro punto para moverlo."
        drawPoint()
    }

    private fun drawPoint() {
        val p = chosen ?: return
        marker?.let { map.overlays.remove(it) }
        circle?.let { map.overlays.remove(it) }

        circle = Polygon(map).apply {
            setPoints(Polygon.pointsAsCircle(p, radius.toDouble()))
            fillPaint.color = ContextCompat.getColor(this@MapPickerActivity, R.color.zone_fill)
            outlinePaint.color = ContextCompat.getColor(this@MapPickerActivity, R.color.zone_stroke)
            outlinePaint.strokeWidth = 4f
            setOnClickListener { _, _, _ -> false }
        }
        marker = Marker(map).apply {
            position = p
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            setOnMarkerClickListener { _, _ -> true }
        }
        map.overlays.add(circle)
        map.overlays.add(marker)
        map.invalidate()
    }

    private fun save() {
        val nameField = findViewById<EditText>(R.id.input_name)
        val noteField = findViewById<EditText>(R.id.input_note)
        val name = nameField.text.toString().trim()
        val note = noteField.text.toString().trim()
        val p = chosen

        if (p == null) {
            Toast.makeText(this, "Toca el mapa o usa \"Estoy aquí\" para marcar el lugar", Toast.LENGTH_LONG).show()
            return
        }
        if (name.isEmpty()) {
            nameField.error = "Ponle un nombre, por ejemplo Ferretería"
            nameField.requestFocus()
            return
        }

        val place = Place(name = name, lat = p.latitude, lng = p.longitude, radius = radius)
        if (note.isNotEmpty()) place.notes.add(Note(text = note))
        Store.update(this) { it.add(place) }
        GeofenceHelper.syncAll(this)
        Toast.makeText(this, "Guardado: $name", Toast.LENGTH_SHORT).show()
        finish()
    }
}
