package io.github.yunuserdemsihhatli.homecast.browser

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Pulls a web page address out of links other apps share with or open in HomeCast. */
object SharedLink {
    private val WEB_URL = Regex("""https?://[^\s<>"]+""", RegexOption.IGNORE_CASE)
    private const val TRAILING_PUNCTUATION = ".,;:!?)]}'"

    /** The first http(s) URL in shared text (ACTION_SEND), without sentence punctuation stuck to its end. */
    fun firstWebUrl(text: String?): String? =
        text
            ?.let { WEB_URL.find(it)?.value }
            ?.trimEnd { it in TRAILING_PUNCTUATION }
            ?.let(::webUrlOrNull)

    /** [uri] when it is an http(s) URL (ACTION_VIEW data); anything else (intent:, file:, ...) is refused. */
    fun webUrlOrNull(uri: String?): String? = uri?.takeIf { it.toHttpUrlOrNull() != null }
}
