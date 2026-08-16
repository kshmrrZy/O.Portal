package com.twnkos.oportal

object PlaylistEpgUtils {

    fun extractEpgSourcesFromPlaylist(content: String): List<String> {
        if (!content.contains("x-tvg-url=\"")) return emptyList()

        return content
            .substringAfter("x-tvg-url=\"", "")
            .substringBefore("\"")
            .split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
    }

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
