package cloud.samlo.couch

import cloud.samlo.couch.hass.QrCode
import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.Assert.assertEquals
import org.junit.Test

/** Encodes with Couch's own encoder and reads back with ZXing (a test-only dependency). */
class QrCodeTest {
    private fun decode(qr: QrCode): String {
        val scale = 4
        val side = (qr.size + QrCode.QUIET * 2) * scale
        val pixels = IntArray(side * side) { i ->
            val x = (i % side) / scale - QrCode.QUIET
            val y = (i / side) / scale - QrCode.QUIET
            val dark = x in 0 until qr.size && y in 0 until qr.size && qr.isDark(x, y)
            if (dark) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
        val bitmap = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(side, side, pixels)))
        return QRCodeReader().decode(bitmap).text
    }

    @Test
    fun shortTextReadsBack() {
        val text = "http://10.0.0.2:8790/a"
        assertEquals(text, decode(QrCode.encode(text)))
    }

    @Test
    fun everyVersionReadsBack() {
        for (version in 1..10) {
            val text = "x".repeat(QrCode.capacity(version))
            val qr = QrCode.encode(text)
            assertEquals(version * 4 + 17, qr.size)
            assertEquals(text, decode(qr))
        }
    }

    @Test
    fun linkAddressReadsBack() {
        val text = "http://192.168.1.20:40123/k3v9q2xa"
        assertEquals(text, decode(QrCode.encode(text)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun tooLongIsRefused() {
        QrCode.encode("x".repeat(QrCode.capacity(10) + 1))
    }
}
