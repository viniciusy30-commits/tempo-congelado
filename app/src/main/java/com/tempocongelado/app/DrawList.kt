package com.tempocongelado.app

/** Um desenho: malha + matriz do modelo + material. Descrito sem nada de OpenGL. */
class Cmd {
    var mesh = 0
    val m = FloatArray(16)
    var r = 1f
    var g = 1f
    var b = 1f
    var emis = 0f
    var alpha = 1f
    var spec = 0f
    var rim = 0f
    var halo = 0f
    var blob = 0f
    var grid = 0f
    var unlit = 0f

    fun reset(mesh: Int, src: FloatArray): Cmd {
        this.mesh = mesh
        System.arraycopy(src, 0, m, 0, 16)
        r = 1f; g = 1f; b = 1f
        emis = 0f; alpha = 1f; spec = 0f; rim = 0f
        halo = 0f; blob = 0f; grid = 0f; unlit = 0f
        return this
    }

    fun color(rr: Float, gg: Float, bb: Float): Cmd {
        r = rr; g = gg; b = bb
        return this
    }
}

/**
 * Lista de desenhos de um quadro, separada em passes. Quem desenha (o Renderer3D no celular,
 * ou o visualizador de teste) só percorre os passes na ordem.
 */
class DrawList {
    companion object {
        const val P_REFLECT = 0
        const val P_FLOOR = 1
        const val P_OPAQUE = 2
        const val P_BLOB = 3
        const val P_ALPHA = 4
        const val P_ADD = 5
        const val P_GUN = 6
        const val P_GUN_ADD = 7
        const val NP = 8
        const val MAX = 3000
    }

    private val pool = Array(MAX) { Cmd() }
    private val scratch = Cmd()
    private var used = 0
    private val lists = Array(NP) { IntArray(MAX) }
    val counts = IntArray(NP)

    // câmera e luz
    val view = FloatArray(16)
    var fov = 72f
    var ex = 0f
    var ey = 1.6f
    var ez = 0f
    var fogDen = 0.032f
    var fogR = 0.914f
    var fogG = 0.925f
    var fogB = 0.949f
    var lx = -0.45f
    var ly = 0.85f
    var lz = 0.35f
    var timeFx = 0f

    fun reset() {
        used = 0
        for (i in 0 until NP) counts[i] = 0
    }

    fun add(pass: Int, mesh: Int, m: FloatArray): Cmd {
        if (used >= MAX) return scratch.reset(mesh, m)
        val c = pool[used]
        lists[pass][counts[pass]++] = used
        used++
        return c.reset(mesh, m)
    }

    fun get(pass: Int, i: Int): Cmd = pool[lists[pass][i]]
}
