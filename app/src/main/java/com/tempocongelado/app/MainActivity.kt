package com.tempocongelado.app

import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var tvRecorde: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvRecorde = findViewById(R.id.tvRecorde)
        findViewById<TextView>(R.id.tvVersao).text = "Versão " + versaoNome()

        findViewById<TextView>(R.id.btnJogar).setOnClickListener {
            startActivity(Intent(this, GameActivity::class.java))
        }
        findViewById<TextView>(R.id.btnComo).setOnClickListener { mostrarComoJogar() }
        findViewById<TextView>(R.id.btnConfig).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        Novidades.mostrarSeNovo(this)
        Updater.autoCheck(this)
    }

    override fun onResume() {
        super.onResume()
        atualizarRecorde()
        Updater.retomar(this)
    }

    private fun atualizarRecorde() {
        val melhor = Prefs.melhorSala(this)
        tvRecorde.text = if (melhor > 0) {
            "RECORDE: SALA $melhor   •   ${Prefs.abatesTotais(this)} ABATES"
        } else {
            "VÁ ATÉ O FIM DA PRIMEIRA SALA"
        }
    }

    private fun mostrarComoJogar() {
        AlertDialog.Builder(this)
            .setTitle("Como jogar")
            .setMessage(
                "• O tempo só anda quando você se mexe. Parado, tudo quase congela.\n\n" +
                    "• Arraste o dedo na METADE ESQUERDA da tela para mover. Quanto mais longe do ponto inicial, mais rápido o tempo corre.\n\n" +
                    "• Toque (ou segure) na METADE DIREITA para atirar naquela direção.\n\n" +
                    "• Qualquer tiro inimigo ou encostão pode te derrubar. Seus tiros também destroem os tiros deles.\n\n" +
                    "• Limpou a sala? Escolha uma melhoria e siga em frente. A cada 5 salas tem um chefe.\n\n" +
                    "• Morreu? Começa tudo de novo, com outra build."
            )
            .setPositiveButton("Entendi", null)
            .show()
    }
}
