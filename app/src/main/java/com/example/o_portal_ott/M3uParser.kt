package com.example.o_portal_ott

object M3uParser {
    fun parse(m3uText: String): List<Channel> {
        val channels = mutableListOf<Channel>()

        // Временные переменные для хранения данных из #EXTINF
        var currentName = ""
        var currentLogo = ""
        var currentTvgId = ""
        var currentTvgName = ""

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

            } else if (trimmedLine.startsWith("http") && !trimmedLine.contains("x-tvg-url")) {
                // Создаем объект Channel, передавая ВСЕ параметры в правильном порядке
                channels.add(
                    Channel(
                        name = currentName,
                        url = trimmedLine,
                        tvgId = if (currentTvgId.isEmpty()) null else currentTvgId,
                        tvgName = if (currentTvgName.isEmpty()) null else currentTvgName,
                        logoFromPlaylist = if (currentLogo.isEmpty()) null else currentLogo
                    )
                )
                // Сбрасываем временные данные для следующего канала
                currentName = ""; currentLogo = ""; currentTvgId = ""; currentTvgName = ""
            }
        }
        return channels
    }
}