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
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Desenha o mundo 3D com OpenGL ES 2.0, tudo feito de caixas (sem arquivos de modelo ou textura).
 * Visual: sala clara com neblina, inimigos vermelhos de aparência cristalina, sombras suaves.
 */
class Renderer3D(private val g: Game3D) : GLSurfaceView.Renderer {

    private val VERT = """
        uniform mat4 uMVP;
        uniform mat4 uModel;
        uniform mat4 uRot;
        attribute vec3 aPos;
        attribute vec3 aNor;
        varying vec3 vNor;
        varying vec3 vWorld;
        void main() {
            vec4 w = uModel * vec4(aPos, 1.0);
            vWorld = w.xyz;
            vNor = (uRot * vec4(aNor, 0.0)).xyz;
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
        varying vec3 vNor;
        varying vec3 vWorld;
        void main() {
            vec3 n = normalize(vNor);
            float d = max(dot(n, normalize(uLight)), 0.0);
            float hemi = 0.5 + 0.5 * n.y;
            vec3 col = uColor * (0.46 + 0.40 * d + 0.14 * hemi);
            col = mix(col, uColor, uEmis);
            if (uGrid > 0.5) {
                vec2 q = abs(fract(vWorld.xz / 2.0) - 0.5);
                float line = smoothstep(0.465, 0.5, max(q.x, q.y));
                col *= (1.0 - 0.13 * line);
            }
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
    private val C_BLOCK = 0xE9ECF2
    private val C_RED = 0xE5322D
    private val C_DARKRED = 0x9C1F1B
    private val C_WHITE = 0xFFFFFF
    private val C_BLACK = 0x15171C
    private val C_GREY = 0x9AA0AE
    private val C_GUN = 0x1B1D23
    private val C_GUN2 = 0x30343D

    private var prog = 0
    private var aPos = 0
    private var aNor = 0
    private var uMVP = 0
    private var uModel = 0
    private var uRot = 0
    private var uColor = 0
    private var uLight = 0
    private var uFogColor = 0
    private var uCam = 0
    private var uFog = 0
    private var uEmis = 0
    private var uAlpha = 0
    private var uGrid = 0

    private var cube: FloatBuffer? = null

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
    private val ident = FloatArray(16)

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
        uColor = GLES20.glGetUniformLocation(prog, "uColor")
        uLight = GLES20.glGetUniformLocation(prog, "uLight")
        uFogColor = GLES20.glGetUniformLocation(prog, "uFogColor")
        uCam = GLES20.glGetUniformLocation(prog, "uCam")
        uFog = GLES20.glGetUniformLocation(prog, "uFog")
        uEmis = GLES20.glGetUniformLocation(prog, "uEmis")
        uAlpha = GLES20.glGetUniformLocation(prog, "uAlpha")
        uGrid = GLES20.glGetUniformLocation(prog, "uGrid")

        cube = montarCubo()
        Matrix.setIdentityM(ident, 0)

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

    /** Cubo de lado 1 centrado na origem: 36 vértices com posição e normal. */
    private fun montarCubo(): FloatBuffer {
        // cada face: normal (3), eixo u (3), eixo v (3)
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
        val bb = ByteBuffer.allocateDirect(dados.size * 4).order(ByteOrder.nativeOrder())
        val fb = bb.asFloatBuffer()
        fb.put(dados)
        fb.position(0)
        return fb
    }

    // ------------------------------------------------------------------
    // Utilidades de desenho
    // ------------------------------------------------------------------

    /** Define a base (posição + giro em Y, em graus) para as peças seguintes. */
    private fun setBase(x: Float, y: Float, z: Float, yawDeg: Float) {
        Matrix.setIdentityM(bR, 0)
        Matrix.rotateM(bR, 0, yawDeg, 0f, 1f, 0f)
        Matrix.setIdentityM(m1, 0)
        Matrix.translateM(m1, 0, x, y, z)
        Matrix.multiplyMM(bA, 0, m1, 0, bR, 0)
    }

    /**
     * Desenha uma caixa: base * T(tx,ty,tz) * Rx(rxDeg) * T(0,oy,0) * S(sx,sy,sz).
     * O pivô do giro em X é o ponto (tx,ty,tz); oy desloca a caixa depois do giro.
     */
    private fun peca(
        tx: Float, ty: Float, tz: Float, rxDeg: Float, oy: Float,
        sx: Float, sy: Float, sz: Float,
        cor: Int, emis: Float, alpha: Float, grid: Float
    ) {
        Matrix.translateM(m1, 0, bA, 0, tx, ty, tz)
        Matrix.rotateM(m2, 0, m1, 0, rxDeg, 1f, 0f, 0f)
        Matrix.translateM(m1, 0, m2, 0, 0f, oy, 0f)
        Matrix.scaleM(model, 0, m1, 0, sx, sy, sz)
        Matrix.rotateM(rot, 0, bR, 0, rxDeg, 1f, 0f, 0f)
        Matrix.multiplyMM(mvp, 0, vp, 0, model, 0)

        GLES20.glUniformMatrix4fv(uMVP, 1, false, mvp, 0)
        GLES20.glUniformMatrix4fv(uModel, 1, false, model, 0)
        GLES20.glUniformMatrix4fv(uRot, 1, false, rot, 0)
        GLES20.glUniform3f(
            uColor,
            ((cor shr 16) and 255) / 255f, ((cor shr 8) and 255) / 255f, (cor and 255) / 255f
        )
        GLES20.glUniform1f(uEmis, emis)
        GLES20.glUniform1f(uAlpha, alpha)
        GLES20.glUniform1f(uGrid, grid)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 36)
    }

    /** Caixa solta no mundo, sem giro. */
    private fun caixa(
        cx: Float, cy: Float, cz: Float, sx: Float, sy: Float, sz: Float,
        cor: Int, emis: Float, grid: Float
    ) {
        setBase(cx, cy, cz, 0f)
        peca(0f, 0f, 0f, 0f, 0f, sx, sy, sz, cor, emis, 1f, grid)
    }

    private fun corDe(kind: Int): Int = when (kind) {
        Game3D.K_RED -> C_RED
        Game3D.K_DARKRED -> C_DARKRED
        Game3D.K_BLACK -> C_BLACK
        Game3D.K_WHITE -> C_WHITE
        else -> C_GREY
    }

    // ------------------------------------------------------------------
    // Cena
    // ------------------------------------------------------------------

    private fun desenharCena() {
        val buf = cube ?: return
        GLES20.glUseProgram(prog)

        buf.position(0)
        GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 24, buf)
        GLES20.glEnableVertexAttribArray(aPos)
        buf.position(3)
        GLES20.glVertexAttribPointer(aNor, 3, GLES20.GL_FLOAT, false, 24, buf)
        GLES20.glEnableVertexAttribArray(aNor)

        // câmera
        val cp = cos(g.pitch)
        val fx = sin(g.yaw) * cp
        val fy = sin(g.pitch)
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
        desenharBalas()
        desenharDebris()

        if (g.state != Game3D.S_DEAD) desenharArma()
    }

    private fun desenharSala() {
        val ax = Game3D.AX
        val az = Game3D.AZ
        // chão com grade sutil
        caixa(0f, -0.25f, 0f, ax * 2f, 0.5f, az * 2f, C_FLOOR, 0f, 1f)
        // paredes
        val esp = 0.5f
        caixa(0f, 2f, az + esp / 2f, ax * 2f + esp * 2f, 4f, esp, C_WALL, 0.55f, 0f)
        caixa(0f, 2f, -az - esp / 2f, ax * 2f + esp * 2f, 4f, esp, C_WALL, 0.55f, 0f)
        caixa(ax + esp / 2f, 2f, 0f, esp, 4f, az * 2f, C_WALL, 0.55f, 0f)
        caixa(-ax - esp / 2f, 2f, 0f, esp, 4f, az * 2f, C_WALL, 0.55f, 0f)
        // rodapé escuro
        val ri = 0.04f
        caixa(0f, 0.18f, az - ri / 2f, ax * 2f, 0.36f, ri, C_TRIM, 0.6f, 0f)
        caixa(0f, 0.18f, -az + ri / 2f, ax * 2f, 0.36f, ri, C_TRIM, 0.6f, 0f)
        caixa(ax - ri / 2f, 0.18f, 0f, ri, 0.36f, az * 2f, C_TRIM, 0.6f, 0f)
        caixa(-ax + ri / 2f, 0.18f, 0f, ri, 0.36f, az * 2f, C_TRIM, 0.6f, 0f)
        // teto e luminárias
        caixa(0f, 4.15f, 0f, ax * 2f + 1f, 0.3f, az * 2f + 1f, C_CEIL, 0.7f, 0f)
        for (i in -1..1) {
            caixa(i * 6f, 3.98f, 0f, 1.2f, 0.04f, az * 1.6f, C_WHITE, 1f, 0f)
        }
        // blocos (pilares e caixotes)
        for (b in g.blocks) {
            val w = b.x1 - b.x0
            val d = b.z1 - b.z0
            caixa((b.x0 + b.x1) / 2f, b.h / 2f, (b.z0 + b.z1) / 2f, w, b.h, d, C_BLOCK, 0f, 0f)
            // faixa escura na base para dar peso
            caixa((b.x0 + b.x1) / 2f, 0.12f, (b.z0 + b.z1) / 2f, w + 0.03f, 0.24f, d + 0.03f, C_GREY, 0.1f, 0f)
        }
    }

    private fun desenharSombras() {
        GLES20.glDepthMask(false)
        for (e in g.enemies) {
            setBase(e.x, 0.02f, e.z, 0f)
            peca(0f, 0f, 0f, 0f, 0f, 1.5f * e.scale, 0.01f, 1.5f * e.scale, C_BLACK, 0f, 0.16f, 0f)
        }
        if (g.state != Game3D.S_DEAD) {
            setBase(g.px, 0.02f, g.pz, 0f)
            peca(0f, 0f, 0f, 0f, 0f, 1.0f, 0.01f, 1.0f, C_BLACK, 0f, 0.12f, 0f)
        }
        GLES20.glDepthMask(true)
    }

    private fun desenharInimigo(e: Game3D.Enemy) {
        val s = e.scale
        setBase(e.x, 0f, e.z, e.yaw * RAD)
        val flash = e.flash > 0f
        val corpo = if (flash) C_WHITE else C_RED
        val escuro = if (flash) C_WHITE else C_DARKRED
        val pulso = if (e.tele) 0.5f * abs(sin(clk * 18f)) else 0f
        val emis = 0.28f + pulso
        val balanco = sin(e.walk) * 28f

        // pernas (pivô no quadril)
        peca(-0.15f * s, 0.82f * s, 0f, balanco, -0.41f * s, 0.2f * s, 0.82f * s, 0.22f * s, escuro, emis, 1f, 0f)
        peca(0.15f * s, 0.82f * s, 0f, -balanco, -0.41f * s, 0.2f * s, 0.82f * s, 0.22f * s, escuro, emis, 1f, 0f)
        // tronco e cabeça
        peca(0f, 1.2f * s, 0f, 0f, 0f, 0.56f * s, 0.7f * s, 0.3f * s, corpo, emis, 1f, 0f)
        peca(0f, 1.76f * s, 0f, 0f, 0f, 0.3f * s, 0.3f * s, 0.3f * s, corpo, emis, 1f, 0f)
        // braços (pivô no ombro): apontando para o jogador, ou balançando no perseguidor
        val braco = if (e.type == 1) -balanco else -78f
        peca(-0.36f * s, 1.5f * s, 0f, braco, -0.3f * s, 0.14f * s, 0.62f * s, 0.14f * s, escuro, emis, 1f, 0f)
        peca(0.36f * s, 1.5f * s, 0f, braco, -0.3f * s, 0.14f * s, 0.62f * s, 0.14f * s, escuro, emis, 1f, 0f)

        if (e.type == 2) {
            // ombreiras do brutamonte
            peca(-0.38f * s, 1.58f * s, 0f, 0f, 0f, 0.24f * s, 0.14f * s, 0.34f * s, escuro, emis, 1f, 0f)
            peca(0.38f * s, 1.58f * s, 0f, 0f, 0f, 0.24f * s, 0.14f * s, 0.34f * s, escuro, emis, 1f, 0f)
        } else if (e.type == 3) {
            // coroa de espetos do chefe
            for (i in -1..1) {
                peca(i * 0.1f * s, 2.02f * s, 0f, 0f, 0f, 0.07f * s, 0.26f * s, 0.07f * s, escuro, emis, 1f, 0f)
            }
            peca(-0.42f * s, 1.58f * s, 0f, 0f, 0f, 0.26f * s, 0.16f * s, 0.38f * s, escuro, emis, 1f, 0f)
            peca(0.42f * s, 1.58f * s, 0f, 0f, 0f, 0.26f * s, 0.16f * s, 0.38f * s, escuro, emis, 1f, 0f)
        }

        // barra de vida flutuante (brutamonte e chefe feridos)
        if (e.type >= 2 && e.hp < e.maxHp) {
            val frac = e.hp.toFloat() / e.maxHp
            val larg = 0.9f * s
            val y = 2.35f * s
            peca(0f, y, 0f, 0f, 0f, larg, 0.06f, 0.06f, C_BLACK, 1f, 0.35f, 0f)
            peca(-(larg * (1f - frac)) / 2f, y, 0f, 0f, 0f, larg * frac, 0.07f, 0.07f, C_RED, 1f, 1f, 0f)
        }
    }

    private fun desenharBalas() {
        for (b in g.bullets) {
            if (b.dead) continue
            val total = sqrt(b.vx * b.vx + b.vy * b.vy + b.vz * b.vz)
            if (total < 0.0001f) continue
            val yaw = atan2(b.vx, b.vz) * RAD
            val pit = -asin((b.vy / total).coerceIn(-1f, 1f)) * RAD
            setBase(b.x, b.y, b.z, yaw)
            if (b.mine) {
                val w = b.radius * 1.3f
                peca(0f, 0f, 0f, pit, 0f, w, w, 0.7f, C_BLACK, 0.3f, 1f, 0f)
            } else {
                val w = b.radius * 2.2f
                // halo translúcido + núcleo brilhante
                peca(0f, 0f, 0f, pit, 0f, w * 1.7f, w * 1.7f, w * 2.8f, C_RED, 1f, 0.25f, 0f)
                peca(0f, 0f, 0f, pit, 0f, w, w, w * 2.2f, C_RED, 1f, 1f, 0f)
                peca(0f, 0f, 0f, pit, 0f, w * 0.45f, w * 0.45f, w * 1.6f, C_WHITE, 1f, 1f, 0f)
            }
        }
    }

    private fun desenharDebris() {
        for (s in g.debris) {
            val f = min(1f, s.life / 0.8f)
            if (f <= 0f) continue
            val t = s.size * f
            setBase(s.x, s.y, s.z, s.rot)
            peca(0f, 0f, 0f, s.rot * 1.7f, 0f, t, t * 0.6f, t, corDe(s.kind), 0.25f, 1f, 0f)
        }
    }

    /** Pistola em primeira pessoa, desenhada no espaço da câmera, por cima de tudo. */
    private fun desenharArma() {
        GLES20.glClear(GLES20.GL_DEPTH_BUFFER_BIT)
        System.arraycopy(proj, 0, vpGun, 0, 16)
        vp = vpGun
        GLES20.glUniform1f(uFog, 0f)
        GLES20.glUniform3f(uLight, 0.3f, 0.8f, 0.6f)

        val bobX = sin(g.bob * 0.5f) * 0.012f
        val bobY = abs(sin(g.bob)) * 0.014f
        val coice = g.recoil

        // base da arma no espaço da câmera (olhando para -Z)
        Matrix.setIdentityM(bR, 0)
        Matrix.rotateM(bR, 0, 3f, 0f, 1f, 0f)
        Matrix.setIdentityM(m1, 0)
        Matrix.translateM(m1, 0, 0.19f + bobX, -0.2f - bobY, -0.5f + coice * 0.07f)
        Matrix.multiplyMM(bA, 0, m1, 0, bR, 0)

        val rx = coice * 9f
        // cano, ferrolho, cabo e braço
        peca(0f, 0f, 0f, rx, 0f, 0.07f, 0.09f, 0.36f, C_GUN, 0.05f, 1f, 0f)
        peca(0f, 0.065f, 0f, rx, 0f, 0.055f, 0.045f, 0.34f, C_GUN2, 0.05f, 1f, 0f)
        peca(0f, -0.1f, 0.1f, rx + 14f, 0f, 0.065f, 0.16f, 0.08f, C_GUN, 0.05f, 1f, 0f)
        peca(0.02f, -0.17f, 0.22f, 8f, 0f, 0.1f, 0.12f, 0.5f, C_GUN2, 0.05f, 1f, 0f)

        // clarão do tiro
        if (coice > 0.6f) {
            val k = (coice - 0.6f) / 0.4f
            peca(0f, 0.01f, -0.26f, 0f, 0f, 0.07f * k + 0.02f, 0.07f * k + 0.02f, 0.1f, C_WHITE, 1f, 0.9f, 0f)
            peca(0f, 0.01f, -0.3f, 0f, 0f, 0.12f * k + 0.02f, 0.12f * k + 0.02f, 0.06f, C_RED, 1f, 0.7f, 0f)
        }
        vp = vpWorld
    }
}
