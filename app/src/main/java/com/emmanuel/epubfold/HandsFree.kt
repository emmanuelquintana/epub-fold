package com.emmanuel.epubfold

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import kotlin.math.abs

enum class HandsFreeMode(val label: String) {
    OFF("No"),
    WAVE("Mano"),
    TILT("Inclinar");
}

/**
 * Pasar página sin tocar la pantalla:
 * - Mano: pasa la mano por encima del sensor de proximidad (junto a la cámara
 *   frontal) para avanzar; déjala encima un segundo y medio para retroceder.
 * - Inclinar: un giro rápido hacia la derecha avanza y hacia la izquierda retrocede.
 * No usa la cámara: sólo sensores, sin permisos y con muy poco gasto de batería.
 */
class HandsFree(context: Context, private val onTurn: (forward: Boolean) -> Unit) : SensorEventListener {

    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private var mode = HandsFreeMode.OFF

    // Mano
    private var nearSince = 0L
    private var longFired = false
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    // El sensor sólo avisa al cambiar: el «mantener» se mide con un temporizador
    private val hold = Runnable {
        if (nearSince != 0L) {
            longFired = true
            onTurn(false)
        }
    }

    // Inclinar
    private var baseline = Float.NaN
    private var armed = true
    private var lastTurn = 0L

    /** Devuelve false si el teléfono no tiene el sensor necesario. */
    fun start(m: HandsFreeMode): Boolean {
        stop()
        mode = m
        val sensor = when (m) {
            HandsFreeMode.OFF -> return true
            HandsFreeMode.WAVE -> sm.getDefaultSensor(Sensor.TYPE_PROXIMITY)
            HandsFreeMode.TILT -> sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        } ?: return false
        sm.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
        return true
    }

    fun stop() {
        sm.unregisterListener(this)
        handler.removeCallbacks(hold)
        nearSince = 0L
        baseline = Float.NaN
        armed = true
    }

    override fun onSensorChanged(e: SensorEvent) {
        val now = SystemClock.elapsedRealtime()
        when (mode) {
            HandsFreeMode.WAVE -> {
                val near = e.values[0] < e.sensor.maximumRange.coerceAtMost(5f)
                if (near) {
                    if (nearSince == 0L) {
                        nearSince = now
                        longFired = false
                        // Mantener la mano encima: retroceder
                        handler.postDelayed(hold, 1500)
                    }
                } else if (nearSince != 0L) {
                    handler.removeCallbacks(hold)
                    val held = now - nearSince
                    nearSince = 0L
                    if (!longFired && held in 60..1200 && now - lastTurn > 600) {
                        lastTurn = now
                        onTurn(true)
                    }
                }
            }
            HandsFreeMode.TILT -> {
                val x = e.values[0]
                if (baseline.isNaN()) baseline = x
                val d = x - baseline
                if (armed && abs(d) > 4.2f && now - lastTurn > 700) {
                    // x baja al inclinar el borde derecho hacia abajo
                    onTurn(d < 0)
                    lastTurn = now
                    armed = false
                } else if (!armed && abs(d) < 1.5f) {
                    armed = true
                }
                // La postura de reposo se adapta despacio (por si cambias de posición al leer)
                if (armed) baseline += (x - baseline) * 0.02f
            }
            HandsFreeMode.OFF -> {}
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
