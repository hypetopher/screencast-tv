package com.screencast.tv.airplay

/**
 * Simple parser for Apple plist data (XML format and text key-value format).
 * Handles the subset of plist used by AirPlay protocol.
 */
object PlistParser {

    fun parse(data: String): Map<String, Any> {
        val trimmed = data.trim()
        return when {
            trimmed.startsWith("<?xml") || trimmed.startsWith("<plist") || trimmed.startsWith("<!DOCTYPE") ->
                parseXmlPlist(trimmed)
            trimmed.contains("Content-Location:") || trimmed.contains("Start-Position:") ->
                parseTextHeaders(trimmed)
            else -> parseSimpleKeyValue(trimmed)
        }
    }

    fun parseBytes(data: ByteArray): Map<String, Any> {
        // Check for binary plist magic "bplist"
        if (data.size >= 6 && String(data, 0, 6) == "bplist") {
            // Binary plist - try to extract just the URL if present
            return extractFromBinaryPlist(data)
        }
        return parse(String(data))
    }

    private fun parseXmlPlist(xml: String): Map<String, Any> {
        val result = mutableMapOf<String, Any>()
        // Simple regex-based XML plist parser
        val keyRegex = Regex("<key>([^<]+)</key>\\s*<(\\w+)>([^<]*)</\\2>", RegexOption.DOT_MATCHES_ALL)
        for (match in keyRegex.findAll(xml)) {
            val key = match.groupValues[1]
            val type = match.groupValues[2]
            val value = match.groupValues[3]
            result[key] = when (type) {
                "real" -> value.toDoubleOrNull() ?: 0.0
                "integer" -> value.toLongOrNull() ?: 0L
                "string" -> value
                "true" -> true
                "false" -> false
                else -> value
            }
        }
        // Handle <true/> and <false/> tags
        val boolRegex = Regex("<key>([^<]+)</key>\\s*<(true|false)\\s*/>")
        for (match in boolRegex.findAll(xml)) {
            result[match.groupValues[1]] = match.groupValues[2] == "true"
        }
        return result
    }

    private fun parseTextHeaders(text: String): Map<String, Any> {
        val result = mutableMapOf<String, Any>()
        for (line in text.lines()) {
            val colonIndex = line.indexOf(':')
            if (colonIndex > 0) {
                val key = line.substring(0, colonIndex).trim()
                val value = line.substring(colonIndex + 1).trim()
                result[key] = value.toDoubleOrNull() ?: value
            }
        }
        return result
    }

    private fun parseSimpleKeyValue(text: String): Map<String, Any> {
        val result = mutableMapOf<String, Any>()
        for (line in text.lines()) {
            val parts = line.split("=", limit = 2)
            if (parts.size == 2) {
                val key = parts[0].trim()
                val value = parts[1].trim()
                result[key] = value.toDoubleOrNull() ?: value
            }
        }
        if (result.isEmpty() && text.isNotBlank()) {
            // Might just be a URL
            result["Content-Location"] = text.trim()
        }
        return result
    }

    private fun extractFromBinaryPlist(data: ByteArray): Map<String, Any> {
        // Very basic binary plist extraction - look for URL strings
        val result = mutableMapOf<String, Any>()
        val str = String(data, Charsets.ISO_8859_1)
        val urlRegex = Regex("(https?://[^\\x00-\\x1F]+)")
        val urlMatch = urlRegex.find(str)
        if (urlMatch != null) {
            result["Content-Location"] = urlMatch.value
        }
        return result
    }

    fun buildPlaybackInfoPlist(
        position: Double,
        duration: Double,
        rate: Double,
        isPlaying: Boolean
    ): String {
        return """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>duration</key>
    <real>$duration</real>
    <key>position</key>
    <real>$position</real>
    <key>rate</key>
    <real>$rate</real>
    <key>readyToPlay</key>
    <${if (isPlaying || duration > 0) "true" else "false"}/>
    <key>playbackBufferEmpty</key>
    <false/>
    <key>playbackBufferFull</key>
    <true/>
    <key>playbackLikelyToKeepUp</key>
    <true/>
    <key>loadedTimeRanges</key>
    <array>
        <dict>
            <key>duration</key>
            <real>$duration</real>
            <key>start</key>
            <real>0.0</real>
        </dict>
    </array>
    <key>seekableTimeRanges</key>
    <array>
        <dict>
            <key>duration</key>
            <real>$duration</real>
            <key>start</key>
            <real>0.0</real>
        </dict>
    </array>
</dict>
</plist>"""
    }
}
