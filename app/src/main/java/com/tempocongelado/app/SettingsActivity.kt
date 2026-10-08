package com.tempocongelado.app

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat

class SettingsActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvEstatisticas: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        tvStatus = findViewById(R.id.tvStatus)
        tvEstatisticas = findViewById(R.id.tvEstatisticas)

        findViewById<TextView>(R.id.btnVoltar).setOnClickListener { finish() }

        val sw = findViewById<SwitchCompat>(R.id.swVibracao)
        sw.isChecked = Prefs.vibracao(this)
        sw.setOnCheckedChangeListener { _, marcado -> Prefs.setVibracao(this, marcado) }

        findViewById<TextView>(R.id.btnAtualizar).setOnClickListener {
            Updater.verificar(this, true) { msg -> tvStatus.text = msg }
        }

        findViewById<TextView>(R.id.btnNovidades).setOnClickListener {
            Novidades.mostrar(this, Novidades.carregar(this), "O que mudou")
        }

        findViewById<TextView>(R.id.btnApagar).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Apagar recorde?")
                .setMessage("Isso zera seu recorde, partidas e abates. Não dá para desfazer.")
                .setPositiveButton("Apagar") { _, _ ->
                    Prefs.apagarRecorde(this)
                    atualizarEstatisticas()
                }
                .setNegativeButton("Cancelar", null)
                .show()
        }

        findViewById<TextView>(R.id.tvVersao).text =
            "Tempo Congelado • versão " + versaoNome() + " (código " + versaoCodigo() + ")"
        atualizarEstatisticas()
    }

    override fun onResume() {
        super.onResume()
        Updater.retomar(this)
    }

    private fun atualizarEstatisticas() {
        tvEstatisticas.text =
            "Melhor sala: " + Prefs.melhorSala(this) + "\n" +
                "Partidas: " + Prefs.partidas(this) + "\n" +
                "Inimigos abatidos: " + Prefs.abatesTotais(this)
    }
}
