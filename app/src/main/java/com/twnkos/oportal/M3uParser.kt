package com.twnkos.oportal

import android.util.Log

object M3uParser {
    private val STREAM_URL_REGEX = Regex(
        """(?i)\b((?:https?|udp|rtp|rtsp|mms|mmsh|mmst|rtmp|rtmps|file)://\S+)"""
    )

    fun parse(m3uText: String): List<Channel> {
        val channels = mutableListOf<Channel>()

        var currentName = ""
        var currentLogo = ""
        var currentTvgId = ""
        var currentTvgName = ""
        var currentCatchupDays = 0
        var currentCatchupSource = ""
        var currentGroupTitle = ""
        var currentExtInfRaw = ""
        var debugLogged = 0
        var awaitingUrl = false

        fun resetCurrent() {
            currentName = ""
            currentLogo = ""
            currentTvgId = ""
            currentTvgName = ""
            currentCatchupDays = 0
            currentCatchupSource = ""
            currentGroupTitle = ""
            currentExtInfRaw = ""
            awaitingUrl = false
        }

        fun addChannel(url: String, nameOverride: String? = null) {
            val resolvedName = nameOverride?.takeIf { it.isNotBlank() }
                ?: currentName.ifBlank {
                    when {
                        currentTvgName.isNotBlank() -> currentTvgName
                        currentTvgId.isNotBlank() -> currentTvgId
                        else -> "Без названия"
                    }
                }

            if (debugLogged < 3) {
                Log.d(
                    "M3U_GROUP",
                    "EXTINF_RAW=$currentExtInfRaw PARSED_GROUP_TITLE=$currentGroupTitle " +
                        "CHANNEL_NAME=$resolvedName URL=$url"
                )
                debugLogged++
            }

            channels.add(
                Channel(
                    name = resolvedName,
                    url = url.trim(),
                    tvgId = currentTvgId.ifBlank { null },
                    tvgName = currentTvgName.ifBlank { null },
                    logoFromPlaylist = currentLogo.ifBlank { null },
                    groupTitle = currentGroupTitle.ifBlank { null },
                    catchupDays = currentCatchupDays,
                    catchupSource = currentCatchupSource.ifBlank { null }
                )
            )
            resetCurrent()
        }

        fun applyExtInf(trimmedLine: String) {
            currentExtInfRaw = trimmedLine
            val afterComma = trimmedLine.substringAfterLast(",", "").trim()
            val inlineUrl = extractStreamUrl(afterComma)
            currentName = if (inlineUrl != null) {
                afterComma.replace(inlineUrl, "").trim().trim(',', ' ', '\t')
            } else {
                afterComma
            }
            currentLogo = trimmedLine.substringAfter("tvg-logo=\"", "").substringBefore("\"")
            currentTvgId = trimmedLine.substringAfter("tvg-id=\"", "").substringBefore("\"")
            currentTvgName = trimmedLine.substringAfter("tvg-name=\"", "").substringBefore("\"")
            currentCatchupDays = trimmedLine.substringAfter("catchup-days=\"", "").substringBefore("\"")
                .toIntOrNull()
                ?: trimmedLine.substringAfter("catchup-days=", "").substringBefore(" ").trim().toIntOrNull()
                ?: 0
            currentCatchupSource = trimmedLine.substringAfter("catchup-source=\"", "").substringBefore("\"")
                .ifBlank {
                    trimmedLine.substringAfter("catchup-source=", "").substringBefore(" ").trim()
                }
            currentGroupTitle = extractGroupTitle(trimmedLine)
            awaitingUrl = true

            // PowerNet / some IPTV apps put the stream URL on the same line as #EXTINF.
            if (inlineUrl != null) {
                addChannel(inlineUrl)
            }
        }

        normalizePlaylistLines(m3uText).forEach { line ->
            val trimmedLine = line.trim()
            if (trimmedLine.isEmpty()) return@forEach

            if (trimmedLine.startsWith("#EXTINF", ignoreCase = true)) {
                applyExtInf(trimmedLine)
            } else if (trimmedLine.startsWith("#")) {
                // Other tags — ignore for channel list.
            } else {
                val url = extractStreamUrl(trimmedLine) ?: trimmedLine.takeIf { looksLikeStreamUrl(it) }
                if (url != null && (awaitingUrl || currentExtInfRaw.isNotBlank())) {
                    addChannel(url)
                }
            }
        }
        return channels
    }

    /**
     * Some playlists (browser "Save as", broken exporters) collapse newlines so many
     * #EXTINF entries sit on one physical line. Re-split on #EXTINF when needed.
     */
    private fun normalizePlaylistLines(m3uText: String): List<String> {
        val normalized = m3uText
            .replace("\r\n", "\n")
            .replace('\r', '\n')
        val lines = normalized.lineSequence().map { it.trimEnd() }.toList()
        val extInfCount = Regex("#EXTINF", RegexOption.IGNORE_CASE).findAll(normalized).count()
        if (extInfCount <= 1 || lines.size >= extInfCount) return lines

        val rebuilt = mutableListOf<String>()
        for (line in lines) {
            if (!line.contains("#EXTINF", ignoreCase = true)) {
                rebuilt += line
                continue
            }
            val parts = line.split(Regex("(?=#EXTINF)", RegexOption.IGNORE_CASE))
            parts.forEach { part ->
                val t = part.trim()
                if (t.isNotEmpty()) rebuilt += t
            }
        }
        return rebuilt
    }

    private fun extractStreamUrl(text: String): String? =
        STREAM_URL_REGEX.find(text)?.groupValues?.getOrNull(1)?.trimEnd(',', ';', ')', ']')

    private fun looksLikeStreamUrl(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty() || t.startsWith("#")) return false
        return t.contains("://") || t.startsWith("//")
    }

    private fun extractGroupTitle(extinf: String): String {
        val quotedDouble = Regex("""group-title\s*=\s*"([^"]*)"""", RegexOption.IGNORE_CASE)
        val quotedSingle = Regex("""group-title\s*=\s*'([^']*)'""", RegexOption.IGNORE_CASE)
        val unquoted = Regex("""group-title\s*=\s*([^, ]+)""", RegexOption.IGNORE_CASE)

        val groupTitle = quotedDouble.find(extinf)?.groupValues?.getOrNull(1)
            ?: quotedSingle.find(extinf)?.groupValues?.getOrNull(1)
            ?: unquoted.find(extinf)?.groupValues?.getOrNull(1)

        return groupTitle?.trim().takeUnless { it.isNullOrEmpty() } ?: "Без категории"
    }
}
