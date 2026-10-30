package com.rojea.ytdd.core

import android.content.Context
import android.content.Intent
import android.net.Uri

/** The "buy me a coffee" link, shown in Settings (and hinted at on the home screen). */
object Support {
    // TODO: replace with Rojea's own page (e.g. https://ko-fi.com/<name>) before release.
    const val URL = "https://ko-fi.com/"

    fun open(context: Context) = runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
