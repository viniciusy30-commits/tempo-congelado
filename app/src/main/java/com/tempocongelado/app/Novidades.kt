package com.tempocongelado.app

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/** Lê assets/novidades.txt e mostra o resumo "O que mudou". */
object Novidades {

    class Item(val id: String, val titulo: String, val linhas: List<String>)

    fun carregar(c: Context): List<Item> {
        val lista = ArrayList<Item>()
        try {
            val texto = c.assets.open("novidades.txt").bufferedReader().use { it.readText() }
            var id: String? = null
            var titulo = ""
            var linhas = ArrayList<String>()
            for (bruta in texto.lines()) {
                val l = bruta.trim()
                if (l.startsWith("#id ")) {
                    if (id != null) lista.add(Item(id, titulo, linhas))
                    id = l.substring(4).trim()
                    titulo = ""
                    linhas = ArrayList()
                } else if (l.startsWith("#titulo ")) {
                    titulo = l.substring(8).trim()
                } else if (l.startsWith("- ")) {
                    linhas.add(l.substring(2).trim())
                }
            }
            if (id != null) lista.add(Item(id, titulo, linhas))
        } catch (e: Exception) {
            // sem novidades: tudo bem
        }
        return lista
    }

    /** Mostra o resumo na primeira vez que o app abre depois de atualizar. */
    fun mostrarSeNovo(act: AppCompatActivity) {
        val itens = carregar(act)
        if (itens.isEmpty()) return
        val sp = act.getSharedPreferences("novidades", Context.MODE_PRIVATE)
        val visto = sp.getString("visto", null)
        val topo = itens[0].id
        if (visto == topo) return
        sp.edit().putString("visto", topo).apply()
        if (visto == null) return // primeira instalação: nada a mostrar

        val novos = ArrayList<Item>()
        for (it in itens) {
            if (it.id == visto) break
            novos.add(it)
        }
        if (novos.isEmpty()) novos.add(itens[0])
        mostrar(act, novos, "O que mudou")
    }

    fun mostrar(act: AppCompatActivity, itens: List<Item>, titulo: String) {
        val caixa = LinearLayout(act)
        caixa.orientation = LinearLayout.VERTICAL
        caixa.setPadding(act.dp(24), act.dp(8), act.dp(24), act.dp(8))

        if (itens.isEmpty()) {
            val t = TextView(act)
            t.text = "Nada por aqui ainda."
            caixa.addView(t)
        }

        for (item in itens) {
            val h = TextView(act)
            h.text = item.titulo.ifEmpty { item.id }
            h.setTextColor(Color.parseColor("#E5322D"))
            h.textSize = 16f
            h.setTypeface(null, Typeface.BOLD)
            val lpH = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lpH.topMargin = act.dp(12)
            caixa.addView(h, lpH)

            for (linha in item.linhas) {
                val t = TextView(act)
                t.text = "•  $linha"
                t.setTextColor(Color.parseColor("#15171C"))
                t.textSize = 14f
                val lp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
                lp.topMargin = act.dp(6)
                caixa.addView(t, lp)
            }
        }

        val rolagem = ScrollView(act)
        rolagem.addView(caixa)

        AlertDialog.Builder(act)
            .setTitle(titulo)
            .setView(rolagem)
            .setPositiveButton("Beleza", null)
            .show()
    }
}
