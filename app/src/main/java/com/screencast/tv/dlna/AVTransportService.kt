package com.screencast.tv.dlna

import android.util.Log
import com.screencast.tv.common.CastEvent

class AVTransportService(
    private val onCastEvent: (CastEvent) -> Unit,
    private val getPositionSec: () -> Double,
    private val getDurationSec: () -> Double,
    private val getIsPlaying: () -> Boolean
) {
    companion object {
        private const val TAG = "AVTransport"
    }

    private var currentUri: String? = null
    private var currentMetadata: String? = null
    private var transportState = "NO_MEDIA_PRESENT"

    fun handleAction(action: String, body: String): String {
        Log.d(TAG, "SOAP action: $action, body length: ${body.length}")
        Log.d(TAG, "SOAP body: $body")
        return when {
            action.contains("SetAVTransportURI") -> handleSetUri(body)
            action.contains("Play") -> handlePlay()
            action.contains("Pause") -> handlePause()
            action.contains("Stop") -> handleStop()
            action.contains("Seek") -> handleSeek(body)
            action.contains("GetTransportInfo") -> getTransportInfo()
            action.contains("GetPositionInfo") -> getPositionInfo()
            action.contains("GetMediaInfo") -> getMediaInfo()
            action.contains("GetTransportSettings") -> getTransportSettings()
            action.contains("GetCurrentTransportActions") -> getCurrentTransportActions()
            else -> soapError(401, "Invalid Action")
        }
    }

    private fun handleSetUri(body: String): String {
        currentUri = extractXmlValue(body, "CurrentURI")
        currentMetadata = extractXmlValue(body, "CurrentURIMetaData")

        // Try to decode HTML entities in URL
        currentUri = currentUri?.replace("&amp;", "&")
            ?.replace("&lt;", "<")
            ?.replace("&gt;", ">")
            ?.replace("&quot;", "\"")
            ?.replace("&apos;", "'")
            ?.trim()

        // Metadata may be HTML-entity encoded, decode it first for title extraction
        val decodedMetadata = (currentMetadata ?: "")
            .replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&apos;", "'")
            .replace("&amp;", "&")
        val title = extractXmlValue(decodedMetadata, "dc:title")

        // Parse subtitle URL from DIDL-Lite metadata
        val subtitleUrl = parseSubtitleUrl(decodedMetadata)

        Log.d(TAG, "SetAVTransportURI raw URI: '$currentUri'")
        Log.d(TAG, "SetAVTransportURI metadata: '$currentMetadata'")
        Log.d(TAG, "SetAVTransportURI title: '$title'")
        Log.d(TAG, "SetAVTransportURI subtitleUrl: '$subtitleUrl'")

        val url = currentUri
        if (url != null && (url.startsWith("http://") || url.startsWith("https://"))) {
            Log.d(TAG, "Valid URL, starting playback: $url")
            transportState = "TRANSITIONING"
            onCastEvent(CastEvent.Play(url, title, subtitleUrl = subtitleUrl))
            transportState = "PLAYING"
        } else {
            Log.w(TAG, "Invalid or non-HTTP URI received: '$url'")
            // Don't play - not a valid URL
        }

        return soapResponse("SetAVTransportURI", "")
    }

    private fun handlePlay(): String {
        transportState = "PLAYING"
        onCastEvent(CastEvent.Resume())
        return soapResponse("Play", "")
    }

    private fun handlePause(): String {
        transportState = "PAUSED_PLAYBACK"
        onCastEvent(CastEvent.Pause())
        return soapResponse("Pause", "")
    }

    private fun handleStop(): String {
        transportState = "STOPPED"
        currentUri = null
        onCastEvent(CastEvent.Stop())
        return soapResponse("Stop", "")
    }

    private fun handleSeek(body: String): String {
        val target = extractXmlValue(body, "Target") ?: return soapError(402, "Invalid Args")
        val seconds = parseTimeToSeconds(target)
        onCastEvent(CastEvent.Seek(seconds))
        return soapResponse("Seek", "")
    }

    private fun getTransportInfo(): String {
        val state = if (getIsPlaying()) "PLAYING" else transportState
        return soapResponse("GetTransportInfo", """
            <CurrentTransportState>$state</CurrentTransportState>
            <CurrentTransportStatus>OK</CurrentTransportStatus>
            <CurrentSpeed>1</CurrentSpeed>
        """.trimIndent())
    }

    private fun getPositionInfo(): String {
        val pos = getPositionSec()
        val dur = getDurationSec()
        val posStr = formatTime(pos)
        val durStr = formatTime(dur)
        return soapResponse("GetPositionInfo", """
            <Track>1</Track>
            <TrackDuration>$durStr</TrackDuration>
            <TrackMetaData>${currentMetadata?.escapeXml() ?: ""}</TrackMetaData>
            <TrackURI>${currentUri?.escapeXml() ?: ""}</TrackURI>
            <RelTime>$posStr</RelTime>
            <AbsTime>$posStr</AbsTime>
            <RelCount>2147483647</RelCount>
            <AbsCount>2147483647</AbsCount>
        """.trimIndent())
    }

    private fun getMediaInfo(): String {
        val dur = formatTime(getDurationSec())
        return soapResponse("GetMediaInfo", """
            <NrTracks>1</NrTracks>
            <MediaDuration>$dur</MediaDuration>
            <CurrentURI>${currentUri?.escapeXml() ?: ""}</CurrentURI>
            <CurrentURIMetaData>${currentMetadata?.escapeXml() ?: ""}</CurrentURIMetaData>
            <NextURI></NextURI>
            <NextURIMetaData></NextURIMetaData>
            <PlayMedium>NETWORK</PlayMedium>
            <RecordMedium>NOT_IMPLEMENTED</RecordMedium>
            <WriteStatus>NOT_IMPLEMENTED</WriteStatus>
        """.trimIndent())
    }

    private fun getTransportSettings(): String {
        return soapResponse("GetTransportSettings", """
            <PlayMode>NORMAL</PlayMode>
            <RecQualityMode>NOT_IMPLEMENTED</RecQualityMode>
        """.trimIndent())
    }

    private fun getCurrentTransportActions(): String {
        return soapResponse("GetCurrentTransportActions", """
            <Actions>Play,Pause,Stop,Seek</Actions>
        """.trimIndent())
    }

    private fun soapResponse(action: String, body: String): String {
        return """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
<s:Body>
<u:${action}Response xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
$body
</u:${action}Response>
</s:Body>
</s:Envelope>"""
    }

    private fun soapError(code: Int, description: String): String {
        return """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
<s:Body>
<s:Fault>
<faultcode>s:Client</faultcode>
<faultstring>UPnPError</faultstring>
<detail>
<UPnPError xmlns="urn:schemas-upnp-org:control-1-0">
<errorCode>$code</errorCode>
<errorDescription>$description</errorDescription>
</UPnPError>
</detail>
</s:Fault>
</s:Body>
</s:Envelope>"""
    }

    private fun parseSubtitleUrl(metadata: String): String? {
        if (metadata.isBlank()) return null

        // 1. Check Samsung DLNA extensions: sec:CaptionInfoEx and sec:CaptionInfo
        val captionExUrl = extractXmlValue(metadata, "sec:CaptionInfoEx")
        if (!captionExUrl.isNullOrBlank() && captionExUrl.startsWith("http")) {
            Log.d(TAG, "Found subtitle via sec:CaptionInfoEx: $captionExUrl")
            return captionExUrl
        }
        val captionUrl = extractXmlValue(metadata, "sec:CaptionInfo")
        if (!captionUrl.isNullOrBlank() && captionUrl.startsWith("http")) {
            Log.d(TAG, "Found subtitle via sec:CaptionInfo: $captionUrl")
            return captionUrl
        }

        // 2. Check <res> elements with subtitle protocolInfo mime types
        val subtitleMimeTypes = listOf(
            "text/srt", "application/x-subrip",
            "text/vtt", "text/webvtt",
            "application/x-ssa", "text/x-ssa",
            "application/x-ass", "text/x-ass",
            "text/plain" // some apps send .srt as text/plain
        )
        val resRegex = Regex("""<res\s[^>]*>(.*?)</res>""", RegexOption.DOT_MATCHES_ALL)
        val protocolRegex = Regex("""protocolInfo\s*=\s*"([^"]*)"""")
        for (match in resRegex.findAll(metadata)) {
            val fullMatch = match.value
            val protocolInfo = protocolRegex.find(fullMatch)?.groupValues?.get(1) ?: ""
            val resUrl = match.groupValues[1].trim()
            if (subtitleMimeTypes.any { protocolInfo.contains(it, ignoreCase = true) } &&
                resUrl.startsWith("http")) {
                Log.d(TAG, "Found subtitle via <res> protocolInfo: $resUrl (protocol: $protocolInfo)")
                return resUrl
            }
        }

        // 3. Check <res> elements by URL extension as fallback
        for (match in resRegex.findAll(metadata)) {
            val resUrl = match.groupValues[1].trim()
            if (resUrl.startsWith("http") &&
                resUrl.matches(Regex(""".*\.(srt|vtt|ass|ssa|sub|ttml)(\?.*)?$""", RegexOption.IGNORE_CASE))) {
                Log.d(TAG, "Found subtitle via <res> URL extension: $resUrl")
                return resUrl
            }
        }

        return null
    }

    private fun extractXmlValue(xml: String, tag: String): String? {
        val regex = Regex("<$tag[^>]*>(.*?)</$tag>", RegexOption.DOT_MATCHES_ALL)
        return regex.find(xml)?.groupValues?.get(1)
    }

    private fun formatTime(seconds: Double): String {
        if (seconds <= 0) return "00:00:00"
        val h = (seconds / 3600).toInt()
        val m = ((seconds % 3600) / 60).toInt()
        val s = (seconds % 60).toInt()
        return "%02d:%02d:%02d".format(h, m, s)
    }

    private fun parseTimeToSeconds(time: String): Double {
        val parts = time.split(":")
        return when (parts.size) {
            3 -> parts[0].toDouble() * 3600 + parts[1].toDouble() * 60 + parts[2].toDouble()
            2 -> parts[0].toDouble() * 60 + parts[1].toDouble()
            1 -> parts[0].toDouble()
            else -> 0.0
        }
    }

    private fun String.escapeXml(): String {
        return this.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&apos;")
    }
}
