package cloud.samlo.couch.hass

import android.content.Context
import android.net.ConnectivityManager
import cloud.samlo.couch.R
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.Inet4Address
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.SecureRandom
import kotlin.concurrent.thread

/**
 * The page a phone opens to hand Couch Home Assistant's address and token, since typing a token
 * with a remote is out of the question. Listens only while the TV shows the code, at a path with
 * a random secret, so a neighbour on the network can't find it by scanning. Each connection is
 * short and bounded; anything but the secret path gets a 404.
 */
class PhoneLink(
    private val context: Context,
    private val hass: HomeAssistant,
    /** Prefilled on the page; the user can change it. */
    private val suggestedUrl: String,
    /** Called on the link's thread once Home Assistant accepted the address and token. */
    private val onLinked: (url: String) -> Unit,
) {
    private var socket: ServerSocket? = null
    private val secret = randomSecret()

    /** Starts listening; returns the address to show, or null without a network. */
    fun start(): String? {
        val host = address() ?: return null
        val server = try {
            ServerSocket(0)
        } catch (e: Exception) {
            return null
        }
        socket = server
        thread(name = "couch-phone-link", isDaemon = true) {
            while (!server.isClosed) {
                val client = try {
                    server.accept()
                } catch (e: Exception) {
                    break
                }
                thread(name = "couch-phone-link-client", isDaemon = true) { client.use { serve(it) } }
            }
        }
        return "http://$host:${server.localPort}/$secret"
    }

    fun stop() {
        runCatching { socket?.close() }
        socket = null
    }

    private fun serve(client: Socket) {
        client.soTimeout = TIMEOUT_MS
        val input = BufferedInputStream(client.getInputStream())
        val request = readHead(input) ?: return
        val first = request.lineSequence().firstOrNull().orEmpty().split(' ')
        val method = first.getOrNull(0).orEmpty()
        val path = first.getOrNull(1).orEmpty().substringBefore('?')
        if (path != "/$secret") return respond(client, 404, "")
        when (method) {
            "GET" -> respond(client, 200, page(suggestedUrl, null))
            "POST" -> {
                val length = request.lineSequence()
                    .firstOrNull { it.startsWith("content-length:", ignoreCase = true) }
                    ?.substringAfter(':')?.trim()?.toIntOrNull() ?: 0
                if (length !in 1..MAX_BODY) return respond(client, 400, "")
                val body = ByteArray(length)
                var read = 0
                while (read < length) {
                    val n = input.read(body, read, length - read)
                    if (n < 0) return
                    read += n
                }
                val form = parseForm(String(body, Charsets.UTF_8))
                val url = HassJson.baseUrl(form["url"].orEmpty())
                val token = form["token"].orEmpty().trim()
                if (url.isNotEmpty() && token.isNotEmpty() && hass.check(url, token)) {
                    hass.token = token
                    respond(client, 200, done())
                    onLinked(url)
                } else {
                    respond(client, 200, page(url.ifEmpty { suggestedUrl }, context.getString(R.string.hass_page_rejected)))
                }
            }
            else -> respond(client, 405, "")
        }
    }

    /** The request line and headers, up to the blank line; null if they never end. */
    private fun readHead(input: InputStream): String? {
        val out = ByteArrayOutputStream()
        var last4 = 0
        while (out.size() < MAX_HEAD) {
            val b = input.read()
            if (b < 0) return null
            out.write(b)
            last4 = (last4 shl 8) or b
            if (last4 == 0x0D0A0D0A) return out.toString("UTF-8")
        }
        return null
    }

    private fun respond(client: Socket, code: Int, html: String) {
        val body = html.toByteArray(Charsets.UTF_8)
        val status = when (code) {
            200 -> "OK"
            400 -> "Bad Request"
            404 -> "Not Found"
            else -> "Method Not Allowed"
        }
        val head = "HTTP/1.1 $code $status\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${body.size}\r\n" +
            "Cache-Control: no-store\r\nConnection: close\r\n\r\n"
        client.getOutputStream().apply {
            write(head.toByteArray())
            write(body)
            flush()
        }
    }

    private fun page(url: String, error: String?): String {
        val s = { id: Int -> escape(context.getString(id)) }
        return shell(
            """<h1>${s(R.string.hass_link_title)}</h1>
            ${error?.let { "<p class=\"err\">${escape(it)}</p>" }.orEmpty()}
            <form method="post">
              <label>${s(R.string.hass_page_url)}<input name="url" value="${escape(url)}" inputmode="url" autocapitalize="off" autocorrect="off" spellcheck="false"></label>
              <label>${s(R.string.hass_page_token)}<textarea name="token" rows="4" autocapitalize="off" autocorrect="off" spellcheck="false"></textarea></label>
              <p class="help">${s(R.string.hass_page_token_help)}</p>
              <button>${s(R.string.hass_page_send)}</button>
            </form>""",
        )
    }

    private fun done() = shell("<h1>${escape(context.getString(R.string.hass_linked))}</h1><p>${escape(context.getString(R.string.hass_page_done))}</p>")

    private fun shell(body: String) = """<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>Couch</title><style>
body{margin:0;padding:24px 20px;background:#0A0D0C;color:#EDEDEF;font:16px/1.45 system-ui,sans-serif}
main{max-width:480px;margin:0 auto}h1{font-size:22px;margin:0 0 18px}
label{display:block;margin:0 0 14px;color:#B9C2BF;font-size:14px}
input,textarea{display:block;width:100%;box-sizing:border-box;margin-top:6px;padding:12px;border-radius:10px;border:1px solid #2C3532;background:#1A201E;color:#EDEDEF;font:15px ui-monospace,monospace;overflow-wrap:anywhere}
button{width:100%;padding:14px;border:0;border-radius:10px;background:#34D399;color:#06140E;font:600 16px system-ui,sans-serif}
.help{color:#8A9490;font-size:13px;margin:-6px 0 18px}.err{color:#FBBF24}
</style></head><body><main>$body</main></body></html>"""

    private fun address(): String? {
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val network = connectivity.activeNetwork ?: return null
        return connectivity.getLinkProperties(network)?.linkAddresses
            ?.firstOrNull { it.address is Inet4Address }?.address?.hostAddress
    }

    companion object {
        private const val TIMEOUT_MS = 10_000
        private const val MAX_HEAD = 8 * 1024
        private const val MAX_BODY = 8 * 1024

        /** `application/x-www-form-urlencoded`, as a browser posts a form. */
        fun parseForm(body: String): Map<String, String> = body.split('&').mapNotNull { pair ->
            val key = pair.substringBefore('=', "")
            if (key.isEmpty()) return@mapNotNull null
            runCatching { URLDecoder.decode(key, "UTF-8") to URLDecoder.decode(pair.substringAfter('='), "UTF-8") }.getOrNull()
        }.toMap()

        fun escape(text: String): String = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&#39;")

        private fun randomSecret(): String {
            val alphabet = "abcdefghijkmnpqrstuvwxyz23456789"
            val random = SecureRandom()
            return String(CharArray(10) { alphabet[random.nextInt(alphabet.length)] })
        }
    }
}
