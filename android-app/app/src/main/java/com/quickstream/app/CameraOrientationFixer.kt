package com.quickstream.app

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import android.view.OrientationEventListener
import org.webrtc.VideoFrame
import org.webrtc.VideoProcessor
import org.webrtc.VideoSink

/**
 * Corrige la orientación de la imagen de la cámara según cómo está girado el móvil
 * DE VERDAD (sensor de giro), no según la pantalla.
 *
 * WebRTC marca cada fotograma con un giro calculado a partir de la orientación de
 * la PANTALLA. Como QuickStream está fijada en vertical, la pantalla dice "vertical"
 * aunque el móvil esté apaisado, y quien mira veía la imagen tumbada. Aquí se lee el
 * sensor y se ajusta la marca de giro de cada fotograma: el visor (y OBS) giran la
 * imagen solos, sin que nadie toque los botones de girar.
 *
 * La marca de giro viaja con el vídeo (extensión de orientación de WebRTC), así que
 * no hay que recodificar nada: solo cambia un número por fotograma.
 */
class CameraOrientationFixer(
    context: Context,
    private val frontFacing: Boolean
) : VideoProcessor {

    private val display: Display? =
        (context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).getDisplay(Display.DEFAULT_DISPLAY)

    /** Giro físico del móvil expresado como lo haría la pantalla (0, 90, 180, 270). */
    @Volatile private var physicalRotation = 0

    private var sink: VideoSink? = null

    private val listener = object : OrientationEventListener(context) {
        override fun onOrientationChanged(orientation: Int) {
            // Plano sobre una mesa: no se sabe hacia dónde mira, se deja como estaba.
            if (orientation == ORIENTATION_UNKNOWN) return
            // Cuadrante actual (0 = vertical normal, 1 = girado 90° a la derecha...).
            // Margen de 60° para no ir dando saltos cuando el móvil está a medio girar.
            val currentQuadrant = ((360 - physicalRotation) % 360) / 90
            val center = currentQuadrant * 90
            var diff = Math.abs(orientation - center)
            if (diff > 180) diff = 360 - diff
            if (diff <= 60) return
            val quadrant = ((orientation + 45) / 90) % 4
            physicalRotation = (360 - quadrant * 90) % 360
        }
    }

    fun start() {
        if (listener.canDetectOrientation()) listener.enable()
    }

    fun stop() {
        listener.disable()
    }

    override fun setSink(sink: VideoSink?) {
        this.sink = sink
    }

    override fun onCapturerStarted(success: Boolean) {}
    override fun onCapturerStopped() {}

    override fun onFrameCaptured(frame: VideoFrame) {
        val out = sink ?: return
        // Lo que WebRTC ya tuvo en cuenta (giro de la pantalla) frente al giro real.
        val displayRotation = when (display?.rotation) {
            android.view.Surface.ROTATION_90 -> 90
            android.view.Surface.ROTATION_180 -> 180
            android.view.Surface.ROTATION_270 -> 270
            else -> 0
        }
        val delta = (physicalRotation - displayRotation + 360) % 360
        if (delta == 0) {
            out.onFrame(frame)
            return
        }
        // Misma fórmula que usa WebRTC (Camera2Session): la trasera resta el giro
        // del móvil y la frontal lo suma, porque está en espejo.
        val rotation = if (frontFacing) {
            (frame.rotation + delta) % 360
        } else {
            (frame.rotation - delta + 360) % 360
        }
        val buffer = frame.buffer
        buffer.retain()
        val rotated = VideoFrame(buffer, rotation, frame.timestampNs)
        try {
            out.onFrame(rotated)
        } finally {
            rotated.release()
        }
    }
}
