package com.tempocongelado.app

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Monta o quadro: converte o estado do jogo (Game3D) numa DrawList. Não usa OpenGL nem Android.
 * Câmera, sala, caixotes, inimigos, balas com rastro, estilhaços, efeitos de impacto e a arma
 * em primeira pessoa (passes P_GUN) com coice, recuo do ferrolho e clarão em estrela.
 */
object Scene {
    private val m = FloatArray(16)
    private val m2 = FloatArray(16)
    private val id = FloatArray(16)

    // base da câmera
    private var rX = 0f; private var rY = 0f; private var rZ = 0f
    private var uX = 0f; private var uY = 1f; private var uZ = 0f
    private var fX = 0f; private var fY = 0f; private var fZ = 1f

    private var blocksSig = -1

    init {
        M4.identity(id)
    }

    /** Recria as malhas dos caixotes quando a sala muda. Chamar dentro de synchronized(game). */
    fun prepare(g: Game3D) {
        var sig = g.room * 131 + g.blocks.size
        for (b in g.blocks) sig = sig * 31 + (b.x0 * 100f).toInt() + (b.z1 * 70f).toInt()
        if (sig == blocksSig) return
        blocksSig = sig
        for (i in 0 until min(Models.BLOCKS, g.blocks.size)) {
            val b = g.blocks[i]
            val mb = MeshBuilder()
            Environment.block(mb, b.x1 - b.x0, b.h, b.z1 - b.z0, g.room * 17 + i)
            Models.setBlockMesh(i, mb.toArray())
        }
    }

    // ------------------------------------------------------------------
    // Auxiliares de matriz
    // ------------------------------------------------------------------

    private fun orient(o: FloatArray, x: Float, y: Float, z: Float, dx: Float, dy: Float, dz: Float, sx: Float, sy: Float, sz: Float) {
        val l = max(1e-6f, sqrt(dx * dx + dy * dy + dz * dz))
        val zx = dx / l; val zy = dy / l; val zz = dz / l
        var ux = 0f; var uy = 1f; var uz = 0f
        if (abs(zy) > 0.98f) { ux = 1f; uy = 0f }
        var xx = uy * zz - uz * zy
        var xy = uz * zx - ux * zz
        var xz = ux * zy - uy * zx
        val xl = max(1e-6f, sqrt(xx * xx + xy * xy + xz * xz))
        xx /= xl; xy /= xl; xz /= xl
        val yx = zy * xz - zz * xy
        val yy = zz * xx - zx * xz
        val yz = zx * xy - zy * xx
        o[0] = xx * sx; o[1] = xy * sx; o[2] = xz * sx; o[3] = 0f
        o[4] = yx * sy; o[5] = yy * sy; o[6] = yz * sy; o[7] = 0f
        o[8] = zx * sz; o[9] = zy * sz; o[10] = zz * sz; o[11] = 0f
        o[12] = x; o[13] = y; o[14] = z; o[15] = 1f
    }

    /** Voltada para a câmera, com giro em torno do eixo de visão (graus). */
    private fun bill(o: FloatArray, x: Float, y: Float, z: Float, sx: Float, sy: Float, roll: Float) {
        val c = cos(roll * 0.017453292f)
        val s = sin(roll * 0.017453292f)
        val ax = rX * c + uX * s; val ay = rY * c + uY * s; val az = rZ * c + uZ * s
        val bx = -rX * s + uX * c; val by = -rY * s + uY * c; val bz = -rZ * s + uZ * c
        o[0] = ax * sx; o[1] = ay * sx; o[2] = az * sx; o[3] = 0f
        o[4] = bx * sy; o[5] = by * sy; o[6] = bz * sy; o[7] = 0f
        o[8] = -fX; o[9] = -fY; o[10] = -fZ; o[11] = 0f
        o[12] = x; o[13] = y; o[14] = z; o[15] = 1f
    }

    private fun flatRing(o: FloatArray, x: Float, y: Float, z: Float, r: Float) {
        M4.identity(o)
        M4.translate(o, x, y, z)
        M4.rotX(o, -90f)
        M4.scale(o, r, r, r)
    }

    private fun add(dl: DrawList, pass: Int, mesh: Int, mat: FloatArray, r: Float, g: Float, b: Float, alpha: Float, unlit: Float = 1f, emis: Float = 1f): Cmd {
        val c = dl.add(pass, mesh, mat)
        c.r = r; c.g = g; c.b = b
        c.alpha = alpha
        c.unlit = unlit
        c.emis = emis
        return c
    }

    private fun add(dl: DrawList, pass: Int, mesh: Int, r: Float, g: Float, b: Float, alpha: Float, unlit: Float = 1f, emis: Float = 1f): Cmd =
        add(dl, pass, mesh, m, r, g, b, alpha, unlit, emis)

    private fun kindColor(kind: Int, out: FloatArray) {
        when (kind) {
            Game3D.K_RED -> { out[0] = 0.95f; out[1] = 0.22f; out[2] = 0.18f }
            Game3D.K_DARKRED -> { out[0] = 0.62f; out[1] = 0.09f; out[2] = 0.10f }
            Game3D.K_BLACK -> { out[0] = 0.10f; out[1] = 0.10f; out[2] = 0.12f }
            Game3D.K_WHITE -> { out[0] = 1f; out[1] = 1f; out[2] = 1f }
            Game3D.K_GREY -> { out[0] = 0.6f; out[1] = 0.62f; out[2] = 0.68f }
            Game3D.K_SPARK -> { out[0] = 1f; out[1] = 0.70f; out[2] = 0.28f }
            else -> { out[0] = 1f; out[1] = 0.82f; out[2] = 0.45f }
        }
    }

    private val col = FloatArray(3)
    private val mz = FloatArray(3)

    // ------------------------------------------------------------------
    // Quadro completo
    // ------------------------------------------------------------------

    fun build(dl: DrawList, g: Game3D, clk: Float, aspect: Float) {
        dl.reset()
        prepare(g)

        // ---------------- câmera ----------------
        val dead = g.state == Game3D.S_DEAD
        val dd = if (dead) min(1f, g.stateT / 0.9f) else 0f
        val bobY = sin(g.bob) * 0.035f
        val bobX = cos(g.bob * 0.5f) * 0.02f
        val sh = g.shake * g.shake
        val sx = sin(clk * 61f) * 0.05f * sh
        val sy = sin(clk * 73f + 1f) * 0.05f * sh
        val pitch = g.pitch + g.kick * 0.035f - dd * 0.5f
        val cp = cos(pitch)
        val ex = g.px + (-cos(g.yaw)) * (bobX + sx)
        val ez = g.pz + sin(g.yaw) * (bobX + sx)
        val ey = Game3D.EYE + bobY + sy - dd * 1.15f
        fX = sin(g.yaw) * cp; fY = sin(pitch); fZ = cos(g.yaw) * cp
        val roll = (dd * 35f + sin(clk * 40f) * 1.2f * sh) * 0.017453292f
        // direita e cima
        var rx = -cos(g.yaw); var ry = 0f; var rz = sin(g.yaw)
        val ux0 = ry * fZ - rz * fY
        val uy0 = rz * fX - rx * fZ
        val uz0 = rx * fY - ry * fX
        // (right x forward) aponta para baixo; inverte
        uX = ux0; uY = uy0; uZ = uz0
        // roll em torno de F
        val cr = cos(roll); val sr = sin(roll)
        val nrx = rx * cr + uX * sr; val nry = ry * cr + uY * sr; val nrz = rz * cr + uZ * sr
        val nux = -rx * sr + uX * cr; val nuy = -ry * sr + uY * cr; val nuz = -rz * sr + uZ * cr
        rX = nrx; rY = nry; rZ = nrz; uX = nux; uY = nuy; uZ = nuz
        M4.lookAt(dl.view, ex, ey, ez, ex + fX, ey + fY, ez + fZ, uX, uY, uZ)
        dl.ex = ex; dl.ey = ey; dl.ez = ez
        dl.fov = 74f - g.recoil * 1.5f + g.screenFlash * 4f
        dl.timeFx = g.timeScale

        // ---------------- sala ----------------
        dl.add(DrawList.P_OPAQUE, Models.ROOM, id).let { it.spec = 0.1f; it.rim = 0f }
        dl.add(DrawList.P_FLOOR, Models.FLOOR, id).let { it.spec = 0.55f; it.rim = 0.12f; it.alpha = 0.86f }

        for (i in 0 until min(Models.BLOCKS, g.blocks.size)) {
            val b = g.blocks[i]
            M4.identity(m)
            M4.translate(m, (b.x0 + b.x1) / 2f, 0f, (b.z0 + b.z1) / 2f)
            dl.add(DrawList.P_OPAQUE, Models.BLOCK0 + i, m).let { it.spec = 0.18f; it.rim = 0.06f }
            // sombra de contato
            M4.identity(m)
            M4.translate(m, (b.x0 + b.x1) / 2f, 0.01f, (b.z0 + b.z1) / 2f)
            M4.scale(m, (b.x1 - b.x0) + 1.4f, 0.002f, (b.z1 - b.z0) + 1.4f)
            add(dl, DrawList.P_BLOB, Models.CUBE, 0f, 0f, 0f, 0.38f, 0f, 0f).blob = 1f
        }

        // ---------------- inimigos ----------------
        for (e in g.enemies) {
            Rig.enemy(dl, e, clk, null)
            val s = e.scale * max(0.05f, Rig.grow(e.age))
            M4.identity(m)
            M4.translate(m, e.x, 0.012f, e.z)
            M4.scale(m, 1.6f * s, 0.002f, 1.6f * s)
            add(dl, DrawList.P_BLOB, Models.CUBE, 0f, 0f, 0f, 0.45f, 0f, 0f).blob = 1f

            // aparição: anel de luz no chão
            if (e.age < 0.55f) {
                val u = e.age / 0.55f
                flatRing(m, e.x, 0.03f, e.z, (0.4f + u * 2.4f) * e.scale)
                add(dl, DrawList.P_ADD, Models.RING, 1f, 0.45f, 0.35f, 1f - u)
                bill(m, e.x, 1f * s, e.z, 2.4f * s * (1f - u), 3.4f * s * (1f - u * 0.5f), 0f)
                add(dl, DrawList.P_ADD, Models.DISC, 1f, 0.6f, 0.5f, (1f - u) * 0.8f)
            }
            if (e.type == 2 || e.type == 3) {
                val p = 0.55f + 0.45f * sin(clk * 3f + e.x)
                bill(m, e.x, 1.25f * s, e.z, 1.6f * s * p, 1.6f * s * p, 0f)
                add(dl, DrawList.P_ADD, Models.DISC, 1f, 0.15f, 0.1f, 0.5f)
            }
            if (e.type == 3) {
                // coroa de estilhaços girando e aura no chão
                for (k in 0 until 6) {
                    val a = clk * 1.4f + k * (2f * PI.toFloat() / 6f)
                    M4.identity(m)
                    M4.translate(m, e.x + cos(a) * 0.75f * s, 2.05f * s + sin(clk * 2f + k) * 0.08f, e.z + sin(a) * 0.75f * s)
                    M4.rotY(m, a * 57.3f)
                    M4.rotZ(m, 14f)
                    M4.scale(m, 0.42f * s, 0.62f * s, 0.42f * s)
                    add(dl, DrawList.P_OPAQUE, Models.SHARD, 0.95f, 0.18f, 0.16f, 1f, 0f, 0.7f).let { it.spec = 0.8f; it.rim = 0.7f }
                }
                flatRing(m, e.x, 0.03f, e.z, 2.4f * s * (0.92f + 0.08f * sin(clk * 4f)))
                add(dl, DrawList.P_ADD, Models.RING, 1f, 0.2f, 0.15f, 0.55f)
            }
            if (e.tele) {
                val pu = 0.5f + 0.5f * sin(clk * 24f)
                bill(m, e.x, 1.25f * s, e.z, 1.1f * s, 1.1f * s, 0f)
                add(dl, DrawList.P_ADD, Models.DISC, 1f, 0.25f, 0.12f, 0.25f + 0.35f * pu)
            }
        }

        // ---------------- balas ----------------
        for (b in g.bullets) bala(dl, b, clk)

        // ---------------- estilhaços e fagulhas ----------------
        for (s in g.debris) fragmento(dl, s)

        // ---------------- efeitos ----------------
        for (f in g.fx) efeito(dl, f)

        // ---------------- arma em primeira pessoa ----------------
        arma(dl, g, clk)
    }

    // ------------------------------------------------------------------
    private fun bala(dl: DrawList, b: Game3D.Bullet, clk: Float) {
        val sp = sqrt(b.vx * b.vx + b.vy * b.vy + b.vz * b.vz)
        if (sp < 0.001f) return
        val r = b.radius
        if (b.mine) {
            val len = 1.7f + r * 4f
            orient(m, b.x, b.y, b.z, b.vx, b.vy, b.vz, r * 1.7f, r * 1.7f, len)
            add(dl, DrawList.P_ADD, Models.TRAIL, 1f, 0.55f, 0.15f, 0.95f)
            orient(m, b.x, b.y, b.z, b.vx, b.vy, b.vz, r * 0.9f, r * 0.9f, len * 0.55f)
            add(dl, DrawList.P_ADD, Models.TRAIL, 1f, 0.95f, 0.7f, 1f)
            M4.identity(m); M4.translate(m, b.x, b.y, b.z); M4.scale(m, r * 1.7f, r * 1.7f, r * 1.7f)
            add(dl, DrawList.P_OPAQUE, Models.SPHERE, 1f, 0.97f, 0.82f, 1f)
            bill(m, b.x, b.y, b.z, r * 9f, r * 9f, 0f)
            add(dl, DrawList.P_ADD, Models.DISC, 1f, 0.55f, 0.18f, 0.9f)
        } else {
            val p = 0.85f + 0.15f * sin(clk * 30f + b.x * 5f)
            orient(m, b.x, b.y, b.z, b.vx, b.vy, b.vz, r * 2.4f * p, r * 2.4f * p, 1.4f + r * 4f)
            add(dl, DrawList.P_ADD, Models.TRAIL, 1f, 0.12f, 0.08f, 0.9f)
            M4.identity(m); M4.translate(m, b.x, b.y, b.z); M4.scale(m, r * 2.2f * p, r * 2.2f * p, r * 2.2f * p)
            add(dl, DrawList.P_OPAQUE, Models.SPHERE, 1f, 0.25f, 0.18f, 1f)
            M4.identity(m); M4.translate(m, b.x, b.y, b.z); M4.scale(m, r * 1.1f, r * 1.1f, r * 1.1f)
            add(dl, DrawList.P_OPAQUE, Models.SPHERE, 1f, 0.9f, 0.7f, 1f)
            bill(m, b.x, b.y, b.z, r * 11f * p, r * 11f * p, 0f)
            add(dl, DrawList.P_ADD, Models.DISC, 1f, 0.12f, 0.08f, 0.85f)
        }
        // sombra no chão
        if (b.y > 0.1f) {
            M4.identity(m); M4.translate(m, b.x, 0.012f, b.z); M4.scale(m, r * 5f, 0.002f, r * 5f)
            add(dl, DrawList.P_BLOB, Models.CUBE, 0f, 0f, 0f, 0.25f, 0f, 0f).blob = 1f
        }
    }

    private fun fragmento(dl: DrawList, s: Game3D.Debris) {
        val u = (s.life / s.maxLife).coerceIn(0f, 1f)
        kindColor(s.kind, col)
        if (s.kind == Game3D.K_SPARK) {
            val sp = sqrt(s.vx * s.vx + s.vy * s.vy + s.vz * s.vz)
            val len = 0.07f + min(0.5f, sp * 0.05f)
            val dx = if (sp < 0.01f) 0f else s.vx
            val dy = if (sp < 0.01f) 1f else s.vy
            val dz = if (sp < 0.01f) 0f else s.vz
            orient(m, s.x, s.y, s.z, dx, dy, dz, s.size * 0.9f * u, s.size * 0.9f * u, len * (0.4f + u))
            add(dl, DrawList.P_ADD, Models.TRAIL, 1f, 0.72f, 0.3f, min(1f, u * 1.6f))
            return
        }
        val fade = if (u < 0.2f) u / 0.2f else 1f
        val sz = s.size * fade
        if (sz < 0.003f) return
        val h = s.size * 7919f
        M4.identity(m)
        M4.translate(m, s.x, s.y, s.z)
        M4.rotY(m, sin(h) * 180f)
        M4.rotX(m, s.rot)
        M4.rotZ(m, cos(h * 1.7f) * 90f)
        if (s.kind == Game3D.K_BRASS) {
            M4.scale(m, sz, sz, sz * 1.9f)
            dl.add(DrawList.P_OPAQUE, Models.CASING, m).let { it.spec = 0.9f; it.rim = 0.3f; it.emis = 0.1f }
            return
        }
        val mesh = if (s.shape == 1) Models.SHARD else Models.CHUNK
        if (s.shape == 1) M4.scale(m, sz * 1.5f, sz * 2.2f, sz * 1.5f) else M4.scale(m, sz, sz * 0.8f, sz)
        val c = dl.add(DrawList.P_OPAQUE, mesh, m)
        c.r = col[0]; c.g = col[1]; c.b = col[2]
        val red = s.kind == Game3D.K_RED || s.kind == Game3D.K_DARKRED
        c.emis = if (red) 0.2f else 0f
        c.spec = if (red) 0.85f else 0.3f
        c.rim = if (red) 0.6f else 0.1f
    }

    private fun efeito(dl: DrawList, f: Game3D.Fx) {
        val u = (f.t / f.maxT).coerceIn(0f, 1f)
        val inv = 1f - u
        when (f.kind) {
            0 -> { // choque de balas
                val e = 1f - inv * inv * inv
                // clarão central
                bill(m, f.x, f.y, f.z, 4.2f * f.size * (0.3f + inv * 0.7f), 4.2f * f.size * (0.3f + inv * 0.7f), 0f)
                add(dl, DrawList.P_ADD, Models.DISC, 1f, 0.85f, 0.55f, min(1f, inv * 1.6f))
                // ondas de choque em anel (voltadas para a câmera e uma horizontal)
                bill(m, f.x, f.y, f.z, 7f * f.size * e + 0.2f, 7f * f.size * e + 0.2f, 0f)
                add(dl, DrawList.P_ADD, Models.RING, 1f, 0.7f, 0.3f, inv)
                bill(m, f.x, f.y, f.z, 4f * f.size * e + 0.1f, 4f * f.size * e + 0.1f, 0f)
                add(dl, DrawList.P_ADD, Models.RING, 1f, 0.2f, 0.15f, inv * 0.9f)
                flatRing(m, f.x, f.y, f.z, 5.5f * f.size * e + 0.1f)
                add(dl, DrawList.P_ADD, Models.RING, 1f, 0.9f, 0.6f, inv * 0.9f)
                // raios em estrela
                for (k in 0 until 10) {
                    val a = k * 0.6283f + 0.3f
                    val dxr = rX * cos(a) + uX * sin(a)
                    val dyr = rY * cos(a) + uY * sin(a)
                    val dzr = rZ * cos(a) + uZ * sin(a)
                    val L = (0.8f + (k % 3) * 0.45f) * f.size * (0.4f + e * 2.8f)
                    orient(m, f.x + dxr * L * 0.5f, f.y + dyr * L * 0.5f, f.z + dzr * L * 0.5f, dxr, dyr, dzr, 0.05f * inv, 0.05f * inv, L)
                    add(dl, DrawList.P_ADD, Models.TRAIL, 1f, 0.8f, 0.4f, inv)
                }
                // miolo branco
                M4.identity(m); M4.translate(m, f.x, f.y, f.z); M4.scale(m, 0.5f * f.size * inv, 0.5f * f.size * inv, 0.5f * f.size * inv)
                add(dl, DrawList.P_ADD, Models.SPHERE, 1f, 1f, 1f, min(1f, inv * 3f))
            }
            1 -> { // impacto num inimigo
                bill(m, f.x, f.y, f.z, 1.6f * f.size * (0.4f + inv), 1.6f * f.size * (0.4f + inv), 0f)
                add(dl, DrawList.P_ADD, Models.DISC, 1f, 0.3f, 0.22f, inv)
                bill(m, f.x, f.y, f.z, 2.4f * f.size * u + 0.1f, 2.4f * f.size * u + 0.1f, 0f)
                add(dl, DrawList.P_ADD, Models.RING, 1f, 0.5f, 0.4f, inv)
            }
            2 -> { // morte
                val e = 1f - inv * inv
                flatRing(m, f.x, 0.03f, f.z, 0.5f + 4.2f * f.size * e)
                add(dl, DrawList.P_ADD, Models.RING, 1f, 0.2f, 0.16f, inv)
                flatRing(m, f.x, 0.05f, f.z, 0.3f + 2.6f * f.size * e)
                add(dl, DrawList.P_ADD, Models.RING, 1f, 0.75f, 0.6f, inv * 0.8f)
                bill(m, f.x, f.y, f.z, 4.5f * f.size * (0.5f + inv * 0.5f), 4.5f * f.size * (0.5f + inv * 0.5f), 0f)
                add(dl, DrawList.P_ADD, Models.DISC, 1f, 0.22f, 0.15f, inv * 0.9f)
                orient(m, f.x, f.y + 1.5f, f.z, 0f, 1f, 0f, 0.5f * f.size * inv, 0.5f * f.size * inv, 3.2f * f.size)
                add(dl, DrawList.P_ADD, Models.TRAIL, 1f, 0.25f, 0.18f, inv * 0.8f)
            }
            else -> { // fumaça do cano
                for (k in 0 until 3) {
                    val rise = u * (0.5f + k * 0.25f)
                    val sz = f.size * (0.35f + u * (1.2f + k * 0.5f))
                    M4.identity(m)
                    M4.translate(m, f.x + (k - 1) * 0.06f * u, f.y + rise * 0.6f, f.z + (k - 1) * 0.05f * u)
                    M4.scale(m, sz, sz, sz)
                    add(dl, DrawList.P_ALPHA, Models.SPHERE, 0.55f, 0.57f, 0.62f, 0.2f * inv * inv, 0f, 0f)
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Arma em primeira pessoa (espaço da câmera)
    // ------------------------------------------------------------------
    private fun arma(dl: DrawList, g: Game3D, clk: Float) {
        if (g.state == Game3D.S_DEAD) return
        val rc = g.recoil
        val k = rc * rc
        val walk = sin(g.bob) * 0.012f
        val walkX = cos(g.bob * 0.5f) * 0.008f
        val idle = sin(clk * 1.7f) * 0.0035f
        val appear = if (g.state == Game3D.S_INTRO) min(1f, g.stateT / 0.6f) else 1f
        val ap = 1f - (1f - appear) * (1f - appear)
        M4.identity(m)
        M4.translate(m, 0.17f + walkX, -0.15f + walk + idle - (1f - ap) * 0.4f, -0.40f + k * 0.07f)
        M4.rotX(m, k * 11f)
        M4.rotY(m, -3f + g.timeScale * 0f)
        M4.scale(m, 1f, 1f, -1f)
        M4.scale(m, 1.15f, 1.15f, 1.15f)
        M4.copy(m2, m)
        dl.add(DrawList.P_GUN, Models.GUN_BODY, m2).let { it.spec = 0.9f; it.rim = 0.22f; it.emis = 0f }
        dl.add(DrawList.P_GUN, Models.GUN_HAND, m2).let { it.spec = 0.35f; it.rim = 0.25f }
        // ferrolho
        M4.copy(m, m2)
        M4.translate(m, 0f, 0f, -0.045f * min(1f, rc * 1.6f))
        dl.add(DrawList.P_GUN, Models.GUN_SLIDE, m).let { it.spec = 0.95f; it.rim = 0.25f }

        // clarão do tiro em estrela, na boca da arma
        if (g.flashT > 0f) {
            val u = (g.flashT / 0.09f).coerceIn(0f, 1f)
            val bx = 0f; val by = 0.04f; val bz = 0.215f
            // posição da boca em espaço da câmera
            val px = m2[0] * bx + m2[4] * by + m2[8] * bz + m2[12]
            val py = m2[1] * bx + m2[5] * by + m2[9] * bz + m2[13]
            val pz = m2[2] * bx + m2[6] * by + m2[10] * bz + m2[14]
            val rot = (g.stateT * 913f) % 360f
            for (j in 0 until 2) {
                M4.identity(m)
                M4.translate(m, px, py, pz - 0.02f)
                M4.rotZ(m, rot + j * 45f)
                M4.scale(m, 0.34f * (0.6f + u * 0.4f), 0.34f * (0.6f + u * 0.4f), 1f)
                add(dl, DrawList.P_GUN_ADD, Models.DISC, 1f, 0.8f - j * 0.2f, 0.4f - j * 0.15f, u)
            }
            for (j in 0 until 6) {
                val a = rot * 0.017453292f + j * 1.0472f
                val L = (0.10f + (j % 2) * 0.09f) * (0.5f + u)
                M4.identity(m)
                M4.translate(m, px, py, pz - 0.03f)
                M4.rotZ(m, a * 57.3f)
                M4.translate(m, 0f, L * 0.5f, 0f)
                M4.rotX(m, 90f)
                M4.scale(m, 0.012f, 0.012f, L)
                add(dl, DrawList.P_GUN_ADD, Models.TRAIL, 1f, 0.85f, 0.45f, u)
            }
            M4.identity(m); M4.translate(m, px, py, pz - 0.01f); M4.scale(m, 0.07f * u, 0.07f * u, 0.07f * u)
            add(dl, DrawList.P_GUN_ADD, Models.SPHERE, 1f, 1f, 0.9f, u)
        }
    }
}
