package com.screencast.tv.airplay

import android.util.Log
import com.dd.plist.NSArray
import com.dd.plist.NSData
import com.dd.plist.NSDictionary
import com.dd.plist.NSNumber
import com.dd.plist.NSObject
import com.dd.plist.NSString
import com.dd.plist.BinaryPropertyListWriter
import com.dd.plist.PropertyListParser
import com.screencast.tv.airplay.mirror.AirPlayCryptoBridge
import com.screencast.tv.airplay.mirror.AirPlayMirrorRenderer
import com.screencast.tv.airplay.mirror.AirPlayMirrorStreamProcessor
import com.screencast.tv.common.CastEvent
import com.screencast.tv.common.NetworkUtils
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ThreadLocalRandom
import java.util.UUID

class AirPlayServer(
    private val rtspPort: Int,
    private val onCastEvent: (CastEvent) -> Unit,
    private val getPositionSec: () -> Double,
    private val getDurationSec: () -> Double,
    private val getIsPlaying: () -> Boolean
) : RtspServer(rtspPort) {

    companion object {
        private const val TAG = "AirPlayServer"
        private const val INFO_TEMPLATE_DEVICE_ID = "08:00:27:01:02:03"
        private const val MIRROR_SINK_PORT = 7100
        private const val AUDIO_DATA_SINK_PORT = 7101
        private const val AUDIO_CONTROL_SINK_PORT = 7102
    }

    val pairing = AirPlayPairing()
    @Volatile
    private var sessionId: String? = null
    @Volatile
    private var sessionHasAudio: Boolean = false
    @Volatile
    private var sessionIsMirroring: Boolean = false
    @Volatile
    private var sessionStreamConnectionId: Long? = null
    @Volatile
    private var mirrorSinkServer: TcpSinkServer? = null
    @Volatile
    private var audioReceiver: AirPlayAudioReceiver? = null
    @Volatile
    private var timingClient: TimingUdpClient? = null
    @Volatile
    private var mirrorUiStarted: Boolean = false
    @Volatile
    private var fairPlayKeyMsg: ByteArray? = null
    @Volatile
    private var encryptedMirrorAesKey: ByteArray? = null
    @Volatile
    private var sessionEiv: ByteArray? = null
    @Volatile
    private var reverseSocket: java.net.Socket? = null
    @Volatile
    private var reverseOutput: java.io.OutputStream? = null
    private val mirrorStreamProcessor = AirPlayMirrorStreamProcessor()

    override fun serve(request: Request): Response {
        val uri = request.uri
        val method = request.method
        Log.d(TAG, ">>> $method $uri from ${request.remoteAddress}:${request.remotePort} content-type=${request.headers["content-type"]} body=${request.body.size}")
        val response = if (method == "OPTIONS") {
            handleOptions()
        } else if (method == "SETUP") {
            handleRtspSetup(request)
        } else if (method == "RECORD") {
            handleRecord()
        } else if (method == "FLUSH") {
            handleFlush()
        } else if (method == "PAUSE") {
            handlePause()
        } else if (method == "TEARDOWN") {
            handleTeardown(request)
        } else if (method == "GET_PARAMETER") {
            handleGetParameter(request)
        } else if (method == "SET_PARAMETER") {
            handleSetParameter(request)
        } else when {
            uri == "/play" && method == "POST" -> handlePlay(request)
            uri == "/scrub" && method == "POST" -> handleScrub(request)
            uri == "/scrub" && method == "GET" -> handleGetScrub()
            uri == "/rate" && method == "POST" -> handleRate(request)
            uri == "/stop" && method == "POST" -> handleStop()
            uri == "/playback-info" -> handlePlaybackInfo()
            uri == "/server-info" -> handleServerInfo()
            uri == "/info" -> handleInfo(request)
            uri == "/pair-setup" -> handlePairSetup(request)
            uri == "/pair-verify" -> handlePairVerify(request)
            uri == "/fp-setup2" -> handleFpSetup2(request)
            uri.startsWith("/fp-setup") -> handleFpSetup(request)
            uri.startsWith("/photo") -> Response.ok()
            uri == "/reverse" -> handleReverse()
            uri == "/feedback" -> Response.ok("text/plain", "")
            uri == "/action" -> handleAction(request)
            uri.startsWith("/getProperty") -> {
                Log.d(TAG, "getProperty: $uri")
                Response.ok("text/plain", "")
            }
            uri.startsWith("/setProperty") -> {
                Log.d(TAG, "setProperty: $uri, body=${request.body.size} bytes")
                if (request.body.isNotEmpty()) {
                    val params = parsePlistBody(request.body)
                    Log.d(TAG, "setProperty params: $params")
                }
                Response.ok()
            }
            else -> {
                Log.d(TAG, "Unhandled: $method $uri")
                Response.ok()
            }
        }
        return withSession(response)
    }

    private fun handleOptions(): Response {
        return Response(
            200,
            "OK",
            null,
            ByteArray(0),
            mapOf(
                "Public" to "SETUP, RECORD, PAUSE, FLUSH, TEARDOWN, OPTIONS, GET_PARAMETER, SET_PARAMETER"
            )
        )
    }

    private fun handleRtspSetup(request: Request): Response {
        Log.i(TAG, "RTSP SETUP uri=${request.uri}, content-type=${request.headers["content-type"]}, body=${request.body.size} bytes")

        val setupReq = parseSetupRequest(request.body)
        val streamTypes = setupReq?.streamTypes ?: emptyList()
        val streamlessMirroringSession = streamTypes.isEmpty() && (setupReq?.isScreenMirroringSession == true)
        Log.i(
            TAG,
            "RTSP SETUP parsed stream types=$streamTypes, source=${setupReq?.streamSource}, mirroring=${setupReq?.isScreenMirroringSession}, streamlessMirroring=$streamlessMirroringSession, hasEiv=${setupReq?.hasEiv}, hasEkey=${setupReq?.hasEkey}, timingRemotePort=${setupReq?.timingRemotePort}, keys=${setupReq?.rootKeys}"
        )

        val negotiatedSession = request.headers["session"]?.substringBefore(";")?.trim().orEmpty()
        if (sessionId.isNullOrBlank()) {
            sessionId = if (negotiatedSession.isNotEmpty()) negotiatedSession else UUID.randomUUID().toString()
        }
        sessionHasAudio = streamTypes.contains(96L)
        sessionIsMirroring = (setupReq?.isScreenMirroringSession == true) || streamTypes.contains(110L)
        if (setupReq?.streamConnectionId != null) {
            sessionStreamConnectionId = setupReq.streamConnectionId
        }
        if (sessionIsMirroring && sessionStreamConnectionId == null) {
            sessionStreamConnectionId = ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE)
        }
        if (setupReq?.encryptedEkey != null) {
            encryptedMirrorAesKey = setupReq.encryptedEkey.copyOf()
        }
        if (setupReq?.eiv != null) {
            sessionEiv = setupReq.eiv.copyOf()
        }
        maybeConfigureMirrorDecryptor()

        val streams = mutableListOf<Map<String, Any>>()
        for (type in streamTypes) {
            when (type) {
                110L -> {
                    if (!mirrorUiStarted) {
                        mirrorUiStarted = true
                        onCastEvent(CastEvent.StartMirroring())
                    }
                    streams.add(
                        mapOf(
                            "dataPort" to ensureMirrorSinkServer().port.toLong(),
                            "type" to 110L
                        )
                    )
                }
                96L -> {
                    val receiver = ensureAudioReceiver()
                    streams.add(
                        mapOf(
                            "dataPort" to receiver.dataPort.toLong(),
                            "controlPort" to receiver.controlPort.toLong(),
                            "type" to 96L
                        )
                    )
                }
                else -> Log.w(TAG, "RTSP SETUP unknown stream type=$type")
            }
        }

        val responseMap = mutableMapOf<String, Any>()

        if (streams.isNotEmpty()) {
            responseMap["streams"] = streams
        }
        if (setupReq?.hasEiv == true && setupReq.hasEkey == true) {
            // First mirroring SETUP usually carries timing/event setup only.
            val remoteTimingPort = setupReq.timingRemotePort
            val localTimingPort = if (remoteTimingPort != null) {
                ensureTimingClient(request.remoteAddress, remoteTimingPort).localPort
            } else {
                -1
            }
            responseMap["eventPort"] = 0L
            if (localTimingPort > 0) {
                responseMap["timingPort"] = localTimingPort.toLong()
            }
        }
        if (streamlessMirroringSession) {
            val mirrorPort = ensureMirrorSinkServer().port.toLong()
            val streamConnectionId = sessionStreamConnectionId
                ?: ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE).also { sessionStreamConnectionId = it }
            // Some senders use legacy streamless mirroring contract.
            responseMap["dataPort"] = mirrorPort
            responseMap["streamConnectionID"] = streamConnectionId
            if (!mirrorUiStarted) {
                mirrorUiStarted = true
                onCastEvent(CastEvent.StartMirroring())
            }
        }

        // Configure audio decryption after audio receiver is created
        maybeConfigureAudioDecryptor()

        val responseBody = buildSetupResponsePlist(responseMap)
        val mirrorPort = mirrorSinkServer?.port ?: -1
        val audioDataPort = audioReceiver?.dataPort ?: -1
        val audioControlPort = audioReceiver?.controlPort ?: -1
        val eventPort = rtspPort
        val timingPort = timingClient?.localPort ?: -1
        Log.i(
            TAG,
            "RTSP SETUP response: ${responseBody.size} bytes, mirrorPort=$mirrorPort, audioDataPort=$audioDataPort, audioControlPort=$audioControlPort, eventPort=$eventPort, timingPort=$timingPort"
        )
        return Response.ok("application/x-apple-binary-plist", responseBody)
    }

    private fun buildSetupResponsePlist(responseMap: Map<String, Any>): ByteArray {
        val root = NSDictionary()

        for ((key, value) in responseMap) {
            when (value) {
                is Long -> root.put(key, NSNumber(value))
                is Int -> root.put(key, NSNumber(value))
                is String -> root.put(key, value)
                is List<*> -> {
                    val arr = NSArray(value.size)
                    value.forEachIndexed { i, item ->
                        arr.setValue(i, toPlistObject(item))
                    }
                    root.put(key, arr)
                }
                is Map<*, *> -> {
                    root.put(key, toPlistObject(value))
                }
            }
        }

        return BinaryPropertyListWriter.writeToArray(root)
    }

    private fun toPlistObject(value: Any?): NSObject {
        return when (value) {
            is NSDictionary -> value
            is NSObject -> value
            is String -> com.dd.plist.NSString(value)
            is Long -> NSNumber(value)
            is Int -> NSNumber(value)
            is Boolean -> NSNumber(value)
            is Map<*, *> -> {
                val dict = NSDictionary()
                for ((k, v) in value) {
                    if (k is String && v != null) {
                        dict.put(k, toPlistObject(v))
                    }
                }
                dict
            }
            is List<*> -> {
                val arr = NSArray(value.size)
                value.forEachIndexed { i, item ->
                    arr.setValue(i, toPlistObject(item))
                }
                arr
            }
            else -> com.dd.plist.NSString(value?.toString() ?: "")
        }
    }

    private fun handleRecord(): Response {
        val headers = when {
            sessionIsMirroring -> mapOf(
                "Audio-Latency" to "0",
                "Audio-Jack-Status" to "connected; type=digital"
            )
            sessionHasAudio -> mapOf(
                "Audio-Latency" to "11025",
                "Audio-Jack-Status" to "connected; type=analog"
            )
            else -> emptyMap()
        }
        return Response(200, "OK", null, ByteArray(0), headers)
    }

    private fun handleFlush(): Response {
        return Response(200, "OK", null, ByteArray(0))
    }

    private fun handlePause(): Response {
        onCastEvent(CastEvent.Pause())
        return Response(200, "OK", null, ByteArray(0))
    }

    private fun handleTeardown(request: Request): Response {
        Log.d(TAG, "TEARDOWN uri=${request.uri}, content-type=${request.headers["content-type"]}, body=${request.body.size} bytes")
        if (request.body.isNotEmpty()) {
            Log.d(TAG, "TEARDOWN body hex: ${request.body.toHexString(64)}")
        }
        onCastEvent(CastEvent.Stop())
        return Response(200, "OK", null, ByteArray(0))
    }

    private fun ensureMirrorSinkServer(): TcpSinkServer {
        mirrorSinkServer?.let { return it }
        return synchronized(this) {
            mirrorSinkServer?.let { return@synchronized it }
            TcpSinkServer("MirrorSink") { socket ->
                mirrorStreamProcessor.processSocket(socket)
            }.also {
                it.startPreferred(MIRROR_SINK_PORT)
                mirrorSinkServer = it
            }
        }
    }

    private fun ensureAudioReceiver(): AirPlayAudioReceiver {
        audioReceiver?.let { return it }
        return synchronized(this) {
            audioReceiver?.let { return@synchronized it }
            AirPlayAudioReceiver().also {
                it.start(AUDIO_DATA_SINK_PORT, AUDIO_CONTROL_SINK_PORT)
                audioReceiver = it
            }
        }
    }

    private fun ensureTimingClient(remoteAddress: String, remoteTimingPort: Int): TimingUdpClient {
        timingClient?.let { return it }
        return synchronized(this) {
            timingClient?.let { return@synchronized it }
            TimingUdpClient(remoteAddress, remoteTimingPort).also {
                it.start()
                timingClient = it
            }
        }
    }

    private fun handleGetParameter(request: Request): Response {
        val body = String(request.body)
        if (body.contains("volume")) {
            return Response.ok("text/parameters", "volume: 0.0\r\n")
        }
        return Response.ok("text/plain", "")
    }

    private fun handleSetParameter(request: Request): Response {
        Log.d(TAG, "SET_PARAMETER content-type=${request.headers["content-type"]}, size=${request.body.size}")
        return Response.ok("text/plain", "")
    }

    private fun handlePlay(request: Request): Response {
        Log.d(TAG, "Play: content-type=${request.headers["content-type"]}, body=${request.body.size} bytes")

        val params = parsePlistBody(request.body)
        Log.d(TAG, "Play params: $params")

        val url = params["Content-Location"]?.toString()
            ?: params["location"]?.toString()
            ?: ""
        val startPosition = when (val pos = params["Start-Position"]) {
            is Double -> pos
            is Number -> pos.toDouble()
            is String -> pos.toDoubleOrNull() ?: 0.0
            else -> 0.0
        }

        if (url.isNotBlank()) {
            Log.d(TAG, "Play URL=$url startPosition=$startPosition")
            onCastEvent(CastEvent.Play(url, null, startPosition))
        } else {
            Log.w(TAG, "Play: no URL found in body")
        }

        return Response.ok()
    }

    /**
     * Parse a request body that may be binary plist, XML plist, or text key-value.
     * Uses dd-plist for binary/XML plists to avoid data corruption.
     */
    private fun parsePlistBody(body: ByteArray): Map<String, Any> {
        if (body.isEmpty()) return emptyMap()
        try {
            // Try dd-plist first - handles binary plist, XML plist, and ASCII plist
            val root = PropertyListParser.parse(body)
            if (root is NSDictionary) {
                return plistDictToMap(root)
            }
        } catch (e: Exception) {
            Log.d(TAG, "dd-plist parse failed, falling back to text: ${e.message}")
        }
        // Fallback: treat as text (old-style AirPlay text headers)
        return PlistParser.parse(String(body))
    }

    private fun plistDictToMap(dict: NSDictionary): Map<String, Any> {
        val result = mutableMapOf<String, Any>()
        for (key in dict.allKeys()) {
            val keyStr = key.toString()
            val value = dict.objectForKey(keyStr)
            result[keyStr] = when (value) {
                is NSString -> value.content
                is NSNumber -> {
                    if (value.isBoolean) value.boolValue()
                    else if (value.isInteger) value.longValue()
                    else value.doubleValue()
                }
                is NSData -> value.bytes()
                else -> value?.toString() ?: ""
            }
        }
        return result
    }

    private fun handleScrub(request: Request): Response {
        val position = request.params["position"]?.toDoubleOrNull()
        if (position != null) {
            onCastEvent(CastEvent.Seek(position))
        } else if (request.body.isNotEmpty()) {
            val bodyParams = parsePlistBody(request.body)
            val pos = bodyParams["position"]
            val posDouble = when (pos) {
                is Double -> pos
                is Number -> pos.toDouble()
                is String -> pos.toDoubleOrNull()
                else -> null
            }
            if (posDouble != null) {
                onCastEvent(CastEvent.Seek(posDouble))
            }
        }
        return Response.ok()
    }

    private fun handleGetScrub(): Response {
        val position = getPositionSec()
        val duration = getDurationSec()
        val body = "duration: $duration\nposition: $position\n"
        return Response.ok("text/plain", body)
    }

    private fun handleRate(request: Request): Response {
        val value = request.params["value"]?.toFloatOrNull()
        if (value != null) {
            if (value == 0f) {
                onCastEvent(CastEvent.Pause())
            } else {
                onCastEvent(CastEvent.Resume())
            }
        }
        return Response.ok()
    }

    private fun handleStop(): Response {
        onCastEvent(CastEvent.Stop())
        return Response.ok()
    }

    private fun handlePlaybackInfo(): Response {
        val position = getPositionSec()
        val duration = getDurationSec()
        val isPlaying = getIsPlaying()
        val rate = if (isPlaying) 1.0 else 0.0

        val plist = PlistParser.buildPlaybackInfoPlist(position, duration, rate, isPlaying)
        return Response.ok("text/x-apple-plist+xml", plist)
    }

    private fun handleServerInfo(): Response {
        // Return simplified plist for HTTP connections (video casting).
        // Features 0x27F = Video + Photo + VolumeControl + HLS + Slideshow + Screen + Audio
        // (no VideoFairPlay bit 2, no FPSAPv2pt5 bit 12 → iOS won't require fp-setup2)
        val deviceId = NetworkUtils.macToString(NetworkUtils.getMacAddress())
        Log.d(TAG, "server-info requested, deviceId=$deviceId features=0x27F")

        val plist = NSDictionary()
        plist.put("features", NSNumber(0x27FL))
        plist.put("macAddress", deviceId)
        plist.put("deviceid", deviceId)
        plist.put("model", "AppleTV3,2")
        plist.put("osBuildVersion", "12B435")
        plist.put("protovers", "1.0")
        plist.put("srcvers", "220.68")
        plist.put("vv", NSNumber(2))
        plist.put("pk", NSData(pairing.edPublicKeyRaw))

        val responseBody = BinaryPropertyListWriter.writeToArray(plist)
        return Response.ok("text/x-apple-plist+xml", responseBody)
    }

    /**
     * Pre-built binary plist template generated by Python's plistlib.
     * Structure: {"txtAirPlay": {deviceid, features, model, pk, ...}}
     * The pk field (32 bytes of 0xAA placeholder) is at offset 484.
     */
    private val infoResponseTemplate: ByteArray by lazy {
        android.util.Base64.decode(
            "YnBsaXN0MDDRAQJadHh0QWlyUGxhed8QEAMEBQYHCAkKCwwNDg8QERITHCQl" +
            "JicnJCgpKissLS4vXGF1ZGlvRm9ybWF0c15hdWRpb0xhdGVuY2llc1hkZXZp" +
            "Y2VpZFhmZWF0dXJlc11pbml0aWFsVm9sdW1lXxARa2VlcEFsaXZlTG93UG93" +
            "ZXJfEBhrZWVwQWxpdmVTZW5kU3RhdHNBc0JvZHlabWFjQWRkcmVzc1Vtb2Rl" +
            "bFRuYW1lUnBpUnBrWXByb3RvdmVyc1dzcmN2ZXJzW3N0YXR1c0ZsYWdzUnZ2" +
            "ohQa0xUWFxgYGV8QEWF1ZGlvSW5wdXRGb3JtYXRzXxASYXVkaW9PdXRwdXRG" +
            "b3JtYXRzVHR5cGUSA////BBg0xUWFxgYGxAgoR3UHh8gFyEiIxlZYXVkaW9U" +
            "eXBlXxASaW5wdXRMYXRlbmN5TWljcm9zXxATb3V0cHV0TGF0ZW5jeU1pY3Jv" +
            "c1dkZWZhdWx0EAASAAYagF8QETA4OjAwOjI3OjAxOjAyOjAzEwAAAB5af//3" +
            "Wi0yMC4wMDAwMDAJWkFwcGxlVFYzLDJdU2NyZWVuQ2FzdCBUVl8QJDJlMzg4" +
            "MDA2LTEzYmEtNDA0MS05YTY3LTI1ZGQ0YTQzZDUzNk8QIKqqqqqqqqqqqqqqq" +
            "qqqqqqqqqqqqqqqqqqqqqqqqqqqUzEuMVYyMjAuNjgQRBACAAgACwAWADkARg" +
            "BVAF4AZwB1AIkApACvALUAugC9AMAAygDSAN4A4QDkAOsA/wEUARkBHgEgAScB" +
            "KQErATQBPgFTAWkBcQFzAXgBjAGVAaABoQGsAboB4QIEAggCDwIRAAAAAAAA" +
            "AgEAAAAAAAAAMAAAAAAAAAAAAAAAAAAAAhM=",
            android.util.Base64.DEFAULT
        )
    }
    private val PK_OFFSET_IN_TEMPLATE = 484
    private val DEVICE_ID_OFFSET_IN_TEMPLATE = 379
    private val fpHeader = hexToBytes("46504c590301040000000014")
    private val fpReplies = listOf(
        hexToBytes("46504c59030102000000008202000f9f3f9e0a2521dbdf312ab2bfb29e8d232b6376a8c818701d22ae93d82737feaf9db4fdf41c2dba9d1f49caaabf6591ac1f7bc6f7e0663d21afe01565953eab81f418ceed095adb7c3d0e254909a79831d49c3982973434facb42c63a1cd911a6fe941a8a6d4a743b46c3a7649e44c78955e49d8155009549c4e2f7a3f6d5ba"),
        hexToBytes("46504c5903010200000000820201cf32a25714b2524f8aa0ad7af164e37bcf4424e200047efc0ad67afcd95ded1c2730bb591b962ed63a9c4ded88ba8fc78de64d91ccfd5c7b56da88e31f5cceafc7431995a01665a54e1939d25b94db64b9e45d8d063e1e6af07e9656162b0efa404275ea5a44d9591c7256b9fbe6513898b80227721988571650942ad946688a"),
        hexToBytes("46504c5903010200000000820202c169a352eeed35b18cdd9c58d64f16c1519a89eb5317bd0d4336cd68f638ff9d016a5b52b7fa9216b2b65482c78444118121a2c7fed83db7119e9182aad7d18c7063e2a457555910af9e0efc76347d164043807f581ee4fbe42ca9dedc1b5eb2a3aa3d2ecd59e7eee70b3629f22afd161d877353ddb99adc8e07006e56f850ce"),
        hexToBytes("46504c59030102000000008202039001e1727e0f57f9f5880db104a6257a23f5cfff1abbe1e93045251afb97eb9fc0011ebe0f3a81df5b691d76acb2f7a5c708e3d328f56bb39dbde5f29c8a17f481487e3ae863c678325422e6f78e166d18aa7fd636258bce28726f661f738893ce44311e4be6c0535193e5ef72e8686233729c227d820c999445d89246c8c359")
    )

    private fun handleInfo(request: Request): Response {
        if (request.body.isNotEmpty()) {
            Log.d(TAG, "info request body: ${request.body.size} bytes")
        }

        val deviceId = NetworkUtils.macToString(NetworkUtils.getMacAddress())
        Log.d(TAG, "info requested, deviceid=$deviceId, pk=${pairing.getPublicKeyHex()}")
        val responseBody = buildInfoResponsePlist(deviceId)

        Log.d(TAG, "info response: ${responseBody.size} bytes")

        return Response.ok("application/x-apple-binary-plist", responseBody)
    }

    private fun buildInfoResponsePlist(deviceId: String): ByteArray {
        val root = NSDictionary()
        root.put("txtAirPlay", NSData(ByteArray(0)))
        root.put("features", NSNumber(130367356919L)) // (0x1E << 32) | 0x5A7FFFF7
        root.put("name", "ScreenCast TV")
        root.put("pi", "2e388006-13ba-4041-9a67-25dd4a43d536")
        root.put("vv", NSNumber(2))
        root.put("statusFlags", NSNumber(68))
        root.put("keepAliveLowPower", NSNumber(true))
        root.put("sourceVersion", "220.68")
        root.put("pk", NSData(pairing.edPublicKeyRaw))
        root.put("keepAliveSendStatsAsBody", NSNumber(true))
        root.put("deviceID", deviceId)
        root.put("model", "AppleTV3,2")
        root.put("macAddress", deviceId)

        val audioFormats = NSArray(2)
        audioFormats.setValue(0, NSDictionary().apply {
            put("type", NSNumber(100))
            put("audioInputFormats", NSNumber(67108860L))
            put("audioOutputFormats", NSNumber(67108860L))
        })
        audioFormats.setValue(1, NSDictionary().apply {
            put("type", NSNumber(101))
            put("audioInputFormats", NSNumber(67108860L))
            put("audioOutputFormats", NSNumber(67108860L))
        })
        root.put("audioFormats", audioFormats)

        val audioLatencies = NSArray(2)
        audioLatencies.setValue(0, NSDictionary().apply {
            put("outputLatencyMicros", NSNumber(0))
            put("type", NSNumber(100))
            put("audioType", "default")
            put("inputLatencyMicros", NSNumber(0))
        })
        audioLatencies.setValue(1, NSDictionary().apply {
            put("outputLatencyMicros", NSNumber(0))
            put("type", NSNumber(101))
            put("audioType", "default")
            put("inputLatencyMicros", NSNumber(0))
        })
        root.put("audioLatencies", audioLatencies)

        val displays = NSArray(1)
        displays.setValue(0, NSDictionary().apply {
            put("uuid", "e0ff8a27-6738-3d56-8a16-cc53aacee925")
            put("widthPhysical", NSNumber(0))
            put("heightPhysical", NSNumber(0))
            put("width", NSNumber(1920))
            put("height", NSNumber(1080))
            put("widthPixels", NSNumber(1920))
            put("heightPixels", NSNumber(1080))
            put("rotation", NSNumber(false))
            put("refreshRate", NSNumber(1.0 / 60.0))
            put("overscanned", NSNumber(true))
            put("features", NSNumber(14))
        })
        root.put("displays", displays)

        return BinaryPropertyListWriter.writeToArray(root)
    }

    private fun patchDeviceId(responseBody: ByteArray, deviceId: String) {
        val expectedLength = INFO_TEMPLATE_DEVICE_ID.length
        if (deviceId.length != expectedLength) {
            Log.w(TAG, "Unexpected device ID length ${deviceId.length}, expected $expectedLength")
            return
        }

        val expectedBytes = INFO_TEMPLATE_DEVICE_ID.toByteArray(Charsets.US_ASCII)
        val actualTemplateBytes = responseBody.copyOfRange(
            DEVICE_ID_OFFSET_IN_TEMPLATE,
            DEVICE_ID_OFFSET_IN_TEMPLATE + expectedLength
        )
        if (!actualTemplateBytes.contentEquals(expectedBytes)) {
            Log.w(TAG, "info template device ID placeholder mismatch; skipping patch")
            return
        }

        val replacement = deviceId.toByteArray(Charsets.US_ASCII)
        System.arraycopy(replacement, 0, responseBody, DEVICE_ID_OFFSET_IN_TEMPLATE, expectedLength)
    }

    private fun handlePairSetup(request: Request): Response {
        Log.d(TAG, "pair-setup: received ${request.body.size} bytes")

        val responseBytes = pairing.handlePairSetup(request.body)
        Log.d(TAG, "pair-setup: responding with ${responseBytes.size} bytes")

        return Response.ok("application/octet-stream", responseBytes)
    }

    private fun handlePairVerify(request: Request): Response {
        Log.d(TAG, "pair-verify: received ${request.body.size} bytes")

        val responseBytes = pairing.handlePairVerify(request.body)
        Log.d(TAG, "pair-verify: responding with ${responseBytes.size} bytes")

        return Response.ok("application/octet-stream", responseBytes)
    }

    /**
     * Handle /fp-setup2 - FairPlay SAP v1 handshake for the HTTP event channel.
     * Only FairPlay v3 (fp-setup) is implemented. Return 421 to signal
     * that this version is not supported, allowing iOS to continue
     * with an alternative auth path for video casting.
     */
    private fun handleFpSetup2(request: Request): Response {
        val body = request.body
        Log.d(TAG, "fp-setup2: received ${body.size} bytes")

        if (body.size >= 12 && body[0] == 0x46.toByte() && body[1] == 0x50.toByte() &&
            body[2] == 0x4C.toByte() && body[3] == 0x59.toByte()) {
            val majorVer = body[4].toInt() and 0xFF
            val minorVer = body[5].toInt() and 0xFF
            val phase = body[6].toInt() and 0xFF
            Log.d(TAG, "fp-setup2: FPLY v$majorVer.$minorVer phase=$phase - only v3 supported, returning 421")
        }

        return Response(421, "Misdirected Request", "application/x-apple-binary-plist", ByteArray(0))
    }

    private fun handleFpSetup(request: Request): Response {
        val body = request.body
        Log.d(TAG, "fp-setup: received ${body.size} bytes")
        Log.d(TAG, "fp-setup body hex: ${body.toHexString(64)}")

        return when (body.size) {
            16 -> {
                if (body[4].toInt() != 0x03) {
                    Log.w(TAG, "fp-setup: unsupported fairplay version ${body[4].toInt() and 0xFF}")
                    return Response.ok("application/octet-stream", ByteArray(0))
                }
                fairPlayKeyMsg = null
                val mode = body[14].toInt() and 0xFF
                if (mode !in 0..3) {
                    Log.w(TAG, "fp-setup: invalid mode=$mode")
                    return Response.ok("application/octet-stream", ByteArray(0))
                }
                val responseBody = fpReplies[mode]
                Log.d(TAG, "fp-setup phase1 mode=$mode response=${responseBody.size} bytes")
                Response.ok("application/octet-stream", responseBody)
            }
            164 -> {
                if (body[4].toInt() != 0x03) {
                    Log.w(TAG, "fp-setup: unsupported fairplay version ${body[4].toInt() and 0xFF}")
                    return Response.ok("application/octet-stream", ByteArray(0))
                }
                fairPlayKeyMsg = body.copyOf()
                val responseBody = ByteArray(32)
                System.arraycopy(fpHeader, 0, responseBody, 0, fpHeader.size)
                System.arraycopy(body, 144, responseBody, 12, 20)
                Log.d(TAG, "fp-setup phase2 response=${responseBody.size} bytes")
                Response.ok("application/octet-stream", responseBody)
            }
            else -> {
                Log.w(TAG, "fp-setup: invalid data length=${body.size}")
                Response.ok("application/octet-stream", ByteArray(0))
            }
        }
    }

    private fun ByteArray.toHexString(limit: Int): String {
        val count = kotlin.math.min(size, limit)
        val prefix = copyOfRange(0, count).joinToString("") { "%02x".format(it) }
        return if (size > limit) "$prefix..." else prefix
    }

    private fun hexToBytes(hex: String): ByteArray {
        val clean = hex.trim()
        val out = ByteArray(clean.length / 2)
        var i = 0
        while (i < clean.length) {
            out[i / 2] = clean.substring(i, i + 2).toInt(16).toByte()
            i += 2
        }
        return out
    }

    private fun parseSetupRequest(body: ByteArray): SetupRequest? {
        if (body.isEmpty()) return null
        return try {
            val root = PropertyListParser.parse(body)
            if (root !is NSDictionary) return null
            dumpPlist("setup", root, 0)

            val hasEiv = root.objectForKey("eiv") is NSData
            val hasEkey = root.objectForKey("ekey") is NSData
            val encryptedEkey = (root.objectForKey("ekey") as? NSData)?.bytes()?.copyOf()
            val eiv = (root.objectForKey("eiv") as? NSData)?.bytes()?.copyOf()
            val rootKeys = root.allKeys().map { it.toString() }.sorted()
            val timingRemotePort = (root.objectForKey("timingPort") as? NSNumber)?.intValue()
            val isScreenMirroringSession = when (val v = root.objectForKey("isScreenMirroringSession")) {
                is NSNumber -> v.boolValue()
                else -> false
            }

            val streamTypes = linkedSetOf<Long>()
            val streamSources = mutableListOf<String>()
            extractStreamTypesRecursively(
                obj = root,
                path = "root",
                streamTypes = streamTypes,
                streamSources = streamSources
            )
            val streamConnectionId = findStreamConnectionId(root)

            val streamSource = if (streamSources.isNotEmpty()) {
                streamSources.joinToString(",")
            } else {
                "none"
            }

            SetupRequest(
                hasEiv = hasEiv,
                hasEkey = hasEkey,
                streamTypes = streamTypes.toList(),
                streamSource = streamSource,
                rootKeys = rootKeys,
                timingRemotePort = timingRemotePort,
                isScreenMirroringSession = isScreenMirroringSession,
                streamConnectionId = streamConnectionId,
                encryptedEkey = encryptedEkey,
                eiv = eiv
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse RTSP SETUP plist: ${e.message}")
            null
        }
    }

    private data class SetupRequest(
        val hasEiv: Boolean,
        val hasEkey: Boolean,
        val streamTypes: List<Long>,
        val streamSource: String,
        val rootKeys: List<String>,
        val timingRemotePort: Int?,
        val isScreenMirroringSession: Boolean,
        val streamConnectionId: Long?,
        val encryptedEkey: ByteArray?,
        val eiv: ByteArray?
    )

    private fun tryParseEmbeddedPlistDict(bytes: ByteArray): NSDictionary? {
        return try {
            val obj = PropertyListParser.parse(bytes)
            obj as? NSDictionary
        } catch (_: Exception) {
            null
        }
    }

    private fun extractStreamTypesRecursively(
        obj: NSObject?,
        path: String,
        streamTypes: MutableSet<Long>,
        streamSources: MutableList<String>,
        depth: Int = 0
    ) {
        if (obj == null || depth > 8) return
        when (obj) {
            is NSDictionary -> {
                val keys = obj.allKeys().map { it.toString() }
                for (key in keys) {
                    val value = obj.objectForKey(key)
                    if (key == "streams") {
                        val added = addStreamTypesFromArray(value, "$path.streams", streamTypes)
                        if (added) streamSources.add("$path.streams")
                    }
                    if (key == "updateSessionRequest" && value is NSData) {
                        val nested = tryParseEmbeddedPlistDict(value.bytes())
                        if (nested != null) {
                            extractStreamTypesRecursively(
                                obj = nested,
                                path = "$path.updateSessionRequest(data)",
                                streamTypes = streamTypes,
                                streamSources = streamSources,
                                depth = depth + 1
                            )
                            continue
                        }
                    }
                    extractStreamTypesRecursively(
                        obj = value,
                        path = "$path.$key",
                        streamTypes = streamTypes,
                        streamSources = streamSources,
                        depth = depth + 1
                    )
                }
            }
            is NSArray -> {
                for (i in 0 until obj.count()) {
                    extractStreamTypesRecursively(
                        obj = obj.objectAtIndex(i),
                        path = "$path[$i]",
                        streamTypes = streamTypes,
                        streamSources = streamSources,
                        depth = depth + 1
                    )
                }
            }
            else -> {}
        }
    }

    private fun addStreamTypesFromArray(
        streamsObj: NSObject?,
        source: String,
        streamTypes: MutableSet<Long>
    ): Boolean {
        Log.d(
            TAG,
            "RTSP SETUP streams source=$source class=${streamsObj?.javaClass?.simpleName}, count=" +
                if (streamsObj is NSArray) streamsObj.count().toString() else "n/a"
        )
        if (streamsObj !is NSArray) return false
        var addedAny = false
        for (i in 0 until streamsObj.count()) {
            val item = streamsObj.objectAtIndex(i)
            if (item !is NSDictionary) continue
            val typeObj = item.objectForKey("type")
            val type = plistNumberToLong(typeObj)
            if (type != null) {
                streamTypes.add(type)
                addedAny = true
            }
        }
        return addedAny
    }

    private fun plistNumberToLong(obj: NSObject?): Long? {
        return when (obj) {
            is NSNumber -> obj.longValue()
            is NSString -> obj.content.toLongOrNull()
            else -> null
        }
    }

    private fun findStreamConnectionId(obj: NSObject?, depth: Int = 0): Long? {
        if (obj == null || depth > 8) return null
        when (obj) {
            is NSDictionary -> {
                val local = plistNumberToLong(obj.objectForKey("streamConnectionID"))
                if (local != null && local != 0L) return local
                val keys = obj.allKeys().map { it.toString() }
                for (key in keys) {
                    val nested = findStreamConnectionId(obj.objectForKey(key), depth + 1)
                    if (nested != null && nested != 0L) return nested
                }
            }
            is NSArray -> {
                for (i in 0 until obj.count()) {
                    val nested = findStreamConnectionId(obj.objectAtIndex(i), depth + 1)
                    if (nested != null && nested != 0L) return nested
                }
            }
            else -> {}
        }
        return null
    }

    private fun withSession(response: Response): Response {
        val sid = sessionId
        if (sid.isNullOrBlank()) return response
        if (response.headers.keys.any { it.equals("Session", ignoreCase = true) }) return response
        return response.copy(headers = response.headers + ("Session" to sid))
    }

    override fun onAfterServe(request: Request, response: Response) {
        if (request.method == "TEARDOWN") {
            sessionId = null
            sessionHasAudio = false
            sessionIsMirroring = false
            sessionStreamConnectionId = null
            mirrorUiStarted = false
            fairPlayKeyMsg = null
            encryptedMirrorAesKey = null
            sessionEiv = null
            AirPlayMirrorRenderer.reset()
            mirrorStreamProcessor.clearDecryptor()
            audioReceiver?.stop()
            audioReceiver = null
            // Close reverse connection
            try { reverseSocket?.close() } catch (_: Exception) {}
            reverseSocket = null
            reverseOutput = null
        }
    }

    override fun onConnectionUpgraded(socket: java.net.Socket, input: java.io.InputStream, output: java.io.OutputStream) {
        Log.d(TAG, "Reverse (PTTH) connection established, storing for event push")
        // Close any previous reverse connection
        try { reverseSocket?.close() } catch (_: Exception) {}
        reverseSocket = socket
        reverseOutput = output
        // Keep socket alive with long timeout - server pushes events as needed
        socket.soTimeout = 0
        socket.keepAlive = true
    }

    private fun maybeConfigureAudioDecryptor() {
        val receiver = audioReceiver ?: return
        val ekey = encryptedMirrorAesKey ?: return
        val keyMsg = fairPlayKeyMsg ?: return
        val eiv = sessionEiv ?: return
        val ecdhSecret = pairing.getPairVerifySharedSecret() ?: return
        if (ekey.size < 72 || keyMsg.size < 164 || ecdhSecret.size < 32 || eiv.size < 16) return

        val rawAesKey = try {
            com.screencast.tv.airplay.mirror.AirPlayCryptoBridge.nativeDecryptFairPlayAesKey(keyMsg.copyOf(164), ekey.copyOf(72))
        } catch (e: Throwable) {
            Log.w(TAG, "FairPlay AES key decrypt for audio failed: ${e.message}")
            null
        } ?: return

        if (rawAesKey.size != 16) return

        // SHA-512(rawAesKey + ecdhSecret) => first 16 bytes = key, last 16 bytes = IV candidate
        val sha = java.security.MessageDigest.getInstance("SHA-512")
        val fullHash = sha.digest(rawAesKey + ecdhSecret.copyOf(32))
        val derivedKey = fullHash.copyOf(16)
        val derivedIv = fullHash.copyOfRange(48, 64)

        val rawHex = rawAesKey.joinToString("") { "%02x".format(it) }
        val derivedHex = derivedKey.joinToString("") { "%02x".format(it) }
        val eivHex = eiv.joinToString("") { "%02x".format(it) }
        val derivedIvHex = derivedIv.joinToString("") { "%02x".format(it) }
        Log.d(TAG, "Audio keys: raw=$rawHex derived=$derivedHex eiv=$eivHex derivedIv=$derivedIvHex")

        // Derived key + eiv (confirmed correct by UxPlay/RPiPlay reference)
        receiver.configureDecryption(derivedKey, eiv)
        Log.d(TAG, "Audio decryptor configured with derived key + eiv")
    }

    private fun maybeConfigureMirrorDecryptor() {
        val streamConnectionId = sessionStreamConnectionId ?: return
        val ekey = encryptedMirrorAesKey ?: return
        val keyMsg = fairPlayKeyMsg ?: return
        val ecdhSecret = pairing.getPairVerifySharedSecret() ?: return
        if (ekey.size < 72 || keyMsg.size < 164 || ecdhSecret.size < 32) return

        val rawAesKey = try {
            AirPlayCryptoBridge.nativeDecryptFairPlayAesKey(keyMsg.copyOf(164), ekey.copyOf(72))
        } catch (e: Throwable) {
            Log.w(TAG, "FairPlay AES key decrypt failed: ${e.message}")
            null
        } ?: return

        if (rawAesKey.size != 16) {
            Log.w(TAG, "FairPlay AES key decrypt returned ${rawAesKey.size} bytes")
            return
        }

        try {
            mirrorStreamProcessor.configureDecryptor(rawAesKey, ecdhSecret.copyOf(32), streamConnectionId)
            Log.d(TAG, "Mirror decryptor armed for streamConnectionID=${streamConnectionId.toULong()}")
        } catch (e: Exception) {
            Log.w(TAG, "Mirror decryptor init failed: ${e.message}")
        }
    }

    private fun dumpPlist(path: String, obj: NSObject?, depth: Int) {
        if (depth > 3 || obj == null) return
        when (obj) {
            is NSDictionary -> {
                val keys = obj.allKeys().map { it.toString() }.sorted()
                Log.d(TAG, "SETUP plist $path = NSDictionary keys=$keys")
                for (k in keys) {
                    dumpPlist("$path.$k", obj.objectForKey(k), depth + 1)
                }
            }
            is NSArray -> {
                Log.d(TAG, "SETUP plist $path = NSArray count=${obj.count()}")
                for (i in 0 until obj.count()) {
                    dumpPlist("$path[$i]", obj.objectAtIndex(i), depth + 1)
                }
            }
            is NSNumber -> {
                Log.d(TAG, "SETUP plist $path = NSNumber long=${obj.longValue()} bool=${obj.boolValue()}")
            }
            is NSString -> {
                Log.d(TAG, "SETUP plist $path = NSString '${obj.content}'")
            }
            is NSData -> {
                Log.d(TAG, "SETUP plist $path = NSData len=${obj.length()}")
            }
            else -> {
                Log.d(TAG, "SETUP plist $path = ${obj.javaClass.simpleName}")
            }
        }
    }

    private class TcpSinkServer(
        private val name: String,
        private val onClientConnected: ((Socket) -> Unit)? = null
    ) {
        private val logTag = "AirPlayServer"
        @Volatile
        private var serverSocket: ServerSocket = ServerSocket(0)
        @Volatile
        var port: Int = serverSocket.localPort
            private set
        @Volatile
        private var running = true

        fun startPreferred(preferredPort: Int) {
            try {
                serverSocket.close()
            } catch (_: Exception) {}
            serverSocket = try {
                ServerSocket(preferredPort)
            } catch (e: Exception) {
                Log.w(logTag, "$name preferred port $preferredPort unavailable, falling back: ${e.message}")
                ServerSocket(0)
            }
            port = serverSocket.localPort
            start()
        }

        fun start() {
            Thread({
                Log.d(logTag, "$name listening on port $port")
                while (running) {
                    try {
                        val socket = serverSocket.accept()
                        Thread { handleClient(socket) }.start()
                    } catch (e: Exception) {
                        if (running) Log.w(logTag, "$name accept error: ${e.message}")
                    }
                }
            }, "$name-$port").start()
        }

        private fun handleClient(socket: Socket) {
            Log.d(logTag, "$name connected from ${socket.inetAddress.hostAddress}:${socket.port}")
            try {
                socket.soTimeout = 30000
                val input = socket.getInputStream()
                if (onClientConnected != null) {
                    onClientConnected.invoke(socket)
                } else {
                    val buf = ByteArray(8192)
                    var total = 0L
                    while (running) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        total += n
                    }
                    Log.d(logTag, "$name disconnected, bytes=$total")
                }
            } catch (e: Exception) {
                Log.d(logTag, "$name client closed: ${e.message}")
            } finally {
                try { socket.close() } catch (_: Exception) {}
            }
        }
    }

    private class TimingUdpClient(
        private val remoteAddress: String,
        private val remotePort: Int
    ) {
        private val socket = DatagramSocket(0)
        val localPort: Int = socket.localPort
        @Volatile
        private var running = true

        fun start() {
            Thread({
                Log.d(TAG, "TimingUdpClient localPort=$localPort remote=$remoteAddress:$remotePort")
                socket.soTimeout = 500
                val remote = InetAddress.getByName(remoteAddress)
                val req = ByteArray(32)
                req[0] = 0x80.toByte()
                req[1] = 0xD2.toByte()
                req[2] = 0x00
                req[3] = 0x07
                while (running) {
                    try {
                        writeNtpTimestamp(req, 24, System.currentTimeMillis())
                        val packet = DatagramPacket(req, req.size, remote, remotePort)
                        socket.send(packet)
                        val buf = ByteArray(128)
                        val reply = DatagramPacket(buf, buf.size)
                        socket.receive(reply)
                        Log.d(TAG, "TimingUdpClient recv ${reply.length} bytes from ${reply.address.hostAddress}:${reply.port}")
                    } catch (_: Exception) {
                        // Ignore timeout/no-response; keep probing to mimic RAOP NTP client behavior.
                    }
                    try {
                        Thread.sleep(3000)
                    } catch (_: InterruptedException) {}
                }
            }, "TimingUdpClient-$localPort").start()
        }

        private fun writeNtpTimestamp(buf: ByteArray, offset: Int, ms: Long) {
            val seconds = (ms / 1000L) + 2208988800L
            val fraction = ((ms % 1000L) * 0x100000000L) / 1000L
            writeInt32(buf, offset, seconds.toInt())
            writeInt32(buf, offset + 4, fraction.toInt())
        }

        private fun writeInt32(buf: ByteArray, offset: Int, value: Int) {
            buf[offset] = ((value ushr 24) and 0xFF).toByte()
            buf[offset + 1] = ((value ushr 16) and 0xFF).toByte()
            buf[offset + 2] = ((value ushr 8) and 0xFF).toByte()
            buf[offset + 3] = (value and 0xFF).toByte()
        }
    }

    private fun handleReverse(): Response {
        Log.d(TAG, "Reverse connection requested - upgrading to PTTH/1.0")
        return Response(
            101, "Switching Protocols", null, ByteArray(0),
            mapOf("Upgrade" to "PTTH/1.0", "Connection" to "Upgrade")
        )
    }

    private fun handleAction(request: Request): Response {
        Log.d(TAG, "Action: content-type=${request.headers["content-type"]}, body=${request.body.size} bytes")
        val params = parsePlistBody(request.body)
        Log.d(TAG, "Action params: $params")

        val type = params["type"]?.toString() ?: ""
        when (type) {
            "wirePlayFromBrowse", "wirePlayFromBrowse-v2", "playFromBrowse" -> {
                // iOS sends play action with URL via /action
                val url = params["Content-Location"]?.toString()
                    ?: params["url"]?.toString()
                    ?: ""
                val startPosition = when (val pos = params["Start-Position"]) {
                    is Double -> pos
                    is Number -> pos.toDouble()
                    is String -> pos.toDoubleOrNull() ?: 0.0
                    else -> 0.0
                }
                if (url.isNotBlank()) {
                    Log.d(TAG, "Action play URL=$url startPosition=$startPosition")
                    onCastEvent(CastEvent.Play(url, null, startPosition))
                }
            }
            "wirePlayCommand", "wireSeekCommand", "wireRateCommand" -> {
                // Playback control commands
                val params2 = (params["params"] as? Map<*, *>)
                Log.d(TAG, "Action command: $type subParams=$params2")
            }
            else -> {
                Log.d(TAG, "Action unhandled type=$type")
            }
        }
        return Response.ok()
    }
}
