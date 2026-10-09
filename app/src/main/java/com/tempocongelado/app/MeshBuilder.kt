package com.tempocongelado.app

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Construtor de malhas procedurais. Formato final: 9 floats por vértice
 * (posição xyz, normal xyz, cor rgb), triângulos soltos. Tudo com sombreamento facetado
 * (uma normal por triângulo), como num modelo low-poly feito à mão.
 */
class MeshBuilder {
    /** Anel de uma seção: pontos (u,v) no plano da seção, posição w ao longo do eixo. */
    class Ring(val w: Float, val pts: FloatArray, val shade: Float)

    private var d = FloatArray(1 shl 14)
    private var n = 0
    val xf = FloatArray(16)
    private var seed = 1

    init {
        M4.identity(xf)
    }

    fun setSeed(s: Int) {
        seed = s
    }

    private fun rnd(): Float {
        seed = seed * 1103515245 + 12345
        return ((seed ushr 8) and 0xFFFF) / 65535f
    }

    fun toArray(): FloatArray = d.copyOf(n)

    fun vertexCount(): Int = n / 9

    private fun grow() {
        if (n + 9 * 6 > d.size) d = d.copyOf(d.size * 2)
    }

    private fun tx(x: Float, y: Float, z: Float) = xf[0] * x + xf[4] * y + xf[8] * z + xf[12]
    private fun ty(x: Float, y: Float, z: Float) = xf[1] * x + xf[5] * y + xf[9] * z + xf[13]
    private fun tz(x: Float, y: Float, z: Float) = xf[2] * x + xf[6] * y + xf[10] * z + xf[14]

    private fun put(
        x: Float, y: Float, z: Float, nx: Float, ny: Float, nz: Float,
        r: Float, g: Float, b: Float
    ) {
        d[n++] = x
        d[n++] = y
        d[n++] = z
        d[n++] = nx
        d[n++] = ny
        d[n++] = nz
        d[n++] = r
        d[n++] = g
        d[n++] = b
    }

    /**
     * Triângulo facetado. Pontos em coordenadas locais (a transformação xf é aplicada aqui).
     * A normal aponta para longe de (cx,cy,cz), o "centro" da peça. Cor por vértice.
     */
    fun tri(
        a: FloatArray, b: FloatArray, c: FloatArray, cen: FloatArray,
        ca: FloatArray, cb: FloatArray, cc: FloatArray, facet: Float
    ) {
        grow()
        val ax = tx(a[0], a[1], a[2]); val ay = ty(a[0], a[1], a[2]); val az = tz(a[0], a[1], a[2])
        val bx = tx(b[0], b[1], b[2]); val by = ty(b[0], b[1], b[2]); val bz = tz(b[0], b[1], b[2])
        val cx = tx(c[0], c[1], c[2]); val cy = ty(c[0], c[1], c[2]); val cz = tz(c[0], c[1], c[2])
        val ox = tx(cen[0], cen[1], cen[2]); val oy = ty(cen[0], cen[1], cen[2]); val oz = tz(cen[0], cen[1], cen[2])
        val ux = bx - ax; val uy = by - ay; val uz = bz - az
        val vx = cx - ax; val vy = cy - ay; val vz = cz - az
        var nx = uy * vz - uz * vy
        var ny = uz * vx - ux * vz
        var nz = ux * vy - uy * vx
        val len = sqrt(nx * nx + ny * ny + nz * nz)
        if (len < 1e-9f) return
        nx /= len; ny /= len; nz /= len
        val mx = (ax + bx + cx) / 3f - ox
        val my = (ay + by + cy) / 3f - oy
        val mz = (az + bz + cz) / 3f - oz
        if (nx * mx + ny * my + nz * mz < 0f) {
            nx = -nx; ny = -ny; nz = -nz
        }
        put(ax, ay, az, nx, ny, nz, ca[0] * facet, ca[1] * facet, ca[2] * facet)
        put(bx, by, bz, nx, ny, nz, cb[0] * facet, cb[1] * facet, cb[2] * facet)
        put(cx, cy, cz, nx, ny, nz, cc[0] * facet, cc[1] * facet, cc[2] * facet)
    }

    /** Triângulo com normais suaves dadas (esferas, glows). */
    fun triSmooth(
        a: FloatArray, b: FloatArray, c: FloatArray,
        na: FloatArray, nb: FloatArray, nc: FloatArray,
        ca: FloatArray, cb: FloatArray, cc: FloatArray
    ) {
        grow()
        put(tx(a[0], a[1], a[2]), ty(a[0], a[1], a[2]), tz(a[0], a[1], a[2]), na[0], na[1], na[2], ca[0], ca[1], ca[2])
        put(tx(b[0], b[1], b[2]), ty(b[0], b[1], b[2]), tz(b[0], b[1], b[2]), nb[0], nb[1], nb[2], cb[0], cb[1], cb[2])
        put(tx(c[0], c[1], c[2]), ty(c[0], c[1], c[2]), tz(c[0], c[1], c[2]), nc[0], nc[1], nc[2], cc[0], cc[1], cc[2])
    }

    // ------------------------------------------------------------------
    // Anéis
    // ------------------------------------------------------------------

    /** Anel elíptico com N lados. jit = irregularidade (0 a ~0.08) para dar aspecto de cristal. */
    fun ellipse(
        w: Float, cu: Float, cv: Float, ru: Float, rv: Float, sides: Int,
        rot: Float, shade: Float, jit: Float
    ): Ring {
        val p = FloatArray(sides * 2)
        for (k in 0 until sides) {
            val a = rot + (2.0 * PI * k / sides).toFloat()
            val j = 1f + (rnd() - 0.5f) * 2f * jit
            p[k * 2] = cu + cos(a) * ru * j
            p[k * 2 + 1] = cv + sin(a) * rv * j
        }
        return Ring(w, p, shade)
    }

    /** Anel retangular com cantos chanfrados (8 pontos). */
    fun rect(w: Float, cu: Float, cv: Float, hu: Float, hv: Float, ch: Float, shade: Float): Ring {
        val c = ch.coerceAtMost(minOf(hu, hv) * 0.95f)
        val p = floatArrayOf(
            cu + hu - c, cv - hv,
            cu + hu, cv - hv + c,
            cu + hu, cv + hv - c,
            cu + hu - c, cv + hv,
            cu - hu + c, cv + hv,
            cu - hu, cv + hv - c,
            cu - hu, cv - hv + c,
            cu - hu + c, cv - hv
        )
        return Ring(w, p, shade)
    }

    /**
     * Costura uma lista de anéis (todos com o mesmo número de pontos).
     * axis: 0 = eixo Y (u=x, v=z), 1 = eixo Z (u=x, v=y), 2 = eixo X (u=z, v=y).
     */
    fun loft(
        rings: List<Ring>, axis: Int, col: FloatArray,
        capStart: Boolean, capEnd: Boolean, facetVar: Float,
        tint: ((Float, Float, Float) -> Float)? = null
    ) {
        val m = rings[0].pts.size / 2
        val pos = Array(rings.size) { i ->
            Array(m) { k ->
                val r = rings[i]
                val u = r.pts[k * 2]
                val v = r.pts[k * 2 + 1]
                when (axis) {
                    0 -> floatArrayOf(u, r.w, v)
                    1 -> floatArrayOf(u, v, r.w)
                    else -> floatArrayOf(r.w, v, u)
                }
            }
        }
        val colors = Array(rings.size) { i ->
            Array(m) { k ->
                val p = pos[i][k]
                var s = rings[i].shade
                if (tint != null) s *= tint(p[0], p[1], p[2])
                floatArrayOf(col[0] * s, col[1] * s, col[2] * s)
            }
        }
        val rc = Array(rings.size) { i ->
            val c = FloatArray(3)
            for (k in 0 until m) {
                c[0] += pos[i][k][0]; c[1] += pos[i][k][1]; c[2] += pos[i][k][2]
            }
            c[0] /= m; c[1] /= m; c[2] /= m
            c
        }
        val part = FloatArray(3)
        for (i in rings.indices) {
            part[0] += rc[i][0]; part[1] += rc[i][1]; part[2] += rc[i][2]
        }
        part[0] /= rings.size; part[1] /= rings.size; part[2] /= rings.size

        val cen = FloatArray(3)
        for (i in 0 until rings.size - 1) {
            cen[0] = (rc[i][0] + rc[i + 1][0]) / 2f
            cen[1] = (rc[i][1] + rc[i + 1][1]) / 2f
            cen[2] = (rc[i][2] + rc[i + 1][2]) / 2f
            for (k in 0 until m) {
                val k2 = (k + 1) % m
                val f1 = 1f + (rnd() - 0.5f) * 2f * facetVar
                val f2 = 1f + (rnd() - 0.5f) * 2f * facetVar
                tri(pos[i][k], pos[i + 1][k2], pos[i + 1][k], cen, colors[i][k], colors[i + 1][k2], colors[i + 1][k], f1)
                tri(pos[i][k], pos[i][k2], pos[i + 1][k2], cen, colors[i][k], colors[i][k2], colors[i + 1][k2], f2)
            }
        }
        if (capStart) cap(pos[0], rc[0], part, colors[0], facetVar)
        if (capEnd) cap(pos[rings.size - 1], rc[rings.size - 1], part, colors[rings.size - 1], facetVar)
    }

    private fun cap(p: Array<FloatArray>, c: FloatArray, part: FloatArray, cols: Array<FloatArray>, facetVar: Float) {
        val m = p.size
        val cc = FloatArray(3)
        for (k in 0 until m) {
            cc[0] += cols[k][0]; cc[1] += cols[k][1]; cc[2] += cols[k][2]
        }
        cc[0] /= m; cc[1] /= m; cc[2] /= m
        // fatia o polígono em triângulos a partir do centro
        // o "centro da peça" para orientar a normal é part
        for (k in 0 until m) {
            val k2 = (k + 1) % m
            val f = 1f + (rnd() - 0.5f) * 2f * facetVar
            tri(c, p[k], p[k2], part, cc, cols[k], cols[k2], f)
        }
    }

    // ------------------------------------------------------------------
    // Formas prontas
    // ------------------------------------------------------------------

    /** Caixa com quinas chanfradas, centro em (cx,cy,cz), tamanho (sx,sy,sz), chanfro ch. */
    fun cbox(
        cx: Float, cy: Float, cz: Float, sx: Float, sy: Float, sz: Float, ch: Float,
        col: FloatArray, facetVar: Float
    ) {
        val hx = sx / 2f
        val hy = sy / 2f
        val hz = sz / 2f
        val c = ch.coerceAtMost(minOf(hx, hy, hz) * 0.9f)
        val rings = listOf(
            rect(cy - hy, cx, cz, hx - c, hz - c, 0.001f, 1f),
            rect(cy - hy + c, cx, cz, hx, hz, c, 1f),
            rect(cy + hy - c, cx, cz, hx, hz, c, 1f),
            rect(cy + hy, cx, cz, hx - c, hz - c, 0.001f, 1f)
        )
        loft(rings, 0, col, true, true, facetVar)
    }

    /** Esfera lisa (normais suaves) de diâmetro 1, para brilhos e halos. */
    fun smoothSphere(stacks: Int, slices: Int, col: FloatArray) {
        val pi = PI.toFloat()
        for (i in 0 until stacks) {
            val a0 = pi * i / stacks
            val a1 = pi * (i + 1) / stacks
            for (j in 0 until slices) {
                val b0 = 2f * pi * j / slices
                val b1 = 2f * pi * (j + 1) / slices
                val p00 = floatArrayOf(sin(a0) * cos(b0), cos(a0), sin(a0) * sin(b0))
                val p01 = floatArrayOf(sin(a0) * cos(b1), cos(a0), sin(a0) * sin(b1))
                val p11 = floatArrayOf(sin(a1) * cos(b1), cos(a1), sin(a1) * sin(b1))
                val p10 = floatArrayOf(sin(a1) * cos(b0), cos(a1), sin(a1) * sin(b0))
                val h00 = floatArrayOf(p00[0] * 0.5f, p00[1] * 0.5f, p00[2] * 0.5f)
                val h01 = floatArrayOf(p01[0] * 0.5f, p01[1] * 0.5f, p01[2] * 0.5f)
                val h11 = floatArrayOf(p11[0] * 0.5f, p11[1] * 0.5f, p11[2] * 0.5f)
                val h10 = floatArrayOf(p10[0] * 0.5f, p10[1] * 0.5f, p10[2] * 0.5f)
                triSmooth(h00, h01, h11, p00, p01, p11, col, col, col)
                triSmooth(h00, h11, h10, p00, p11, p10, col, col, col)
            }
        }
    }

    /** Quadrilátero plano com normal fixa e cores por canto (para glows). */
    fun quadFlat(
        a: FloatArray, b: FloatArray, c: FloatArray, dd: FloatArray, nor: FloatArray,
        ca: FloatArray, cb: FloatArray, cc: FloatArray, cd: FloatArray
    ) {
        triSmooth(a, b, c, nor, nor, nor, ca, cb, cc)
        triSmooth(a, c, dd, nor, nor, nor, ca, cc, cd)
    }

    companion object {
        fun rgb(h: Int): FloatArray =
            floatArrayOf(((h shr 16) and 255) / 255f, ((h shr 8) and 255) / 255f, (h and 255) / 255f)

        fun len3(x: Float, y: Float, z: Float): Float = max(1e-9f, sqrt(x * x + y * y + z * z))

        fun absf(v: Float): Float = abs(v)
    }
}
