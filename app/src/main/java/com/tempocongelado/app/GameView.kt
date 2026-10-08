package com.tempocongelado.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.MotionEvent
import android.view.View
import java.util.Random
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Tempo Congelado: roguelike de tiro onde o tempo só anda quando o jogador se mexe.
 * Tudo é desenhado em código (sem arquivos de imagem).
 */
class GameView(context: Context, private val listener: Listener) : View(context) {

    interface Listener {
        fun onSair()
    }

    // ------------------------------------------------------------------
    // Classes internas
    // ------------------------------------------------------------------

    private class Enemy(val type: Int, var x: Float, var y: Float, val r: Float, var hp: Int) {
        val maxHp = hp
        var cd = 2f
        var flash = 0f
        var tele = false
        var dir = 1f
        var ang = 0f
        var phase = 0
        var t = 0f
    }

    private class Bullet(var x: Float, var y: Float, var vx: Float, var vy: Float, val mine: Boolean) {
        var r = 6f
        var bounce = 0
        var pierce = 0
        var dmg = 1
        var life = 8f
        var dead = false
        var hits: ArrayList<Enemy>? = null
    }

    private class Shard(
        var x: Float, var y: Float, var vx: Float, var vy: Float,
        var rot: Float, val vr: Float, val size: Float, val color: Int
    ) {
        var life = 1f
        var maxLife = 1f
    }

    private class Upg(val id: Int, val nome: String, val desc: String, val max: Int)

    // ------------------------------------------------------------------
    // Constantes e cores
    // ------------------------------------------------------------------

    private val W = 720f
    private var H = 1280f
    private val arena = RectF(28f, 130f, 692f, 1190f)
    private val PI2 = (Math.PI * 2.0).toFloat()
    private val PIF = Math.PI.toFloat()

    private val corFundo = Color.parseColor("#EEF0F4")
    private val corChaoRapido = Color.parseColor("#F8F9FB")
    private val corChaoLento = Color.parseColor("#DDE1EA")
    private val corEscuro = Color.parseColor("#15171C")
    private val corVermelho = Color.parseColor("#E5322D")
    private val corVermelhoEscuro = Color.parseColor("#8F1D19")
    private val corBloco = Color.parseColor("#C5CAD6")
    private val corCinza = Color.parseColor("#6B7280")
    private val corBranco = Color.WHITE

    private val S_INTRO = 0
    private val S_PLAY = 1
    private val S_CLEAR = 2
    private val S_UPGRADE = 3
    private val S_DEAD = 4

    private val UPGS = listOf(
        Upg(0, "CADÊNCIA", "Atira 22% mais rápido", 4),
        Upg(1, "TIRO MÚLTIPLO", "+1 projétil em leque", 3),
        Upg(2, "ESCUDO", "+1 vida máxima e cura 1", 3),
        Upg(3, "REPARO", "Recupera 1 vida", 99),
        Upg(4, "PERFURANTE", "Balas atravessam +1 inimigo", 3),
        Upg(5, "RICOCHETE", "Balas quicam +1 vez nas paredes", 3),
        Upg(6, "PASSOS LEVES", "+12% de velocidade", 4),
        Upg(7, "FOCO", "Parado, o tempo fica ainda mais lento", 2),
        Upg(8, "CALIBRE", "+1 de dano nas balas", 3)
    )

    // ------------------------------------------------------------------
    // Estado do jogo
    // ------------------------------------------------------------------

    private val enemies = ArrayList<Enemy>()
    private val bullets = ArrayList<Bullet>()
    private val shards = ArrayList<Shard>()
    private val blocks = ArrayList<RectF>()
    private val rng = Random()
    private val tmp = FloatArray(2)

    private var started = false
    private var paused = false
    private var last = 0L

    private var state = S_INTRO
    private var stateT = 0f
    private var room = 1
    private var kills = 0

    private var px = 360f
    private var py = 1000f
    private val pr = 16f
    private var hp = 1
    private var maxHp = 1
    private var invuln = 0f
    private var aimAng = -PIF / 2f

    private var timeScale = 0.05f
    private var minScale = 0.04f
    private var boost = 0f
    private var shake = 0f
    private var fireT = 0f

    // melhorias
    private val lv = IntArray(UPGS.size)
    private var fireCd = 0.40f
    private var shots = 1
    private var pierce = 0
    private var bounce = 0
    private var speedMul = 1f
    private var dmg = 1
    private val choices = ArrayList<Int>()
    private val cardRects = ArrayList<RectF>()

    // dicas iniciais
    private var moveuUmaVez = false
    private var atirouUmaVez = false

    // transformação mundo -> tela
    private var sc = 1f
    private var ox = 0f
    private var oy = 0f

    // botões (coordenadas do mundo)
    private val rPause = RectF(604f, 18f, 696f, 108f)
    private var rBtn1 = RectF(110f, 700f, 610f, 804f)
    private var rBtn2 = RectF(110f, 832f, 610f, 936f)

    // entrada
    private var movId = -1
    private var movAx = 0f
    private var movAy = 0f
    private var movCx = 0f
    private var movCy = 0f
    private var movX = 0f
    private var movY = 0f
    private var aimId = -1
    private var aimSx = 0f
    private var aimSy = 0f

    // pincéis reaproveitados
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val rectTmp = RectF()
    private val densidade = resources.displayMetrics.density

    init {
        keepScreenOn = true
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    // ------------------------------------------------------------------
    // Ciclo de vida / tamanho
    // ------------------------------------------------------------------

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        H = (W * h / w).coerceIn(1000f, 1700f)
        sc = min(w / W, h / H)
        ox = (w - W * sc) / 2f
        oy = (h - H * sc) / 2f
        arena.set(28f, 130f, W - 28f, H - 90f)
        val by = H * 0.52f
        rBtn1 = RectF(110f, by, 610f, by + 104f)
        rBtn2 = RectF(110f, by + 132f, 610f, by + 236f)
        if (!started) {
            started = true
            novaPartida()
        }
    }

    fun pausar() {
        if (!paused) {
            paused = true
            soltarTudo()
        }
        last = 0L
    }

    fun alternarPausa() {
        if (paused) {
            paused = false
            last = 0L
        } else {
            pausar()
        }
    }

    // ------------------------------------------------------------------
    // Nova partida / sala
    // ------------------------------------------------------------------

    private fun novaPartida() {
        for (i in lv.indices) lv[i] = 0
        recalcular()
        hp = maxHp
        room = 1
        kills = 0
        iniciarSala()
    }

    private fun recalcular() {
        fireCd = 0.40f * 0.78f.pow(lv[0])
        shots = 1 + lv[1]
        maxHp = 1 + lv[2]
        pierce = lv[4]
        bounce = lv[5]
        speedMul = 1f + 0.12f * lv[6]
        minScale = 0.04f * 0.5f.pow(lv[7])
        dmg = 1 + lv[8]
    }

    private fun iniciarSala() {
        bullets.clear()
        enemies.clear()
        shards.clear()
        px = W / 2f
        py = arena.bottom - 140f
        invuln = 0f
        fireT = 0f
        boost = 0f
        timeScale = 0.05f
        soltarTudo()
        gerarBlocos()
        gerarInimigos()
        state = S_INTRO
        stateT = 0f
    }

    private fun rf(a: Float, b: Float): Float = a + rng.nextFloat() * max(0f, b - a)

    private fun gerarBlocos() {
        blocks.clear()
        val alvo = min(2 + room / 2, 6)
        var tentativas = 0
        while (blocks.size < alvo && tentativas < 100) {
            tentativas++
            val horizontal = rng.nextBoolean()
            val w = if (horizontal) rf(110f, 230f) else rf(36f, 60f)
            val h = if (horizontal) rf(36f, 60f) else rf(110f, 230f)
            val x = rf(arena.left + 60f, arena.right - 60f - w)
            val y = rf(arena.top + 150f, arena.bottom - 360f - h)
            val r = RectF(x, y, x + w, y + h)
            val seguro = RectF(px - 130f, py - 130f, px + 130f, py + 130f)
            if (RectF.intersects(r, seguro)) continue
            var ok = true
            for (o in blocks) {
                val inf = RectF(o.left - 50f, o.top - 50f, o.right + 50f, o.bottom + 50f)
                if (RectF.intersects(inf, r)) {
                    ok = false
                    break
                }
            }
            if (ok) blocks.add(r)
        }
    }

    private fun gerarInimigos() {
        val chefe = room % 5 == 0
        val nAtiradores = if (chefe) 2 else min(2 + (room - 1) / 2, 5)
        val nPerseguidores = if (room >= 2) min(room / 2, 4) else 0
        val nBrutos = if (room >= 4) min((room - 2) / 2, 3) else 0

        if (chefe) {
            val nivel = room / 5
            achaLugar(52f)
            val b = Enemy(3, tmp[0], arena.top + 200f, 50f, 18 + 6 * (nivel - 1))
            b.cd = 2.2f
            enemies.add(b)
        }
        for (i in 0 until nAtiradores) {
            achaLugar(15f)
            val e = Enemy(0, tmp[0], tmp[1], 15f, 1)
            e.cd = 1.8f + rng.nextFloat() * 2.2f
            e.dir = if (rng.nextBoolean()) 1f else -1f
            enemies.add(e)
        }
        for (i in 0 until nPerseguidores) {
            achaLugar(13f)
            val e = Enemy(1, tmp[0], tmp[1], 13f, 1)
            enemies.add(e)
        }
        for (i in 0 until nBrutos) {
            achaLugar(28f)
            val e = Enemy(2, tmp[0], tmp[1], 28f, 3 + room / 8)
            e.cd = 2.5f + rng.nextFloat() * 2f
            e.dir = if (rng.nextBoolean()) 1f else -1f
            enemies.add(e)
        }
    }

    /** Procura um lugar livre para um inimigo; resultado em tmp[0], tmp[1]. */
    private fun achaLugar(r: Float) {
        for (t in 0 until 70) {
            val x = rf(arena.left + r + 20f, arena.right - r - 20f)
            val y = rf(arena.top + r + 20f, arena.top + arena.height() * 0.62f)
            if (hypot(x - px, y - py) < 380f) continue
            var ok = true
            for (b in blocks) {
                if (circuloNoRetangulo(x, y, r + 14f, b)) {
                    ok = false
                    break
                }
            }
            if (ok) {
                for (e in enemies) {
                    if (hypot(x - e.x, y - e.y) < e.r + r + 40f) {
                        ok = false
                        break
                    }
                }
            }
            if (ok) {
                tmp[0] = x
                tmp[1] = y
                return
            }
        }
        tmp[0] = rf(arena.left + r + 20f, arena.right - r - 20f)
        tmp[1] = arena.top + r + 30f
    }

    // ------------------------------------------------------------------
    // Atualização
    // ------------------------------------------------------------------

    private fun atualizar(dtR: Float) {
        stateT += dtR
        if (shake > 0f) shake = max(0f, shake - dtR * 45f)
        if (invuln > 0f) invuln -= dtR
        for (e in enemies) {
            if (e.flash > 0f) e.flash -= dtR
        }

        when (state) {
            S_INTRO -> {
                timeScale += (0.05f - timeScale) * min(1f, dtR * 10f)
                if (stateT > 1.1f) {
                    state = S_PLAY
                    stateT = 0f
                }
            }
            S_PLAY -> atualizarJogo(dtR)
            S_CLEAR -> {
                timeScale += (0.5f - timeScale) * min(1f, dtR * 6f)
                val dt = dtR * timeScale
                atualizarBalas(dt)
                atualizarEstilhacos(dt)
                if (stateT > 1.3f) abrirMelhorias()
            }
            S_UPGRADE -> {
                timeScale += (0.15f - timeScale) * min(1f, dtR * 6f)
                atualizarEstilhacos(dtR * 0.2f)
            }
            S_DEAD -> {
                timeScale += (0.12f - timeScale) * min(1f, dtR * 6f)
                val dt = dtR * timeScale
                atualizarBalas(dt)
                atualizarEstilhacos(dt)
            }
        }
    }

    private fun atualizarJogo(dtR: Float) {
        val mag = min(1f, hypot(movX, movY))
        var alvo = minScale + (1f - minScale) * mag
        if (boost > 0f) {
            boost -= dtR
            alvo = max(alvo, 0.45f)
        }
        timeScale += (alvo - timeScale) * min(1f, dtR * 14f)
        val dt = dtR * timeScale

        // jogador anda em tempo real; o mundo anda no ritmo do jogador
        if (mag > 0.01f) {
            moveuUmaVez = true
            val vel = 340f * speedMul * mag * dtR
            moverJogador(movX / mag * vel, movY / mag * vel)
        }

        // tiro
        fireT -= dtR
        if (aimId != -1) {
            val tx = mundoX(aimSx)
            val ty = mundoY(aimSy)
            val ddx = tx - px
            val ddy = ty - py
            if (ddx * ddx + ddy * ddy > 400f) aimAng = atan2(ddy, ddx)
            if (fireT <= 0f) atirar()
        }

        // inimigos (de trás para frente: podem ser removidos no loop)
        var i = enemies.size - 1
        while (i >= 0) {
            if (i < enemies.size) atualizarInimigo(i, dt)
            i--
        }

        atualizarBalas(dt)
        atualizarEstilhacos(dt)
    }

    private fun moverJogador(dx: Float, dy: Float) {
        colidir(px + dx, py + dy, pr)
        px = tmp[0]
        py = tmp[1]
    }

    private fun atirar() {
        fireT = fireCd
        boost = 0.25f
        atirouUmaVez = true
        val abertura = 0.16f
        for (i in 0 until shots) {
            val off = (i - (shots - 1) / 2f) * abertura
            val a = aimAng + off
            val b = Bullet(
                px + cos(a) * (pr + 4f), py + sin(a) * (pr + 4f),
                cos(a) * 1000f, sin(a) * 1000f, true
            )
            b.pierce = pierce
            b.bounce = bounce
            b.dmg = dmg
            b.r = 5f + (dmg - 1) * 1.5f
            if (pierce > 0) b.hits = ArrayList(2)
            bullets.add(b)
        }
        vibrar(8)
    }

    private fun atualizarInimigo(idx: Int, dt: Float) {
        val e = enemies[idx]
        val dx = px - e.x
        val dy = py - e.y
        val dist = max(1f, hypot(dx, dy))
        val ux = dx / dist
        val uy = dy / dist
        e.ang = atan2(dy, dx)
        e.t += dt
        if (e.t > 3f) {
            e.t = 0f
            e.dir = -e.dir
        }

        var mx = 0f
        var my = 0f
        var vel = 0f

        when (e.type) {
            0 -> { // atirador
                val querDist = 320f
                if (dist > querDist + 50f) {
                    mx = ux; my = uy
                } else if (dist < querDist - 70f) {
                    mx = -ux; my = -uy
                } else {
                    mx = -uy * e.dir; my = ux * e.dir
                }
                vel = 85f
                e.cd -= dt
                e.tele = e.cd < 0.45f
                if (e.cd <= 0f) {
                    atirarNoJogador(e, 300f, 1, 0f)
                    e.cd = 2.6f + rng.nextFloat() * 1.4f
                    e.tele = false
                }
            }
            1 -> { // perseguidor
                mx = ux; my = uy
                vel = 150f
            }
            2 -> { // brutamontes
                val querDist = 380f
                if (dist > querDist + 50f) {
                    mx = ux; my = uy
                } else if (dist < querDist - 90f) {
                    mx = -ux; my = -uy
                } else {
                    mx = -uy * e.dir; my = ux * e.dir
                }
                vel = 55f
                e.cd -= dt
                e.tele = e.cd < 0.5f
                if (e.cd <= 0f) {
                    atirarNoJogador(e, 270f, 3, 0.3f)
                    e.cd = 3.2f + rng.nextFloat() * 1.2f
                    e.tele = false
                }
            }
            else -> { // chefe
                mx = e.dir
                my = ((arena.top + 220f) - e.y) * 0.01f
                vel = 70f
                if (e.x < arena.left + e.r + 30f) e.dir = 1f
                if (e.x > arena.right - e.r - 30f) e.dir = -1f
                e.cd -= dt
                e.tele = e.cd < 0.5f
                if (e.cd <= 0f) {
                    if (e.phase % 2 == 0) atirarNoJogador(e, 330f, 5, 0.22f) else anel(e, 240f, 14)
                    e.phase++
                    e.cd = 2.0f
                    e.tele = false
                }
            }
        }

        e.x += mx * vel * dt
        e.y += my * vel * dt
        colidir(e.x, e.y, e.r)
        e.x = tmp[0]
        e.y = tmp[1]

        // perseguidor explode no contato
        if (e.type == 1 && dist < e.r + pr + 2f && state == S_PLAY && invuln <= 0f) {
            ferirJogador()
            matarInimigo(idx)
        }
    }

    private fun atirarNoJogador(e: Enemy, vel: Float, qtd: Int, abertura: Float) {
        val base = atan2(py - e.y, px - e.x)
        for (i in 0 until qtd) {
            val a = base + (i - (qtd - 1) / 2f) * abertura
            val b = Bullet(
                e.x + cos(a) * e.r, e.y + sin(a) * e.r,
                cos(a) * vel, sin(a) * vel, false
            )
            b.r = 7f
            bullets.add(b)
        }
    }

    private fun anel(e: Enemy, vel: Float, qtd: Int) {
        val inicio = rng.nextFloat() * PI2
        for (i in 0 until qtd) {
            val a = inicio + i * PI2 / qtd
            val b = Bullet(
                e.x + cos(a) * e.r, e.y + sin(a) * e.r,
                cos(a) * vel, sin(a) * vel, false
            )
            b.r = 7f
            bullets.add(b)
        }
    }

    private fun atualizarBalas(dt: Float) {
        var i = bullets.size - 1
        while (i >= 0) {
            val b = bullets[i]
            var remover = b.dead
            if (!remover) {
                val passo = hypot(b.vx, b.vy) * dt
                val etapas = max(1, ceil(passo / 10f).toInt())
                val sdt = dt / etapas
                var s = 0
                while (s < etapas && !remover) {
                    remover = passoBala(b, sdt)
                    s++
                }
                b.life -= dt
                if (b.life <= 0f) remover = true
            }
            if (remover) bullets.removeAt(i)
            i--
        }
    }

    /** Move a bala um pedacinho. Retorna true se ela deve sumir. */
    private fun passoBala(b: Bullet, sdt: Float): Boolean {
        if (b.dead) return true
        b.x += b.vx * sdt
        b.y += b.vy * sdt

        // paredes da arena
        val fora = b.x < arena.left + b.r || b.x > arena.right - b.r ||
            b.y < arena.top + b.r || b.y > arena.bottom - b.r
        if (fora) {
            if (b.mine && b.bounce > 0) {
                if (b.x < arena.left + b.r) {
                    b.x = arena.left + b.r
                    b.vx = abs(b.vx)
                } else if (b.x > arena.right - b.r) {
                    b.x = arena.right - b.r
                    b.vx = -abs(b.vx)
                }
                if (b.y < arena.top + b.r) {
                    b.y = arena.top + b.r
                    b.vy = abs(b.vy)
                } else if (b.y > arena.bottom - b.r) {
                    b.y = arena.bottom - b.r
                    b.vy = -abs(b.vy)
                }
                b.bounce--
                faiscas(b.x, b.y, 3, corEscuro)
            } else {
                faiscas(b.x, b.y, 3, if (b.mine) corEscuro else corVermelho)
                return true
            }
        }

        // blocos
        for (blk in blocks) {
            if (circuloNoRetangulo(b.x, b.y, b.r, blk)) {
                if (b.mine && b.bounce > 0) {
                    val cx = blk.centerX()
                    val cy = blk.centerY()
                    val penX = blk.width() / 2f + b.r - abs(b.x - cx)
                    val penY = blk.height() / 2f + b.r - abs(b.y - cy)
                    if (penX < penY) {
                        b.vx = -b.vx
                        b.x += if (b.x < cx) -penX else penX
                    } else {
                        b.vy = -b.vy
                        b.y += if (b.y < cy) -penY else penY
                    }
                    b.bounce--
                    faiscas(b.x, b.y, 3, corEscuro)
                } else {
                    faiscas(b.x, b.y, 3, if (b.mine) corEscuro else corVermelho)
                    return true
                }
                break
            }
        }

        if (b.mine) {
            // tiros do jogador destroem tiros inimigos
            for (o in bullets) {
                if (!o.mine && !o.dead && hypot(b.x - o.x, b.y - o.y) < b.r + o.r + 3f) {
                    o.dead = true
                    faiscas(o.x, o.y, 5, corVermelho)
                    if (b.pierce <= 0) return true
                }
            }
            // acerta inimigos
            var k = enemies.size - 1
            while (k >= 0) {
                val e = enemies[k]
                val jaAcertou = b.hits?.contains(e) ?: false
                if (!jaAcertou && hypot(b.x - e.x, b.y - e.y) < e.r + b.r) {
                    e.hp -= b.dmg
                    e.flash = 0.18f
                    faiscas(b.x, b.y, 4, corVermelho)
                    if (e.hp <= 0) {
                        matarInimigo(k)
                    } else {
                        b.hits?.add(e)
                    }
                    if (b.pierce > 0) {
                        b.pierce--
                    } else {
                        return true
                    }
                }
                k--
            }
        } else {
            if (state == S_PLAY && invuln <= 0f && hypot(b.x - px, b.y - py) < pr + b.r - 2f) {
                ferirJogador()
                return true
            }
        }
        return false
    }

    private fun atualizarEstilhacos(dt: Float) {
        var i = shards.size - 1
        while (i >= 0) {
            val s = shards[i]
            s.x += s.vx * dt
            s.y += s.vy * dt
            val atrito = max(0f, 1f - 1.6f * dt)
            s.vx *= atrito
            s.vy *= atrito
            s.rot += s.vr * dt
            s.life -= dt
            if (s.life <= 0f) shards.removeAt(i)
            i--
        }
    }

    private fun matarInimigo(idx: Int) {
        val e = enemies.removeAt(idx)
        val qtd = if (e.type == 3) 40 else if (e.type == 2) 20 else 12
        estilhacos(e.x, e.y, qtd, corVermelho, 260f, 10f)
        estilhacos(e.x, e.y, qtd / 3, corVermelhoEscuro, 200f, 8f)
        kills++
        shake = max(shake, if (e.type == 3) 16f else 7f)
        vibrar(if (e.type == 3) 60 else 18)
        if (enemies.isEmpty() && state == S_PLAY) {
            state = S_CLEAR
            stateT = 0f
            soltarTudo()
            for (b in bullets) {
                if (!b.mine && !b.dead) {
                    b.dead = true
                    faiscas(b.x, b.y, 4, corVermelho)
                }
            }
        }
    }

    private fun ferirJogador() {
        if (hp > 1) {
            hp--
            invuln = 1.4f
            shake = 14f
            estilhacos(px, py, 12, corEscuro, 220f, 8f)
            vibrar(60)
        } else {
            hp = 0
            morrer()
        }
    }

    private fun morrer() {
        state = S_DEAD
        stateT = 0f
        soltarTudo()
        estilhacos(px, py, 34, corEscuro, 300f, 11f)
        estilhacos(px, py, 12, corBranco, 220f, 8f)
        shake = 18f
        vibrar(160)
        Prefs.registrar(context, room, kills)
    }

    // ------------------------------------------------------------------
    // Melhorias
    // ------------------------------------------------------------------

    private fun abrirMelhorias() {
        val disponiveis = ArrayList<Int>()
        for (u in UPGS) {
            if (lv[u.id] >= u.max) continue
            if (u.id == 3 && hp >= maxHp) continue
            disponiveis.add(u.id)
        }
        if (disponiveis.isEmpty()) {
            proximaSala()
            return
        }
        java.util.Collections.shuffle(disponiveis, rng)
        choices.clear()
        for (i in 0 until min(3, disponiveis.size)) choices.add(disponiveis[i])

        cardRects.clear()
        val n = choices.size
        val altura = 220f
        val folga = 28f
        val total = n * altura + (n - 1) * folga
        var y = H / 2f - total / 2f + 40f
        for (i in 0 until n) {
            cardRects.add(RectF(48f, y, W - 48f, y + altura))
            y += altura + folga
        }
        state = S_UPGRADE
        stateT = 0f
        soltarTudo()
    }

    private fun escolher(id: Int) {
        lv[id]++
        recalcular()
        if (id == 2 || id == 3) hp = min(maxHp, hp + 1)
        proximaSala()
    }

    private fun proximaSala() {
        room++
        iniciarSala()
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    private fun circuloNoRetangulo(cx: Float, cy: Float, r: Float, b: RectF): Boolean {
        val nx = cx.coerceIn(b.left, b.right)
        val ny = cy.coerceIn(b.top, b.bottom)
        val dx = cx - nx
        val dy = cy - ny
        return dx * dx + dy * dy < r * r
    }

    /** Empurra um círculo para fora dos blocos e mantém dentro da arena. Resultado em tmp. */
    private fun colidir(x: Float, y: Float, r: Float) {
        var cx = x
        var cy = y
        for (b in blocks) {
            val nx = cx.coerceIn(b.left, b.right)
            val ny = cy.coerceIn(b.top, b.bottom)
            val dx = cx - nx
            val dy = cy - ny
            val d2 = dx * dx + dy * dy
            if (d2 < r * r) {
                if (d2 > 0.0001f) {
                    val d = sqrt(d2)
                    cx = nx + dx / d * r
                    cy = ny + dy / d * r
                } else {
                    val l = cx - b.left
                    val rr = b.right - cx
                    val t = cy - b.top
                    val bb = b.bottom - cy
                    val m = min(min(l, rr), min(t, bb))
                    if (m == l) {
                        cx = b.left - r
                    } else if (m == rr) {
                        cx = b.right + r
                    } else if (m == t) {
                        cy = b.top - r
                    } else {
                        cy = b.bottom + r
                    }
                }
            }
        }
        cx = cx.coerceIn(arena.left + r, arena.right - r)
        cy = cy.coerceIn(arena.top + r, arena.bottom - r)
        tmp[0] = cx
        tmp[1] = cy
    }

    private fun estilhacos(x: Float, y: Float, n: Int, cor: Int, vel: Float, tam: Float) {
        for (i in 0 until n) {
            val a = rng.nextFloat() * PI2
            val v = vel * (0.3f + rng.nextFloat() * 0.9f)
            val s = Shard(
                x, y, cos(a) * v, sin(a) * v,
                rng.nextFloat() * PI2, (rng.nextFloat() - 0.5f) * 14f,
                tam * (0.5f + rng.nextFloat()), cor
            )
            s.maxLife = 1.0f + rng.nextFloat() * 0.9f
            s.life = s.maxLife
            shards.add(s)
        }
        while (shards.size > 420) shards.removeAt(0)
    }

    private fun faiscas(x: Float, y: Float, n: Int, cor: Int) {
        estilhacos(x, y, n, cor, 140f, 4f)
    }

    private fun vibrar(ms: Int) {
        if (!Prefs.vibracao(context)) return
        val v = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        val dur = ms.toLong()
        if (Build.VERSION.SDK_INT >= 26) {
            v.vibrate(VibrationEffect.createOneShot(dur, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            v.vibrate(dur)
        }
    }

    private fun mundoX(sx: Float): Float = (sx - ox) / sc
    private fun mundoY(sy: Float): Float = (sy - oy) / sc

    private fun misturar(a: Int, b: Int, t: Float): Int {
        val k = t.coerceIn(0f, 1f)
        val r = (Color.red(a) + (Color.red(b) - Color.red(a)) * k).toInt()
        val g = (Color.green(a) + (Color.green(b) - Color.green(a)) * k).toInt()
        val bl = (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * k).toInt()
        return Color.rgb(r, g, bl)
    }

    private fun soltarTudo() {
        movId = -1
        aimId = -1
        movX = 0f
        movY = 0f
    }

    // ------------------------------------------------------------------
    // Entrada
    // ------------------------------------------------------------------

    private fun atualizarJoystick() {
        val raio = 56f * densidade
        var dx = (movCx - movAx) / raio
        var dy = (movCy - movAy) / raio
        val len = hypot(dx, dy)
        if (len > 1f) {
            dx /= len
            dy /= len
        }
        val l = min(1f, len)
        if (l < 0.10f) {
            movX = 0f
            movY = 0f
        } else {
            val mag = (l - 0.10f) / 0.90f
            val n = max(0.0001f, hypot(dx, dy))
            movX = dx / n * mag
            movY = dy / n * mag
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
                    if (id == movId) {
                        movCx = e.getX(i)
                        movCy = e.getY(i)
                        atualizarJoystick()
                    } else if (id == aimId) {
                        aimSx = e.getX(i)
                        aimSy = e.getY(i)
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val id = e.getPointerId(e.actionIndex)
                if (id == movId) {
                    movId = -1
                    movX = 0f
                    movY = 0f
                } else if (id == aimId) {
                    aimId = -1
                }
            }
            MotionEvent.ACTION_CANCEL -> soltarTudo()
        }
        return true
    }

    private fun aoTocar(id: Int, sx: Float, sy: Float) {
        if (!started) return
        val x = mundoX(sx)
        val y = mundoY(sy)

        if (paused) {
            if (rBtn1.contains(x, y)) {
                paused = false
                last = 0L
            } else if (rBtn2.contains(x, y)) {
                listener.onSair()
            }
            return
        }

        when (state) {
            S_UPGRADE -> {
                if (stateT > 0.35f) {
                    for (i in cardRects.indices) {
                        if (cardRects[i].contains(x, y)) {
                            escolher(choices[i])
                            return
                        }
                    }
                }
            }
            S_DEAD -> {
                if (stateT > 1.0f) {
                    if (rBtn1.contains(x, y)) {
                        novaPartida()
                    } else if (rBtn2.contains(x, y)) {
                        listener.onSair()
                    }
                }
            }
            else -> {
                if (rPause.contains(x, y)) {
                    pausar()
                    return
                }
                if (sx < width / 2f) {
                    if (movId == -1) {
                        movId = id
                        movAx = sx
                        movAy = sy
                        movCx = sx
                        movCy = sy
                        atualizarJoystick()
                    }
                } else {
                    if (aimId == -1) {
                        aimId = id
                        aimSx = sx
                        aimSy = sy
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Desenho
    // ------------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        val agora = System.nanoTime()
        var dt = if (last == 0L) 0.016f else (agora - last) / 1e9f
        last = agora
        if (dt > 0.05f) dt = 0.05f
        if (dt < 0f) dt = 0f

        if (started && !paused) atualizar(dt)
        if (started) desenhar(canvas)
        postInvalidateOnAnimation()
    }

    private fun preencher(cor: Int) {
        paint.style = Paint.Style.FILL
        paint.color = cor
    }

    private fun contorno(cor: Int, largura: Float) {
        paint.style = Paint.Style.STROKE
        paint.color = cor
        paint.strokeWidth = largura
    }

    private fun poligono(c: Canvas, cx: Float, cy: Float, r: Float, n: Int, rot: Float) {
        path.reset()
        for (i in 0 until n) {
            val a = rot + i * PI2 / n
            val x = cx + cos(a) * r
            val y = cy + sin(a) * r
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        c.drawPath(path, paint)
    }

    private fun texto(
        c: Canvas, s: String, x: Float, y: Float, tam: Float, cor: Int,
        alinhar: Paint.Align = Paint.Align.CENTER, alpha: Int = 255
    ) {
        paint.style = Paint.Style.FILL
        paint.color = cor
        paint.alpha = alpha
        paint.textSize = tam
        paint.textAlign = alinhar
        paint.letterSpacing = 0.06f
        c.drawText(s, x, y, paint)
        paint.letterSpacing = 0f
        paint.alpha = 255
    }

    private fun botao(c: Canvas, r: RectF, rotulo: String, principal: Boolean) {
        preencher(if (principal) corVermelho else corBranco)
        c.drawRoundRect(r, 6f, 6f, paint)
        contorno(corEscuro, 4f)
        c.drawRoundRect(r, 6f, 6f, paint)
        texto(c, rotulo, r.centerX(), r.centerY() + 12f, 34f, if (principal) corBranco else corEscuro)
    }

    private fun desenhar(c: Canvas) {
        c.drawColor(corFundo)

        // mundo (com tremida)
        c.save()
        var sx = 0f
        var sy = 0f
        if (shake > 0f) {
            sx = (rng.nextFloat() - 0.5f) * shake
            sy = (rng.nextFloat() - 0.5f) * shake
        }
        c.translate(ox + sx * sc, oy + sy * sc)
        c.scale(sc, sc)
        desenharArena(c)
        desenharBlocos(c)
        desenharInimigos(c)
        desenharBalas(c)
        desenharEstilhacos(c)
        if (state != S_DEAD) desenharJogador(c)
        if (aimId != -1 && state == S_PLAY) desenharMira(c)
        c.restore()

        // interface (sem tremida)
        c.save()
        c.translate(ox, oy)
        c.scale(sc, sc)
        desenharHud(c)
        desenharSobreposicoes(c)
        c.restore()

        desenharJoystick(c)
    }

    private fun desenharArena(c: Canvas) {
        preencher(misturar(corChaoLento, corChaoRapido, timeScale))
        c.drawRect(arena, paint)

        // grade sutil
        contorno(Color.argb(28, 21, 23, 28), 2f)
        var gx = arena.left + 80f
        while (gx < arena.right) {
            c.drawLine(gx, arena.top, gx, arena.bottom, paint)
            gx += 80f
        }
        var gy = arena.top + 80f
        while (gy < arena.bottom) {
            c.drawLine(arena.left, gy, arena.right, gy, paint)
            gy += 80f
        }

        contorno(corEscuro, 6f)
        c.drawRect(arena, paint)
    }

    private fun desenharBlocos(c: Canvas) {
        for (b in blocks) {
            preencher(Color.argb(55, 21, 23, 28))
            rectTmp.set(b.left + 8f, b.top + 10f, b.right + 8f, b.bottom + 10f)
            c.drawRect(rectTmp, paint)
            preencher(corBloco)
            c.drawRect(b, paint)
            contorno(corEscuro, 3f)
            c.drawRect(b, paint)
        }
    }

    private fun desenharInimigos(c: Canvas) {
        for (e in enemies) {
            // sombra
            preencher(Color.argb(40, 21, 23, 28))
            c.drawCircle(e.x + 5f, e.y + 8f, e.r, paint)

            val branco = e.flash > 0f
            val corCorpo = if (branco) corBranco else corVermelho
            preencher(corCorpo)
            when (e.type) {
                0 -> poligono(c, e.x, e.y, e.r * 1.15f, 4, e.ang)
                1 -> poligono(c, e.x, e.y, e.r * 1.2f, 3, e.ang)
                2 -> poligono(c, e.x, e.y, e.r, 6, e.ang)
                else -> poligono(c, e.x, e.y, e.r, 8, e.ang)
            }
            contorno(corVermelhoEscuro, 3f)
            when (e.type) {
                0 -> poligono(c, e.x, e.y, e.r * 1.15f, 4, e.ang)
                1 -> poligono(c, e.x, e.y, e.r * 1.2f, 3, e.ang)
                2 -> poligono(c, e.x, e.y, e.r, 6, e.ang)
                else -> poligono(c, e.x, e.y, e.r, 8, e.ang)
            }

            if (e.type >= 2) {
                preencher(if (branco) corVermelho else corVermelhoEscuro)
                poligono(c, e.x, e.y, e.r * 0.5f, if (e.type == 2) 6 else 4, e.ang + PIF / 4f)
            }

            // aviso de tiro
            if (e.tele) {
                contorno(corEscuro, 3f)
                c.drawCircle(e.x, e.y, e.r + 9f, paint)
            }

            // barra de vida (brutamontes e chefe)
            if (e.type >= 2 && e.hp < e.maxHp) {
                val larg = e.r * 2f
                preencher(Color.argb(60, 21, 23, 28))
                c.drawRect(e.x - larg / 2f, e.y - e.r - 20f, e.x + larg / 2f, e.y - e.r - 14f, paint)
                preencher(corEscuro)
                val f = e.hp.toFloat() / e.maxHp
                c.drawRect(e.x - larg / 2f, e.y - e.r - 20f, e.x - larg / 2f + larg * f, e.y - e.r - 14f, paint)
            }
        }
    }

    private fun desenharBalas(c: Canvas) {
        for (b in bullets) {
            if (b.dead) continue
            if (b.mine) {
                contorno(corEscuro, b.r * 1.6f)
                c.drawLine(b.x - b.vx * 0.012f, b.y - b.vy * 0.012f, b.x, b.y, paint)
            } else {
                preencher(Color.argb(60, 229, 50, 45))
                c.drawCircle(b.x, b.y, b.r + 6f, paint)
                preencher(corVermelho)
                c.drawCircle(b.x, b.y, b.r, paint)
                preencher(corBranco)
                c.drawCircle(b.x, b.y, b.r * 0.35f, paint)
            }
        }
    }

    private fun desenharEstilhacos(c: Canvas) {
        for (s in shards) {
            val f = (s.life / s.maxLife).coerceIn(0f, 1f)
            paint.style = Paint.Style.FILL
            paint.color = s.color
            paint.alpha = (255 * min(1f, f * 1.6f)).toInt()
            poligono(c, s.x, s.y, s.size * (0.4f + 0.6f * f), 3, s.rot)
            paint.alpha = 255
        }
    }

    private fun desenharJogador(c: Canvas) {
        if (invuln > 0f && ((invuln * 12f).toInt() % 2 == 0)) return
        preencher(Color.argb(45, 21, 23, 28))
        c.drawCircle(px + 4f, py + 7f, pr, paint)

        contorno(corEscuro, 7f)
        c.drawLine(px, py, px + cos(aimAng) * (pr + 12f), py + sin(aimAng) * (pr + 12f), paint)
        preencher(corEscuro)
        c.drawCircle(px, py, pr, paint)
        preencher(corBranco)
        c.drawCircle(px, py, pr * 0.42f, paint)
    }

    private fun desenharMira(c: Canvas) {
        val tx = mundoX(aimSx)
        val ty = mundoY(aimSy)
        contorno(Color.argb(150, 229, 50, 45), 3f)
        c.drawCircle(tx, ty, 18f, paint)
        c.drawLine(tx - 28f, ty, tx - 10f, ty, paint)
        c.drawLine(tx + 10f, ty, tx + 28f, ty, paint)
        c.drawLine(tx, ty - 28f, tx, ty - 10f, paint)
        c.drawLine(tx, ty + 10f, tx, ty + 28f, paint)
    }

    private fun desenharHud(c: Canvas) {
        texto(c, "SALA $room", 36f, 72f, 44f, corEscuro, Paint.Align.LEFT)
        texto(c, "INIMIGOS: " + enemies.size, 38f, 102f, 22f, corCinza, Paint.Align.LEFT)

        // vidas
        for (i in 0 until maxHp) {
            val cx = 470f + i * 34f
            if (i < hp) {
                preencher(corVermelho)
                c.drawCircle(cx, 62f, 12f, paint)
            } else {
                contorno(corEscuro, 3f)
                c.drawCircle(cx, 62f, 11f, paint)
            }
        }

        // botão de pausa
        contorno(corEscuro, 4f)
        c.drawRoundRect(rPause, 6f, 6f, paint)
        preencher(corEscuro)
        c.drawRect(rPause.centerX() - 14f, rPause.centerY() - 16f, rPause.centerX() - 5f, rPause.centerY() + 16f, paint)
        c.drawRect(rPause.centerX() + 5f, rPause.centerY() - 16f, rPause.centerX() + 14f, rPause.centerY() + 16f, paint)

        // barra do tempo
        contorno(Color.argb(60, 21, 23, 28), 6f)
        c.drawLine(arena.left, 120f, arena.right, 120f, paint)
        contorno(corVermelho, 6f)
        val ate = arena.left + (arena.right - arena.left) * timeScale.coerceIn(0f, 1f)
        c.drawLine(arena.left, 120f, max(arena.left + 1f, ate), 120f, paint)

        // dicas da primeira sala
        if (room == 1 && state == S_PLAY && !(moveuUmaVez && atirouUmaVez)) {
            val y = arena.bottom - 330f
            if (!moveuUmaVez) {
                texto(c, "ARRASTE NA ESQUERDA PARA MOVER", W / 2f, y, 28f, corEscuro)
                texto(c, "(o tempo anda junto com você)", W / 2f, y + 34f, 22f, corCinza)
            }
            if (!atirouUmaVez) {
                texto(c, "TOQUE NA DIREITA PARA ATIRAR", W / 2f, y + 90f, 28f, corVermelho)
            }
        }
    }

    private fun desenharSobreposicoes(c: Canvas) {
        when (state) {
            S_INTRO -> {
                val a = if (stateT < 0.7f) 255 else (255 * (1f - (stateT - 0.7f) / 0.4f)).toInt().coerceIn(0, 255)
                val chefe = room % 5 == 0
                texto(c, if (chefe) "CHEFE" else "SALA $room", W / 2f, H * 0.42f, if (chefe) 96f else 84f,
                    if (chefe) corVermelho else corEscuro, Paint.Align.CENTER, a)
                texto(c, if (chefe) "SALA $room" else "ELIMINE TODOS", W / 2f, H * 0.42f + 50f, 28f, corCinza,
                    Paint.Align.CENTER, a)
            }
            S_CLEAR -> {
                val a = (255 * min(1f, stateT * 4f) * (1f - max(0f, (stateT - 1.0f) / 0.3f))).toInt().coerceIn(0, 255)
                texto(c, "SALA LIMPA", W / 2f, H * 0.42f, 80f, corVermelho, Paint.Align.CENTER, a)
            }
            S_UPGRADE -> desenharMelhorias(c)
            S_DEAD -> desenharMorte(c)
        }

        if (paused) {
            preencher(Color.argb(215, 238, 240, 244))
            c.drawRect(0f, 0f, W, H, paint)
            texto(c, "PAUSADO", W / 2f, H * 0.52f - 60f, 80f, corEscuro)
            botao(c, rBtn1, "CONTINUAR", true)
            botao(c, rBtn2, "SAIR", false)
        }
    }

    private fun desenharMelhorias(c: Canvas) {
        val fade = min(1f, stateT / 0.25f)
        preencher(Color.argb((225 * fade).toInt(), 238, 240, 244))
        c.drawRect(0f, 0f, W, H, paint)
        texto(c, "ESCOLHA UMA MELHORIA", W / 2f, cardRects[0].top - 50f, 36f, corEscuro, Paint.Align.CENTER,
            (255 * fade).toInt())

        for (i in cardRects.indices) {
            val k = ((stateT - i * 0.08f) / 0.28f).coerceIn(0f, 1f)
            val r = cardRects[i]
            val u = UPGS[choices[i]]

            c.save()
            c.translate(0f, (1f - k) * 50f)
            c.scale(0.9f + 0.1f * k, 0.9f + 0.1f * k, r.centerX(), r.centerY())

            preencher(Color.argb(50, 21, 23, 28))
            rectTmp.set(r.left + 8f, r.top + 10f, r.right + 8f, r.bottom + 10f)
            c.drawRoundRect(rectTmp, 10f, 10f, paint)
            preencher(corBranco)
            c.drawRoundRect(r, 10f, 10f, paint)
            contorno(corEscuro, 4f)
            c.drawRoundRect(r, 10f, 10f, paint)

            // ícone
            val ix = r.left + 86f
            val iy = r.centerY()
            preencher(corEscuro)
            c.drawCircle(ix, iy, 56f, paint)
            desenharIcone(c, u.id, ix, iy, 44f)

            texto(c, u.nome, r.left + 170f, r.top + 82f, 38f, corEscuro, Paint.Align.LEFT)
            texto(c, u.desc, r.left + 170f, r.top + 124f, 24f, corCinza, Paint.Align.LEFT)
            val nivel = if (u.max < 90) "NÍVEL ${lv[u.id] + 1} DE ${u.max}" else "VIDA ${hp} DE $maxHp"
            texto(c, nivel, r.left + 170f, r.top + 176f, 22f, corVermelho, Paint.Align.LEFT)
            c.restore()
        }
    }

    private fun desenharIcone(c: Canvas, id: Int, cx: Float, cy: Float, s: Float) {
        contorno(corBranco, 6f)
        when (id) {
            0 -> {
                for (k in -1..1) {
                    val y = cy + k * s * 0.34f
                    c.drawLine(cx - s * 0.5f, y, cx + s * (0.5f - 0.2f * (k + 1)), y, paint)
                }
            }
            1 -> {
                val bx = cx
                val by = cy + s * 0.45f
                for (k in -1..1) {
                    val a = k * 0.55f
                    c.drawLine(bx, by, bx + sin(a) * s * 0.95f, by - cos(a) * s * 0.95f, paint)
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
                preencher(corBranco)
                c.drawCircle(cx, cy, s * 0.36f, paint)
            }
        }
    }

    private fun desenharMorte(c: Canvas) {
        val fade = ((stateT - 0.6f) / 0.5f).coerceIn(0f, 1f)
        if (fade <= 0f) return
        val a = (fade * 255).toInt()
        preencher(Color.argb((230 * fade).toInt(), 238, 240, 244))
        c.drawRect(0f, 0f, W, H, paint)

        texto(c, "VOCÊ MORREU", W / 2f, H * 0.24f, 78f, corVermelho, Paint.Align.CENTER, a)
        texto(c, "SALA $room", W / 2f, H * 0.24f + 70f, 44f, corEscuro, Paint.Align.CENTER, a)
        texto(c, "$kills inimigos abatidos", W / 2f, H * 0.24f + 116f, 28f, corCinza, Paint.Align.CENTER, a)
        val melhor = max(Prefs.melhorSala(context), room)
        texto(c, "RECORDE: SALA $melhor", W / 2f, H * 0.24f + 160f, 28f, corEscuro, Paint.Align.CENTER, a)

        if (stateT > 1.0f) {
            botao(c, rBtn1, "TENTAR DE NOVO", true)
            botao(c, rBtn2, "MENU", false)
        }
    }

    /** Joystick desenhado em coordenadas de tela. */
    private fun desenharJoystick(c: Canvas) {
        if (state == S_UPGRADE || state == S_DEAD || paused) return
        val raio = 56f * densidade
        if (movId != -1) {
            contorno(Color.argb(110, 21, 23, 28), 3f * densidade)
            c.drawCircle(movAx, movAy, raio, paint)
            var dx = movCx - movAx
            var dy = movCy - movAy
            val d = hypot(dx, dy)
            if (d > raio) {
                dx = dx / d * raio
                dy = dy / d * raio
            }
            preencher(Color.argb(170, 21, 23, 28))
            c.drawCircle(movAx + dx, movAy + dy, raio * 0.42f, paint)
        } else if (room == 1 && !moveuUmaVez) {
            contorno(Color.argb(60, 21, 23, 28), 3f * densidade)
            c.drawCircle(width * 0.22f, height * 0.84f, raio, paint)
            preencher(Color.argb(60, 21, 23, 28))
            c.drawCircle(width * 0.22f, height * 0.84f, raio * 0.42f, paint)
        }
    }
}
