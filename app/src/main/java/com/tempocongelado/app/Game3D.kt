package com.tempocongelado.app

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
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
 * Lógica do jogo em 3D (primeira pessoa). Não desenha nada: o Renderer3D desenha o mundo
 * e o HudView desenha a interface. Todo acesso de fora deve usar synchronized(game).
 *
 * Mundo: chão no plano XZ, altura em Y. A sala vai de -10 a 10 em X e Z.
 * yaw = 0 olha para +Z. A direita da câmera é (-cos(yaw), 0, sin(yaw)).
 */
class Game3D(private val ctx: Context, private val density: Float) {

    class Enemy(val type: Int, var x: Float, var z: Float, val scale: Float, var hp: Int) {
        val maxHp = hp
        var cd = 2f
        var flash = 0f
        var tele = false
        var dir = 1f
        var yaw = 0f
        var phase = 0
        var t = 0f
        var walk = 0f
        var shot = 0f
        val radius: Float get() = 0.42f * scale
        val height: Float get() = 1.95f * scale
        val girth: Float get() = if (type == 2) 1.25f else if (type == 3) 1.15f else 1f
        fun muzzleX(): Float = e_mx(this)
        fun muzzleY(): Float = 1.30f * scale
        fun muzzleZ(): Float = e_mz(this)
    }

    class Bullet(
        var x: Float, var y: Float, var z: Float,
        var vx: Float, var vy: Float, var vz: Float,
        val mine: Boolean
    ) {
        var radius = 0.12f
        var bounce = 0
        var pierce = 0
        var dmg = 1
        var life = 7f
        var dead = false
        var hits: ArrayList<Enemy>? = null
    }

    class Debris(
        var x: Float, var y: Float, var z: Float,
        var vx: Float, var vy: Float, var vz: Float,
        val kind: Int, val size: Float, val shape: Int
    ) {
        var rot = 0f
        var vr = 0f
        var life = 3f
        var maxLife = 3f
        var rest = false
    }

    /** Efeito visual de curta duração (tempo real): 0 choque de balas, 1 impacto, 2 morte. */
    class Fx(val kind: Int, val x: Float, val y: Float, val z: Float, val maxT: Float, val size: Float) {
        var t = 0f
    }

    class Block(val x0: Float, val z0: Float, val x1: Float, val z1: Float, val h: Float)

    class Upg(val id: Int, val nome: String, val desc: String, val max: Int)

    companion object {
        const val AX = 10f
        const val AZ = 10f
        const val EYE = 1.6f
        const val PR = 0.35f

        const val S_INTRO = 0
        const val S_PLAY = 1
        const val S_CLEAR = 2
        const val S_UPGRADE = 3
        const val S_DEAD = 4

        const val K_RED = 0
        const val K_DARKRED = 1
        const val K_BLACK = 2
        const val K_WHITE = 3
        const val K_GREY = 4
        const val K_SPARK = 5
        const val K_BRASS = 6

        // posição da boca da arma do inimigo (braço levantado, lado direito dele)
        fun e_mx(e: Enemy): Float =
            (-0.34f * e.scale * e.girth) * cos(e.yaw) + 1.03f * e.scale * sin(e.yaw) + e.x

        fun e_mz(e: Enemy): Float =
            -(-0.34f * e.scale * e.girth) * sin(e.yaw) + 1.03f * e.scale * cos(e.yaw) + e.z

        val UPGS = listOf(
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
    }

    // ------------------------------------------------------------------
    // Estado (leitura livre pelo renderer e pelo HUD, dentro de synchronized)
    // ------------------------------------------------------------------

    val enemies = ArrayList<Enemy>()
    val bullets = ArrayList<Bullet>()
    val debris = ArrayList<Debris>()
    val blocks = ArrayList<Block>()
    val fx = ArrayList<Fx>()
    val choices = ArrayList<Int>()
    val lv = IntArray(UPGS.size)

    private val rng = Random()
    private val tmp = FloatArray(2)

    var paused = false
    var state = S_INTRO
    var stateT = 0f
    var room = 1
    var kills = 0

    var px = 0f
    var pz = -8f
    var yaw = 0f
    var pitch = 0f
    var hp = 1
    var maxHp = 1
    var invuln = 0f
    var hurt = 0f
    var hitMark = 0f
    var shake = 0f
    var recoil = 0f
    var flashT = 0f
    var kick = 0f
    var screenFlash = 0f
    private var hitStop = 0f
    var bob = 0f

    var timeScale = 0.08f
    private var minScale = 0.05f
    private var boost = 0f
    private var lookSm = 0f

    // melhorias
    private var fireCd = 0.40f
    private var shots = 1
    private var pierce = 0
    private var bounce = 0
    private var speedMul = 1f
    private var dmg = 1
    private var fireT = 0f

    // dicas iniciais
    var moved = false
    var looked = false
    var fired = false

    // entrada
    private var moveX = 0f
    private var moveY = 0f
    private var fireHeld = false
    private var pendLookX = 0f
    private var pendLookY = 0f

    init {
        novaPartida()
    }

    // ------------------------------------------------------------------
    // Entrada (chamada pela thread da interface)
    // ------------------------------------------------------------------

    @Synchronized
    fun setMove(x: Float, y: Float) {
        moveX = x
        moveY = y
    }

    @Synchronized
    fun setFire(v: Boolean) {
        fireHeld = v
    }

    @Synchronized
    fun addLook(dx: Float, dy: Float) {
        pendLookX += dx
        pendLookY += dy
    }

    @Synchronized
    fun soltarEntradas() {
        moveX = 0f
        moveY = 0f
        fireHeld = false
        pendLookX = 0f
        pendLookY = 0f
    }

    @Synchronized
    fun pause() {
        paused = true
        soltarEntradas()
    }

    @Synchronized
    fun resume() {
        paused = false
    }

    @Synchronized
    fun alternarPausa() {
        if (paused) resume() else pause()
    }

    @Synchronized
    fun escolherMelhoria(i: Int) {
        if (state == S_UPGRADE && stateT > 0.35f && i >= 0 && i < choices.size) {
            escolher(choices[i])
        }
    }

    @Synchronized
    fun reiniciar() {
        if (state == S_DEAD && stateT > 1.0f) novaPartida()
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
        minScale = 0.05f * 0.5f.pow(lv[7])
        dmg = 1 + lv[8]
    }

    private fun iniciarSala() {
        bullets.clear()
        enemies.clear()
        debris.clear()
        fx.clear()
        screenFlash = 0f
        hitStop = 0f
        px = 0f
        pz = -AZ + 2f
        yaw = 0f
        pitch = 0f
        invuln = 0f
        hurt = 0f
        fireT = 0f
        boost = 0f
        lookSm = 0f
        timeScale = 0.08f
        pendLookX = 0f
        pendLookY = 0f
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
        while (blocks.size < alvo && tentativas < 120) {
            tentativas++
            val horizontal = rng.nextBoolean()
            val w = if (horizontal) rf(2f, 4f) else rf(0.9f, 1.4f)
            val d = if (horizontal) rf(0.9f, 1.4f) else rf(2f, 4f)
            val x0 = rf(-AX + 1.5f, AX - 1.5f - w)
            val z0 = rf(-3.5f, AZ - 3f - d)
            val h = rf(1.0f, 2.6f)
            var ok = true
            for (o in blocks) {
                if (x0 < o.x1 + 1.6f && x0 + w > o.x0 - 1.6f && z0 < o.z1 + 1.6f && z0 + d > o.z0 - 1.6f) {
                    ok = false
                    break
                }
            }
            if (ok) blocks.add(Block(x0, z0, x0 + w, z0 + d, h))
        }
    }

    private fun gerarInimigos() {
        val chefe = room % 5 == 0
        val nAtiradores = if (chefe) 2 else min(2 + (room - 1) / 2, 5)
        val nPerseguidores = if (room >= 2) min(room / 2, 4) else 0
        val nBrutos = if (room >= 4) min((room - 2) / 2, 3) else 0

        if (chefe) {
            val nivel = room / 5
            val b = Enemy(3, 0f, AZ - 3f, 2.1f, 18 + 6 * (nivel - 1))
            b.cd = 2.4f
            enemies.add(b)
        }
        for (i in 0 until nAtiradores) {
            achaLugar(0.5f)
            val e = Enemy(0, tmp[0], tmp[1], 1f, 1)
            e.cd = 1.8f + rng.nextFloat() * 2.2f
            e.dir = if (rng.nextBoolean()) 1f else -1f
            enemies.add(e)
        }
        for (i in 0 until nPerseguidores) {
            achaLugar(0.5f)
            enemies.add(Enemy(1, tmp[0], tmp[1], 0.95f, 1))
        }
        for (i in 0 until nBrutos) {
            achaLugar(0.8f)
            val e = Enemy(2, tmp[0], tmp[1], 1.35f, 3 + room / 8)
            e.cd = 2.5f + rng.nextFloat() * 2f
            e.dir = if (rng.nextBoolean()) 1f else -1f
            enemies.add(e)
        }
    }

    /** Procura um lugar livre para um inimigo; resultado em tmp[0], tmp[1]. */
    private fun achaLugar(r: Float) {
        for (t in 0 until 80) {
            val x = rf(-AX + r + 1f, AX - r - 1f)
            val z = rf(1f, AZ - r - 1f)
            if (hypot(x - px, z - pz) < 7f) continue
            var ok = true
            for (b in blocks) {
                if (x > b.x0 - r - 0.4f && x < b.x1 + r + 0.4f && z > b.z0 - r - 0.4f && z < b.z1 + r + 0.4f) {
                    ok = false
                    break
                }
            }
            if (ok) {
                for (e in enemies) {
                    if (hypot(x - e.x, z - e.z) < e.radius + r + 1.4f) {
                        ok = false
                        break
                    }
                }
            }
            if (ok) {
                tmp[0] = x
                tmp[1] = z
                return
            }
        }
        tmp[0] = rf(-AX + r + 1f, AX - r - 1f)
        tmp[1] = AZ - r - 1.5f
    }

    // ------------------------------------------------------------------
    // Atualização (chamada pela thread de GL a cada quadro)
    // ------------------------------------------------------------------

    @Synchronized
    fun update(dtIn: Float) {
        if (paused) return
        val dtR = min(max(dtIn, 0f), 0.05f)
        stateT += dtR
        if (shake > 0f) shake = max(0f, shake - dtR * 2.5f)
        if (invuln > 0f) invuln -= dtR
        if (hurt > 0f) hurt = max(0f, hurt - dtR * 1.6f)
        if (hitMark > 0f) hitMark -= dtR
        if (recoil > 0f) recoil = max(0f, recoil - dtR * 5f)
        if (flashT > 0f) flashT = max(0f, flashT - dtR)
        if (kick > 0f) kick = max(0f, kick - dtR * 7f)
        if (screenFlash > 0f) screenFlash = max(0f, screenFlash - dtR * 3.5f)
        if (hitStop > 0f) hitStop = max(0f, hitStop - dtR)
        var fi = fx.size - 1
        while (fi >= 0) {
            val f = fx[fi]
            f.t += dtR
            if (f.t >= f.maxT) fx.removeAt(fi)
            fi--
        }
        for (e in enemies) {
            if (e.flash > 0f) e.flash -= dtR
            if (e.shot > 0f) e.shot -= dtR
        }

        val lx = pendLookX
        val ly = pendLookY
        pendLookX = 0f
        pendLookY = 0f
        if (state == S_INTRO || state == S_PLAY || state == S_CLEAR) {
            val k = 1f / (density * 140f)
            yaw -= lx * k
            pitch = (pitch - ly * k).coerceIn(-0.6f, 0.6f)
        }

        when (state) {
            S_INTRO -> {
                timeScale += (0.08f - timeScale) * min(1f, dtR * 8f)
                if (stateT > 1.1f) {
                    state = S_PLAY
                    stateT = 0f
                }
            }
            S_PLAY -> atualizarJogo(dtR, lx, ly)
            S_CLEAR -> {
                timeScale += (0.5f - timeScale) * min(1f, dtR * 6f)
                val dt = dtR * timeScale
                atualizarBalas(dt)
                atualizarDebris(dt)
                if (stateT > 1.3f) abrirMelhorias()
            }
            S_UPGRADE -> {
                timeScale += (0.15f - timeScale) * min(1f, dtR * 6f)
                atualizarDebris(dtR * 0.2f)
            }
            else -> {
                timeScale += (0.12f - timeScale) * min(1f, dtR * 6f)
                val dt = dtR * timeScale
                atualizarBalas(dt)
                atualizarDebris(dt)
            }
        }
    }

    private fun atualizarJogo(dtR: Float, lx: Float, ly: Float) {
        val mag = min(1f, hypot(moveX, moveY))
        val rate = hypot(lx, ly) / max(dtR, 0.004f) / (density * 600f)
        lookSm += (min(1f, rate) - lookSm) * min(1f, dtR * 10f)
        val atividade = max(mag, lookSm * 0.85f)
        var alvo = minScale + (1f - minScale) * atividade
        if (boost > 0f) {
            boost -= dtR
            alvo = max(alvo, 0.4f)
        }
        timeScale += (alvo - timeScale) * min(1f, dtR * 12f)
        val dt = dtR * timeScale * (if (hitStop > 0f) 0.12f else 1f)

        // o jogador anda em tempo real; o mundo anda no ritmo do jogador
        if (mag > 0.01f) {
            moved = true
            val fx = sin(yaw)
            val fz = cos(yaw)
            val rx = -fz
            val rz = fx
            val dx = fx * (-moveY) + rx * moveX
            val dz = fz * (-moveY) + rz * moveX
            val n = max(0.0001f, hypot(dx, dz))
            val vel = 4.6f * speedMul * mag * dtR
            resolve(px + dx / n * vel, pz + dz / n * vel, PR)
            px = tmp[0]
            pz = tmp[1]
            bob += dtR * 9f * mag
        }
        if (abs(lx) + abs(ly) > 0.5f) looked = true

        fireT -= dtR
        if (fireHeld && fireT <= 0f) atirar()

        var i = enemies.size - 1
        while (i >= 0) {
            if (i < enemies.size) atualizarInimigo(i, dt)
            i--
        }
        atualizarBalas(dt)
        atualizarDebris(dt)
    }

    private fun atirar() {
        fireT = fireCd
        boost = 0.3f
        fired = true
        recoil = 1f
        flashT = 0.09f
        kick = 1f
        val cp = cos(pitch)
        val fy = sin(pitch)
        val rx = -cos(yaw)
        val rz = sin(yaw)
        val fx0 = sin(yaw) * cp
        val fz0 = cos(yaw) * cp
        val mx = px + fx0 * 0.7f + rx * 0.18f
        val my = EYE + fy * 0.7f - 0.16f
        val mz = pz + fz0 * 0.7f + rz * 0.18f
        for (i in 0 until shots) {
            val a = yaw + (i - (shots - 1) / 2f) * 0.07f
            val tx = px + sin(a) * cp * 30f
            val ty = EYE + fy * 30f
            val tz = pz + cos(a) * cp * 30f
            var dx = tx - mx
            var dy = ty - my
            var dz = tz - mz
            val n = max(0.0001f, sqrt(dx * dx + dy * dy + dz * dz))
            dx = dx / n * 45f
            dy = dy / n * 45f
            dz = dz / n * 45f
            val b = Bullet(mx, my, mz, dx, dy, dz, true)
            b.pierce = pierce
            b.bounce = bounce
            b.dmg = dmg
            b.radius = 0.1f + (dmg - 1) * 0.03f
            if (pierce > 0) b.hits = ArrayList(2)
            bullets.add(b)
        }
        // cartucho ejetado
        val c = Debris(
            mx + rx * 0.05f, my - 0.03f, mz + rz * 0.05f,
            rx * (1.6f + rng.nextFloat()) + (rng.nextFloat() - 0.5f), 1.6f + rng.nextFloat(),
            rz * (1.6f + rng.nextFloat()) + (rng.nextFloat() - 0.5f),
            K_BRASS, 0.045f, 0
        )
        c.rot = rng.nextFloat() * 360f
        c.vr = (rng.nextFloat() - 0.5f) * 1400f
        c.maxLife = 2.5f
        c.life = 2.5f
        debris.add(c)
        vibrar(8)
    }

    private fun atualizarInimigo(idx: Int, dt: Float) {
        val e = enemies[idx]
        val dx = px - e.x
        val dz = pz - e.z
        val dist = max(0.01f, hypot(dx, dz))
        val ux = dx / dist
        val uz = dz / dist
        e.yaw = atan2(dx, dz)
        e.t += dt
        if (e.t > 3f) {
            e.t = 0f
            e.dir = -e.dir
        }

        var mx = 0f
        var mz = 0f
        var vel = 0f

        when (e.type) {
            0 -> { // atirador
                val querDist = 7f
                if (dist > querDist + 1.2f) {
                    mx = ux
                    mz = uz
                } else if (dist < querDist - 1.8f) {
                    mx = -ux
                    mz = -uz
                } else {
                    mx = -uz * e.dir
                    mz = ux * e.dir
                }
                vel = 1.8f
                e.cd -= dt
                e.tele = e.cd < 0.5f
                if (e.cd <= 0f) {
                    dispararNoJogador(e, 7f, 1, 0f)
                    e.cd = 2.6f + rng.nextFloat() * 1.4f
                    e.tele = false
                }
            }
            1 -> { // perseguidor
                mx = ux
                mz = uz
                vel = 3.3f
            }
            2 -> { // brutamontes
                val querDist = 8f
                if (dist > querDist + 1.2f) {
                    mx = ux
                    mz = uz
                } else if (dist < querDist - 2f) {
                    mx = -ux
                    mz = -uz
                } else {
                    mx = -uz * e.dir
                    mz = ux * e.dir
                }
                vel = 1.1f
                e.cd -= dt
                e.tele = e.cd < 0.55f
                if (e.cd <= 0f) {
                    dispararNoJogador(e, 6.2f, 3, 0.16f)
                    e.cd = 3.2f + rng.nextFloat() * 1.2f
                    e.tele = false
                }
            }
            else -> { // chefe
                mx = e.dir
                mz = (AZ - 3f - e.z) * 0.2f
                vel = 1.6f
                if (e.x < -AX + 3f) e.dir = 1f
                if (e.x > AX - 3f) e.dir = -1f
                e.cd -= dt
                e.tele = e.cd < 0.55f
                if (e.cd <= 0f) {
                    if (e.phase % 2 == 0) dispararNoJogador(e, 8f, 5, 0.2f) else anel(e, 6f, 16)
                    e.phase++
                    e.cd = 2.0f
                    e.tele = false
                }
            }
        }

        e.x += mx * vel * dt
        e.z += mz * vel * dt
        resolve(e.x, e.z, e.radius)
        e.x = tmp[0]
        e.z = tmp[1]
        e.walk += vel * dt * 3.2f

        // perseguidor explode no contato
        if (e.type == 1 && dist < e.radius + PR + 0.25f && state == S_PLAY && invuln <= 0f) {
            ferirJogador()
            matarInimigo(idx)
        }
    }

    private fun dispararNoJogador(e: Enemy, vel: Float, qtd: Int, abertura: Float) {
        val ox = e.muzzleX()
        val oy = e.muzzleY()
        val oz = e.muzzleZ()
        val ty = 1.2f
        val hd = max(0.5f, hypot(px - ox, pz - oz))
        val slope = (ty - oy) / hd
        val n = sqrt(1f + slope * slope)
        val base = atan2(px - ox, pz - oz)
        e.shot = 0.3f
        faiscas(ox, oy, oz, 8, K_SPARK)
        for (i in 0 until qtd) {
            val a = base + (i - (qtd - 1) / 2f) * abertura
            val dx = sin(a)
            val dz = cos(a)
            val b = Bullet(ox, oy, oz, dx * vel / n, slope * vel / n, dz * vel / n, false)
            b.radius = 0.2f
            bullets.add(b)
        }
    }

    private fun anel(e: Enemy, vel: Float, qtd: Int) {
        e.shot = 0.3f
        val inicio = rng.nextFloat() * 6.2831855f
        for (i in 0 until qtd) {
            val a = inicio + i * 6.2831855f / qtd
            val dx = sin(a)
            val dz = cos(a)
            val b = Bullet(
                e.x + dx * e.radius * 1.2f, 1.2f, e.z + dz * e.radius * 1.2f,
                dx * vel, 0f, dz * vel, false
            )
            b.radius = 0.2f
            bullets.add(b)
        }
    }

    private fun atualizarBalas(dt: Float) {
        var i = bullets.size - 1
        while (i >= 0) {
            val b = bullets[i]
            var remover = b.dead
            if (!remover) {
                val passo = sqrt(b.vx * b.vx + b.vy * b.vy + b.vz * b.vz) * dt
                val etapas = max(1, ceil(passo / 0.25f).toInt())
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
        b.z += b.vz * sdt
        val r = b.radius
        val corFaisca = if (b.mine) K_BLACK else K_RED
        val quica = b.mine && b.bounce > 0

        // chão e teto
        if (b.y < r) {
            if (quica) {
                b.y = r
                b.vy = abs(b.vy)
                b.bounce--
            } else {
                faiscas(b.x, b.y, b.z, 4, corFaisca)
                return true
            }
        } else if (b.y > 4f - r) {
            if (quica) {
                b.y = 4f - r
                b.vy = -abs(b.vy)
                b.bounce--
            } else {
                faiscas(b.x, b.y, b.z, 4, corFaisca)
                return true
            }
        }

        // paredes
        if (b.x < -AX + r || b.x > AX - r || b.z < -AZ + r || b.z > AZ - r) {
            if (b.mine && b.bounce > 0) {
                if (b.x < -AX + r) {
                    b.x = -AX + r
                    b.vx = abs(b.vx)
                } else if (b.x > AX - r) {
                    b.x = AX - r
                    b.vx = -abs(b.vx)
                }
                if (b.z < -AZ + r) {
                    b.z = -AZ + r
                    b.vz = abs(b.vz)
                } else if (b.z > AZ - r) {
                    b.z = AZ - r
                    b.vz = -abs(b.vz)
                }
                b.bounce--
            } else {
                faiscas(b.x, b.y, b.z, 4, corFaisca)
                return true
            }
        }

        // blocos
        for (blk in blocks) {
            if (b.y < blk.h && b.x > blk.x0 - r && b.x < blk.x1 + r && b.z > blk.z0 - r && b.z < blk.z1 + r) {
                if (b.mine && b.bounce > 0) {
                    val cx = (blk.x0 + blk.x1) / 2f
                    val cz = (blk.z0 + blk.z1) / 2f
                    val penX = (blk.x1 - blk.x0) / 2f + r - abs(b.x - cx)
                    val penZ = (blk.z1 - blk.z0) / 2f + r - abs(b.z - cz)
                    if (penX < penZ) {
                        b.vx = -b.vx
                        b.x += if (b.x < cx) -penX else penX
                    } else {
                        b.vz = -b.vz
                        b.z += if (b.z < cz) -penZ else penZ
                    }
                    b.bounce--
                } else {
                    faiscas(b.x, b.y, b.z, 4, corFaisca)
                    return true
                }
                break
            }
        }

        if (b.mine) {
            // tiros do jogador destroem tiros inimigos
            for (o in bullets) {
                if (o.mine || o.dead) continue
                val ddx = b.x - o.x
                val ddy = b.y - o.y
                val ddz = b.z - o.z
                val lim = b.radius + o.radius + 0.15f
                if (ddx * ddx + ddy * ddy + ddz * ddz < lim * lim) {
                    o.dead = true
                    choque((b.x + o.x) / 2f, (b.y + o.y) / 2f, (b.z + o.z) / 2f)
                    if (b.pierce <= 0) return true
                }
            }
            // acerta inimigos
            var k = enemies.size - 1
            while (k >= 0) {
                val e = enemies[k]
                val jaAcertou = b.hits?.contains(e) ?: false
                if (!jaAcertou && b.y > 0f && b.y < e.height &&
                    hypot(b.x - e.x, b.z - e.z) < e.radius + b.radius
                ) {
                    e.hp -= b.dmg
                    e.flash = 0.18f
                    hitMark = 0.18f
                    faiscas(b.x, b.y, b.z, 6, K_RED)
                    fx.add(Fx(1, b.x, b.y, b.z, 0.3f, 0.5f))
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
            if (state == S_PLAY && invuln <= 0f && b.y > 0.1f && b.y < 1.9f &&
                hypot(b.x - px, b.z - pz) < PR + r
            ) {
                ferirJogador()
                return true
            }
        }
        return false
    }

    private fun atualizarDebris(dt: Float) {
        var i = debris.size - 1
        while (i >= 0) {
            val s = debris[i]
            if (!s.rest) {
                s.vy -= 14f * dt
                s.x += s.vx * dt
                s.y += s.vy * dt
                s.z += s.vz * dt
                s.rot += s.vr * dt
                val meio = s.size * 0.5f
                if (s.y < meio) {
                    s.y = meio
                    if (abs(s.vy) < 1.2f) {
                        s.rest = true
                        s.vx = 0f
                        s.vy = 0f
                        s.vz = 0f
                    } else {
                        s.vy = -s.vy * 0.35f
                        s.vx *= 0.6f
                        s.vz *= 0.6f
                        s.vr *= 0.6f
                    }
                }
                if (s.x < -AX + 0.1f || s.x > AX - 0.1f) {
                    s.vx = -s.vx * 0.4f
                    s.x = s.x.coerceIn(-AX + 0.1f, AX - 0.1f)
                }
                if (s.z < -AZ + 0.1f || s.z > AZ - 0.1f) {
                    s.vz = -s.vz * 0.4f
                    s.z = s.z.coerceIn(-AZ + 0.1f, AZ - 0.1f)
                }
            }
            s.life -= dt
            if (s.life <= 0f) debris.removeAt(i)
            i--
        }
    }

    private fun burst(x: Float, y: Float, z: Float, n: Int, kind: Int, speed: Float, size: Float, shape: Int) {
        for (i in 0 until n) {
            val a = rng.nextFloat() * 6.2831855f
            val v = speed * (0.3f + rng.nextFloat() * 0.9f)
            val s = Debris(
                x, y, z,
                cos(a) * v, speed * (0.3f + rng.nextFloat() * 0.9f), sin(a) * v,
                kind, size * (0.5f + rng.nextFloat()), shape
            )
            s.rot = rng.nextFloat() * 360f
            s.vr = (rng.nextFloat() - 0.5f) * 900f
            s.maxLife = if (kind == K_SPARK) 0.35f + rng.nextFloat() * 0.35f else 3f + rng.nextFloat() * 2f
            s.life = s.maxLife
            debris.add(s)
        }
        while (debris.size > 500) debris.removeAt(0)
    }

    private fun faiscas(x: Float, y: Float, z: Float, n: Int, kind: Int) {
        if (kind == K_SPARK) {
            burst(x, y, z, n, K_SPARK, 3.5f, 0.07f, 1)
        } else {
            burst(x, y, z, n, kind, 2.5f, 0.05f, 0)
            burst(x, y, z, n / 2 + 1, K_SPARK, 3.5f, 0.06f, 1)
        }
    }

    /** Tiro do jogador batendo num tiro inimigo: explosão com onda de choque, faíscas e pausa de impacto. */
    private fun choque(x: Float, y: Float, z: Float) {
        fx.add(Fx(0, x, y, z, 0.6f, 0.6f))
        burst(x, y, z, 30, K_SPARK, 6.5f, 0.08f, 1)
        burst(x, y, z, 8, K_RED, 3.5f, 0.07f, 1)
        burst(x, y, z, 6, K_WHITE, 3f, 0.05f, 1)
        screenFlash = max(screenFlash, 0.45f)
        shake = max(shake, 0.3f)
        hitStop = 0.07f
        vibrar(25)
    }

    private fun matarInimigo(idx: Int) {
        val e = enemies.removeAt(idx)
        val n = if (e.type == 3) 70 else if (e.type == 2) 40 else 26
        val tam = 0.09f * max(1f, e.scale)
        burst(e.x, 0.9f * e.scale, e.z, n, K_RED, 5f, tam * 1.4f, 1)
        burst(e.x, 0.9f * e.scale, e.z, n / 3, K_DARKRED, 4f, tam * 1.2f, 1)
        burst(e.x, 1.1f * e.scale, e.z, 6, K_RED, 3f, 0.3f * e.scale, 1)
        burst(e.x, 1.0f * e.scale, e.z, 10, K_SPARK, 4f, 0.07f, 1)
        kills++
        fx.add(Fx(2, e.x, 0.9f * e.scale, e.z, 0.7f, 1.5f * e.scale))
        screenFlash = max(screenFlash, if (e.type == 3) 0.6f else 0.2f)
        shake = max(shake, if (e.type == 3) 0.9f else 0.4f)
        vibrar(if (e.type == 3) 60 else 18)
        if (enemies.isEmpty() && state == S_PLAY) {
            state = S_CLEAR
            stateT = 0f
            for (b in bullets) {
                if (!b.mine && !b.dead) {
                    b.dead = true
                    faiscas(b.x, b.y, b.z, 5, K_RED)
                }
            }
        }
    }

    private fun ferirJogador() {
        if (hp > 1) {
            hp--
            invuln = 1.4f
            hurt = 1f
            shake = 0.8f
            vibrar(60)
        } else {
            hp = 0
            morrer()
        }
    }

    private fun morrer() {
        state = S_DEAD
        stateT = 0f
        hurt = 1f
        shake = 1f
        burst(px, 1.1f, pz, 40, K_BLACK, 5f, 0.1f, 1)
        burst(px, 1.1f, pz, 14, K_WHITE, 4f, 0.08f, 1)
        vibrar(160)
        Prefs.registrar(ctx, room, kills)
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
        state = S_UPGRADE
        stateT = 0f
        moveX = 0f
        moveY = 0f
        fireHeld = false
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

    /** Empurra um círculo (plano XZ) para fora dos blocos e mantém dentro da sala. Resultado em tmp. */
    private fun resolve(x: Float, z: Float, r: Float) {
        var cx = x
        var cz = z
        for (b in blocks) {
            val nx = cx.coerceIn(b.x0, b.x1)
            val nz = cz.coerceIn(b.z0, b.z1)
            val dx = cx - nx
            val dz = cz - nz
            val d2 = dx * dx + dz * dz
            if (d2 < r * r) {
                if (d2 > 0.00001f) {
                    val d = sqrt(d2)
                    cx = nx + dx / d * r
                    cz = nz + dz / d * r
                } else {
                    val l = cx - b.x0
                    val rr = b.x1 - cx
                    val t = cz - b.z0
                    val bb = b.z1 - cz
                    val m = min(min(l, rr), min(t, bb))
                    if (m == l) {
                        cx = b.x0 - r
                    } else if (m == rr) {
                        cx = b.x1 + r
                    } else if (m == t) {
                        cz = b.z0 - r
                    } else {
                        cz = b.z1 + r
                    }
                }
            }
        }
        cx = cx.coerceIn(-AX + r, AX - r)
        cz = cz.coerceIn(-AZ + r, AZ - r)
        tmp[0] = cx
        tmp[1] = cz
    }

    @Suppress("DEPRECATION")
    private fun vibrar(ms: Int) {
        if (!Prefs.vibracao(ctx)) return
        val v = ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        val dur = ms.toLong()
        if (Build.VERSION.SDK_INT >= 26) {
            v.vibrate(VibrationEffect.createOneShot(dur, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            v.vibrate(dur)
        }
    }
}
