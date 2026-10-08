package com.tempocongelado.app

import android.content.Context
import android.content.SharedPreferences

/** Dados salvos no celular (recorde, estatísticas e configurações). */
object Prefs {
    private fun p(c: Context): SharedPreferences =
        c.getSharedPreferences("tempo", Context.MODE_PRIVATE)

    fun melhorSala(c: Context): Int = p(c).getInt("melhor", 0)

    fun partidas(c: Context): Int = p(c).getInt("partidas", 0)

    fun abatesTotais(c: Context): Int = p(c).getInt("abates", 0)

    fun vibracao(c: Context): Boolean = p(c).getBoolean("vibracao", true)

    fun setVibracao(c: Context, v: Boolean) {
        p(c).edit().putBoolean("vibracao", v).apply()
    }

    fun registrar(c: Context, sala: Int, abates: Int) {
        val sp = p(c)
        sp.edit()
            .putInt("melhor", maxOf(sp.getInt("melhor", 0), sala))
            .putInt("partidas", sp.getInt("partidas", 0) + 1)
            .putInt("abates", sp.getInt("abates", 0) + abates)
            .apply()
    }

    fun apagarRecorde(c: Context) {
        p(c).edit().putInt("melhor", 0).putInt("partidas", 0).putInt("abates", 0).apply()
    }
}
