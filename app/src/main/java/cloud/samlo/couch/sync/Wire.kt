package cloud.samlo.couch.sync

import org.json.JSONException
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.util.Base64

/** One message is a 4-byte length and a JSON object; nothing a peer sends is trusted to be small or well-formed. */
object Wire {
    const val MAX_FRAME = 3_000_000

    /** Everything a TV sends before it has been heard from is a few hundred bytes. */
    const val MAX_REQUEST = 4_096

    fun write(out: DataOutputStream, message: JSONObject) {
        val bytes = message.toString().toByteArray()
        out.writeInt(bytes.size)
        out.write(bytes)
        out.flush()
    }

    /** Throws [IOException] for a closed stream, a size out of range, or text that isn't JSON. */
    fun read(input: DataInputStream, max: Int = MAX_FRAME): JSONObject {
        val size = input.readInt()
        if (size !in 1..max) throw IOException("frame of $size bytes")
        val bytes = ByteArray(size).also(input::readFully)
        try {
            return JSONObject(String(bytes))
        } catch (e: JSONException) {
            throw IOException("not JSON")
        }
    }

    fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    /** Throws [IOException] rather than [IllegalArgumentException], so one catch covers a bad peer. */
    fun unb64(text: String): ByteArray = try {
        Base64.getDecoder().decode(text)
    } catch (e: IllegalArgumentException) {
        throw IOException("not base64")
    }
}
