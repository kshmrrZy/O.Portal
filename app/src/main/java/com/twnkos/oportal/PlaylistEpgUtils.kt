package com.twnkos.oportal

object PlaylistEpgUtils {

    fun extractEpgSourcesFromPlaylist(content: String): List<String> {
        if (content.isBlank()) return emptyList()
        val headerLine = content.lineSequence().firstOrNull { line ->
            line.trimStart().startsWith("#EXTM3U", ignoreCase = true)
        }.orEmpty().ifBlank {
            content.take(8192)
        }
        val quoted = Regex(
            """(?i)(?:x-tvg-url|url-tvg|tvg-url)\s*=\s*["“”']([^"“”']+)["“”']"""
        )
        quoted.find(headerLine)?.groupValues?.getOrNull(1)?.let { return split(it) }
        quoted.find(content)?.groupValues?.getOrNull(1)?.let { return split(it) }
        val unquoted = Regex(
            """(?i)(?:x-tvg-url|url-tvg|tvg-url)\s*=\s*([^\s"“”']+)"""
        )
        unquoted.find(headerLine)?.groupValues?.getOrNull(1)?.let { return split(it) }
        val tagName = when {
            content.contains("x-tvg-url=\"", ignoreCase = true) -> "x-tvg-url=\""
            content.contains("url-tvg=\"", ignoreCase = true) -> "url-tvg=\""
            content.contains("tvg-url=\"", ignoreCase = true) -> "tvg-url=\""
            else -> return emptyList()
        }
        val idx = content.indexOf(tagName, ignoreCase = true)
        if (idx < 0) return emptyList()
        return split(content.substring(idx + tagName.length).substringBefore("\""))
    }

    private fun split(raw: String): List<String> =
        raw.split(",")
            .map { it.trim().trim('"').trim('\'') }
            .filter { it.isNotBlank() }
            .distinct()
            .take(3)

    fun buildEpgUrlCandidates(url: String): List<String> {
        val clean = url.trim()
        if (clean.isBlank()) return emptyList()
        val result = linkedSetOf(clean)
        if (!clean.endsWith(".xml.gz", true) && !clean.endsWith(".xml", true)) {
            result += "$clean.xml.gz"
            result += "$clean.xml"
            result += clean.trimEnd('/') + "/xmltv.xml.gz"
            result += clean.trimEnd('/') + "/epg.xml.gz"
        }
        return result.toList()
    }
}
