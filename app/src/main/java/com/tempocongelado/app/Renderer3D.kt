package com.tempocongelado.app

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/** Executa a DrawList montada pela Scene. Todo o visual está em Scene/Models/Rig/Environment. */
class Renderer3D(private val g: Game3D) : GLSurfaceView.Renderer {

    private val VERT = """
uniform mat4 uMVP; uniform mat4 uModel;
attribute vec3 aPos; attribute vec3 aNor; attribute vec3 aCol;
varying vec3 vNor; varying vec3 vWorld; varying vec3 vLocal; varying vec3 vCol;
void main(){
  vec4 w = uModel * vec4(aPos,1.0);
  vWorld = w.xyz; vLocal = aPos; vCol = aCol;
  vNor = (uModel * vec4(aNor,0.0)).xyz;
  gl_Position = uMVP * vec4(aPos,1.0);
}"""
    private val FRAG = """
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
uniform vec3 uColor; uniform vec3 uLight; uniform vec3 uFogColor; uniform vec3 uCam;
uniform float uFog; uniform float uEmis; uniform float uAlpha; uniform float uGrid;
uniform float uSpec; uniform float uRim; uniform float uHalo; uniform float uBlob; uniform float uUnlit;
varying vec3 vNor; varying vec3 vWorld; varying vec3 vLocal; varying vec3 vCol;
void main(){
  if (uBlob > 0.5) {
    float r = length(vLocal.xz) * 2.0;
    gl_FragColor = vec4(0.0,0.0,0.0, uAlpha * (1.0 - smoothstep(0.1, 1.0, r)));
    return;
  }
  vec3 base = uColor * vCol;
  if (uUnlit > 0.5) {
    float dist0 = length(vWorld - uCam);
    float f0 = clamp(exp(-uFog * dist0 * 0.5), 0.0, 1.0);
    gl_FragColor = vec4(base * f0, uAlpha);
    return;
  }
  vec3 N = normalize(vNor);
  vec3 V = normalize(uCam - vWorld);
  if (dot(N, V) < 0.0 && uHalo < 0.5) N = -N;
  vec3 L = normalize(uLight);
  float diff = max(dot(N, L), 0.0);
  float wrap = max(dot(N, L) * 0.5 + 0.5, 0.0);
  float hemi = 0.5 + 0.5 * N.y;
  float ao = mix(0.78, 1.0, clamp(vWorld.y * 0.6, 0.0, 1.0));
  ao = mix(ao, 1.0, max(N.y, 0.0) * 0.8);
  vec3 col = base * (0.50 + 0.46 * diff + 0.10 * wrap + 0.14 * hemi) * ao;
  if (uGrid > 0.5) {
    vec2 q = abs(fract(vWorld.xz / 2.0) - 0.5);
    float line = smoothstep(0.475, 0.5, max(q.x, q.y));
    col *= (1.0 - 0.14 * line);
  }
  col = mix(col, base, uEmis);
  vec3 H = normalize(L + V);
  float spec = pow(max(dot(N, H), 0.0), 36.0) * uSpec * (1.0 - uEmis);
  float fres = pow(1.0 - max(dot(N, V), 0.0), 2.2);
  col += vec3(spec) + (base * 0.7 + 0.3) * fres * uRim;
  float dist = length(vWorld - uCam);
  float f = clamp(exp(-uFog * dist), 0.0, 1.0);
  gl_FragColor = vec4(mix(uFogColor, col, f), uAlpha);
}"""

    private val dl = DrawList()
    private val vbo = IntArray(Models.COUNT)
    private val cnt = IntArray(Models.COUNT)
    private val ver = IntArray(Models.COUNT) { -1 }
    private var prog = 0
    private var aPos = 0; private var aNor = 0; private var aCol = 0
    private val u = HashMap<String, Int>()
    private var w = 1; private var h = 1
    private var last = 0L
    private var clk = 0f
    private val proj = FloatArray(16)
    private val vp = FloatArray(16)
    private val mvp = FloatArray(16)
    private val idm = FloatArray(16)

    private fun sh(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        return s
    }

    private fun U(n: String): Int = u.getOrPut(n) { GLES20.glGetUniformLocation(prog, n) }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        prog = GLES20.glCreateProgram()
        GLES20.glAttachShader(prog, sh(GLES20.GL_VERTEX_SHADER, VERT))
        GLES20.glAttachShader(prog, sh(GLES20.GL_FRAGMENT_SHADER, FRAG))
        GLES20.glLinkProgram(prog)
        aPos = GLES20.glGetAttribLocation(prog, "aPos")
        aNor = GLES20.glGetAttribLocation(prog, "aNor")
        aCol = GLES20.glGetAttribLocation(prog, "aCol")
        u.clear()
        GLES20.glGenBuffers(Models.COUNT, vbo, 0)
        for (i in ver.indices) ver[i] = -1
        android.opengl.Matrix.setIdentityM(idm, 0)
        last = System.nanoTime()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        w = width; h = height
        GLES20.glViewport(0, 0, w, h)
    }

    private fun upload(i: Int) {
        val d = Models.meshes[i]
        cnt[i] = d.size / 9
        val bb = ByteBuffer.allocateDirect(d.size * 4).order(ByteOrder.nativeOrder())
        bb.asFloatBuffer().put(d)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo[i])
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, d.size * 4, bb, GLES20.GL_STATIC_DRAW)
        ver[i] = Models.version[i]
    }

    private fun run(pass: Int, view: FloatArray, camX: Float, camY: Float, camZ: Float) {
        for (k in 0 until dl.counts[pass]) {
            val c = dl.get(pass, k)
            if (ver[c.mesh] != Models.version[c.mesh]) upload(c.mesh)
            if (cnt[c.mesh] == 0) continue
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo[c.mesh])
            GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 36, 0)
            GLES20.glVertexAttribPointer(aNor, 3, GLES20.GL_FLOAT, false, 36, 12)
            GLES20.glVertexAttribPointer(aCol, 3, GLES20.GL_FLOAT, false, 36, 24)
            M4.mul(mvp, vp, c.m)
            GLES20.glUniformMatrix4fv(U("uMVP"), 1, false, mvp, 0)
            GLES20.glUniformMatrix4fv(U("uModel"), 1, false, c.m, 0)
            GLES20.glUniform3f(U("uColor"), c.r, c.g, c.b)
            GLES20.glUniform1f(U("uEmis"), c.emis)
            GLES20.glUniform1f(U("uAlpha"), if (pass == DrawList.P_REFLECT) c.alpha * 0.35f else c.alpha)
            GLES20.glUniform1f(U("uSpec"), c.spec)
            GLES20.glUniform1f(U("uRim"), c.rim)
            GLES20.glUniform1f(U("uHalo"), c.halo)
            GLES20.glUniform1f(U("uBlob"), c.blob)
            GLES20.glUniform1f(U("uGrid"), c.grid)
            GLES20.glUniform1f(U("uUnlit"), c.unlit)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, cnt[c.mesh])
        }
    }

    override fun onDrawFrame(gl: GL10?) {
        val now = System.nanoTime()
        val dt = ((now - last) / 1e9f).coerceIn(0f, 0.1f)
        last = now
        clk += dt
        val aspect = w.toFloat() / h.toFloat()
        synchronized(g) {
            g.update(dt)
            Scene.build(dl, g, clk, aspect)
        }
        GLES20.glClearColor(dl.fogR, dl.fogG, dl.fogB, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glUseProgram(prog)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glEnableVertexAttribArray(aNor)
        GLES20.glEnableVertexAttribArray(aCol)
        GLES20.glUniform3f(U("uLight"), dl.lx, dl.ly, dl.lz)
        GLES20.glUniform3f(U("uFogColor"), dl.fogR, dl.fogG, dl.fogB)
        GLES20.glUniform1f(U("uFog"), dl.fogDen)

        M4.perspective(proj, dl.fov, aspect, 0.05f, 80f)
        M4.mul(vp, proj, dl.view)
        GLES20.glUniform3f(U("uCam"), dl.ex, dl.ey, dl.ez)
        for (p in 0..5) {
            GLES20.glDepthMask(p < 3)
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, if (p == DrawList.P_ADD) GLES20.GL_ONE else GLES20.GL_ONE_MINUS_SRC_ALPHA)
            run(p, dl.view, dl.ex, dl.ey, dl.ez)
        }

        // arma: sem profundidade da sala, câmera na origem
        GLES20.glDepthMask(true)
        GLES20.glClear(GLES20.GL_DEPTH_BUFFER_BIT)
        M4.perspective(vp, 60f, aspect, 0.02f, 10f)
        GLES20.glUniform3f(U("uCam"), 0f, 0f, 0f)
        GLES20.glUniform1f(U("uFog"), 0f)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        run(DrawList.P_GUN, idm, 0f, 0f, 0f)
        GLES20.glDepthMask(false)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE)
        run(DrawList.P_GUN_ADD, idm, 0f, 0f, 0f)
        GLES20.glDepthMask(true)
    }
}
