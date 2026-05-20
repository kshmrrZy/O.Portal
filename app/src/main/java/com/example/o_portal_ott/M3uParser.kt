package com.example.o_portal_ott

import android.util.Log

object M3uParser {
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

        m3uText.lines().forEach { line ->
            val trimmedLine = line.trim()

            if (trimmedLine.startsWith("#EXTINF")) {
                currentExtInfRaw = trimmedLine
                currentName = trimmedLine.substringAfterLast(",").trim()
                currentLogo = trimmedLine.substringAfter("tvg-logo=\"", "").substringBefore("\"")
                currentTvgId = trimmedLine.substringAfter("tvg-id=\"", "").substringBefore("\"")
                currentTvgName = trimmedLine.substringAfter("tvg-name=\"", "").substringBefore("\"")
                currentCatchupDays = trimmedLine.substringAfter("catchup-days=\"", "").substringBefore("\"").toIntOrNull()
                    ?: trimmedLine.substringAfter("catchup-days=", "").substringBefore(" ").trim().toIntOrNull()
                    ?: 0
                currentCatchupSource = trimmedLine.substringAfter("catchup-source=\"", "").substringBefore("\"")
                    .ifBlank {
                        trimmedLine.substringAfter("catchup-source=", "").substringBefore(" ").trim()
                    }
                currentGroupTitle = extractGroupTitle(trimmedLine)
            } else if (trimmedLine.startsWith("http") && !trimmedLine.contains("x-tvg-url")) {
                val resolvedName = currentName.ifBlank {
                    when {
                        currentTvgName.isNotBlank() -> currentTvgName
                        currentTvgId.isNotBlank() -> currentTvgId
                        else -> "Без названия"
                    }
                }

                if (debugLogged < 3) {
                    Log.d(
                        "M3U_GROUP",
                        "EXTINF_RAW=$currentExtInfRaw PARSED_GROUP_TITLE=$currentGroupTitle CHANNEL_NAME=$resolvedName"
                    )
                    debugLogged++
                }

                channels.add(
                    Channel(
                        name = resolvedName,
                        url = trimmedLine,
                        tvgId = currentTvgId.ifBlank { null },
                        tvgName = currentTvgName.ifBlank { null },
                        logoFromPlaylist = currentLogo.ifBlank { null },
                        groupTitle = currentGroupTitle.ifBlank { null },
                        catchupDays = currentCatchupDays,
                        catchupSource = currentCatchupSource.ifBlank { null }
                    )
                )

                currentName = ""
                currentLogo = ""
                currentTvgId = ""
                currentTvgName = ""
                currentCatchupDays = 0
                currentCatchupSource = ""
                currentGroupTitle = ""
                currentExtInfRaw = ""
            }
        }
        return channels
    }

    private fun extractGroupTitle(extInfLine: String): String {
        val patterns = listOf(
            Regex("""group-title\s*=\s*"([^"]*)""", RegexOption.IGNORE_CASE),
            Regex("""group-title\s*=\s*'([^']*)'""", RegexOption.IGNORE_CASE),
            Regex("""group-title\s*=\s*([^,\s]+)""", RegexOption.IGNORE_CASE)
        )
        for (pattern in patterns) {
            val match = pattern.find(extInfLine) ?: continue
            val value = match.groupValues.getOrNull(1)?.trim()?.trim('"', '\'') ?: continue
            if (value.isNotBlank()) return value
        }
        return ""
    }
}
