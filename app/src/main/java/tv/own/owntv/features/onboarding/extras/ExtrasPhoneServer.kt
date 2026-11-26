package tv.own.owntv.features.onboarding.extras

import android.graphics.Bitmap
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import tv.own.owntv.core.companion.CompanionLink
import tv.own.owntv.features.keys.ApiKeys
import tv.own.owntv.features.keys.ApiService

/** Where the phone link of the Extras chapter stands. */
sealed interface PhoneLinkState {
    /** Not started, or binding the port. */
    data object Starting : PhoneLinkState

    /** Listening: [url] (with its session token) is what the phone opens; [qr] encodes it. */
    data class Ready(val url: String, val qr: Bitmap?) : PhoneLinkState

    /** The TV has no LAN address, so no phone can reach it. */
    data object NoNetwork : PhoneLinkState

    /** No port could be opened. */
    data object Failed : PhoneLinkState

    /** Closed after too many requests with a wrong token; a restart mints a new one. */
    data object Locked : PhoneLinkState
}

/**
 * A tiny HTTP page on the home network for the Extras chapter: the phone opens
 * `http://<tv>:<port>/<token>`, pastes up to three keys and sends them; [onKeys] receives the
 * non-empty, sanitised ones (on a worker thread).
 *
 * Security posture, the same as the companion page's:
 * - only clients from private, link-local or loopback addresses are answered;
 * - a fresh random token per [start] is part of the path, compared in constant time; a request with a
 *   wrong token counts as a strike, and after [MAX_STRIKES] the listener closes itself ([PhoneLinkState.Locked]);
 * - the listener runs only while the chapter is on screen and the app is in front; the chapter calls
 *   [start] and [stop] one after another on a single thread.
 *
 * Nothing is logged: the URL carries the token and the bodies carry keys. [page] renders the HTML
 * (true = after a plain form post, with the "sent" notice) and is called on a worker thread.
 */
class ExtrasPhoneServer(
    private val page: (sent: Boolean) -> String,
    private val onKeys: (Map<ApiService, String>) -> Unit,
) {
    private val _state = MutableStateFlow<PhoneLinkState>(PhoneLinkState.Starting)
    val state: StateFlow<PhoneLinkState> = _state.asStateFlow()

    @Volatile private var socket: ServerSocket? = null
    @Volatile private var workers: ThreadPoolExecutor? = null
    @Volatile private var token: String = ""
    private val strikes = AtomicInteger(0)

    /** Opens the listener with a new token. Blocking (network lookup and bind): call off the main thread. */
    @Synchronized
    fun start() {
        close()
        _state.value = PhoneLinkState.Starting
        if (CompanionLink.lanHosts().isEmpty()) {
            _state.value = PhoneLinkState.NoNetwork
            return
        }
        val bound = PORTS.firstNotNullOfOrNull { port ->
            val candidate = ServerSocket()
            runCatching {
                candidate.reuseAddress = true
                candidate.bind(InetSocketAddress(port))
                candidate
            }.getOrElse {
                runCatching { candidate.close() }
                null
            }
        }
        if (bound == null) {
            _state.value = PhoneLinkState.Failed
            return
        }
        val pool = ThreadPoolExecutor(0, MAX_CLIENTS, IDLE_S, TimeUnit.SECONDS, SynchronousQueue())
        token = newToken()
        strikes.set(0)
        socket = bound
        workers = pool
        Thread({ acceptLoop(bound, pool) }, THREAD_NAME).apply { isDaemon = true }.start()
        val url = CompanionLink.lanUrl(bound.localPort) + token
        _state.value = PhoneLinkState.Ready(url, CompanionLink.renderQr(url))
    }

    /** Closes the listener; the token stops working. Safe to call from any thread, any number of times. */
    @Synchronized
    fun stop() {
        close()
        _state.value = PhoneLinkState.Starting
    }

    private fun close() {
        token = ""
        runCatching { socket?.close() }
        socket = null
        workers?.shutdownNow()
        workers = null
    }

    @Synchronized
    private fun lock(from: ServerSocket) {
        if (socket !== from) return
        close()
        _state.value = PhoneLinkState.Locked
    }

    private fun acceptLoop(server: ServerSocket, pool: ThreadPoolExecutor) {
        while (!server.isClosed) {
            val client = try {
                server.accept()
            } catch (e: IOException) {
                break
            }
            try {
                pool.execute { serve(server, client) }
            } catch (e: RejectedExecutionException) {
                // Busy or shutting down: shed the connection rather than queue it.
                runCatching { client.close() }
            }
        }
    }

    private fun serve(server: ServerSocket, client: Socket) {
        runCatching {
            client.use { connection ->
                if (!isLocal(connection.inetAddress)) return@use
                connection.soTimeout = SOCKET_TIMEOUT_MS
                val input = BufferedInputStream(connection.getInputStream())
                val parts = readLine(input)?.split(' ').orEmpty()
                if (parts.size < 3) return@use respond(connection, 400, null)
                val method = parts[0]
                val path = parts[1].substringBefore('?')
                var length = 0
                var headers = 0
                while (headers++ < MAX_HEADERS) {
                    val line = readLine(input) ?: break
                    if (line.isEmpty()) break
                    val colon = line.indexOf(':')
                    if (colon > 0 && line.substring(0, colon).trim().equals(CONTENT_LENGTH, ignoreCase = true)) {
                        length = line.substring(colon + 1).trim().toIntOrNull() ?: -1
                    }
                }
                // Browsers ask for these on their own; they are not guesses at the token.
                if (path == ROOT || path == FAVICON) return@use respond(connection, 404, null)
                if (!tokenMatches(path)) {
                    respond(connection, 404, null)
                    if (strikes.incrementAndGet() >= MAX_STRIKES) lock(server)
                    return@use
                }
                when (method) {
                    GET -> respond(connection, 200, page(false))
                    POST -> {
                        if (length !in 0..MAX_BODY) return@use respond(connection, 413, null)
                        val keys = parseKeys(readBody(input, length))
                        if (keys.isNotEmpty()) onKeys(keys)
                        respond(connection, 200, page(true))
                    }
                    else -> respond(connection, 405, null)
                }
            }
        }
    }

    private fun tokenMatches(path: String): Boolean {
        val expected = token
        if (expected.isEmpty()) return false
        val candidate = path.removePrefix(ROOT).removeSuffix(ROOT)
        return MessageDigest.isEqual(
            candidate.toByteArray(StandardCharsets.UTF_8),
            expected.toByteArray(StandardCharsets.UTF_8),
        )
    }

    private fun respond(connection: Socket, code: Int, html: String?) {
        val body = (html ?: code.toString()).toByteArray(StandardCharsets.UTF_8)
        val type = if (html != null) HTML_TYPE else TEXT_TYPE
        val head = buildString {
            append(HTTP_VERSION).append(code).append(' ').append(reason(code)).append(CRLF)
            append(CONTENT_TYPE_HEADER).append(type).append(CRLF)
            append(CONTENT_LENGTH_HEADER).append(body.size).append(CRLF)
            append(NO_STORE).append(CRLF)
            append(NO_REFERRER).append(CRLF)
            append(CLOSE).append(CRLF).append(CRLF)
        }
        connection.getOutputStream().apply {
            write(head.toByteArray(StandardCharsets.UTF_8))
            write(body)
            flush()
        }
    }

    companion object {
        /** Tried in order; the companion page uses 8089, so these never collide with it. */
        private val PORTS = 8090..8099

        /** Wrong-token requests before the link closes itself. */
        internal const val MAX_STRIKES = 12

        private const val MAX_CLIENTS = 4
        private const val IDLE_S = 20L
        private const val SOCKET_TIMEOUT_MS = 10_000
        private const val MAX_HEADERS = 64
        private const val MAX_LINE = 4_096

        /** Three fields of at most [ApiKeys.MAX_LENGTH] characters, URL-encoded, with room to spare. */
        private const val MAX_BODY = 8_192

        /** 10 characters from 31 unambiguous ones: about 50 bits, short enough to type from the screen. */
        private const val TOKEN_LENGTH = 10
        private const val TOKEN_ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789"

        private const val THREAD_NAME = "mayo-extras-link"
        private const val ROOT = "/"
        private const val FAVICON = "/favicon.ico"
        private const val GET = "GET"
        private const val POST = "POST"
        private const val CONTENT_LENGTH = "Content-Length"
        private const val HTTP_VERSION = "HTTP/1.1 "
        private const val CRLF = "\r\n"
        private const val CONTENT_TYPE_HEADER = "Content-Type: "
        private const val CONTENT_LENGTH_HEADER = "Content-Length: "
        private const val HTML_TYPE = "text/html; charset=utf-8"
        private const val TEXT_TYPE = "text/plain; charset=utf-8"
        private const val NO_STORE = "Cache-Control: no-store"
        private const val NO_REFERRER = "Referrer-Policy: no-referrer"
        private const val CLOSE = "Connection: close"

        private val random = SecureRandom()

        private fun newToken(): String =
            String(CharArray(TOKEN_LENGTH) { TOKEN_ALPHABET[random.nextInt(TOKEN_ALPHABET.length)] })

        private fun reason(code: Int): String = when (code) {
            200 -> "OK"
            400 -> "Bad Request"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            413 -> "Payload Too Large"
            else -> "Error"
        }

        /**
         * Whether [address] is on the home network: an RFC 1918 or IPv6 unique-local address, a
         * link-local one, or this device itself.
         */
        internal fun isLocal(address: InetAddress?): Boolean {
            if (address == null) return false
            if (address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress) return true
            if (address is Inet6Address) {
                val first = address.address.firstOrNull()?.toInt() ?: return false
                return (first and 0xfe) == 0xfc // fc00::/7, unique local
            }
            return false
        }

        /**
         * The keys in a URL-encoded form body: one field per service, named after it in lower case
         * ("tmdb", "elevenlabs", "typesafe"). Values are sanitised; empty ones are left out.
         */
        internal fun parseKeys(body: String): Map<ApiService, String> {
            val fields = body.split('&').mapNotNull { pair ->
                val name = pair.substringBefore('=')
                val value = pair.substringAfter('=', missingDelimiterValue = "")
                runCatching {
                    URLDecoder.decode(name, StandardCharsets.UTF_8.name()) to URLDecoder.decode(value, StandardCharsets.UTF_8.name())
                }.getOrNull()
            }.toMap()
            return ApiService.entries.mapNotNull { service ->
                val clean = ApiKeys.sanitize(fields[service.name.lowercase()].orEmpty())
                if (clean.isEmpty()) null else service to clean
            }.toMap()
        }

        private fun readLine(input: InputStream): String? {
            val buffer = ByteArrayOutputStream()
            while (buffer.size() < MAX_LINE) {
                val next = input.read()
                if (next == -1) {
                    if (buffer.size() == 0) return null
                    break
                }
                if (next == '\n'.code) break
                if (next != '\r'.code) buffer.write(next)
            }
            return buffer.toString(StandardCharsets.UTF_8.name())
        }

        private fun readBody(input: InputStream, length: Int): String {
            val bytes = ByteArray(length)
            var read = 0
            while (read < length) {
                val n = input.read(bytes, read, length - read)
                if (n < 0) break
                read += n
            }
            return String(bytes, 0, read, StandardCharsets.UTF_8)
        }
    }
}
