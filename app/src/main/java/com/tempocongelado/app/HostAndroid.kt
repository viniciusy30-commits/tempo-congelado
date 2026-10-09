package com.tempocongelado.app

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator

/** Liga o Game3D ao Android: vibração (respeitando a configuração) e recorde salvo. */
class HostAndroid(private val ctx: Context) : Game3D.Host {

    @Suppress("DEPRECATION")
    override fun vibrar(ms: Int) {
        if (!Prefs.vibracao(ctx)) return
        val v = ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        val dur = ms.toLong()
        if (Build.VERSION.SDK_INT >= 26) {
            v.vibrate(VibrationEffect.createOneShot(dur, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            v.vibrate(dur)
        }
    }

    override fun registrar(sala: Int, abates: Int) {
        Prefs.registrar(ctx, sala, abates)
    }
}
