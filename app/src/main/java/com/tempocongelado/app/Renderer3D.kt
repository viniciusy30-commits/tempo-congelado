package com.tempocongelado.app

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.Random
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Desenha o mundo 3D com OpenGL ES 2.0, sem arquivos de modelo ou textura.
 * Formas arredondadas (esferas/elipsoides e caixas com borda suave), brilho, luz de contorno,
 * sombras suaves, neblina, rastros de bala e clarões de tiro.
 */
class Renderer3D(private val g: Game3D) : GLSurfaceView.Renderer {

    private class Mesh(val buf: FloatBuffer, val count: Int)

    private val VERT = """
        uniform mat4 uMVP;
        uniform mat4 uModel;
        uniform mat4 uRot;
        uniform vec3 uScale;
        attribute vec3 aPos;
        attribute vec3 aNor;
        varying vec3 vNor;
        varying vec3 vWorld;
        varying vec3 vLocal;
        void main() {
            vec4 w = uModel * vec4(aPos, 1.0);
            vWorld = w.xyz;
            vLocal = aPos;
            vNor = (uRot * vec4(aNor / uScale, 0.0)).xyz;
            gl_Position = uMVP * vec4(aPos, 1.0);
        }
    """.trimIndent()

    private val FRAG = """
        #ifdef GL_FRAGMENT_PRECISION_HIGH
        precision highp float;
        #else
        precision mediump float;
        #endif
        uniform vec3 uColor;
        uniform vec3 uLight;
        uniform vec3 uFogColor;
        uniform vec3 uCam;
        uniform float uFog;
        uniform float uEmis;
        uniform float uAlpha;
        uniform float uGrid;
        uniform float uSpec;
        uniform float uRim;
        uniform float uBlob;
        varying vec3 vNor;
        varying vec3 vWorld;
        varying vec3 vLocal;
        void main() {
            if (uBlob > 0.5) {
                float r = length(vLocal.xz) * 2.0;
                gl_FragColor = vec4(0.0, 0.0, 0.0, uAlpha * (1.0 - smoothstep(0.15, 1.0, r)));
                return;
            }
            vec3 N = normalize(vNor);
            vec3 V = normalize(uCam - vWorld);
            vec3 L = normalize(uLight);
            float diff = max(dot(N, L), 0.0);
            float hemi = 0.5 + 0.5 * N.y;
            float ao = mix(0.80, 1.0, clamp(vWorld.y * 0.7, 0.0, 1.0));
            ao = mix(ao, 1.0, max(N.y, 0.0));
            vec3 col = uColor * (0.44 + 0.42 * diff + 0.14 * hemi) * ao;
            if (uGrid > 0.5) {
                vec2 q = abs(fract(vWorld.xz / 2.0) - 0.5);
                float line = smoothstep(0.47, 0.5, max(q.x, q.y));
                col *= (1.0 - 0.10 * line);
            }
            col = mix(col, uColor, uEmis);
            vec3 H = normalize(L + V);
            float spec = pow(max(dot(N, H), 0.0), 40.0) * uSpec * (1.0 - uEmis);
            float fres = pow(1.0 - max(dot(N, V), 0.0), 2.5);
            col += vec3(spec) + (uColor * 0.6 + 0.4) * fres * uRim;
            float dist = length(vWorld - uCam);
            float f = clamp(exp(-uFog * dist), 0.0, 1.0);
            gl_FragColor = vec4(mix(uFogColor, col, f), uAlpha);
        }
    """.trimIndent()

    private val RAD = 57.29578f

    // cores (0xRRGGBB)
    private val C_FLOOR = 0xF3F4F8
    private val C_WALL = 0xDFE3EB
    private val C_TRIM = 0x2A2D35
    private val C_CEIL = 0xF6F7FA
    private val C_BLOCK = 0xE6E9F0
    private val C_RED = 0xE5322D
    private val C_RED_LIGHT = 0xF0524C
    private val C_DARKRED = 0xA82420
    private val C_WHITE = 0xFFFFFF
    private val C_BLACK = 0x15171C
    private val C_GREY = 0x9AA0AE
    private val C_GUN = 0x23262D
    private val C_GUN2 = 0x3B404B
    private val C_SPARK = 0xFFB347
    private val C_BRASS = 0xD9A441
    private val C_TRACER = 0xFF9A2E

    private var prog = 0
    private var aPos = 0
    private var aNor = 0
    private var uMVP = 0
    private var uModel = 0
    private var uRot = 0
    private var uScale = 0
    private var uColor = 0
    private var uLight = 0
    private var uFogColor = 0
    private var uCam = 0
    private var uFog = 0
    private var uEmis = 0
    private var uAlpha = 0
    private var uGrid = 0
    private var uSpec = 0
    private var uRim = 0
    private var uBlob = 0

    private var cubeM: Mesh? = null
    private var sphereM: Mesh? = null
    private var rboxM: Mesh? = null

    private val proj = FloatArray(16)
    private val view = FloatArray(16)
    private val vpWorld = FloatArray(16)
    private val vpGun = FloatArray(16)
    private var vp = vpWorld

    private val model = FloatArray(16)
    private val rot = FloatArray(16)
    private val mvp = FloatArray(16)
    private val m1 = FloatArray(16)
    private val m2 = FloatArray(16)
    private val bA = FloatArray(16)
    private val bR = FloatArray(16)

    // estilo das próximas peças
    private var sSpec = 0f
    private var sRim = 0f
    private var sGrid = 0f
    private var sBlob = 0f

    private val rng = Random()
    private var lastT = 0L
    private var clk = 0f
    private var ready = false

    // ------------------------------------------------------------------
    // Ciclo de vida do GL
    // ------------------------------------------------------------------

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        ready = false
        val vs = compilar(GLES20.GL_VERTEX_SHADER, VERT)
        val fs = compilar(GLES20.GL_FRAGMENT_SHADER, FRAG)
        if (vs == 0 || fs == 0) return
        prog = GLES20.glCreateProgram()
        GLES20.glAttachShader(prog, vs)
        GLES20.glAttachShader(prog, fs)
        GLES20.glLinkProgram(prog)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) {
            Log.e("Renderer3D", "Falha ao ligar o programa: " + GLES20.glGetProgramInfoLog(prog))
            return
        }

        aPos = GLES20.glGetAttribLocation(prog, "aPos")
        aNor = GLES20.glGetAttribLocation(prog, "aNor")
        uMVP = GLES20.glGetUniformLocation(prog, "uMVP")
        uModel = GLES20.glGetUniformLocation(prog, "uModel")
        uRot = GLES20.glGetUniformLocation(prog, "uRot")
        uScale = GLES20.glGetUniformLocation(prog, "uScale")
        uColor = GLES20.glGetUniformLocation(prog, "uColor")
        uLight = GLES20.glGetUniformLocation(prog, "uLight")
        uFogColor = GLES20.glGetUniformLocation(prog, "uFogColor")
        uCam = GLES20.glGetUniformLocation(prog, "uCam")
        uFog = GLES20.glGetUniformLocation(prog, "uFog")
        uEmis = GLES20.glGetUniformLocation(prog, "uEmis")
        uAlpha = GLES20.glGetUniformLocation(prog, "uAlpha")
        uGrid = GLES20.glGetUniformLocation(prog, "uGrid")
        uSpec = GLES20.glGetUniformLocation(prog, "uSpec")
        uRim = GLES20.glGetUniformLocation(prog, "uRim")
        uBlob = GLES20.glGetUniformLocation(prog, "uBlob")

        cubeM = montarMalha(gerarCubo())
        sphereM = montarMalha(gerarEsfera(14, 24))
        rboxM = montarMalha(gerarCaixaArredondada(0.16f))

        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glClearColor(0.914f, 0.925f, 0.949f, 1f)
        lastT = 0L
        ready = true
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        val aspect = width.toFloat() / max(1, height).toFloat()
        Matrix.perspectiveM(proj, 0, 70f, aspect, 0.1f, 70f)
    }

    override fun onDrawFrame(gl: GL10?) {
        val agora = System.nanoTime()
        var dt = if (lastT == 0L) 0.016f else (agora - lastT) / 1e9f
        lastT = agora
        if (dt > 0.05f) dt = 0.05f
        if (dt < 0f) dt = 0f
        clk += dt

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        if (!ready) return
        synchronized(g) {
            g.update(dt)
            desenharCena()
        }
    }

    private fun compilar(tipo: Int, fonte: String): Int {
        val s = GLES20.glCreateShader(tipo)
        GLES20.glShaderSource(s, fonte)
        GLES20.glCompileShader(s)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) {
            Log.e("Renderer3D", "Falha no shader: " + GLES20.glGetShaderInfoLog(s))
            GLES20.glDeleteShader(s)
            return 0
        }
        return s
    }

    // ------------------------------------------------------------------
    // Malhas (posição + normal, 6 floats por vértice, triângulos soltos)
    // ------------------------------------------------------------------

    private fun montarMalha(dados: FloatArray): Mesh {
        val bb = ByteBuffer.allocateDirect(dados.size * 4).order(ByteOrder.nativeOrder())
        val fb = bb.asFloatBuffer()
        fb.put(dados)
        fb.position(0)
        return Mesh(fb, dados.size / 6)
    }

    private fun gerarCubo(): FloatArray {
        val faces = arrayOf(
            floatArrayOf(0f, 0f, 1f, 1f, 0f, 0f, 0f, 1f, 0f),
            floatArrayOf(0f, 0f, -1f, 1f, 0f, 0f, 0f, 1f, 0f),
            floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 1f, 0f),
            floatArrayOf(-1f, 0f, 0f, 0f, 0f, 1f, 0f, 1f, 0f),
            floatArrayOf(0f, 1f, 0f, 1f, 0f, 0f, 0f, 0f, 1f),
            floatArrayOf(0f, -1f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)
        )
        val sinais = arrayOf(
            floatArrayOf(-1f, -1f), floatArrayOf(1f, -1f), floatArrayOf(1f, 1f),
            floatArrayOf(-1f, -1f), floatArrayOf(1f, 1f), floatArrayOf(-1f, 1f)
        )
        val dados = FloatArray(36 * 6)
        var k = 0
        for (f in faces) {
            for (sg in sinais) {
                val su = sg[0] * 0.5f
                val sv = sg[1] * 0.5f
                dados[k++] = f[0] * 0.5f + f[3] * su + f[6] * sv
                dados[k++] = f[1] * 0.5f + f[4] * su + f[7] * sv
                dados[k++] = f[2] * 0.5f + f[5] * su + f[8] * sv
                dados[k++] = f[0]
                dados[k++] = f[1]
                dados[k++] = f[2]
            }
        }
        return dados
    }

    /** Esfera de diâmetro 1, normais suaves. */
    private fun gerarEsfera(pilhas: Int, fatias: Int): FloatArray {
        val dados = FloatArray(pilhas * fatias * 6 * 6)
        var k = 0
        val pi = Math.PI.toFloat()
        for (i in 0 until pilhas) {
            val a0 = pi * i / pilhas
            val a1 = pi * (i + 1) / pilhas
            for (j in 0 until fatias) {
                val b0 = 2f * pi * j / fatias
                val b1 = 2f * pi * (j + 1) / fatias
                // quatro cantos do quadrilátero
                val px = floatArrayOf(
                    sin(a0) * cos(b0), sin(a0) * cos(b1), sin(a1) * cos(b1), sin(a1) * cos(b0)
                )
                val py = floatArrayOf(cos(a0), cos(a0), cos(a1), cos(a1))
                val pz = floatArrayOf(
                    sin(a0) * sin(b0), sin(a0) * sin(b1), sin(a1) * sin(b1), sin(a1) * sin(b0)
                )
                val ordem = intArrayOf(0, 1, 2, 0, 2, 3)
                for (o in ordem) {
                    dados[k++] = px[o] * 0.5f
                    dados[k++] = py[o] * 0.5f
                    dados[k++] = pz[o] * 0.5f
                    dados[k++] = px[o]
                    dados[k++] = py[o]
                    dados[k++] = pz[o]
                }
            }
        }
        return dados
    }

    /** Cubo de lado 1 com cantos e quinas arredondados (raio r, em unidades do cubo). */
    private fun gerarCaixaArredondada(r: Float): FloatArray {
        val c = floatArrayOf(
            -0.5f, -0.5f + r * 0.25f, -0.5f + r * 0.6f, -0.5f + r,
            0.5f - r, 0.5f - r * 0.6f, 0.5f - r * 0.25f, 0.5f
        )
        val n = c.size
        val lista = ArrayList<Float>()
        // eixo normal da face: 0=x, 1=y, 2=z; sinal da face
        for (eixo in 0 until 3) {
            for (sinal in intArrayOf(-1, 1)) {
                for (a in 0 until n - 1) {
                    for (b in 0 until n - 1) {
                        val cantos = arrayOf(
                            floatArrayOf(c[a], c[b]), floatArrayOf(c[a + 1], c[b]),
                            floatArrayOf(c[a + 1], c[b + 1]), floatArrayOf(c[a], c[b + 1])
                        )
                        val ordem = intArrayOf(0, 1, 2, 0, 2, 3)
                        for (o in ordem) {
                            val u = cantos[o][0]
                            val v = cantos[o][1]
                            val p = FloatArray(3)
                            val e1 = (eixo + 1) % 3
                            val e2 = (eixo + 2) % 3
                            p[eixo] = 0.5f * sinal
                            p[e1] = u
                            p[e2] = v
                            // projeta na superfície arredondada
                            val ix = p[0].coerceIn(-0.5f + r, 0.5f - r)
                            val iy = p[1].coerceIn(-0.5f + r, 0.5f - r)
                            val iz = p[2].coerceIn(-0.5f + r, 0.5f - r)
                            var dx = p[0] - ix
                            var dy = p[1] - iy
                            var dz = p[2] - iz
                            val len = sqrt(dx * dx + dy * dy + dz * dz)
                            if (len < 0.00001f) {
                                dx = 0f
                                dy = 0f
                                dz = 0f
                                if (eixo == 0) dx = sinal.toFloat() else if (eixo == 1) dy = sinal.toFloat() else dz = sinal.toFloat()
                            } else {
                                dx /= len
                                dy /= len
                                dz /= len
                            }
                            lista.add(ix + dx * r)
                            lista.add(iy + dy * r)
                            lista.add(iz + dz * r)
                            lista.add(dx)
                            lista.add(dy)
                            lista.add(dz)
                        }
                    }
                }
            }
        }
        val dados = FloatArray(lista.size)
        for (i in dados.indices) dados[i] = lista[i]
        return dados
    }

    // ------------------------------------------------------------------
    // Utilidades de desenho
    // ------------------------------------------------------------------

    private fun estilo(spec: Float, rim: Float) {
        sSpec = spec
        sRim = rim
    }

    /** Define a base (posição + giro em Y, em graus) para as peças seguintes. */
    private fun setBase(x: Float, y: Float, z: Float, yawDeg: Float) {
        Matrix.setIdentityM(bR, 0)
        Matrix.rotateM(bR, 0, yawDeg, 0f, 1f, 0f)
        Matrix.setIdentityM(m1, 0)
        Matrix.translateM(m1, 0, x, y, z)
        Matrix.multiplyMM(bA, 0, m1, 0, bR, 0)
    }

    /**
     * Desenha uma peça: base * T(tx,ty,tz) * Rx(rxDeg) * T(0,oy,0) * S(sx,sy,sz).
     * O pivô do giro em X é o ponto (tx,ty,tz); oy desloca a peça depois do giro.
     */
    private fun peca(
        m: Mesh?, tx: Float, ty: Float, tz: Float, rxDeg: Float, oy: Float,
        sx: Float, sy: Float, sz: Float,
        cor: Int, emis: Float, alpha: Float
    ) {
        if (m == null) return
        Matrix.translateM(m1, 0, bA, 0, tx, ty, tz)
        Matrix.rotateM(m2, 0, m1, 0, rxDeg, 1f, 0f, 0f)
        Matrix.translateM(m1, 0, m2, 0, 0f, oy, 0f)
        Matrix.scaleM(model, 0, m1, 0, sx, sy, sz)
        Matrix.rotateM(rot, 0, bR, 0, rxDeg, 1f, 0f, 0f)
        Matrix.multiplyMM(mvp, 0, vp, 0, model, 0)

        GLES20.glUniformMatrix4fv(uMVP, 1, false, mvp, 0)
        GLES20.glUniformMatrix4fv(uModel, 1, false, model, 0)
        GLES20.glUniformMatrix4fv(uRot, 1, false, rot, 0)
        GLES20.glUniform3f(uScale, max(sx, 0.0001f), max(sy, 0.0001f), max(sz, 0.0001f))
        GLES20.glUniform3f(
            uColor,
            ((cor shr 16) and 255) / 255f, ((cor shr 8) and 255) / 255f, (cor and 255) / 255f
        )
        GLES20.glUniform1f(uEmis, emis)
        GLES20.glUniform1f(uAlpha, alpha)
        GLES20.glUniform1f(uGrid, sGrid)
        GLES20.glUniform1f(uSpec, sSpec)
        GLES20.glUniform1f(uRim, sRim)
        GLES20.glUniform1f(uBlob, sBlob)

        m.buf.position(0)
        GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 24, m.buf)
        m.buf.position(3)
        GLES20.glVertexAttribPointer(aNor, 3, GLES20.GL_FLOAT, false, 24, m.buf)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, m.count)
    }

    /** Peça solta no mundo, sem giro. */
    private fun caixa(
        m: Mesh?, cx: Float, cy: Float, cz: Float, sx: Float, sy: Float, sz: Float,
        cor: Int, emis: Float
    ) {
        setBase(cx, cy, cz, 0f)
        peca(m, 0f, 0f, 0f, 0f, 0f, sx, sy, sz, cor, emis, 1f)
    }

    private fun corDe(kind: Int): Int = when (kind) {
        Game3D.K_RED -> C_RED
        Game3D.K_DARKRED -> C_DARKRED
        Game3D.K_BLACK -> C_BLACK
        Game3D.K_WHITE -> C_WHITE
        Game3D.K_SPARK -> C_SPARK
        Game3D.K_BRASS -> C_BRASS
        else -> C_GREY
    }

    // ------------------------------------------------------------------
    // Cena
    // ------------------------------------------------------------------

    private fun desenharCena() {
        GLES20.glUseProgram(prog)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glEnableVertexAttribArray(aNor)
        sSpec = 0f
        sRim = 0f
        sGrid = 0f
        sBlob = 0f

        // câmera (com coice do tiro)
        val pit = g.pitch + g.kick * 0.02f
        val cp = cos(pit)
        val fx = sin(g.yaw) * cp
        val fy = sin(pit)
        val fz = cos(g.yaw) * cp
        var ex = g.px
        var ey = Game3D.EYE + sin(g.bob) * 0.04f
        var ez = g.pz
        if (g.state == Game3D.S_DEAD) {
            ey -= min(1.1f, g.stateT * 1.2f)
        }
        if (g.shake > 0f) {
            val s = g.shake * 0.12f
            ex += (rng.nextFloat() - 0.5f) * s
            ey += (rng.nextFloat() - 0.5f) * s
            ez += (rng.nextFloat() - 0.5f) * s
        }
        Matrix.setLookAtM(view, 0, ex, ey, ez, ex + fx, ey + fy, ez + fz, 0f, 1f, 0f)
        Matrix.multiplyMM(vpWorld, 0, proj, 0, view, 0)
        vp = vpWorld

        GLES20.glUniform3f(uCam, ex, ey, ez)
        GLES20.glUniform1f(uFog, 0.032f)
        GLES20.glUniform3f(uFogColor, 0.914f, 0.925f, 0.949f)
        GLES20.glUniform3f(uLight, -0.45f, 0.85f, 0.35f)

        desenharSala()
        desenharSombras()
        for (e in g.enemies) desenharInimigo(e)
        desenharDebris()
        desenharBalas()
        desenharEfeitos()

        if (g.state != Game3D.S_DEAD) desenharArma()
    }

    private fun desenharSala() {
        val ax = Game3D.AX
        val az = Game3D.AZ
        val cubo = cubeM
        val rbox = rboxM
        // chão com grade sutil
        sGrid = 1f
        sSpec = 0.25f
        caixa(cubo, 0f, -0.25f, 0f, ax * 2f, 0.5f, az * 2f, C_FLOOR, 0f)
        sGrid = 0f
        sSpec = 0f
        // paredes
        val esp = 0.5f
        caixa(cubo, 0f, 2f, az + esp / 2f, ax * 2f + esp * 2f, 4f, esp, C_WALL, 0.55f)
        caixa(cubo, 0f, 2f, -az - esp / 2f, ax * 2f + esp * 2f, 4f, esp, C_WALL, 0.55f)
        caixa(cubo, ax + esp / 2f, 2f, 0f, esp, 4f, az * 2f, C_WALL, 0.55f)
        caixa(cubo, -ax - esp / 2f, 2f, 0f, esp, 4f, az * 2f, C_WALL, 0.55f)
        // rodapé arredondado
        val ri = 0.14f
        caixa(rbox, 0f, 0.17f, az - ri / 2f, ax * 2f, 0.34f, ri, C_TRIM, 0.6f)
        caixa(rbox, 0f, 0.17f, -az + ri / 2f, ax * 2f, 0.34f, ri, C_TRIM, 0.6f)
        caixa(rbox, ax - ri / 2f, 0.17f, 0f, ri, 0.34f, az * 2f, C_TRIM, 0.6f)
        caixa(rbox, -ax + ri / 2f, 0.17f, 0f, ri, 0.34f, az * 2f, C_TRIM, 0.6f)
        // teto e luminárias
        caixa(cubo, 0f, 4.15f, 0f, ax * 2f + 1f, 0.3f, az * 2f + 1f, C_CEIL, 0.7f)
        for (i in -1..1) {
            caixa(rbox, i * 6f, 3.97f, 0f, 1.2f, 0.06f, az * 1.6f, C_WHITE, 1f)
        }
        // blocos (pilares e caixotes com quinas suaves)
        estilo(0.35f, 0.25f)
        for (b in g.blocks) {
            val w = b.x1 - b.x0
            val d = b.z1 - b.z0
            caixa(rbox, (b.x0 + b.x1) / 2f, b.h / 2f, (b.z0 + b.z1) / 2f, w, b.h, d, C_BLOCK, 0f)
            caixa(rbox, (b.x0 + b.x1) / 2f, 0.12f, (b.z0 + b.z1) / 2f, w + 0.06f, 0.24f, d + 0.06f, C_GREY, 0.1f)
        }
        estilo(0f, 0f)
    }

    private fun desenharSombras() {
        GLES20.glDepthMask(false)
        sBlob = 1f
        for (e in g.enemies) {
            setBase(e.x, 0.02f, e.z, 0f)
            peca(cubeM, 0f, 0f, 0f, 0f, 0f, 1.9f * e.scale, 0.01f, 1.9f * e.scale, C_BLACK, 0f, 0.30f)
        }
        if (g.state != Game3D.S_DEAD) {
            setBase(g.px, 0.02f, g.pz, 0f)
            peca(cubeM, 0f, 0f, 0f, 0f, 0f, 1.3f, 0.01f, 1.3f, C_BLACK, 0f, 0.22f)
        }
        // sombras dos pedaços grandes
        for (s in g.debris) {
            if (s.size > 0.2f && s.y < 1.5f) {
                setBase(s.x, 0.02f, s.z, 0f)
                peca(cubeM, 0f, 0f, 0f, 0f, 0f, s.size * 1.6f, 0.01f, s.size * 1.6f, C_BLACK, 0f, 0.22f)
            }
        }
        sBlob = 0f
        GLES20.glDepthMask(true)
    }

    private fun desenharInimigo(e: Game3D.Enemy) {
        val s = e.scale
        val esf = sphereM
        val rbox = rboxM
        val flash = e.flash > 0f
        val k = (e.shot / 0.3f).coerceIn(0f, 1f)
        val tele = e.tele
        val pulso = if (tele) 0.5f + 0.5f * sin(clk * 22f) else 0f
        val larg = e.girth

        // recuo do corpo ao atirar + balanço leve
        val bx = e.x - sin(e.yaw) * 0.14f * s * k
        val bz = e.z - cos(e.yaw) * 0.14f * s * k
        val bob = sin(clk * 3f + e.x) * 0.015f * s
        setBase(bx, bob, bz, e.yaw * RAD)

        val corpo = if (flash) C_WHITE else C_RED
        val membro = if (flash) C_WHITE else C_DARKRED
        val cabeca = if (flash) C_WHITE else C_RED_LIGHT
        val emis = if (flash) 0.85f else 0.16f + 0.5f * pulso
        val sw = sin(e.walk) * 30f
        val armado = e.type != 1
        val subir = -(78f + 22f * k)

        estilo(0.6f, 0.55f)
        // pernas e pés
        peca(esf, -0.14f * s, 0.84f * s, 0f, sw, -0.40f * s, 0.22f * s * larg, 0.84f * s, 0.25f * s, membro, emis, 1f)
        peca(esf, 0.14f * s, 0.84f * s, 0f, -sw, -0.40f * s, 0.22f * s * larg, 0.84f * s, 0.25f * s, membro, emis, 1f)
        peca(esf, -0.14f * s, 0.84f * s, 0f, sw, -0.82f * s, 0.23f * s * larg, 0.14f * s, 0.38f * s, corpo, emis, 1f)
        peca(esf, 0.14f * s, 0.84f * s, 0f, -sw, -0.82f * s, 0.23f * s * larg, 0.14f * s, 0.38f * s, corpo, emis, 1f)
        // pelve, tronco e cabeça
        peca(esf, 0f, 0.92f * s, 0f, 0f, 0f, 0.48f * s * larg, 0.32f * s, 0.34f * s, membro, emis, 1f)
        peca(esf, 0f, 1.26f * s, 0f, 0f, 0f, 0.64f * s * larg, 0.80f * s, 0.40f * s, corpo, emis, 1f)
        peca(esf, 0f, 1.84f * s, 0.02f * s, 0f, 0f, 0.35f * s, 0.37f * s, 0.37f * s, cabeca, emis, 1f)
        // visor escuro (mostra para onde ele olha)
        estilo(0.9f, 0.2f)
        peca(esf, 0f, 1.86f * s, 0.14f * s, 0f, 0f, 0.27f * s, 0.10f * s, 0.18f * s, C_BLACK, 0f, 1f)
        estilo(0.6f, 0.55f)

        // ombros
        val ombroX = 0.36f * s * larg
        peca(esf, -ombroX, 1.54f * s, 0f, 0f, 0f, 0.24f * s, 0.24f * s, 0.24f * s, membro, emis, 1f)
        peca(esf, ombroX, 1.54f * s, 0f, 0f, 0f, 0.24f * s, 0.24f * s, 0.24f * s, membro, emis, 1f)

        // braços: direito (lado -X) levanta a arma; esquerdo acompanha
        val anguloD = if (armado) subir else -sw
        val anguloE = if (e.type == 3) subir else if (armado) -30f else sw
        peca(esf, -ombroX, 1.52f * s, 0f, anguloD, -0.30f * s, 0.17f * s, 0.66f * s, 0.17f * s, membro, emis, 1f)
        peca(esf, -ombroX, 1.52f * s, 0f, anguloD, -0.64f * s, 0.19f * s, 0.19f * s, 0.19f * s, corpo, emis, 1f)
        peca(esf, ombroX, 1.52f * s, 0f, anguloE, -0.30f * s, 0.17f * s, 0.66f * s, 0.17f * s, membro, emis, 1f)
        peca(esf, ombroX, 1.52f * s, 0f, anguloE, -0.64f * s, 0.19f * s, 0.19f * s, 0.19f * s, corpo, emis, 1f)

        if (armado) {
            // pistola preta na mão
            estilo(0.8f, 0.15f)
            peca(rbox, -ombroX, 1.52f * s, 0f, anguloD, -0.82f * s, 0.11f * s, 0.36f * s, 0.15f * s, C_GUN, 0f, 1f)
            peca(rbox, -ombroX, 1.52f * s, 0f, anguloD, -0.80f * s, 0.07f * s, 0.34f * s, 0.07f * s, C_GUN2, 0f, 1f)
            if (e.type == 3) {
                peca(rbox, ombroX, 1.52f * s, 0f, anguloE, -0.82f * s, 0.11f * s, 0.36f * s, 0.15f * s, C_GUN, 0f, 1f)
            }
            estilo(0.6f, 0.55f)
        }

        if (e.type == 2) {
            // ombreiras do brutamonte
            peca(esf, -ombroX, 1.62f * s, 0f, 0f, 0f, 0.34f * s, 0.2f * s, 0.4f * s, membro, emis, 1f)
            peca(esf, ombroX, 1.62f * s, 0f, 0f, 0f, 0.34f * s, 0.2f * s, 0.4f * s, membro, emis, 1f)
        } else if (e.type == 3) {
            // coroa de espetos e núcleo brilhante do chefe
            for (i in -1..1) {
                peca(esf, i * 0.12f * s, 2.14f * s, 0f, 0f, 0f, 0.09f * s, 0.34f * s, 0.09f * s, membro, emis, 1f)
            }
            peca(esf, -ombroX, 1.66f * s, 0f, 0f, 0f, 0.4f * s, 0.24f * s, 0.46f * s, membro, emis, 1f)
            peca(esf, ombroX, 1.66f * s, 0f, 0f, 0f, 0.4f * s, 0.24f * s, 0.46f * s, membro, emis, 1f)
            peca(esf, 0f, 1.3f * s, 0.2f * s, 0f, 0f, 0.26f * s, 0.26f * s, 0.14f * s, C_SPARK, 1f, 1f)
        }
        estilo(0f, 0f)

        // carga do tiro e clarão na boca da arma
        if (armado) {
            if (tele) {
                val tam = (0.10f + 0.16f * pulso) * s
                peca(esf, -ombroX, 1.52f * s, 0f, anguloD, -1.04f * s, tam * 2f, tam * 2f, tam * 2f, C_RED, 1f, 0.35f)
                peca(esf, -ombroX, 1.52f * s, 0f, anguloD, -1.04f * s, tam, tam, tam, C_WHITE, 1f, 0.9f)
            }
            if (k > 0.35f) {
                val f = (k - 0.35f) / 0.65f
                val tam = (0.42f * f + 0.1f) * s
                GLES20.glDepthMask(false)
                peca(esf, -ombroX, 1.52f * s, 0f, anguloD, -1.06f * s, tam * 1.8f, tam * 1.8f, tam * 1.8f, C_SPARK, 1f, 0.45f * f)
                peca(esf, -ombroX, 1.52f * s, 0f, anguloD, -1.06f * s, tam, tam, tam, C_WHITE, 1f, 1f)
                GLES20.glDepthMask(true)
            }
        }

        // raio de mira vermelho enquanto carrega o tiro
        if (armado && tele) {
            val ox = e.muzzleX()
            val oy = e.muzzleY()
            val oz = e.muzzleZ()
            val dx = g.px - ox
            val dy = 1.2f - oy
            val dz = g.pz - oz
            val dist = max(0.5f, sqrt(dx * dx + dy * dy + dz * dz))
            val comp = min(dist, 16f)
            val ux = dx / dist
            val uy = dy / dist
            val uz = dz / dist
            val yaw = atan2(dx, dz) * RAD
            val pit = -asin(uy.coerceIn(-1f, 1f)) * RAD
            GLES20.glDepthMask(false)
            setBase(ox + ux * comp / 2f, oy + uy * comp / 2f, oz + uz * comp / 2f, yaw)
            peca(esf, 0f, 0f, 0f, pit, 0f, 0.035f, 0.035f, comp, C_RED, 1f, 0.18f + 0.22f * pulso)
            GLES20.glDepthMask(true)
        }

        // barra de vida flutuante (brutamonte e chefe feridos)
        if (e.type >= 2 && e.hp < e.maxHp) {
            setBase(bx, 0f, bz, e.yaw * RAD)
            val frac = e.hp.toFloat() / e.maxHp
            val larguraBarra = 0.9f * s
            val y = 2.5f * s
            peca(rbox, 0f, y, 0f, 0f, 0f, larguraBarra, 0.07f, 0.07f, C_BLACK, 1f, 0.35f)
            peca(rbox, -(larguraBarra * (1f - frac)) / 2f, y, 0f, 0f, 0f, max(0.02f, larguraBarra * frac), 0.08f, 0.08f, C_RED, 1f, 1f)
        }
    }

    private fun desenharDebris() {
        for (s in g.debris) {
            val f = min(1f, s.life / 0.5f)
            if (f <= 0f) continue
            val t = s.size * f
            val malha = if (s.shape == 1) sphereM else rboxM
            setBase(s.x, s.y, s.z, s.rot)
            when (s.kind) {
                Game3D.K_SPARK -> {
                    peca(malha, 0f, 0f, 0f, s.rot * 1.7f, 0f, t, t, t, C_SPARK, 1f, f)
                }
                Game3D.K_BRASS -> {
                    estilo(0.9f, 0.2f)
                    peca(malha, 0f, 0f, 0f, s.rot * 1.7f, 0f, t * 0.6f, t * 0.6f, t * 1.6f, C_BRASS, 0f, 1f)
                    estilo(0f, 0f)
                }
                else -> {
                    estilo(0.6f, 0.45f)
                    peca(malha, 0f, 0f, 0f, s.rot * 1.7f, 0f, t, t * 0.85f, t, corDe(s.kind), 0.15f, 1f)
                    estilo(0f, 0f)
                }
            }
        }
    }

    private fun desenharBalas() {
        val esf = sphereM
        GLES20.glDepthMask(false)
        for (b in g.bullets) {
            if (b.dead) continue
            val total = sqrt(b.vx * b.vx + b.vy * b.vy + b.vz * b.vz)
            if (total < 0.0001f) continue
            val dx = b.vx / total
            val dy = b.vy / total
            val dz = b.vz / total
            val yaw = atan2(b.vx, b.vz) * RAD
            val pit = -asin(dy.coerceIn(-1f, 1f)) * RAD
            if (b.mine) {
                val w = 0.07f + b.radius * 0.5f
                // rastro luminoso que desbota
                for (i in 0 until 7) {
                    val t = 0.30f + i * 0.34f
                    val f = 1f - i / 7f
                    setBase(b.x - dx * t, b.y - dy * t, b.z - dz * t, yaw)
                    peca(esf, 0f, 0f, 0f, pit, 0f, w * (0.55f + 0.5f * f), w * (0.55f + 0.5f * f), 0.5f, C_TRACER, 1f, 0.5f * f)
                }
                setBase(b.x, b.y, b.z, yaw)
                peca(esf, 0f, 0f, 0f, pit, 0f, w * 2.4f, w * 2.4f, w * 4f, C_TRACER, 1f, 0.35f)
                peca(esf, 0f, 0f, 0f, pit, 0f, w * 1.3f, w * 1.3f, 0.6f, C_SPARK, 1f, 1f)
                peca(esf, 0f, 0f, 0f, pit, 0f, w * 0.7f, w * 0.7f, 0.4f, C_WHITE, 1f, 1f)
            } else {
                val pulso = 0.5f + 0.5f * sin(clk * 14f + b.x * 3f + b.z * 2f)
                val r = b.radius
                // rastro curto de brasas
                for (i in 1..5) {
                    val t = i * 0.26f
                    val f = 1f - i / 6f
                    setBase(b.x - dx * t, b.y - dy * t, b.z - dz * t, yaw)
                    peca(esf, 0f, 0f, 0f, pit, 0f, r * 1.6f * f, r * 1.6f * f, r * 1.6f * f, C_RED, 1f, 0.4f * f)
                }
                setBase(b.x, b.y, b.z, yaw)
                // halo pulsante, miolo vermelho e núcleo branco
                peca(esf, 0f, 0f, 0f, pit, 0f, r * (3.4f + 0.9f * pulso), r * (3.4f + 0.9f * pulso), r * (3.4f + 0.9f * pulso), C_RED, 1f, 0.20f)
                peca(esf, 0f, 0f, 0f, pit, 0f, r * 2.1f, r * 2.1f, r * 2.1f, C_RED_LIGHT, 1f, 0.7f)
                peca(esf, 0f, 0f, 0f, pit, 0f, r * 1.1f, r * 1.1f, r * 1.1f, C_WHITE, 1f, 1f)
            }
        }
        GLES20.glDepthMask(true)
    }

    /** Efeitos de impacto: choque de balas, acerto e morte (ondas de choque e clarões). */
    private fun desenharEfeitos() {
        val esf = sphereM
        GLES20.glDepthMask(false)
        for (f in g.fx) {
            val p = (f.t / f.maxT).coerceIn(0f, 1f)
            val q = 1f - p
            setBase(f.x, f.y, f.z, 0f)
            when (f.kind) {
                0 -> {
                    // clarão central + bolas de fogo + três ondas de choque em planos diferentes
                    val bola = f.size * (0.7f + 1.6f * sqrt(p))
                    peca(esf, 0f, 0f, 0f, 0f, 0f, bola * 1.8f, bola * 1.8f, bola * 1.8f, C_SPARK, 1f, 0.35f * q)
                    peca(esf, 0f, 0f, 0f, 0f, 0f, bola, bola, bola, C_SPARK, 1f, 0.8f * q)
                    val miolo = f.size * 0.9f * q
                    peca(esf, 0f, 0f, 0f, 0f, 0f, miolo, miolo, miolo, C_WHITE, 1f, q)
                    val d = f.size * (0.5f + 6f * sqrt(p))
                    val a = 0.7f * q * q
                    peca(esf, 0f, 0f, 0f, 0f, 0f, d, d * 0.04f, d, C_WHITE, 1f, a)
                    peca(esf, 0f, 0f, 0f, 0f, 0f, d, d, d * 0.04f, C_SPARK, 1f, a)
                    peca(esf, 0f, 0f, 0f, 0f, 0f, d * 0.04f, d, d, C_RED_LIGHT, 1f, a)
                    // segunda onda, mais lenta e maior
                    val d2 = f.size * (0.2f + 8.5f * p)
                    peca(esf, 0f, 0f, 0f, 0f, 0f, d2, d2 * 0.03f, d2, C_SPARK, 1f, 0.35f * q * q)
                }
                1 -> {
                    val bola = f.size * (0.5f + 1.2f * sqrt(p))
                    peca(esf, 0f, 0f, 0f, 0f, 0f, bola, bola, bola, C_WHITE, 1f, 0.9f * q)
                    peca(esf, 0f, 0f, 0f, 0f, 0f, bola * 1.8f, bola * 1.8f, bola * 1.8f, C_RED_LIGHT, 1f, 0.4f * q)
                }
                else -> {
                    // morte: clarão vermelho + anel no chão
                    val bola = f.size * (0.5f + 1.4f * sqrt(p))
                    peca(esf, 0f, 0f, 0f, 0f, 0f, bola, bola, bola, C_RED_LIGHT, 1f, 0.55f * q)
                    peca(esf, 0f, 0f, 0f, 0f, 0f, bola * 0.5f, bola * 0.5f, bola * 0.5f, C_WHITE, 1f, 0.9f * q)
                    setBase(f.x, 0.05f, f.z, 0f)
                    val d = f.size * (0.5f + 4.5f * sqrt(p))
                    peca(esf, 0f, 0f, 0f, 0f, 0f, d, 0.02f, d, C_RED, 1f, 0.5f * q * q)
                    val d2 = d * 0.7f
                    peca(esf, 0f, 0f, 0f, 0f, 0f, d2, 0.02f, d2, C_WHITE, 1f, 0.35f * q)
                }
            }
        }
        GLES20.glDepthMask(true)
    }

    /** Pistola em primeira pessoa, no espaço da câmera, por cima de tudo. */
    private fun desenharArma() {
        GLES20.glClear(GLES20.GL_DEPTH_BUFFER_BIT)
        System.arraycopy(proj, 0, vpGun, 0, 16)
        vp = vpGun
        GLES20.glUniform1f(uFog, 0f)
        GLES20.glUniform3f(uCam, 0f, 0f, 0f)
        GLES20.glUniform3f(uLight, 0.3f, 0.8f, 0.6f)

        val rbox = rboxM
        val esf = sphereM
        val bobX = sin(g.bob * 0.5f) * 0.012f
        val bobY = abs(sin(g.bob)) * 0.014f
        val coice = g.recoil

        // base da arma no espaço da câmera (olhando para -Z)
        Matrix.setIdentityM(bR, 0)
        Matrix.rotateM(bR, 0, 3f, 0f, 1f, 0f)
        Matrix.setIdentityM(m1, 0)
        Matrix.translateM(m1, 0, 0.17f + bobX, -0.17f - bobY, -0.42f + coice * 0.06f)
        Matrix.multiplyMM(bA, 0, m1, 0, bR, 0)

        val rx = coice * 10f
        estilo(0.9f, 0.35f)
        // armação, ferrolho (recua no tiro), cano e cabo
        peca(rbox, 0f, 0f, 0f, rx, 0f, 0.07f, 0.08f, 0.30f, C_GUN, 0.03f, 1f)
        peca(rbox, 0f, 0.058f, coice * 0.05f, rx, 0f, 0.064f, 0.05f, 0.30f, C_GUN2, 0.03f, 1f)
        peca(esf, 0f, 0.05f, -0.17f, rx, 0f, 0.034f, 0.034f, 0.07f, C_BLACK, 0f, 1f)
        peca(rbox, 0f, -0.095f, 0.095f, rx + 14f, 0f, 0.064f, 0.15f, 0.08f, C_GUN, 0.03f, 1f)
        // mão e antebraço de luva
        estilo(0.35f, 0.4f)
        peca(esf, 0f, -0.115f, 0.115f, rx + 8f, 0f, 0.105f, 0.12f, 0.125f, C_GUN2, 0f, 1f)
        peca(esf, 0.03f, -0.2f, 0.32f, 10f, 0f, 0.10f, 0.10f, 0.46f, C_GUN, 0f, 1f)
        estilo(0f, 0f)

        // clarão do tiro (estrela + brilho) na boca do cano
        if (g.flashT > 0f) {
            val k = (g.flashT / 0.09f).coerceIn(0f, 1f)
            val big = 0.07f + 0.10f * k
            GLES20.glDepthMask(false)
            peca(esf, 0f, 0.05f, -0.24f, rx, 0f, big * 2.6f, big * 2.6f, big * 2.6f, C_SPARK, 1f, 0.45f * k)
            peca(esf, 0f, 0.05f, -0.24f, rx, 0f, big * 1.5f, big * 1.5f, big * 1.5f, C_SPARK, 1f, 0.9f * k)
            peca(esf, 0f, 0.05f, -0.25f, rx, 0f, big, big, big, C_WHITE, 1f, 1f)
            // pontas da estrela
            peca(esf, 0f, 0.05f, -0.26f, rx, 0f, big * 0.25f, big * 0.25f, big * 3.6f, C_WHITE, 1f, 0.9f * k)
            peca(esf, 0f, 0.05f, -0.26f, rx, 0f, big * 3.2f, big * 0.25f, big * 0.25f, C_WHITE, 1f, 0.9f * k)
            peca(esf, 0f, 0.05f, -0.26f, rx, 0f, big * 0.25f, big * 3.0f, big * 0.25f, C_WHITE, 1f, 0.9f * k)
            GLES20.glDepthMask(true)
        }
        vp = vpWorld
    }
}
