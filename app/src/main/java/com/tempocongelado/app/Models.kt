package com.tempocongelado.app

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Todas as malhas do jogo, geradas por código (nada de arquivos de modelo).
 * Humanoide inimigo facetado, pistolas detalhadas, estilhaços de vidro, brilhos e a sala.
 */
object Models {
    const val CUBE = 0
    const val SPHERE = 1
    const val RING = 2
    const val DISC = 3
    const val TRAIL = 4
    const val SHARD = 5
    const val CHUNK = 6
    const val CASING = 7
    const val HEAD = 8
    const val TORSO = 9
    const val PELVIS = 10
    const val UARM = 11
    const val FARM = 12
    const val HAND = 13
    const val THIGH = 14
    const val SHIN = 15
    const val FOOT = 16
    const val PISTOL = 17
    const val GUN_BODY = 18
    const val GUN_SLIDE = 19
    const val GUN_HAND = 20
    const val ROOM = 21
    const val FLOOR = 22
    const val BLOCK0 = 23
    const val BLOCKS = 8
    const val COUNT = 31

    val meshes: Array<FloatArray> = Array(COUNT) { FloatArray(0) }
    val version = IntArray(COUNT)

    private val WHITE = floatArrayOf(1f, 1f, 1f)
    private val STEEL = floatArrayOf(0.62f, 0.64f, 0.70f)
    private val DARK = floatArrayOf(0.16f, 0.17f, 0.20f)
    private val BLACK = floatArrayOf(0.07f, 0.075f, 0.09f)
    private val GLOVE = floatArrayOf(0.20f, 0.21f, 0.25f)
    private val BRASS = floatArrayOf(1f, 0.82f, 0.45f)

    init {
        buildAll()
    }

    fun setBlockMesh(slot: Int, data: FloatArray) {
        meshes[BLOCK0 + slot] = data
        version[BLOCK0 + slot]++
    }

    private fun buildAll() {
        meshes[CUBE] = build(1) { b -> b.cbox(0f, 0f, 0f, 1f, 1f, 1f, 0.002f, WHITE, 0f) }
        meshes[SPHERE] = build(2) { b -> b.smoothSphere(14, 24, WHITE) }
        meshes[RING] = build(3) { b -> glowRing(b) }
        meshes[DISC] = build(4) { b -> glowDisc(b) }
        meshes[TRAIL] = build(5) { b -> trail(b) }
        meshes[SHARD] = build(6) { b -> shard(b) }
        meshes[CHUNK] = build(7) { b -> b.cbox(0f, 0f, 0f, 1f, 1f, 1f, 0.22f, WHITE, 0.07f) }
        meshes[CASING] = build(8) { b -> casing(b) }
        meshes[HEAD] = build(11) { b -> head(b) }
        meshes[TORSO] = build(12) { b -> torso(b) }
        meshes[PELVIS] = build(13) { b -> pelvis(b) }
        meshes[UARM] = build(14) { b -> upperArm(b) }
        meshes[FARM] = build(15) { b -> foreArm(b) }
        meshes[HAND] = build(16) { b -> hand(b) }
        meshes[THIGH] = build(17) { b -> thigh(b) }
        meshes[SHIN] = build(18) { b -> shin(b) }
        meshes[FOOT] = build(19) { b -> foot(b) }
        meshes[PISTOL] = build(20) { b -> pistolEnemy(b) }
        meshes[GUN_BODY] = build(21) { b -> gunBody(b) }
        meshes[GUN_SLIDE] = build(22) { b -> gunSlide(b) }
        meshes[GUN_HAND] = build(23) { b -> gunHand(b) }
        meshes[ROOM] = build(24) { b -> Environment.room(b) }
        meshes[FLOOR] = build(25) { b -> Environment.floor(b) }
    }

    private fun build(seed: Int, f: (MeshBuilder) -> Unit): FloatArray {
        val b = MeshBuilder()
        b.setSeed(seed * 7919)
        f(b)
        return b.toArray()
    }

    private fun shadeRow(
        b: MeshBuilder, rows: Array<FloatArray>, sides: Int, rot: Float, jit: Float
    ): List<MeshBuilder.Ring> {
        val out = ArrayList<MeshBuilder.Ring>()
        for (r in rows) out.add(b.ellipse(r[0], r[1], r[2], r[3], r[4], sides, rot, r[5], jit))
        return out
    }

    private const val R8 = (PI / 8.0).toFloat()

    // ------------------------------------------------------------------
    // Humanoide (origem de cada peça na articulação; membros descem em -Y)
    // linhas: y, cx, cz, rx, rz, sombra
    // ------------------------------------------------------------------

    private fun head(b: MeshBuilder) {
        val rows = arrayOf(
            floatArrayOf(0.000f, 0f, 0.000f, 0.050f, 0.056f, 0.80f),
            floatArrayOf(0.070f, 0f, 0.004f, 0.048f, 0.054f, 0.85f),
            floatArrayOf(0.098f, 0f, 0.020f, 0.066f, 0.082f, 0.92f),
            floatArrayOf(0.150f, 0f, 0.022f, 0.080f, 0.098f, 1.00f),
            floatArrayOf(0.205f, 0f, 0.014f, 0.088f, 0.106f, 1.05f),
            floatArrayOf(0.258f, 0f, 0.004f, 0.080f, 0.098f, 1.08f),
            floatArrayOf(0.300f, 0f, 0.000f, 0.052f, 0.068f, 1.10f),
            floatArrayOf(0.318f, 0f, 0.000f, 0.020f, 0.030f, 1.10f)
        )
        // faixa escura do "visor" na frente do rosto
        b.loft(shadeRow(b, rows, 8, R8, 0.035f), 0, WHITE, true, true, 0.05f) { _, y, z ->
            if (z > 0.03f && y > 0.145f && y < 0.235f) 0.16f else 1f
        }
    }

    private fun torso(b: MeshBuilder) {
        val rows = arrayOf(
            floatArrayOf(0.000f, 0f, 0f, 0.140f, 0.092f, 0.80f),
            floatArrayOf(0.090f, 0f, 0.004f, 0.152f, 0.098f, 0.88f),
            floatArrayOf(0.200f, 0f, 0.008f, 0.168f, 0.110f, 0.97f),
            floatArrayOf(0.320f, 0f, 0.014f, 0.196f, 0.124f, 1.06f),
            floatArrayOf(0.410f, 0f, 0.006f, 0.222f, 0.116f, 1.10f),
            floatArrayOf(0.470f, 0f, -0.004f, 0.160f, 0.092f, 1.00f),
            floatArrayOf(0.520f, 0f, -0.004f, 0.062f, 0.062f, 0.90f)
        )
        b.loft(shadeRow(b, rows, 8, R8, 0.035f), 0, WHITE, true, true, 0.05f)
    }

    private fun pelvis(b: MeshBuilder) {
        val rows = arrayOf(
            floatArrayOf(-0.080f, 0f, 0f, 0.100f, 0.080f, 0.72f),
            floatArrayOf(-0.030f, 0f, 0f, 0.158f, 0.104f, 0.85f),
            floatArrayOf(0.040f, 0f, 0f, 0.150f, 0.098f, 0.95f),
            floatArrayOf(0.095f, 0f, 0f, 0.136f, 0.092f, 1.00f)
        )
        b.loft(shadeRow(b, rows, 8, R8, 0.035f), 0, WHITE, true, true, 0.05f)
    }

    private fun upperArm(b: MeshBuilder) {
        val rows = arrayOf(
            floatArrayOf(0.030f, 0f, 0f, 0.056f, 0.058f, 1.05f),
            floatArrayOf(-0.040f, 0f, 0f, 0.068f, 0.068f, 1.00f),
            floatArrayOf(-0.150f, 0f, 0f, 0.052f, 0.054f, 0.92f),
            floatArrayOf(-0.300f, 0f, 0f, 0.042f, 0.044f, 0.84f)
        )
        b.loft(shadeRow(b, rows, 7, R8, 0.035f), 0, WHITE, true, true, 0.05f)
    }

    private fun foreArm(b: MeshBuilder) {
        val rows = arrayOf(
            floatArrayOf(0.000f, 0f, 0f, 0.043f, 0.045f, 0.90f),
            floatArrayOf(-0.080f, 0f, 0f, 0.050f, 0.052f, 1.00f),
            floatArrayOf(-0.200f, 0f, 0f, 0.036f, 0.040f, 0.92f),
            floatArrayOf(-0.270f, 0f, 0f, 0.030f, 0.033f, 0.86f)
        )
        b.loft(shadeRow(b, rows, 7, R8, 0.035f), 0, WHITE, true, true, 0.05f)
    }

    private fun hand(b: MeshBuilder) {
        val rows = arrayOf(
            floatArrayOf(0.000f, 0f, 0f, 0.030f, 0.036f, 0.90f),
            floatArrayOf(-0.030f, 0f, 0f, 0.044f, 0.038f, 1.00f),
            floatArrayOf(-0.090f, 0f, 0.004f, 0.042f, 0.034f, 1.00f),
            floatArrayOf(-0.120f, 0f, 0.004f, 0.030f, 0.026f, 0.90f)
        )
        b.loft(shadeRow(b, rows, 6, R8, 0.04f), 0, WHITE, true, true, 0.05f)
    }

    private fun thigh(b: MeshBuilder) {
        val rows = arrayOf(
            floatArrayOf(0.020f, 0f, 0f, 0.092f, 0.098f, 0.95f),
            floatArrayOf(-0.100f, 0f, 0.004f, 0.106f, 0.110f, 1.00f),
            floatArrayOf(-0.240f, 0f, 0.004f, 0.088f, 0.092f, 0.94f),
            floatArrayOf(-0.360f, 0f, 0.002f, 0.072f, 0.076f, 0.88f),
            floatArrayOf(-0.440f, 0f, 0.004f, 0.064f, 0.066f, 0.84f)
        )
        b.loft(shadeRow(b, rows, 8, R8, 0.035f), 0, WHITE, true, true, 0.05f)
    }

    private fun shin(b: MeshBuilder) {
        val rows = arrayOf(
            floatArrayOf(0.000f, 0f, 0f, 0.060f, 0.064f, 0.86f),
            floatArrayOf(-0.070f, 0f, -0.006f, 0.066f, 0.072f, 0.96f),
            floatArrayOf(-0.160f, 0f, -0.014f, 0.066f, 0.078f, 1.00f),
            floatArrayOf(-0.320f, 0f, 0f, 0.046f, 0.052f, 0.90f),
            floatArrayOf(-0.420f, 0f, 0.002f, 0.040f, 0.046f, 0.84f)
        )
        b.loft(shadeRow(b, rows, 8, R8, 0.035f), 0, WHITE, true, true, 0.05f)
    }

    /** Bota: seções ao longo de Z (calcanhar -> ponta). Origem no tornozelo. */
    private fun foot(b: MeshBuilder) {
        val rows = listOf(
            b.ellipse(-0.065f, 0f, -0.050f, 0.046f, 0.050f, 8, R8, 0.85f, 0.03f),
            b.ellipse(0.000f, 0f, -0.048f, 0.052f, 0.056f, 8, R8, 0.95f, 0.03f),
            b.ellipse(0.080f, 0f, -0.062f, 0.056f, 0.042f, 8, R8, 1.00f, 0.03f),
            b.ellipse(0.150f, 0f, -0.074f, 0.052f, 0.032f, 8, R8, 1.05f, 0.03f),
            b.ellipse(0.195f, 0f, -0.082f, 0.040f, 0.022f, 8, R8, 1.05f, 0.03f)
        )
        b.loft(rows, 1, WHITE, true, true, 0.05f)
    }

    // ------------------------------------------------------------------
    // Pistolas
    // ------------------------------------------------------------------

    private fun gunFrame(b: MeshBuilder, detail: Boolean, col: FloatArray) {
        b.cbox(0f, 0.008f, 0.070f, 0.030f, 0.034f, 0.180f, 0.006f, col, 0.03f)
        val tubo = listOf(
            b.ellipse(0.176f, 0f, 0.040f, 0.0085f, 0.0085f, 8, 0f, 0.7f, 0f),
            b.ellipse(0.200f, 0f, 0.040f, 0.0085f, 0.0085f, 8, 0f, 0.5f, 0f)
        )
        b.loft(tubo, 1, BLACK, false, true, 0f)
        b.cbox(0f, -0.020f, 0.118f, 0.010f, 0.026f, 0.008f, 0.002f, col, 0f)
        b.cbox(0f, -0.031f, 0.092f, 0.010f, 0.008f, 0.060f, 0.002f, col, 0f)
        b.cbox(0f, -0.012f, 0.082f, 0.008f, 0.022f, 0.007f, 0.002f, BLACK, 0f)
        // empunhadura inclinada para trás
        M4.identity(b.xf)
        M4.translate(b.xf, 0f, -0.008f, 0f)
        M4.rotX(b.xf, 14f)
        b.cbox(0f, -0.060f, 0f, 0.034f, 0.115f, 0.048f, 0.008f, col, 0.03f)
        b.cbox(0f, -0.124f, -0.002f, 0.037f, 0.011f, 0.054f, 0.003f, BLACK, 0f)
        if (detail) {
            for (i in 0 until 5) {
                val y = -0.030f - i * 0.016f
                b.cbox(0.0180f, y, 0f, 0.003f, 0.008f, 0.040f, 0.001f, BLACK, 0f)
                b.cbox(-0.0180f, y, 0f, 0.003f, 0.008f, 0.040f, 0.001f, BLACK, 0f)
            }
        }
        M4.identity(b.xf)
        b.cbox(0f, 0.052f, -0.044f, 0.008f, 0.014f, 0.014f, 0.002f, BLACK, 0f)
    }

    private fun gunSlideParts(b: MeshBuilder, detail: Boolean, col: FloatArray) {
        b.cbox(0f, 0.040f, 0.075f, 0.034f, 0.040f, 0.215f, 0.007f, col, 0.03f)
        if (detail) {
            for (i in 0 until 6) {
                b.cbox(0f, 0.040f, -0.010f + i * 0.0075f, 0.0365f, 0.034f, 0.003f, 0.001f, DARK, 0f)
            }
            // janela de ejeção
            b.cbox(0.0172f, 0.046f, 0.085f, 0.0035f, 0.018f, 0.050f, 0.001f, BLACK, 0f)
        }
        b.cbox(0f, 0.066f, 0.172f, 0.007f, 0.012f, 0.012f, 0.002f, BLACK, 0f)
        b.cbox(0.009f, 0.066f, -0.026f, 0.008f, 0.012f, 0.012f, 0.002f, BLACK, 0f)
        b.cbox(-0.009f, 0.066f, -0.026f, 0.008f, 0.012f, 0.012f, 0.002f, BLACK, 0f)
    }

    private fun pistolEnemy(b: MeshBuilder) {
        gunFrame(b, false, DARK)
        gunSlideParts(b, false, STEEL)
    }

    private fun gunBody(b: MeshBuilder) {
        gunFrame(b, true, DARK)
    }

    private fun gunSlide(b: MeshBuilder) {
        gunSlideParts(b, true, STEEL)
    }

    /** Mão direita com luva segurando a pistola, mais o antebraço saindo para trás. */
    private fun gunHand(b: MeshBuilder) {
        M4.identity(b.xf)
        M4.translate(b.xf, 0f, -0.008f, 0f)
        M4.rotX(b.xf, 14f)
        // palma e dorso da mão em volta do cabo
        val palma = listOf(
            b.ellipse(-0.012f, 0.002f, -0.014f, 0.030f, 0.036f, 8, R8, 0.9f, 0.03f),
            b.ellipse(-0.050f, 0.003f, -0.014f, 0.036f, 0.042f, 8, R8, 1.0f, 0.03f),
            b.ellipse(-0.100f, 0.003f, -0.012f, 0.034f, 0.040f, 8, R8, 0.95f, 0.03f),
            b.ellipse(-0.128f, 0.002f, -0.010f, 0.028f, 0.034f, 8, R8, 0.85f, 0.03f)
        )
        b.loft(palma, 0, GLOVE, true, true, 0.05f)
        // dedos enrolados na frente do cabo
        for (i in 0 until 3) {
            val y = -0.040f - i * 0.026f
            val dedo = listOf(
                b.ellipse(-0.026f, 0.034f, y, 0.0105f, 0.0105f, 6, 0f, 0.95f, 0.02f),
                b.ellipse(0.020f, 0.036f, y, 0.0115f, 0.0115f, 6, 0f, 1.0f, 0.02f),
                b.ellipse(0.034f, 0.026f, y, 0.0100f, 0.0100f, 6, 0f, 0.9f, 0.02f)
            )
            b.loft(dedo, 2, GLOVE, false, true, 0.05f)
        }
        M4.identity(b.xf)
        // indicador no gatilho
        val ind = listOf(
            b.ellipse(0.020f, -0.026f, -0.012f, 0.0095f, 0.0095f, 6, 0f, 0.95f, 0.02f),
            b.ellipse(0.070f, -0.026f, -0.010f, 0.0095f, 0.0095f, 6, 0f, 1.0f, 0.02f),
            b.ellipse(0.098f, -0.026f, -0.012f, 0.0080f, 0.0080f, 6, 0f, 0.9f, 0.02f)
        )
        b.loft(ind, 1, GLOVE, false, true, 0.05f)
        // polegar ao longo do lado esquerdo da armação
        val pol = listOf(
            b.ellipse(-0.020f, -0.029f, 0.000f, 0.0105f, 0.0105f, 6, 0f, 0.95f, 0.02f),
            b.ellipse(0.030f, -0.027f, 0.006f, 0.0110f, 0.0110f, 6, 0f, 1.0f, 0.02f),
            b.ellipse(0.075f, -0.025f, 0.010f, 0.0090f, 0.0090f, 6, 0f, 0.9f, 0.02f)
        )
        b.loft(pol, 1, GLOVE, true, true, 0.05f)
        // antebraço indo para trás e para baixo, com punho da manga
        val ante = listOf(
            b.ellipse(-0.040f, 0.004f, -0.050f, 0.036f, 0.036f, 8, R8, 0.9f, 0.02f),
            b.ellipse(-0.150f, 0.030f, -0.090f, 0.040f, 0.040f, 8, R8, 1.0f, 0.02f),
            b.ellipse(-0.152f, 0.030f, -0.090f, 0.048f, 0.048f, 8, R8, 0.7f, 0.0f),
            b.ellipse(-0.420f, 0.090f, -0.170f, 0.050f, 0.050f, 8, R8, 0.8f, 0.02f)
        )
        b.loft(ante, 1, GLOVE, true, true, 0.05f)
    }

    // ------------------------------------------------------------------
    // Estilhaço de vidro, glows, rastro, cartucho
    // ------------------------------------------------------------------

    private fun shard(b: MeshBuilder) {
        val a = floatArrayOf(0f, 0.5f, 0f)
        val p1 = floatArrayOf(-0.24f, -0.42f, 0.12f)
        val p2 = floatArrayOf(0.24f, -0.36f, 0.14f)
        val p3 = floatArrayOf(0.02f, -0.30f, -0.24f)
        val c = floatArrayOf(0f, -0.12f, 0f)
        val w = WHITE
        b.tri(a, p1, p2, c, w, w, w, 1.00f)
        b.tri(a, p2, p3, c, w, w, w, 0.82f)
        b.tri(a, p3, p1, c, w, w, w, 0.92f)
        b.tri(p1, p3, p2, c, w, w, w, 0.70f)
    }

    private fun glowRing(b: MeshBuilder) {
        val n = 40
        val radii = floatArrayOf(0.74f, 0.90f, 1.0f)
        val vals = floatArrayOf(0f, 1f, 0f)
        val nor = floatArrayOf(0f, 0f, 1f)
        for (s in 0 until 2) {
            for (k in 0 until n) {
                val a0 = (2.0 * PI * k / n).toFloat()
                val a1 = (2.0 * PI * (k + 1) / n).toFloat()
                val r0 = radii[s]
                val r1 = radii[s + 1]
                val v0 = vals[s]
                val v1 = vals[s + 1]
                b.quadFlat(
                    floatArrayOf(cos(a0) * r0 * 0.5f, sin(a0) * r0 * 0.5f, 0f),
                    floatArrayOf(cos(a1) * r0 * 0.5f, sin(a1) * r0 * 0.5f, 0f),
                    floatArrayOf(cos(a1) * r1 * 0.5f, sin(a1) * r1 * 0.5f, 0f),
                    floatArrayOf(cos(a0) * r1 * 0.5f, sin(a0) * r1 * 0.5f, 0f),
                    nor,
                    floatArrayOf(v0, v0, v0), floatArrayOf(v0, v0, v0),
                    floatArrayOf(v1, v1, v1), floatArrayOf(v1, v1, v1)
                )
            }
        }
    }

    private fun glowDisc(b: MeshBuilder) {
        val n = 32
        val nor = floatArrayOf(0f, 0f, 1f)
        val c = floatArrayOf(0f, 0f, 0f)
        val hi = floatArrayOf(1f, 1f, 1f)
        val mid = floatArrayOf(0.35f, 0.35f, 0.35f)
        val lo = floatArrayOf(0f, 0f, 0f)
        for (k in 0 until n) {
            val a0 = (2.0 * PI * k / n).toFloat()
            val a1 = (2.0 * PI * (k + 1) / n).toFloat()
            val m0 = floatArrayOf(cos(a0) * 0.22f, sin(a0) * 0.22f, 0f)
            val m1 = floatArrayOf(cos(a1) * 0.22f, sin(a1) * 0.22f, 0f)
            val e0 = floatArrayOf(cos(a0) * 0.5f, sin(a0) * 0.5f, 0f)
            val e1 = floatArrayOf(cos(a1) * 0.5f, sin(a1) * 0.5f, 0f)
            b.triSmooth(c, m0, m1, nor, nor, nor, hi, mid, mid)
            b.quadFlat(m0, m1, e1, e0, nor, mid, mid, lo, lo)
        }
    }

    /** Rastro em forma de gota alongada: cabeça brilhante em z=0, cauda apagando em z=-1. */
    private fun trail(b: MeshBuilder) {
        val rings = listOf(
            b.ellipse(0.0f, 0f, 0f, 0.5f, 0.5f, 8, 0f, 1.0f, 0f),
            b.ellipse(-0.35f, 0f, 0f, 0.34f, 0.34f, 8, 0f, 0.5f, 0f),
            b.ellipse(-1.0f, 0f, 0f, 0.02f, 0.02f, 8, 0f, 0.0f, 0f)
        )
        b.loft(rings, 1, WHITE, false, false, 0f)
    }

    private fun casing(b: MeshBuilder) {
        val rings = listOf(
            b.ellipse(-0.5f, 0f, 0f, 0.5f, 0.5f, 8, 0f, 0.8f, 0f),
            b.ellipse(0.35f, 0f, 0f, 0.5f, 0.5f, 8, 0f, 1.0f, 0f),
            b.ellipse(0.5f, 0f, 0f, 0.38f, 0.38f, 8, 0f, 0.9f, 0f)
        )
        b.loft(rings, 1, BRASS, true, true, 0.04f)
    }
}
