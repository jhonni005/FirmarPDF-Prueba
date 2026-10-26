package com.zonadev.firmarpdf.ui.home

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.zonadev.firmarpdf.R

/** Enlaces externos del menú; nunca genera textos legales ni afirma que la ficha esté publicada. */
internal class HomeAppLinks(private val context: Context) {
    private val playUrl get() = "https://play.google.com/store/apps/details?id=${context.packageName}"

    val version: String get() = try {
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        info.versionName ?: context.getString(R.string.version_unknown)
    } catch (_: Exception) { context.getString(R.string.version_unknown) }

    fun openLegal(urlResource: Int): Int? {
        val url = context.getString(urlResource).trim()
        if (url.isEmpty()) return R.string.legal_link_pending
        return if (open(Intent(Intent.ACTION_VIEW, Uri.parse(url)))) null else R.string.external_link_error
    }

    fun rate(): Int? {
        val opened = open(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${context.packageName}"))) ||
            open(Intent(Intent.ACTION_VIEW, Uri.parse(playUrl)))
        return if (opened) R.string.rating_publication_notice else R.string.rating_unavailable
    }

    fun share(): Int? {
        val intent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, playUrl)
        return if (open(Intent.createChooser(intent, context.getString(R.string.drawer_share)))) null else R.string.share_app_error
    }

    private fun open(intent: Intent): Boolean = try {
        context.startActivity(intent)
        true
    } catch (_: android.content.ActivityNotFoundException) { false
    } catch (_: SecurityException) { false }
}
