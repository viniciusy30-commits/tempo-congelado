package com.tempocongelado.app

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Sala branca de laboratório: painéis com frestas, rodapé escuro, colunas, vigas e luminárias
 * no teto, piso de placas com sombra de contato nas paredes. Tudo em cor por vértice.
 */
object Environment {
    private const val W = 10f   // meia largura da sala (X e Z)
    private const val H = 4f

    private fun c(v: Float) = floatArrayOf(v, v * 1.005f, v * 1.03f)
    private fun p(x: Float, y: Float, z: Float) = floatArrayOf(x, y, z)

    /** Quadrilátero com normal e cor fixas (vertex colors podem variar por canto). */
    private fun quad(
        b: MeshBuilder, a: FloatArray, bb: FloatArray, cc: FloatArray, d: FloatArray,
        n: FloatArray, ca: FloatArray, cb: FloatArray = ca, ccc: FloatArray = ca, cd: FloatArray = ca
    ) {
        b.quadFlat(a, bb, cc, d, n, ca, cb, ccc, cd)
    }

    private fun scaled(col: FloatArray, k: Float) = floatArrayOf(col[0] * k, col[1] * k, col[2] * k)

    /** Parede genérica: origem o, direção u (largura), v = Y. n = normal para dentro da sala. */
    private fun wallPanels(b: MeshBuilder, ox: Float, oz: Float, ux: Float, uz: Float, nx: Float, nz: Float, seedOff: Int) {
        val len = 20f
        val cols = 8
        val rows = 2
        val pw = len / cols
        val ph = H / rows
        val n = floatArrayOf(nx, 0f, nz)
        for (i in 0 until cols) {
            for (j in 0 until rows) {
                val tone = 0.90f + 0.04f * (((i * 7 + j * 13 + seedOff) % 5) / 4f)
                val x0 = i * pw
                val x1 = x0 + pw
                val y0 = j * ph
                val y1 = y0 + ph
                val g = 0.07f
                val rec = 0.05f
                // moldura (nível da parede)
                fun P(u: Float, v: Float, d: Float) = p(ox + ux * u + nx * d, v, oz + uz * u + nz * d)
                val base = c(tone * (0.94f + 0.06f * (y0 / H)))
                val dark = c(tone * 0.62f)
                // faixas da moldura
                quad(b, P(x0, y0, 0f), P(x0 + g, y0, 0f), P(x0 + g, y1, 0f), P(x0, y1, 0f), n, scaled(base, 0.9f))
                quad(b, P(x1 - g, y0, 0f), P(x1, y0, 0f), P(x1, y1, 0f), P(x1 - g, y1, 0f), n, scaled(base, 0.9f))
                quad(b, P(x0 + g, y0, 0f), P(x1 - g, y0, 0f), P(x1 - g, y0 + g, 0f), P(x0 + g, y0 + g, 0f), n, scaled(base, 0.9f))
                quad(b, P(x0 + g, y1 - g, 0f), P(x1 - g, y1 - g, 0f), P(x1 - g, y1, 0f), P(x0 + g, y1, 0f), n, scaled(base, 0.9f))
                // painel afundado
                val lo = scaled(base, 0.93f + 0.05f * (((i + j) % 2)))
                val hi = scaled(base, 1.0f)
                quad(
                    b, P(x0 + g, y0 + g, -rec), P(x1 - g, y0 + g, -rec), P(x1 - g, y1 - g, -rec), P(x0 + g, y1 - g, -rec),
                    n, lo, lo, hi, hi
                )
                // laterais do afundado (sombra nas fendas)
                val sd = scaled(dark, 1.0f)
                val nU = floatArrayOf(ux, 0f, uz)
                val nUm = floatArrayOf(-ux, 0f, -uz)
                val nUp = floatArrayOf(0f, 1f, 0f)
                val nDn = floatArrayOf(0f, -1f, 0f)
                quad(b, P(x0 + g, y0 + g, 0f), P(x0 + g, y0 + g, -rec), P(x0 + g, y1 - g, -rec), P(x0 + g, y1 - g, 0f), nU, sd, sd, sd, sd)
                quad(b, P(x1 - g, y0 + g, -rec), P(x1 - g, y0 + g, 0f), P(x1 - g, y1 - g, 0f), P(x1 - g, y1 - g, -rec), nUm, sd, sd, sd, sd)
                quad(b, P(x0 + g, y0 + g, -rec), P(x1 - g, y0 + g, -rec), P(x1 - g, y0 + g, 0f), P(x0 + g, y0 + g, 0f), nUp, sd, sd, sd, sd)
                quad(b, P(x0 + g, y1 - g, 0f), P(x1 - g, y1 - g, 0f), P(x1 - g, y1 - g, -rec), P(x0 + g, y1 - g, -rec), nDn, sd, sd, sd, sd)
            }
        }
    }

    fun room(b: MeshBuilder) {
        // paredes (normal para dentro da sala)
        wallPanels(b, -W, -W, 1f, 0f, 0f, 1f, 0)          // z = -W, olhando +Z
        wallPanels(b, W, W, -1f, 0f, 0f, -1f, 3)                                       // z = +W
        wallPanels(b, -W, W, 0f, -1f, 1f, 0f, 1)                                       // x = -W
        wallPanels(b, W, -W, 0f, 1f, -1f, 0f, 2)                                       // x = +W

        val trim = c(0.16f)
        val rail = c(0.30f)
        val pillar = c(0.80f)
        val beam = c(0.86f)

        // rodapé escuro e friso no meio da parede
        b.cbox(0f, 0.14f, -W + 0.07f, 2 * W, 0.28f, 0.14f, 0.02f, trim, 0.02f)
        b.cbox(0f, 0.14f, W - 0.07f, 2 * W, 0.28f, 0.14f, 0.02f, trim, 0.02f)
        b.cbox(-W + 0.07f, 0.14f, 0f, 0.14f, 0.28f, 2 * W, 0.02f, trim, 0.02f)
        b.cbox(W - 0.07f, 0.14f, 0f, 0.14f, 0.28f, 2 * W, 0.02f, trim, 0.02f)
        b.cbox(0f, 1.05f, -W + 0.03f, 2 * W, 0.05f, 0.06f, 0.01f, rail, 0.02f)
        b.cbox(0f, 1.05f, W - 0.03f, 2 * W, 0.05f, 0.06f, 0.01f, rail, 0.02f)
        b.cbox(-W + 0.03f, 1.05f, 0f, 0.06f, 0.05f, 2 * W, 0.01f, rail, 0.02f)
        b.cbox(W - 0.03f, 1.05f, 0f, 0.06f, 0.05f, 2 * W, 0.01f, rail, 0.02f)

        // colunas nos cantos e a cada 5m
        val pos = floatArrayOf(-W, -W / 2f, 0f, W / 2f, W)
        for (a in pos) {
            val inset = if (abs(a) == W) 0.3f else 0.22f
            b.cbox(a + (if (a == -W) inset else if (a == W) -inset else 0f), H / 2f, -W + 0.2f, 0.6f, H, 0.4f, 0.05f, pillar, 0.025f)
            b.cbox(a + (if (a == -W) inset else if (a == W) -inset else 0f), H / 2f, W - 0.2f, 0.6f, H, 0.4f, 0.05f, pillar, 0.025f)
            if (abs(a) != W) {
                b.cbox(-W + 0.2f, H / 2f, a, 0.4f, H, 0.6f, 0.05f, pillar, 0.025f)
                b.cbox(W - 0.2f, H / 2f, a, 0.4f, H, 0.6f, 0.05f, pillar, 0.025f)
            }
        }

        // teto: placa + vigas + luminárias
        val ceil = c(1.25f)
        quad(
            b, p(-W, H, -W), p(W, H, -W), p(W, H, W), p(-W, H, W), floatArrayOf(0f, -1f, 0f),
            scaled(ceil, 0.9f), scaled(ceil, 0.9f), scaled(ceil, 0.9f), scaled(ceil, 0.9f)
        )
        val bs = floatArrayOf(-W + 0.3f, -W / 2f, 0f, W / 2f, W - 0.3f)
        for (z in bs) b.cbox(0f, H - 0.12f, z, 2 * W, 0.24f, 0.34f, 0.03f, beam, 0.02f)
        for (x in bs) b.cbox(x, H - 0.20f, 0f, 0.3f, 0.12f, 2 * W, 0.02f, c(0.82f), 0.02f)
        val lit = floatArrayOf(1.02f, 1.02f, 1.0f)
        for (xi in 0 until 4) for (zi in 0 until 4) {
            val x = -7.5f + xi * 5f
            val z = -7.5f + zi * 5f
            b.cbox(x, H - 0.28f, z, 1.6f, 0.06f, 0.7f, 0.015f, lit, 0f)
            b.cbox(x, H - 0.26f, z, 1.8f, 0.03f, 0.9f, 0.012f, c(0.55f), 0f)
        }
    }

    fun floor(b: MeshBuilder) {
        val n = floatArrayOf(0f, 1f, 0f)
        val sub = 20       // 20x20 células de 1m
        for (i in 0 until sub) {
            for (k in 0 until sub) {
                val x0 = -W + i
                val z0 = -W + k
                val tx = i / 2
                val tz = k / 2
                val tone = 0.93f + 0.045f * (((tx * 5 + tz * 11) % 6) / 5f)
                fun cc(x: Float, z: Float): FloatArray {
                    val d = min(min(x + W, W - x), min(z + W, W - z))
                    val ao = 1f - 0.22f * max(0f, 1f - d / 1.6f) * max(0f, 1f - d / 1.6f)
                    return c(tone * ao)
                }
                // placa de 2m com uma fresta fina entre elas
                val e = 0.012f
                val a0 = if (i % 2 == 0) x0 + e else x0
                val a1 = if (i % 2 == 1) x0 + 1f - e else x0 + 1f
                val b0 = if (k % 2 == 0) z0 + e else z0
                val b1 = if (k % 2 == 1) z0 + 1f - e else z0 + 1f
                quad(b, p(a0, 0f, b0), p(a0, 0f, b1), p(a1, 0f, b1), p(a1, 0f, b0), n, cc(a0, b0), cc(a0, b1), cc(a1, b1), cc(a1, b0))
            }
        }
        // fendas escuras
        val dk = c(0.30f)
        for (t in 0..10) {
            val v = -W + t * 2f
            val w = 0.024f
            quad(b, p(v - w, 0.001f, -W), p(v - w, 0.001f, W), p(v + w, 0.001f, W), p(v + w, 0.001f, -W), n, dk)
            quad(b, p(-W, 0.001f, v - w), p(W, 0.001f, v - w), p(W, 0.001f, v + w), p(-W, 0.001f, v + w), n, dk)
        }
    }

    /**
     * Bloco (caixote ou coluna) centrado em (0,0,0) no chão, tamanho w x h x d.
     * Caixas baixas ganham moldura e tampa; altas viram colunas com anéis.
     */
    fun block(b: MeshBuilder, w: Float, h: Float, d: Float, seed: Int) {
        b.setSeed(seed * 31 + 7)
        val body = c(0.96f)
        val frame = c(0.80f)
        val dark = c(0.22f)
        val cy = h / 2f
        // corpo
        b.cbox(0f, cy, 0f, w, h, d, 0.07f, body, 0.012f)
        // base escura e tampa
        b.cbox(0f, 0.07f, 0f, w + 0.06f, 0.14f, d + 0.06f, 0.025f, dark, 0.02f)
        b.cbox(0f, h - 0.05f, 0f, w + 0.08f, 0.10f, d + 0.08f, 0.03f, c(0.88f), 0.02f)
        // molduras verticais nos cantos
        val px = w / 2f
        val pz = d / 2f
        for (sx in intArrayOf(-1, 1)) for (sz in intArrayOf(-1, 1)) {
            b.cbox(sx * (px - 0.025f), cy, sz * (pz - 0.025f), 0.11f, h - 0.1f, 0.11f, 0.02f, frame, 0.02f)
        }
        // faixas horizontais
        val bands = max(1, (h / 0.85f).toInt())
        for (i in 1..bands) {
            val y = h * i / (bands + 1)
            b.cbox(0f, y, 0f, w + 0.035f, 0.05f, d + 0.035f, 0.012f, frame, 0.02f)
            b.cbox(0f, y - 0.07f, 0f, w + 0.015f, 0.025f, d + 0.015f, 0.008f, dark, 0.02f)
        }
        // alças/etiquetas escuras nas faces maiores
        val lw = min(w, d) * 0.35f
        b.cbox(0f, h * 0.5f, pz + 0.01f, lw, 0.14f, 0.03f, 0.01f, dark, 0.02f)
        b.cbox(0f, h * 0.5f, -pz - 0.01f, lw, 0.14f, 0.03f, 0.01f, dark, 0.02f)
        b.cbox(px + 0.01f, h * 0.5f, 0f, 0.03f, 0.14f, lw, 0.01f, dark, 0.02f)
        b.cbox(-px - 0.01f, h * 0.5f, 0f, 0.03f, 0.14f, lw, 0.01f, dark, 0.02f)
    }
}
