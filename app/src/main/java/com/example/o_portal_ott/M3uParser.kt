package com.example.o_portal_ott

import android.util.Log

object M3uParser {
    fun parse(m3uText: String): List<Channel> {
        val channels = mutableListOf<Channel>()

        // Временные переменные для хранения данных из #EXTINF
        var currentName = ""
        var currentLogo = ""
        var currentTvgId = ""
        var currentTvgName = ""
        var currentCatchupDays = 0
        var currentCatchupSource = ""
        var                 currentGroupTitle = extractGroupTitle(trimmedLine)

        m3uText.lines().forEach { line ->
            val trimmedLine = line.trim()

            if (trimmedLine.startsWith("#EXTINF")) {
                // Извлекаем название канала (всегда после последней запятой)
                currentName = trimmedLine.substringAfterLast(",").trim()

                // Извлекаем tvg-logo
                currentLogo = trimmedLine.substringAfter("tvg-logo=\"", "").substringBefore("\"")

                // Извлекаем tvg-id
                currentTvgId = trimmedLine.substringAfter("tvg-id=\"", "").substringBefore("\"")

                // Извлекаем tvg-name (часто используется в EPG как альтернативный ID)
                currentTvgName = trimmedLine.substringAfter("tvg-name=\"", "").substringBefore("\"")
                currentCatchupDays = trimmedLine.substringAfter("catchup-days=\"", "").substringBefore("\"").toIntOrNull()
                    ?: trimmedLine.substringAfter("catchup-days=", "").substringBefore(" ").trim().toIntOrNull()
                    ?: 0
                currentCatchupSource = trimmedLine.substringAfter("catchup-source=\"", "").substringBefore("\"")
                    .ifBlank {
                        trimmedLine.substringAfter("catchup-source=", "").substringBefore(" ").trim()
                    }

            } else if (trimmedLine.startsWith("http") && !trimmedLine.contains("x-tvg-url")) {
                val resolvedName = currentName.ifBlank {
                    when {
                        currentTvgName.isNotBlank() -> currentTvgName
                        currentTvgId.isNotBlank() -> currentTvgId
                        else -> "Без названия"
                    }
                }
                // Создаем объект Channel, передавая ВСЕ параметры в правильном порядке
                if (debugLogged < 3) {
                    Log.d("M3U_GROUP", "EXTINF_RAW=$trimmedLine PARSED_GROUP_TITLE=$currentGroupTitle CHANNEL_NAME=$resolvedName")
                    debugLogged++
                }
                channels.add(
                    Channel(
                        name = resolvedName,
                        url = trimmedLine,
                        tvgId = if (currentTvgId.isEmpty()) null else currentTvgId,
                        tvgName = if (currentTvgName.isEmpty()) null else currentTvgName,
                        logoFromPlaylist = if (currentLogo.isEmpty()) null else currentLogo,
                        groupTitle = currentGroupTitle.ifBlank { null },
                        catchupDays = currentCatchupDays,
                        catchupSource = currentCatchupSource.ifBlank { null }
                    )
                )
                // Сбрасываем временные данные для следующего канала
                currentName = ""; currentLogo = ""; currentTvgId = ""; currentTvgName = ""; currentCatchupDays = 0; currentCatchupSource = "";                 currentGroupTitle = extractGroupTitle(trimmedLine)
            }
        }
        return channels
    }
}


    private fun extractGroupTitle(extInfLine: String): String {
        val patterns = listOf(
            Regex("""group-title\s*=\s*"([^"]*)"""", RegexOption.IGNORE_CASE),
            Regex("""group-title\s*=\s*'([^']*)'""", RegexOption.IGNORE_CASE),
            Regex("""group-title\s*=\s*([^,\s]+)""", RegexOption.IGNORE_CASE)
        )
        for (pattern in patterns) {
            val match = pattern.find(extInfLine) ?: continue
            val value = match.groupValues.getOrNull(1)?.trim()?.trim('"', ''') ?: continue
            if (value.isNotBlank()) return value
        }
        return ""
    }
