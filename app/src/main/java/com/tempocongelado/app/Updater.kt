package com.tempocongelado.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/** Atualização pelo próprio APK, usando a última Release do GitHub. */
object Updater {
    const val REPO = "viniciusy30-commits/tempo-congelado"

    private const val PREFS = "updater"
    private const val SEIS_HORAS = 6L * 60L * 60L * 1000L

    class Info(val tag: Long, val nome: String, val url: String, val tamanho: Long)

    private class SemRelease : Exception()

    /** APK já baixado que está esperando o Android liberar "Instalar apps desconhecidos". */
    private var pendente: File? = null

    /** Ao abrir o app: verifica no máximo a cada 6 horas e só avisa se houver versão nova. */
    fun autoCheck(act: AppCompatActivity) {
        val sp = act.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val ultima = sp.getLong("ultima", 0L)
        val agora = System.currentTimeMillis()
        if (agora - ultima < SEIS_HORAS) return
        verificar(act, false, null)
    }

    /**
     * Verifica se há versão nova.
     * manual = true: mostra mensagens (procurando / já está na mais nova / sem internet).
     */
    fun verificar(act: AppCompatActivity, manual: Boolean, status: ((String) -> Unit)?) {
        fun dizer(msg: String) {
            if (!manual) return
            if (status != null) status(msg) else Toast.makeText(act, msg, Toast.LENGTH_SHORT).show()
        }

        dizer("Procurando atualização…")
        Thread {
            var info: Info? = null
            var falhou = false
            var semRelease = false
            try {
                info = buscarUltima()
            } catch (e: SemRelease) {
                semRelease = true
            } catch (e: Exception) {
                falhou = true
            }

            val encontrada: Info? = info
            val deuErro = falhou
            val naoTem = semRelease

            act.runOnUiThread {
                if (act.isFinishing || act.isDestroyed) return@runOnUiThread
                if (deuErro) {
                    dizer("Sem internet (ou o GitHub não respondeu). Tente de novo.")
                    return@runOnUiThread
                }
                act.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putLong("ultima", System.currentTimeMillis()).apply()

                if (naoTem || encontrada == null) {
                    dizer("Ainda não há nenhuma versão publicada.")
                    return@runOnUiThread
                }
                val instalada = act.versaoCodigo()
                if (encontrada.tag > instalada) {
                    dizer("Nova versão encontrada: ${encontrada.tag}")
                    mostrarDialogo(act, encontrada)
                } else {
                    dizer("Você já está na versão mais nova (${act.versaoNome()}).")
                }
            }
        }.start()
    }

    private fun buscarUltima(): Info {
        val conn = URL("https://api.github.com/repos/$REPO/releases/latest")
            .openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10000
            conn.readTimeout = 15000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", "TempoCongelado")
            val codigo = conn.responseCode
            if (codigo == 404) throw SemRelease()
            if (codigo != 200) throw IOException("HTTP $codigo")
            val texto = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(texto)
            val tag = json.getString("tag_name").trim().removePrefix("v").removePrefix("V")
                .toLongOrNull() ?: throw SemRelease()
            val assets = json.optJSONArray("assets") ?: throw SemRelease()
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                val nome = a.optString("name")
                if (nome.endsWith(".apk", ignoreCase = true)) {
                    return Info(tag, nome, a.getString("browser_download_url"), a.optLong("size"))
                }
            }
            throw SemRelease()
        } finally {
            conn.disconnect()
        }
    }

    private fun formatarTamanho(bytes: Long): String {
        if (bytes <= 0L) return ""
        val mb = bytes / (1024.0 * 1024.0)
        return String.format(java.util.Locale.US, "%.1f MB", mb)
    }

    private fun mostrarDialogo(act: AppCompatActivity, info: Info) {
        val tam = formatarTamanho(info.tamanho)
        val extra = if (tam.isEmpty()) "" else " ($tam)"
        AlertDialog.Builder(act)
            .setTitle("Nova versão disponível")
            .setMessage("A versão ${info.tag} está pronta para instalar$extra.\n\nSeus dados e recordes são mantidos.")
            .setPositiveButton("Atualizar") { _, _ -> baixar(act, info) }
            .setNegativeButton("Depois", null)
            .show()
    }

    private fun baixar(act: AppCompatActivity, info: Info) {
        val barra = ProgressBar(act, null, android.R.attr.progressBarStyleHorizontal)
        barra.max = 100
        barra.isIndeterminate = false
        val texto = TextView(act)
        texto.text = "Baixando… 0%"
        val caixa = LinearLayout(act)
        caixa.orientation = LinearLayout.VERTICAL
        caixa.setPadding(act.dp(24), act.dp(16), act.dp(24), act.dp(8))
        caixa.addView(
            texto,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        val lpBarra = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lpBarra.topMargin = act.dp(12)
        caixa.addView(barra, lpBarra)

        val cancelado = AtomicBoolean(false)
        val dialogo = AlertDialog.Builder(act)
            .setTitle("Baixando atualização")
            .setView(caixa)
            .setCancelable(false)
            .setNegativeButton("Cancelar") { _, _ -> cancelado.set(true) }
            .create()
        dialogo.show()

        Thread {
            var arquivo: File? = null
            var erro = false
            try {
                val pasta = File(act.cacheDir, "updates")
                pasta.mkdirs()
                pasta.listFiles()?.forEach { it.delete() }
                val destino = File(pasta, "atualizacao.apk")

                val conn = URL(info.url).openConnection() as HttpURLConnection
                conn.connectTimeout = 15000
                conn.readTimeout = 20000
                conn.instanceFollowRedirects = true
                conn.setRequestProperty("User-Agent", "TempoCongelado")
                try {
                    if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode}")
                    var total = conn.contentLengthLong
                    if (total <= 0L) total = info.tamanho
                    var lidos = 0L
                    var ultimoPct = -1
                    conn.inputStream.use { entrada ->
                        FileOutputStream(destino).use { saida ->
                            val buf = ByteArray(16 * 1024)
                            while (true) {
                                if (cancelado.get()) break
                                val n = entrada.read(buf)
                                if (n < 0) break
                                saida.write(buf, 0, n)
                                lidos += n
                                if (total > 0L) {
                                    val pct = (lidos * 100L / total).toInt().coerceIn(0, 100)
                                    if (pct != ultimoPct) {
                                        ultimoPct = pct
                                        act.runOnUiThread {
                                            barra.progress = pct
                                            texto.text = "Baixando… $pct%"
                                        }
                                    }
                                }
                            }
                        }
                    }
                } finally {
                    conn.disconnect()
                }
                if (cancelado.get()) {
                    destino.delete()
                } else {
                    arquivo = destino
                }
            } catch (e: Exception) {
                erro = true
            }

            val pronto: File? = arquivo
            val deuErro = erro

            act.runOnUiThread {
                try {
                    dialogo.dismiss()
                } catch (e: Exception) {
                    // janela já fechada
                }
                if (act.isFinishing || act.isDestroyed) return@runOnUiThread
                if (deuErro) {
                    Toast.makeText(act, "Falha no download. Verifique a internet e tente de novo.", Toast.LENGTH_LONG).show()
                } else if (pronto != null) {
                    instalar(act, pronto)
                }
            }
        }.start()
    }

    /** Abre o instalador do Android. Se faltar a permissão, explica e leva à configuração. */
    fun instalar(act: AppCompatActivity, arquivo: File) {
        if (Build.VERSION.SDK_INT >= 26 && !act.packageManager.canRequestPackageInstalls()) {
            pendente = arquivo
            AlertDialog.Builder(act)
                .setTitle("Permitir instalação")
                .setMessage(
                    "Para atualizar, o Android precisa liberar \"Instalar apps desconhecidos\" para o Tempo Congelado.\n\n" +
                        "Toque em \"Abrir configuração\", ative a opção e volte aqui. A instalação continua sozinha."
                )
                .setPositiveButton("Abrir configuração") { _, _ ->
                    val i = Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + act.packageName)
                    )
                    act.startActivity(i)
                }
                .setNegativeButton("Agora não") { _, _ -> pendente = null }
                .show()
            return
        }
        pendente = null
        try {
            val uri = FileProvider.getUriForFile(act, act.packageName + ".fileprovider", arquivo)
            val i = Intent(Intent.ACTION_VIEW)
            i.setDataAndType(uri, "application/vnd.android.package-archive")
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            act.startActivity(i)
        } catch (e: Exception) {
            Toast.makeText(act, "Não consegui abrir o instalador.", Toast.LENGTH_LONG).show()
        }
    }

    /** Chame no onResume: continua a instalação depois que a permissão foi liberada. */
    fun retomar(act: AppCompatActivity) {
        val f = pendente ?: return
        if (Build.VERSION.SDK_INT >= 26 && !act.packageManager.canRequestPackageInstalls()) return
        pendente = null
        if (f.exists()) instalar(act, f)
    }
}
