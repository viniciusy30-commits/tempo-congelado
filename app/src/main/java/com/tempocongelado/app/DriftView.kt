package com.tempocongelado.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import java.util.Random
import kotlin.math.cos
import kotlin.math.sin

/** Fundo animado do menu: estilhaços vermelhos flutuando devagar, como se o tempo quase parasse. */
class DriftView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private class Peca(
        var x: Float, var y: Float,
        val vx: Float, val vy: Float,
        var rot: Float, val vr: Float,
        val tam: Float, val lados: Int, val alpha: Int
    )

    private val rng = Random()
    private val pecas = ArrayList<Peca>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private var ultimo = 0L

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        pecas.clear()
        if (w <= 0 || h <= 0) return
        for (i in 0 until 16) {
            val tam = context.dp(10) + rng.nextFloat() * context.dp(26)
            pecas.add(
                Peca(
                    rng.nextFloat() * w, rng.nextFloat() * h,
                    (rng.nextFloat() - 0.5f) * context.dp(14),
                    (rng.nextFloat() - 0.5f) * context.dp(14),
                    rng.nextFloat() * 6.28f, (rng.nextFloat() - 0.5f) * 0.6f,
                    tam, if (rng.nextBoolean()) 3 else 4,
                    40 + rng.nextInt(70)
                )
            )
        }
    }

    override fun onDraw(canvas: Canvas) {
        val agora = System.nanoTime()
        var dt = if (ultimo == 0L) 0.016f else (agora - ultimo) / 1e9f
        ultimo = agora
        if (dt > 0.05f) dt = 0.05f

        for (p in pecas) {
            p.x += p.vx * dt
            p.y += p.vy * dt
            p.rot += p.vr * dt
            if (p.x < -p.tam) p.x = width + p.tam
            if (p.x > width + p.tam) p.x = -p.tam
            if (p.y < -p.tam) p.y = height + p.tam
            if (p.y > height + p.tam) p.y = -p.tam

            path.reset()
            for (i in 0 until p.lados) {
                val a = p.rot + i * 6.2831855f / p.lados
                val px = p.x + cos(a) * p.tam
                val py = p.y + sin(a) * p.tam
                if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            path.close()
            paint.style = Paint.Style.FILL
            paint.color = Color.argb(p.alpha, 229, 50, 45)
            canvas.drawPath(path, paint)
        }
        postInvalidateOnAnimation()
    }
}
