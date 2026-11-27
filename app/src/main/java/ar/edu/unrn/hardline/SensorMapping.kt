package ar.edu.unrn.hardline

import kotlin.math.roundToInt

object SensorMapping {
    const val GRAVITY = 9.80665f
    /** -g = 0 mV, reposo = Vref/2, +g = Vref. Saturación fuera de escala. */
    fun millivolts(acceleration: Float, vref: Int): Int {
        require(acceleration.isFinite() && vref > 0)
        return (((acceleration / GRAVITY).coerceIn(-1f, 1f) + 1f) * vref / 2f).roundToInt()
    }
}
