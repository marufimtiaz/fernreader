package app.epubreader.reader

/**
 * F-044: an external link the reader may hand to another app. [label] names the target in the confirmation: the host
 * for http(s), the address for mailto ([mail]).
 */
data class ExternalLink(val url: String, val label: String, val mail: Boolean)

/**
 * Classifies a link the web side reported as external. Only http and https with a host, and mailto with an address,
 * may leave the app; every other scheme (javascript:, file:, content:, intent:, data:, ...) gives null and is ignored.
 * The web side filters the same way; this is the app's own check (the bridge is not trusted with intents).
 */
fun externalLinkTarget(href: String): ExternalLink? {
    val h = href.trim()
    val colon = h.indexOf(':')
    if (colon <= 0) return null
    val scheme = h.substring(0, colon).lowercase()
    val rest = h.substring(colon + 1)
    val url = "$scheme:$rest"
    return when (scheme) {
        "http", "https" -> {
            if (!rest.startsWith("//")) return null
            val authority = rest.substring(2).takeWhile { it != '/' && it != '?' && it != '#' }
            val hostPort = authority.substringAfterLast('@')
            val host = if (hostPort.startsWith("[")) hostPort.substringBefore(']') + "]" else hostPort.substringBefore(':')
            if (host.isEmpty() || host.any { it.isWhitespace() }) null else ExternalLink(url, host.lowercase(), mail = false)
        }
        "mailto" -> {
            val address = rest.substringBefore('?').trim()
            if (address.isEmpty()) null else ExternalLink(url, address, mail = true)
        }
        else -> null
    }
}
