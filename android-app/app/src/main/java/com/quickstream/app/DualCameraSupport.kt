package com.quickstream.app

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.util.Log

/**
 * Comprueba si ESTE móvil permite tener abiertas a la vez la cámara trasera y la
 * frontal (modo dual). No todos los móviles pueden: Android lo expone desde la
 * versión 11 con [CameraManager.getConcurrentCameraIds], que devuelve los grupos
 * de cámaras que el fabricante garantiza que funcionan juntas. Si no hay ningún
 * grupo con una trasera y una frontal, el modo dual no está disponible — intentar
 * abrir la segunda cámara igualmente acabaría en error.
 */
object DualCameraSupport {

    private const val TAG = "DualCameraSupport"

    /** Pareja de cámaras (trasera, frontal) que se pueden usar a la vez, o null si no hay. */
    fun cameraPair(context: Context): Pair<String, String>? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return try {
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            for (ids in cm.concurrentCameraIds) {
                var back: String? = null
                var front: String? = null
                for (id in ids) {
                    when (cm.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING)) {
                        CameraCharacteristics.LENS_FACING_BACK -> if (back == null) back = id
                        CameraCharacteristics.LENS_FACING_FRONT -> if (front == null) front = id
                    }
                }
                if (back != null && front != null) {
                    Log.i(TAG, "Modo dual disponible: trasera=$back, frontal=$front")
                    return back to front
                }
            }
            Log.i(TAG, "Este móvil no permite usar la trasera y la frontal a la vez")
            null
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo comprobar el soporte de cámaras simultáneas", e)
            null
        }
    }

    fun isSupported(context: Context): Boolean = cameraPair(context) != null
}
