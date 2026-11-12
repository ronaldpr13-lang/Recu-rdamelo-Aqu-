package com.gigaconet.recordatorios

import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.gestures.RotationGestureOverlay
import kotlin.math.abs

/**
 * Giro del mapa más suave: gira a la mitad de la velocidad del dedo y
 * solo empieza cuando el giro es claro, para no girar sin querer al hacer zoom.
 */
class SoftRotation(private val mapView: MapView) : RotationGestureOverlay(mapView) {
    private var accumulated = 0f
    private var rotating = false
    private var lastEvent = 0L

    override fun onRotate(deltaAngle: Float) {
        val now = System.currentTimeMillis()
        if (now - lastEvent > 350) { // gesto nuevo
            accumulated = 0f
            rotating = false
        }
        lastEvent = now
        if (!rotating) {
            accumulated += deltaAngle
            if (abs(accumulated) < 15f) return
            rotating = true
        }
        mapView.mapOrientation = mapView.mapOrientation + deltaAngle * 0.5f
    }
}
