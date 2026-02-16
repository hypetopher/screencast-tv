package com.screencast.tv.airplay

import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Minimal RTSP/HTTP server that responds with the same protocol version
 * as the request (RTSP/1.0 or HTTP/1.1). AirPlay uses RTSP protocol
 * but NanoHTTPD hardcodes HTTP/1.1, which iOS rejects.
 */
abstract class RtspServer(private val port: Int) {

    companion object {
        private const val TAG = "RtspServer"
        private const val MAX_HEADER_SIZE = 8192
        private const val MAX_BODY_SIZE = 1024 * 1024 // 1MB
    }

    private var serverSocket: ServerSocket? = null
    private var serverThread: Thread? = null
    private val rfc1123DateFormat = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("GMT")
    }
    @Volatile
    private var running = false

    fun start() {
        running = true
        serverSocket = ServerSocket(port)
        serverThread = Thread({
            Log.d(TAG, "RTSP server listening on port $port")
            while (running) {
                try {
                    val socket = serverSocket?.accept() ?: break
                    Thread { handleClient(socket) }.start()
                } catch (e: Exception) {
                    if (running) {
                        Log.e(TAG, "Accept error", e)
                    }
                }
            }
        }, "RtspServer-$port")
        serverThread?.isDaemon = true
        serverThread?.start()
    }

    fun stop() {
        running = false
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null
    }

    private fun handleClient(socket: Socket) {
        try {
            socket.soTimeout = 30000
            val input = socket.getInputStream()
            val output = socket.getOutputStream()

            // AirPlay may send multiple requests on the same connection
            while (running) {
                val request = readRequest(input, socket) ?: break
                Log.d(TAG, "=== ${request.method} ${request.uri} (${request.protocol}) ===")
                request.headers.forEach { (key, value) ->
                    Log.d(TAG, "  Header: $key = $value")
                }

                val response = try {
                    serve(request)
                } catch (e: Exception) {
                    Log.e(TAG, "Error handling ${request.uri}", e)
                    Response(500, "Internal Server Error", "text/plain", "Error".toByteArray())
                }

                // Echo back the same protocol version
                writeResponse(output, request.protocol, response, request.headers)
                output.flush()
                onAfterServe(request, response)

                // For 101 Switching Protocols (/reverse), the connection is upgraded
                // to PTTH (reverse HTTP). Keep the socket alive for server→client events.
                if (response.statusCode == 101) {
                    Log.d(TAG, "Connection upgraded to PTTH, keeping socket alive")
                    onConnectionUpgraded(socket, input, output)
                    return // Don't close the socket in finally
                }
            }
        } catch (e: Exception) {
            if (running) {
                Log.d(TAG, "Client disconnected: ${e.message}")
            }
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun readRequest(input: InputStream, socket: Socket): Request? {
        // Read headers (terminated by \r\n\r\n)
        val headerBuf = ByteArrayOutputStream()
        var prev = 0
        var crlfCount = 0
        while (true) {
            val b = input.read()
            if (b == -1) return null
            headerBuf.write(b)
            if (b == '\r'.code) {
                // skip
            } else if (b == '\n'.code && prev == '\r'.code) {
                crlfCount++
                if (crlfCount == 2) break
            } else {
                crlfCount = 0
            }
            prev = b
            if (headerBuf.size() > MAX_HEADER_SIZE) {
                Log.e(TAG, "Header too large")
                return null
            }
        }

        val headerStr = headerBuf.toString("UTF-8")
        val lines = headerStr.trim().split("\r\n", "\n")
        if (lines.isEmpty()) return null

        // Parse request line: "METHOD URI PROTOCOL"
        val requestLine = lines[0].trim()
        val parts = requestLine.split(" ", limit = 3)
        if (parts.size < 2) return null

        val method = parts[0]
        val uri = parts[1]
        val protocol = if (parts.size >= 3) parts[2] else "RTSP/1.0"

        // Parse headers
        val headers = mutableMapOf<String, String>()
        for (i in 1 until lines.size) {
            val line = lines[i]
            val colonIdx = line.indexOf(':')
            if (colonIdx > 0) {
                val key = line.substring(0, colonIdx).trim().lowercase()
                val value = line.substring(colonIdx + 1).trim()
                headers[key] = value
            }
        }

        // Parse query parameters from URI
        val queryParams = mutableMapOf<String, String>()
        val qIdx = uri.indexOf('?')
        val path = if (qIdx >= 0) {
            val query = uri.substring(qIdx + 1)
            query.split("&").forEach { param ->
                val eqIdx = param.indexOf('=')
                if (eqIdx > 0) {
                    queryParams[param.substring(0, eqIdx)] = param.substring(eqIdx + 1)
                }
            }
            uri.substring(0, qIdx)
        } else {
            uri
        }

        // Read body if Content-Length present
        val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
        val body = if (contentLength > 0 && contentLength <= MAX_BODY_SIZE) {
            val bodyBytes = ByteArray(contentLength)
            var read = 0
            while (read < contentLength) {
                val n = input.read(bodyBytes, read, contentLength - read)
                if (n <= 0) break
                read += n
            }
            bodyBytes
        } else {
            ByteArray(0)
        }

        return Request(
            method = method,
            uri = path,
            fullUri = uri,
            protocol = protocol,
            headers = headers,
            params = queryParams,
            body = body,
            remoteAddress = socket.inetAddress?.hostAddress ?: "",
            remotePort = socket.port
        )
    }

    private fun writeResponse(
        output: OutputStream,
        protocol: String,
        response: Response,
        requestHeaders: Map<String, String>
    ) {
        val debugHeaders = mutableListOf<String>()
        val sb = StringBuilder()
        sb.append("$protocol ${response.statusCode} ${response.statusMessage}\r\n")
        debugHeaders.add("$protocol ${response.statusCode} ${response.statusMessage}")

        // CSeq header echo
        val cseq = requestHeaders["cseq"]
        if (cseq != null) {
            sb.append("CSeq: $cseq\r\n")
            debugHeaders.add("CSeq: $cseq")
        }

        sb.append("Server: AirTunes/220.68\r\n")
        debugHeaders.add("Server: AirTunes/220.68")
        sb.append("Date: ${rfc1123DateFormat.format(Date())}\r\n")
        debugHeaders.add("Date: ${rfc1123DateFormat.format(Date())}")

        if (response.contentType != null) {
            sb.append("Content-Type: ${response.contentType}\r\n")
            debugHeaders.add("Content-Type: ${response.contentType}")
        }
        sb.append("Content-Length: ${response.body.size}\r\n")
        debugHeaders.add("Content-Length: ${response.body.size}")

        // Additional headers
        for ((key, value) in response.headers) {
            sb.append("$key: $value\r\n")
            debugHeaders.add("$key: $value")
        }

        sb.append("\r\n")

        Log.d(TAG, "<<< RESPONSE >>>")
        for (h in debugHeaders) {
            Log.d(TAG, "  $h")
        }
        Log.d(TAG, "  body-bytes=${response.body.size}")

        output.write(sb.toString().toByteArray(Charsets.UTF_8))
        if (response.body.isNotEmpty()) {
            output.write(response.body)
        }
    }

    /** Override this to handle requests. */
    abstract fun serve(request: Request): Response

    /** Optional hook for post-response state cleanup. */
    open fun onAfterServe(request: Request, response: Response) {}

    /** Called when a connection is upgraded (101 Switching Protocols). Socket stays open. */
    open fun onConnectionUpgraded(socket: Socket, input: InputStream, output: OutputStream) {}

    data class Request(
        val method: String,
        val uri: String,       // path only (no query)
        val fullUri: String,   // original URI with query
        val protocol: String,  // e.g. "RTSP/1.0" or "HTTP/1.1"
        val headers: Map<String, String>,
        val params: Map<String, String>,
        val body: ByteArray,
        val remoteAddress: String,
        val remotePort: Int
    )

    data class Response(
        val statusCode: Int,
        val statusMessage: String,
        val contentType: String?,
        val body: ByteArray,
        val headers: Map<String, String> = emptyMap()
    ) {
        companion object {
            fun ok(contentType: String?, body: ByteArray) =
                Response(200, "OK", contentType, body)

            fun ok(contentType: String?, body: String) =
                ok(contentType, body.toByteArray(Charsets.UTF_8))

            fun ok() = ok("text/plain", "OK")
        }
    }
}
