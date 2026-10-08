package com.tempocongelado.app

import android.content.Context
import android.content.pm.PackageInfo
import android.os.Build

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

@Suppress("DEPRECATION")
fun Context.pacote(): PackageInfo = packageManager.getPackageInfo(packageName, 0)

@Suppress("DEPRECATION")
fun Context.versaoCodigo(): Long {
    val pi = pacote()
    return if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()
}

fun Context.versaoNome(): String = pacote().versionName ?: "?"
