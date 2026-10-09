package com.tempocongelado.app

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.cos

/**
 * Esqueleto e animação dos inimigos. Monta a pose (passada, mira, recuo, carga do tiro,
 * reação a dano) e emite as peças para a DrawList. Também calcula a posição da boca da arma.
 */
object Rig {
    private val st = Array(32) { FloatArray(16) }
    private var sp = 0
    private val mirror = FloatArray(16)
    private val tmp = FloatArray(16)
    private const val RAD = 57.29578f

    init {
        M4.identity(mirror)
        mirror[5] = -1f
    }

    private fun push() {
        M4.copy(st[sp + 1], st[sp])
        sp++
    }

    private fun pop() {
        sp--
    }

    private fun cur(): FloatArray = st[sp]

    private fun tr(x: Float, y: Float, z: Float) = M4.translate(st[sp], x, y, z)
    private fun rx(a: Float) = M4.rotX(st[sp], a)
    private fun ry(a: Float) = M4.rotY(st[sp], a)
    private fun rz(a: Float) = M4.rotZ(st[sp], a)

    /** Crescimento ao nascer (0 a 1, com leve estouro elástico). */
    fun grow(age: Float): Float {
        val t = (age / 0.55f).coerceIn(0f, 1f)
        if (t >= 1f) return 1f
        val c1 = 1.70158f
        val c3 = c1 + 1f
        val u = t - 1f
        return 1f + c3 * u * u * u + c1 * u * u
    }

    private fun emit(
        dl: DrawList?, mesh: Int, r: Float, g: Float, b: Float,
        emis: Float, spec: Float, rim: Float
    ) {
        if (dl == null) return
        val c = dl.add(DrawList.P_OPAQUE, mesh, cur())
        c.r = r; c.g = g; c.b = b
        c.emis = emis; c.spec = spec; c.rim = rim
        M4.mul(tmp, mirror, cur())
        val c2 = dl.add(DrawList.P_REFLECT, mesh, tmp)
        c2.r = r; c2.g = g; c2.b = b
        c2.emis = emis; c2.spec = spec; c2.rim = rim
    }

    /**
     * Desenha o inimigo. Se out != null, devolve em out[0..2] a posição (mundo) da boca da arma.
     * dl pode ser null só para calcular a posição da boca.
     */
    fun enemy(dl: DrawList?, e: Game3D.Enemy, clk: Float, out: FloatArray?) {
        val gr = grow(e.age)
        val s = e.scale * max(gr, 0.001f)
        val k = (e.shot / 0.3f).coerceIn(0f, 1f)
        val h = (e.flash / 0.18f).coerceIn(0f, 1f)
        val spd = e.speed.coerceIn(0f, 1f)
        val ph = e.walk
        val sprint = e.type == 1
        val armed = !sprint
        val br = sin(clk * 2.1f + e.x * 1.7f)
        val tele = e.tele
        val pulso = if (tele) 0.5f + 0.5f * sin(clk * 22f) else 0f

        st[0].let { M4.identity(it) }
        sp = 0
        tr(e.x, 0f, e.z)
        ry(e.yaw * RAD)
        M4.scale(cur(), s, s, s)

        // cores
        val flash = h > 0f
        val cb = if (flash) floatArrayOf(1f, 1f, 1f) else when (e.type) {
            1 -> floatArrayOf(1f, 0.30f, 0.14f)
            2 -> floatArrayOf(0.72f, 0.10f, 0.14f)
            3 -> floatArrayOf(0.50f, 0.06f, 0.14f)
            else -> floatArrayOf(0.93f, 0.20f, 0.17f)
        }
        val cd = if (flash) floatArrayOf(1f, 1f, 1f) else when (e.type) {
            2 -> floatArrayOf(0.34f, 0.05f, 0.08f)
            3 -> floatArrayOf(0.20f, 0.03f, 0.07f)
            else -> floatArrayOf(0.66f, 0.11f, 0.11f)
        }
        val ch = if (flash) floatArrayOf(1f, 1f, 1f) else when (e.type) {
            1 -> floatArrayOf(1f, 0.42f, 0.2f)
            2 -> floatArrayOf(0.85f, 0.16f, 0.2f)
            3 -> floatArrayOf(0.75f, 0.12f, 0.22f)
            else -> floatArrayOf(0.98f, 0.30f, 0.26f)
        }
        val em = if (flash) 0.9f else 0.14f + 0.40f * pulso + (if (e.type == 3) 0.12f else 0f)
        val spec = 0.55f
        val rim = 0.55f

        // pulos da passada
        val bob = abs(cos(ph)) * 0.035f * spd + br * 0.004f
        val crouch = (if (tele) 0.035f * pulso else 0f) + 0.02f * k
        val twist = sin(ph) * 7f * spd
        val lean = 3f + 12f * spd + (if (sprint) 16f * spd else 0f) - 12f * k - 20f * h - (if (tele) 3f else 0f)

        push() // pelvis
        tr(0f, 0.93f + bob - crouch, -0.05f * k)
        ry(twist * 0.6f)
        emit(dl, Models.PELVIS, cd[0], cd[1], cd[2], em, spec, rim)

        // pernas
        for (side in 0 until 2) {
            val sx = if (side == 0) 0.10f else -0.10f
            val a = ph + (if (side == 0) 0f else PI.toFloat())
            val thigh = -34f * spd * (if (sprint) 1.35f else 1f) * sin(a) + 3f
            val knee = 6f + spd * 56f * max(0f, cos(a)) + 6f * crouch * 10f
            push()
            tr(sx, -0.02f, 0f)
            rx(thigh)
            emit(dl, Models.THIGH, cb[0], cb[1], cb[2], em, spec, rim)
            tr(0f, -0.44f, 0f)
            rx(knee)
            emit(dl, Models.SHIN, cd[0], cd[1], cd[2], em, spec, rim)
            tr(0f, -0.42f, 0f)
            rx(-(thigh + knee) * 0.85f + 8f * spd * max(0f, -sin(a)))
            emit(dl, Models.FOOT, cd[0], cd[1], cd[2], em, spec, rim)
            pop()
        }

        // tronco
        push()
        tr(0f, 0.07f, 0f)
        ry(-twist)
        rx(lean)
        emit(dl, Models.TORSO, cb[0], cb[1], cb[2], em, spec, rim)

        // cabeça
        push()
        tr(0f, 0.50f, 0f)
        rx(-lean * 0.55f + br * 1.2f - 22f * h)
        ry(sin(clk * 0.9f + e.z) * 5f)
        emit(dl, Models.HEAD, ch[0], ch[1], ch[2], em, 0.7f, rim)
        pop()

        // braços
        var mx = 0f
        var my = 0f
        var mz = 0f
        for (side in 0 until 2) {
            val sx = if (side == 0) -0.215f else 0.215f
            push()
            tr(sx, 0.43f, 0f)
            if (armed) {
                val raise = -(80f) - 16f * k - 7f * pulso + br * 0.8f + 5f * h
                ry(if (side == 0) 17f else -17f)
                rx(raise + (if (side == 1) 4f else 0f))
            } else {
                val sw = sin(ph + (if (side == 0) 0f else PI.toFloat()))
                rx(sw * 58f * (0.3f + 0.7f * spd) - 8f)
                rz(if (side == 0) -8f else 8f)
            }
            emit(dl, Models.UARM, cd[0], cd[1], cd[2], em, spec, rim)
            tr(0f, -0.30f, 0f)
            rx(if (armed) -10f - 5f * k else -38f - 20f * spd)
            emit(dl, Models.FARM, cb[0], cb[1], cb[2], em, spec, rim)
            tr(0f, -0.27f, 0f)
            emit(dl, Models.HAND, cd[0], cd[1], cd[2], em, spec, rim)
            if (armed && side == 0) {
                // pistola presa na mão direita
                push()
                tr(0f, -0.07f, 0.06f)
                rx(90f)
                emit(dl, Models.PISTOL, 1f, 1f, 1f, 0f, 0.9f, 0.25f)
                val m = cur()
                // ponta do cano em coordenadas locais da arma: (0, 0.04, 0.205)
                val lx = 0f
                val ly = 0.04f
                val lz = 0.205f
                mx = m[0] * lx + m[4] * ly + m[8] * lz + m[12]
                my = m[1] * lx + m[5] * ly + m[9] * lz + m[13]
                mz = m[2] * lx + m[6] * ly + m[10] * lz + m[14]
                pop()
            }
            pop()
        }
        pop() // tronco
        pop() // pelvis

        if (out != null) {
            out[0] = mx
            out[1] = my
            out[2] = mz
        }
    }
}
