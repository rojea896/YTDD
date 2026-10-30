package com.rojea.ytdd.download

import com.rojea.ytdd.core.Platform

/** Turns yt-dlp's stderr into a short headline and one plain sentence. */
object Errors {
    data class Friendly(
        val title: String,
        val message: String,
        /** yt-dlp's own last error line, shown behind "Details". */
        val detail: String?,
        val retryable: Boolean,
    )

    fun explain(raw: String?, platform: Platform = Platform.Other): Friendly {
        val text = raw.orEmpty()
        val last = text.lines().lastOrNull { it.startsWith("ERROR:") }?.removePrefix("ERROR:")?.trim()
            ?: text.lines().lastOrNull { it.isNotBlank() }?.trim()
        val t = text.lowercase()
        val site = platform.label.takeIf { platform != Platform.Other } ?: "This site"
        fun has(vararg s: String) = s.any { it in t }
        fun f(title: String, message: String, retry: Boolean) = Friendly(title, message, last, retry)
        return when {
            has("unable to resolve host", "network is unreachable", "failed to resolve", "no address associated",
                "connection refused", "timed out", "connection reset", "temporary failure in name resolution") ->
                f("No internet connection", "Check your data or Wi-Fi, then try again.", true)
            has("unsupported url") ->
                f("Can't download this link", "YTDD couldn't find a video at that address. Try sharing the post itself.", false)
            has("private video", "this video is private", "login required", "log in", "logged-in", "sign in to confirm",
                "use --cookies", "cookies", "account authentication", "this content isn't available to everyone",
                "requested content is not available") ->
                f("This post needs a sign-in", "$site only shows it to logged-in accounts, so YTDD can't save it. Public posts work.", false)
            has("there is no video in this post", "no video formats found", "no video could be found") ->
                f("There's no video in this post", "Only posts with a video or sound can be saved.", false)
            has("video unavailable", "this video is unavailable", "has been removed", "http error 404", "does not exist",
                "not available in your country", "geo restriction", "this video is not available") ->
                f("This video isn't available", "It may have been deleted, made private or blocked in your country.", false)
            has("http error 429", "too many requests", "rate-limit") ->
                f("$site is limiting downloads", "Wait a few minutes, then try again.", true)
            has("no space left") -> f("Your phone is full", "Free up some space, then try again.", true)
            else -> f("Couldn't save this video", "Something went wrong. Try again, or tap Details to see why.", true)
        }
    }
}
