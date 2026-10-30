package com.rojea.ytdd.core

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.widget.Toast

/** Getting a finished file out of the app: WhatsApp, the share menu, or a player. */
object Send {
    private val whatsappPackages = listOf("com.whatsapp", "com.whatsapp.w4b")

    /** WhatsApp, or WhatsApp Business if that's the only one installed. Null if neither. */
    fun whatsapp(context: Context): String? = whatsappPackages.firstOrNull { pkg ->
        runCatching { context.packageManager.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0)) }.isSuccess
    }

    private fun sendIntent(item: Saved, pkg: String? = null) = Intent(Intent.ACTION_SEND).apply {
        type = item.mime
        putExtra(Intent.EXTRA_STREAM, item.contentUri)
        clipData = ClipData.newRawUri(item.title, item.contentUri) // carries the read grant
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        pkg?.let { setPackage(it) }
    }

    /**
     * Opens WhatsApp's own picker, which lists "My status" first and then chats — so this one
     * button covers both "post to status" and "send to a friend".
     */
    fun toWhatsApp(context: Context, item: Saved) {
        val pkg = whatsapp(context) ?: return share(context, item)
        start(context, sendIntent(item, pkg))
    }

    fun share(context: Context, item: Saved) =
        start(context, Intent.createChooser(sendIntent(item), null))

    fun play(context: Context, item: Saved) = start(
        context,
        Intent(Intent.ACTION_VIEW).setDataAndType(item.contentUri, item.mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
    )

    fun copyLink(context: Context, item: Saved) {
        val cm = context.getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText("Link", item.url))
    }

    private fun start(context: Context, intent: Intent) {
        if (context !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(context, "No app can open this", Toast.LENGTH_SHORT).show()
        }
    }
}
