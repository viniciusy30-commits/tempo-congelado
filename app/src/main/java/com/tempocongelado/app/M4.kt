package com.tempocongelado.app

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** Matrizes 4x4 em coluna-maior (formato do OpenGL). Sem dependência do Android. */
object M4 {
    fun identity(m: FloatArray) {
        for (i in 0 until 16) m[i] = 0f
        m[0] = 1f
        m[5] = 1f
        m[10] = 1f
        m[15] = 1f
    }

    fun copy(dst: FloatArray, src: FloatArray) {
        System.arraycopy(src, 0, dst, 0, 16)
    }

    /** o = a * b (o não pode ser a nem b). */
    fun mul(o: FloatArray, a: FloatArray, b: FloatArray) {
        for (c in 0 until 4) {
            for (r in 0 until 4) {
                var s = 0f
                for (k in 0 until 4) s += a[k * 4 + r] * b[c * 4 + k]
                o[c * 4 + r] = s
            }
        }
    }

    /** m = m * T(x,y,z) */
    fun translate(m: FloatArray, x: Float, y: Float, z: Float) {
        for (r in 0 until 4) m[12 + r] += m[r] * x + m[4 + r] * y + m[8 + r] * z
    }

    /** m = m * S(x,y,z) */
    fun scale(m: FloatArray, x: Float, y: Float, z: Float) {
        for (r in 0 until 4) {
            m[r] *= x
            m[4 + r] *= y
            m[8 + r] *= z
        }
    }

    private const val D2R = 0.017453292f

    fun rotX(m: FloatArray, deg: Float) {
        val c = cos(deg * D2R)
        val s = sin(deg * D2R)
        for (r in 0 until 4) {
            val c1 = m[4 + r]
            val c2 = m[8 + r]
            m[4 + r] = c * c1 + s * c2
            m[8 + r] = -s * c1 + c * c2
        }
    }

    fun rotY(m: FloatArray, deg: Float) {
        val c = cos(deg * D2R)
        val s = sin(deg * D2R)
        for (r in 0 until 4) {
            val c0 = m[r]
            val c2 = m[8 + r]
            m[r] = c * c0 - s * c2
            m[8 + r] = s * c0 + c * c2
        }
    }

    fun rotZ(m: FloatArray, deg: Float) {
        val c = cos(deg * D2R)
        val s = sin(deg * D2R)
        for (r in 0 until 4) {
            val c0 = m[r]
            val c1 = m[4 + r]
            m[r] = c * c0 + s * c1
            m[4 + r] = -s * c0 + c * c1
        }
    }

    fun perspective(m: FloatArray, fovDeg: Float, aspect: Float, near: Float, far: Float) {
        val f = 1f / tan(fovDeg * D2R / 2f)
        for (i in 0 until 16) m[i] = 0f
        m[0] = f / aspect
        m[5] = f
        m[10] = (far + near) / (near - far)
        m[11] = -1f
        m[14] = 2f * far * near / (near - far)
    }

    fun lookAt(
        m: FloatArray, ex: Float, ey: Float, ez: Float,
        cx: Float, cy: Float, cz: Float, ux: Float, uy: Float, uz: Float
    ) {
        var fx = cx - ex
        var fy = cy - ey
        var fz = cz - ez
        val fl = sqrt(fx * fx + fy * fy + fz * fz)
        fx /= fl
        fy /= fl
        fz /= fl
        var sx = fy * uz - fz * uy
        var sy = fz * ux - fx * uz
        var sz = fx * uy - fy * ux
        val sl = sqrt(sx * sx + sy * sy + sz * sz)
        sx /= sl
        sy /= sl
        sz /= sl
        val tx = sy * fz - sz * fy
        val ty = sz * fx - sx * fz
        val tz = sx * fy - sy * fx
        m[0] = sx; m[1] = tx; m[2] = -fx; m[3] = 0f
        m[4] = sy; m[5] = ty; m[6] = -fy; m[7] = 0f
        m[8] = sz; m[9] = tz; m[10] = -fz; m[11] = 0f
        m[12] = -(sx * ex + sy * ey + sz * ez)
        m[13] = -(tx * ex + ty * ey + tz * ez)
        m[14] = fx * ex + fy * ey + fz * ez
        m[15] = 1f
    }
}
