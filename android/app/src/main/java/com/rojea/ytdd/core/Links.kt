package com.rojea.ytdd.core

import java.net.URI

/** Where a link points. Only used for labels and colours — yt-dlp decides what it can download. */
enum class Platform(val label: String, val color: Long) {
    YouTube("YouTube", 0xFFFF0033),
    TikTok("TikTok", 0xFF25F4EE),
    Instagram("Instagram", 0xFFE1306C),
    X("X / Twitter", 0xFF1D9BF0),
    Facebook("Facebook", 0xFF1877F2),
    Reddit("Reddit", 0xFFFF4500),
    Snapchat("Snapchat", 0xFFFFC700),
    Pinterest("Pinterest", 0xFFE60023),
    Threads("Threads", 0xFF8A8A8A),
    Vimeo("Vimeo", 0xFF1AB7EA),
    Twitch("Twitch", 0xFF9146FF),
    SoundCloud("SoundCloud", 0xFFFF5500),
    Other("Web", 0xFF8A8F98);

    companion object {
        fun fromUrl(url: String): Platform {
            val host = runCatching { URI(url).host }.getOrNull()?.lowercase()
                ?.removePrefix("www.")?.removePrefix("m.") ?: return Other
            fun h(vararg d: String) = d.any { host == it || host.endsWith(".$it") }
            return when {
                h("youtube.com", "youtu.be", "youtube-nocookie.com") -> YouTube
                h("tiktok.com") -> TikTok
                h("instagram.com", "instagr.am") -> Instagram
                h("x.com", "twitter.com", "t.co", "fxtwitter.com", "vxtwitter.com") -> X
                h("facebook.com", "fb.watch", "fb.com") -> Facebook
                h("reddit.com", "redd.it") -> Reddit
                h("snapchat.com") -> Snapchat
                h("pinterest.com", "pin.it") -> Pinterest
                h("threads.net", "threads.com") -> Threads
                h("vimeo.com") -> Vimeo
                h("twitch.tv") -> Twitch
                h("soundcloud.com") -> SoundCloud
                else -> Other
            }
        }
    }
}

object Links {
    // Apps share links wrapped in text ("Check out this video! https://vm.tiktok.com/xyz"), and
    // people copy bare links without the scheme ("youtu.be/abc"), so accept both.
    private val urlRe = Regex(
        """(?i)\b(?:https?://[^\s<>"'`]+|(?:www\.)?(?:youtu\.be|youtube\.com|(?:vm\.|vt\.)?tiktok\.com|x\.com|twitter\.com|instagram\.com|facebook\.com|fb\.watch|reddit\.com|redd\.it|pin\.it|threads\.net)/[^\s<>"'`]+)"""
    )

    fun extract(text: CharSequence?): String? {
        val m = urlRe.find(text ?: return null) ?: return null
        var url = m.value.trimEnd('.', ',', ')', ']', '!', '?', ';', ':', '"', '\'', '>')
        if (!url.startsWith("http", ignoreCase = true)) url = "https://$url"
        return url
    }
}
