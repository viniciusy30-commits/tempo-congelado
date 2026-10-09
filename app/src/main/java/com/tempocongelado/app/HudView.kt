package com.tempocongelado.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Interface sobre o 3D: HUD, joystick, botão de tiro, menus de melhoria, pausa e morte.
 * Também recebe todos os toques e repassa para o Game3D.
 */
class HudView(
    context: Context,
    private val game: Game3D,
    private val onSair: () -> Unit
) : View(context) {

    private val d = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val rect = RectF()

    private val corEscuro = Color.parseColor("#15171C")
    private val corVermelho = Color.parseColor("#E5322D")
    private val corCinza = Color.parseColor("#6B7280")
    private val corFundo = Color.parseColor("#EEF0F4")

    private var vinheta: Paint? = null

    // geometria
    private var fireCx = 0f
    private var fireCy = 0f
    private var fireR = 0f
    private val rPause = RectF()

    // entrada
    private var movId = -1
    private var movAx = 0f
    private var movAy = 0f
    private var movCx = 0f
    private var movCy = 0f
    private var fireId = -1
    private var fireLx = 0f
    private var fireLy = 0f
    private var lookId = -1
    private var lookLx = 0f
    private var lookLy = 0f

    init {
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        tp.typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
    }

    private fun dp(v: Float): Float = v * d

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        fireR = dp(52f)
        fireCx = w - dp(92f)
        fireCy = h - dp(84f)
        rPause.set(w - dp(62f), dp(12f), w - dp(14f), dp(60f))
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val raio = hypot(w.toFloat(), h.toFloat()) * 0.62f
        p.shader = RadialGradient(
            w / 2f, h / 2f, raio,
            intArrayOf(0x00000000, 0x00000000, 0x66150A0A),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        vinheta = p
    }

    // ------------------------------------------------------------------
    // Desenho
    // ------------------------------------------------------------------

    override fun onDraw(c: Canvas) {
        synchronized(game) {
            val v = vinheta
            if (v != null && game.state != Game3D.S_UPGRADE) {
                v.alpha = (255 * (1f - game.timeScale).coerceIn(0f, 1f) * 0.9f).toInt()
                c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), v)
            }
            if (game.hurt > 0f) {
                paint.style = Paint.Style.FILL
                paint.color = Color.argb((120 * game.hurt).toInt().coerceIn(0, 255), 229, 50, 45)
                c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
            }

            val jogando = game.state == Game3D.S_INTRO || game.state == Game3D.S_PLAY ||
                game.state == Game3D.S_CLEAR
            if (jogando) {
                desenharHud(c)
                desenharControles(c)
            }
            desenharSobreposicoes(c)
        }
        postInvalidateOnAnimation()
    }

    private fun texto(
        c: Canvas, s: String, x: Float, y: Float, sp: Float, cor: Int,
        alinhar: Paint.Align, alpha: Int
    ) {
        paint.style = Paint.Style.FILL
        paint.color = cor
        paint.alpha = alpha
        paint.textSize = sp * d
        paint.textAlign = alinhar
        paint.letterSpacing = 0.06f
        c.drawText(s, x, y, paint)
        paint.letterSpacing = 0f
        paint.alpha = 255
    }

    private fun texto(c: Canvas, s: String, x: Float, y: Float, sp: Float, cor: Int, alinhar: Paint.Align) {
        texto(c, s, x, y, sp, cor, alinhar, 255)
    }

    private fun desenharHud(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()

        texto(c, "SALA " + game.room, dp(18f), dp(38f), 22f, corEscuro, Paint.Align.LEFT)
        texto(c, "INIMIGOS: " + game.enemies.size, dp(19f), dp(58f), 11f, corCinza, Paint.Align.LEFT)

        // vidas
        val n = game.maxHp
        val passo = dp(26f)
        val x0 = w / 2f - (n - 1) * passo / 2f
        for (i in 0 until n) {
            val cx = x0 + i * passo
            if (i < game.hp) {
                paint.style = Paint.Style.FILL
                paint.color = corVermelho
                c.drawCircle(cx, dp(26f), dp(9f), paint)
            } else {
                paint.style = Paint.Style.STROKE
                paint.color = corEscuro
                paint.strokeWidth = dp(2f)
                c.drawCircle(cx, dp(26f), dp(8f), paint)
            }
        }

        // barra do tempo
        val bw = dp(150f)
        val bx = w / 2f - bw / 2f
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(4f)
        paint.color = Color.argb(70, 21, 23, 28)
        c.drawLine(bx, dp(48f), bx + bw, dp(48f), paint)
        paint.color = corVermelho
        c.drawLine(bx, dp(48f), bx + max(1f, bw * game.timeScale.coerceIn(0f, 1f)), dp(48f), paint)

        // botão de pausa
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(2.5f)
        paint.color = corEscuro
        c.drawRoundRect(rPause, dp(5f), dp(5f), paint)
        paint.style = Paint.Style.FILL
        c.drawRect(rPause.centerX() - dp(8f), rPause.centerY() - dp(9f), rPause.centerX() - dp(3f), rPause.centerY() + dp(9f), paint)
        c.drawRect(rPause.centerX() + dp(3f), rPause.centerY() - dp(9f), rPause.centerX() + dp(8f), rPause.centerY() + dp(9f), paint)

        // mira
        val cx = w / 2f
        val cy = h / 2f
        val marca = game.hitMark > 0f
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(2f)
        paint.color = if (marca) corVermelho else Color.argb(220, 21, 23, 28)
        val gap = if (marca) dp(4f) else dp(6f)
        val len = dp(8f)
        c.drawLine(cx - gap - len, cy, cx - gap, cy, paint)
        c.drawLine(cx + gap, cy, cx + gap + len, cy, paint)
        c.drawLine(cx, cy - gap - len, cx, cy - gap, paint)
        c.drawLine(cx, cy + gap, cx, cy + gap + len, paint)
        paint.style = Paint.Style.FILL
        c.drawCircle(cx, cy, dp(1.6f), paint)

        // dicas da primeira sala
        if (game.room == 1 && game.state == Game3D.S_PLAY && !(game.moved && game.looked && game.fired)) {
            var y = h - dp(36f)
            if (!game.fired) {
                texto(c, "BOTÃO VERMELHO: ATIRAR", w / 2f, y, 13f, corVermelho, Paint.Align.CENTER)
                y -= dp(22f)
            }
            if (!game.looked) {
                texto(c, "ARRASTE NA DIREITA: OLHAR", w / 2f, y, 13f, corEscuro, Paint.Align.CENTER)
                y -= dp(22f)
            }
            if (!game.moved) {
                texto(c, "ARRASTE NA ESQUERDA: MOVER (o tempo anda com você)", w / 2f, y, 13f, corEscuro, Paint.Align.CENTER)
            }
        }
    }

    private fun desenharControles(c: Canvas) {
        // botão de tiro
        val apertado = fireId != -1
        paint.style = Paint.Style.FILL
        paint.color = if (apertado) Color.argb(235, 160, 30, 26) else Color.argb(200, 229, 50, 45)
        c.drawCircle(fireCx, fireCy, fireR * (if (apertado) 0.94f else 1f), paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(3f)
        paint.color = corEscuro
        c.drawCircle(fireCx, fireCy, fireR * (if (apertado) 0.94f else 1f), paint)
        paint.color = Color.WHITE
        paint.strokeWidth = dp(2.5f)
        val r = fireR * 0.38f
        c.drawCircle(fireCx, fireCy, r, paint)
        c.drawLine(fireCx - r * 1.6f, fireCy, fireCx - r * 0.5f, fireCy, paint)
        c.drawLine(fireCx + r * 0.5f, fireCy, fireCx + r * 1.6f, fireCy, paint)
        c.drawLine(fireCx, fireCy - r * 1.6f, fireCx, fireCy - r * 0.5f, paint)
        c.drawLine(fireCx, fireCy + r * 0.5f, fireCx, fireCy + r * 1.6f, paint)

        // joystick
        val raio = dp(56f)
        if (movId != -1) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(3f)
            paint.color = Color.argb(120, 21, 23, 28)
            c.drawCircle(movAx, movAy, raio, paint)
            var dx = movCx - movAx
            var dy = movCy - movAy
            val dist = hypot(dx, dy)
            if (dist > raio) {
                dx = dx / dist * raio
                dy = dy / dist * raio
            }
            paint.style = Paint.Style.FILL
            paint.color = Color.argb(180, 21, 23, 28)
            c.drawCircle(movAx + dx, movAy + dy, raio * 0.42f, paint)
        } else if (!game.moved) {
            val hx = dp(110f)
            val hy = height - dp(90f)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(3f)
            paint.color = Color.argb(70, 21, 23, 28)
            c.drawCircle(hx, hy, raio, paint)
            paint.style = Paint.Style.FILL
            c.drawCircle(hx, hy, raio * 0.42f, paint)
        }
    }

    private fun desenharSobreposicoes(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        when (game.state) {
            Game3D.S_INTRO -> {
                val t = game.stateT
                val a = if (t < 0.7f) 255 else (255 * (1f - (t - 0.7f) / 0.4f)).toInt().coerceIn(0, 255)
                val chefe = game.room % 5 == 0
                texto(
                    c, if (chefe) "CHEFE" else "SALA " + game.room, w / 2f, h * 0.36f,
                    if (chefe) 54f else 48f, if (chefe) corVermelho else corEscuro, Paint.Align.CENTER, a
                )
                texto(
                    c, if (chefe) "SALA " + game.room else "ELIMINE TODOS", w / 2f, h * 0.36f + dp(30f),
                    16f, corCinza, Paint.Align.CENTER, a
                )
            }
            Game3D.S_CLEAR -> {
                val t = game.stateT
                val a = (255 * min(1f, t * 4f) * (1f - max(0f, (t - 1.0f) / 0.3f))).toInt().coerceIn(0, 255)
                texto(c, "SALA LIMPA", w / 2f, h * 0.36f, 48f, corVermelho, Paint.Align.CENTER, a)
            }
            Game3D.S_UPGRADE -> desenharMelhorias(c)
            Game3D.S_DEAD -> desenharMorte(c)
        }
        if (game.paused) {
            paint.style = Paint.Style.FILL
            paint.color = Color.argb(225, 238, 240, 244)
            c.drawRect(0f, 0f, w, h, paint)
            texto(c, "PAUSADO", w / 2f, h * 0.38f, 44f, corEscuro, Paint.Align.CENTER)
            botao(c, btn(0), "CONTINUAR", true)
            botao(c, btn(1), "SAIR", false)
        }
    }

    private fun btn(i: Int): RectF {
        val bw = dp(190f)
        val bh = dp(52f)
        val gap = dp(16f)
        val total = bw * 2f + gap
        val left = (width - total) / 2f + i * (bw + gap)
        val top = height * 0.58f
        return RectF(left, top, left + bw, top + bh)
    }

    private fun botao(c: Canvas, r: RectF, rotulo: String, principal: Boolean) {
        paint.style = Paint.Style.FILL
        paint.color = if (principal) corVermelho else Color.WHITE
        c.drawRoundRect(r, dp(5f), dp(5f), paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(2.5f)
        paint.color = corEscuro
        c.drawRoundRect(r, dp(5f), dp(5f), paint)
        texto(
            c, rotulo, r.centerX(), r.centerY() + dp(6f), 16f,
            if (principal) Color.WHITE else corEscuro, Paint.Align.CENTER
        )
    }

    private fun desenharMorte(c: Canvas) {
        val t = game.stateT
        val fade = ((t - 0.6f) / 0.5f).coerceIn(0f, 1f)
        if (fade <= 0f) return
        val a = (fade * 255).toInt()
        val w = width.toFloat()
        val h = height.toFloat()
        paint.style = Paint.Style.FILL
        paint.color = Color.argb((225 * fade).toInt(), 238, 240, 244)
        c.drawRect(0f, 0f, w, h, paint)

        texto(c, "VOCÊ MORREU", w / 2f, h * 0.26f, 44f, corVermelho, Paint.Align.CENTER, a)
        texto(c, "SALA " + game.room, w / 2f, h * 0.26f + dp(36f), 24f, corEscuro, Paint.Align.CENTER, a)
        texto(c, game.kills.toString() + " inimigos abatidos", w / 2f, h * 0.26f + dp(62f), 14f, corCinza, Paint.Align.CENTER, a)
        val melhor = max(Prefs.melhorSala(context), game.room)
        texto(c, "RECORDE: SALA $melhor", w / 2f, h * 0.26f + dp(84f), 14f, corEscuro, Paint.Align.CENTER, a)

        if (t > 1.0f) {
            botao(c, btn(0), "TENTAR DE NOVO", true)
            botao(c, btn(1), "MENU", false)
        }
    }

    private fun cartao(i: Int, n: Int): RectF {
        val margem = dp(28f)
        val gap = dp(16f)
        val cw = (width - 2f * margem - (n - 1) * gap) / n
        val ch = min(height * 0.64f, dp(236f))
        val top = height / 2f - ch / 2f + dp(16f)
        val left = margem + i * (cw + gap)
        return RectF(left, top, left + cw, top + ch)
    }

    private fun desenharMelhorias(c: Canvas) {
        val w = width.toFloat()
        val t = game.stateT
        val fade = min(1f, t / 0.25f)
        paint.style = Paint.Style.FILL
        paint.color = Color.argb((230 * fade).toInt(), 238, 240, 244)
        c.drawRect(0f, 0f, w, height.toFloat(), paint)

        val n = game.choices.size
        if (n == 0) return
        val topo = cartao(0, n).top
        texto(c, "ESCOLHA UMA MELHORIA", w / 2f, topo - dp(22f), 20f, corEscuro, Paint.Align.CENTER, (255 * fade).toInt())

        for (i in 0 until n) {
            val k = ((t - i * 0.08f) / 0.28f).coerceIn(0f, 1f)
            val r = cartao(i, n)
            val u = Game3D.UPGS[game.choices[i]]

            c.save()
            c.translate(0f, (1f - k) * dp(30f))
            c.scale(0.92f + 0.08f * k, 0.92f + 0.08f * k, r.centerX(), r.centerY())

            paint.style = Paint.Style.FILL
            paint.color = Color.argb(50, 21, 23, 28)
            rect.set(r.left + dp(4f), r.top + dp(5f), r.right + dp(4f), r.bottom + dp(5f))
            c.drawRoundRect(rect, dp(8f), dp(8f), paint)
            paint.color = Color.WHITE
            c.drawRoundRect(r, dp(8f), dp(8f), paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(2.5f)
            paint.color = corEscuro
            c.drawRoundRect(r, dp(8f), dp(8f), paint)

            // ícone
            val ix = r.centerX()
            val iy = r.top + dp(52f)
            paint.style = Paint.Style.FILL
            paint.color = corEscuro
            c.drawCircle(ix, iy, dp(30f), paint)
            desenharIcone(c, u.id, ix, iy, dp(24f))

            texto(c, u.nome, r.centerX(), r.top + dp(110f), 15f, corEscuro, Paint.Align.CENTER)

            tp.color = corCinza
            tp.textSize = 12f * d
            val larg = (r.width() - dp(24f)).toInt().coerceAtLeast(10)
            val lay = StaticLayout.Builder.obtain(u.desc, 0, u.desc.length, tp, larg)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .build()
            c.save()
            c.translate(r.left + dp(12f), r.top + dp(122f))
            lay.draw(c)
            c.restore()

            val nivel = if (u.max < 90) "NÍVEL ${game.lv[u.id] + 1} DE ${u.max}" else "VIDA ${game.hp} DE ${game.maxHp}"
            texto(c, nivel, r.centerX(), r.bottom - dp(14f), 11f, corVermelho, Paint.Align.CENTER)
            c.restore()
        }
    }

    private fun desenharIcone(c: Canvas, id: Int, cx: Float, cy: Float, s: Float) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(3f)
        paint.color = Color.WHITE
        when (id) {
            0 -> {
                for (k in -1..1) {
                    val y = cy + k * s * 0.34f
                    c.drawLine(cx - s * 0.5f, y, cx + s * (0.5f - 0.2f * (k + 1)), y, paint)
                }
            }
            1 -> {
                val by = cy + s * 0.45f
                for (k in -1..1) {
                    val a = k * 0.55f
                    c.drawLine(cx, by, cx + sin(a) * s * 0.95f, by - cos(a) * s * 0.95f, paint)
                }
            }
            2 -> {
                path.reset()
                path.moveTo(cx, cy - s * 0.55f)
                path.lineTo(cx + s * 0.45f, cy - s * 0.35f)
                path.lineTo(cx + s * 0.45f, cy + s * 0.1f)
                path.lineTo(cx, cy + s * 0.6f)
                path.lineTo(cx - s * 0.45f, cy + s * 0.1f)
                path.lineTo(cx - s * 0.45f, cy - s * 0.35f)
                path.close()
                c.drawPath(path, paint)
            }
            3 -> {
                c.drawLine(cx - s * 0.5f, cy, cx + s * 0.5f, cy, paint)
                c.drawLine(cx, cy - s * 0.5f, cx, cy + s * 0.5f, paint)
            }
            4 -> {
                c.drawCircle(cx + s * 0.1f, cy, s * 0.3f, paint)
                c.drawLine(cx - s * 0.65f, cy, cx + s * 0.65f, cy, paint)
            }
            5 -> {
                path.reset()
                path.moveTo(cx - s * 0.6f, cy + s * 0.3f)
                path.lineTo(cx - s * 0.2f, cy - s * 0.4f)
                path.lineTo(cx + s * 0.2f, cy + s * 0.3f)
                path.lineTo(cx + s * 0.6f, cy - s * 0.4f)
                c.drawPath(path, paint)
            }
            6 -> {
                for (k in 0..1) {
                    val o = (k - 0.5f) * s * 0.55f
                    path.reset()
                    path.moveTo(cx + o - s * 0.2f, cy - s * 0.45f)
                    path.lineTo(cx + o + s * 0.2f, cy)
                    path.lineTo(cx + o - s * 0.2f, cy + s * 0.45f)
                    c.drawPath(path, paint)
                }
            }
            7 -> {
                c.drawCircle(cx, cy, s * 0.5f, paint)
                c.drawLine(cx, cy, cx, cy - s * 0.35f, paint)
                c.drawLine(cx, cy, cx + s * 0.25f, cy + s * 0.1f, paint)
            }
            else -> {
                paint.style = Paint.Style.FILL
                c.drawCircle(cx, cy, s * 0.36f, paint)
            }
        }
    }

    // ------------------------------------------------------------------
    // Toques
    // ------------------------------------------------------------------

    private fun soltarTudo() {
        movId = -1
        fireId = -1
        lookId = -1
        game.soltarEntradas()
    }

    private fun atualizarJoystick() {
        val raio = dp(56f)
        var dx = (movCx - movAx) / raio
        var dy = (movCy - movAy) / raio
        val len = hypot(dx, dy)
        if (len > 1f) {
            dx /= len
            dy /= len
        }
        val l = min(1f, len)
        if (l < 0.10f) {
            game.setMove(0f, 0f)
        } else {
            val mag = (l - 0.10f) / 0.90f
            val n = max(0.0001f, hypot(dx, dy))
            game.setMove(dx / n * mag, dy / n * mag)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val idx = e.actionIndex
                aoTocar(e.getPointerId(idx), e.getX(idx), e.getY(idx))
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until e.pointerCount) {
                    val id = e.getPointerId(i)
                    val x = e.getX(i)
                    val y = e.getY(i)
                    if (id == movId) {
                        movCx = x
                        movCy = y
                        atualizarJoystick()
                    } else if (id == lookId) {
                        game.addLook(x - lookLx, y - lookLy)
                        lookLx = x
                        lookLy = y
                    } else if (id == fireId) {
                        // dá para mirar arrastando o dedo que está no botão de tiro
                        game.addLook(x - fireLx, y - fireLy)
                        fireLx = x
                        fireLy = y
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val id = e.getPointerId(e.actionIndex)
                if (id == movId) {
                    movId = -1
                    game.setMove(0f, 0f)
                } else if (id == lookId) {
                    lookId = -1
                } else if (id == fireId) {
                    fireId = -1
                    game.setFire(false)
                }
            }
            MotionEvent.ACTION_CANCEL -> soltarTudo()
        }
        return true
    }

    private fun aoTocar(id: Int, x: Float, y: Float) {
        synchronized(game) {
            if (game.paused) {
                if (btn(0).contains(x, y)) {
                    game.resume()
                } else if (btn(1).contains(x, y)) {
                    onSair()
                }
                return
            }
            when (game.state) {
                Game3D.S_UPGRADE -> {
                    val n = game.choices.size
                    for (i in 0 until n) {
                        if (cartao(i, n).contains(x, y)) {
                            game.escolherMelhoria(i)
                            return
                        }
                    }
                }
                Game3D.S_DEAD -> {
                    if (game.stateT > 1.0f) {
                        if (btn(0).contains(x, y)) {
                            game.reiniciar()
                        } else if (btn(1).contains(x, y)) {
                            onSair()
                        }
                    }
                }
                else -> {
                    if (rPause.contains(x, y)) {
                        soltarTudo()
                        game.pause()
                        return
                    }
                    if (hypot(x - fireCx, y - fireCy) < fireR * 1.25f) {
                        if (fireId == -1) {
                            fireId = id
                            fireLx = x
                            fireLy = y
                            game.setFire(true)
                        }
                    } else if (x < width * 0.45f) {
                        if (movId == -1) {
                            movId = id
                            movAx = x
                            movAy = y
                            movCx = x
                            movCy = y
                            atualizarJoystick()
                        }
                    } else {
                        if (lookId == -1) {
                            lookId = id
                            lookLx = x
                            lookLy = y
                        }
                    }
                }
            }
        }
    }
}
