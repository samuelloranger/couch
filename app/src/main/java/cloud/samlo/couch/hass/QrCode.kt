package cloud.samlo.couch.hass

import android.graphics.Bitmap
import android.graphics.Color

/**
 * A QR code for a short text (the phone link's address), so Couch needs no library for one image.
 * Byte mode at error correction level M, versions 1 to 10 (up to 213 bytes). Follows Project
 * Nayuki's reference encoder (MIT), trimmed to what Couch uses.
 */
class QrCode private constructor(val size: Int, private val dark: Array<BooleanArray>) {
    fun isDark(x: Int, y: Int): Boolean = dark[y][x]

    /** [scale] pixels per module with a four-module quiet zone, dark on white. */
    fun bitmap(scale: Int): Bitmap {
        val side = (size + QUIET * 2) * scale
        val pixels = IntArray(side * side) { Color.WHITE }
        for (y in 0 until size) for (x in 0 until size) {
            if (!dark[y][x]) continue
            for (dy in 0 until scale) {
                val row = ((y + QUIET) * scale + dy) * side
                for (dx in 0 until scale) pixels[row + (x + QUIET) * scale + dx] = Color.BLACK
            }
        }
        return Bitmap.createBitmap(pixels, side, side, Bitmap.Config.RGB_565)
    }

    companion object {
        const val QUIET = 4
        private const val MAX_VERSION = 10

        // Level M, indexed by version (index 0 unused).
        private val ECC_PER_BLOCK = intArrayOf(-1, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26)
        private val BLOCKS = intArrayOf(-1, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5)

        fun encode(text: String): QrCode {
            val bytes = text.toByteArray(Charsets.UTF_8)
            val version = (1..MAX_VERSION).firstOrNull { bytes.size <= capacity(it) }
                ?: throw IllegalArgumentException("Too long for a QR code: ${bytes.size} bytes")
            return Builder(version).build(codewords(bytes, version))
        }

        /** Bytes that fit at [version]: data codewords less the mode and length header. */
        internal fun capacity(version: Int): Int = dataCodewords(version) - if (version < 10) 2 else 3

        private fun rawModules(version: Int): Int {
            var result = (16 * version + 128) * version + 64
            if (version >= 2) {
                val align = version / 7 + 2
                result -= (25 * align - 10) * align - 55
                if (version >= 7) result -= 36
            }
            return result
        }

        private fun dataCodewords(version: Int) = rawModules(version) / 8 - ECC_PER_BLOCK[version] * BLOCKS[version]

        /** Mode, length, data and padding, then error correction, interleaved by block. */
        private fun codewords(bytes: ByteArray, version: Int): IntArray {
            val bits = ArrayList<Boolean>()
            fun put(value: Int, count: Int) = (count - 1 downTo 0).forEach { bits.add((value ushr it) and 1 == 1) }
            put(0b0100, 4)
            put(bytes.size, if (version < 10) 8 else 16)
            bytes.forEach { put(it.toInt() and 0xFF, 8) }
            val capacityBits = dataCodewords(version) * 8
            put(0, minOf(4, capacityBits - bits.size))
            put(0, (8 - bits.size % 8) % 8)
            var pad = 0xEC
            while (bits.size < capacityBits) {
                put(pad, 8)
                pad = pad xor 0xEC xor 0x11
            }
            val data = IntArray(bits.size / 8) { i -> (0 until 8).fold(0) { acc, b -> (acc shl 1) or if (bits[i * 8 + b]) 1 else 0 } }

            val blockCount = BLOCKS[version]
            val eccLen = ECC_PER_BLOCK[version]
            val raw = rawModules(version) / 8
            val shortBlocks = blockCount - raw % blockCount
            val shortLen = raw / blockCount
            val divisor = divisor(eccLen)
            val blocks = ArrayList<IntArray>()
            var k = 0
            for (i in 0 until blockCount) {
                val len = shortLen - eccLen + if (i < shortBlocks) 0 else 1
                val chunk = data.copyOfRange(k, k + len)
                k += len
                val ecc = remainder(chunk, divisor)
                // Short blocks get a placeholder so every block lines up for interleaving.
                val padded = if (i < shortBlocks) chunk + 0 else chunk
                blocks.add(padded + ecc)
            }
            val out = ArrayList<Int>(raw)
            for (i in blocks[0].indices) for (j in blocks.indices) {
                if (i != shortLen - eccLen || j >= shortBlocks) out.add(blocks[j][i])
            }
            return out.toIntArray()
        }

        private fun divisor(degree: Int): IntArray {
            val result = IntArray(degree)
            result[degree - 1] = 1
            var root = 1
            repeat(degree) {
                for (j in 0 until degree) {
                    result[j] = multiply(result[j], root)
                    if (j + 1 < degree) result[j] = result[j] xor result[j + 1]
                }
                root = multiply(root, 0x02)
            }
            return result
        }

        private fun remainder(data: IntArray, divisor: IntArray): IntArray {
            val result = IntArray(divisor.size)
            for (b in data) {
                val factor = b xor result[0]
                System.arraycopy(result, 1, result, 0, result.size - 1)
                result[result.size - 1] = 0
                for (i in result.indices) result[i] = result[i] xor multiply(divisor[i], factor)
            }
            return result
        }

        /** Multiplication in GF(2^8) modulo x^8 + x^4 + x^3 + x^2 + 1. */
        private fun multiply(x: Int, y: Int): Int {
            var z = 0
            for (i in 7 downTo 0) {
                z = (z shl 1) xor ((z ushr 7) * 0x11D)
                z = z xor (((y ushr i) and 1) * x)
            }
            return z
        }
    }

    /** Lays out the modules for one version: fixed patterns, data, then the least bad mask. */
    private class Builder(private val version: Int) {
        val size = version * 4 + 17
        val dark = Array(size) { BooleanArray(size) }
        val fixed = Array(size) { BooleanArray(size) }

        fun build(codewords: IntArray): QrCode {
            drawFixed()
            drawCodewords(codewords)
            var best = 0
            var bestPenalty = Int.MAX_VALUE
            for (mask in 0 until 8) {
                applyMask(mask)
                drawFormat(mask)
                val p = penalty()
                if (p < bestPenalty) {
                    best = mask
                    bestPenalty = p
                }
                applyMask(mask)
            }
            applyMask(best)
            drawFormat(best)
            return QrCode(size, dark)
        }

        private fun set(x: Int, y: Int, isDark: Boolean) {
            dark[y][x] = isDark
            fixed[y][x] = true
        }

        private fun drawFixed() {
            for (i in 0 until size) {
                set(6, i, i % 2 == 0)
                set(i, 6, i % 2 == 0)
            }
            finder(3, 3)
            finder(size - 4, 3)
            finder(3, size - 4)
            val positions = alignmentPositions()
            val n = positions.size
            for (i in 0 until n) for (j in 0 until n) {
                if ((i == 0 && j == 0) || (i == 0 && j == n - 1) || (i == n - 1 && j == 0)) continue
                for (dy in -2..2) for (dx in -2..2) set(positions[i] + dx, positions[j] + dy, maxOf(Math.abs(dx), Math.abs(dy)) != 1)
            }
            drawFormat(0)
            drawVersion()
        }

        private fun finder(cx: Int, cy: Int) {
            for (dy in -4..4) for (dx in -4..4) {
                val x = cx + dx
                val y = cy + dy
                if (x !in 0 until size || y !in 0 until size) continue
                val dist = maxOf(Math.abs(dx), Math.abs(dy))
                set(x, y, dist != 2 && dist != 4)
            }
        }

        private fun alignmentPositions(): IntArray {
            if (version == 1) return IntArray(0)
            val n = version / 7 + 2
            val step = (version * 4 + n * 2 + 1) / (n * 2 - 2) * 2
            val result = IntArray(n)
            result[0] = 6
            var pos = size - 7
            for (i in n - 1 downTo 1) {
                result[i] = pos
                pos -= step
            }
            return result
        }

        /** Level M's two format bits are 00. */
        private fun drawFormat(mask: Int) {
            val data = mask
            var rem = data
            repeat(10) { rem = (rem shl 1) xor ((rem ushr 9) * 0x537) }
            val bits = ((data shl 10) or rem) xor 0x5412
            fun bit(i: Int) = (bits ushr i) and 1 == 1
            for (i in 0..5) set(8, i, bit(i))
            set(8, 7, bit(6))
            set(8, 8, bit(7))
            set(7, 8, bit(8))
            for (i in 9 until 15) set(14 - i, 8, bit(i))
            for (i in 0 until 8) set(size - 1 - i, 8, bit(i))
            for (i in 8 until 15) set(8, size - 15 + i, bit(i))
            set(8, size - 8, true)
        }

        private fun drawVersion() {
            if (version < 7) return
            var rem = version
            repeat(12) { rem = (rem shl 1) xor ((rem ushr 11) * 0x1F25) }
            val bits = (version shl 12) or rem
            for (i in 0 until 18) {
                val bit = (bits ushr i) and 1 == 1
                val a = size - 11 + i % 3
                val b = i / 3
                set(a, b, bit)
                set(b, a, bit)
            }
        }

        private fun drawCodewords(data: IntArray) {
            var i = 0
            var right = size - 1
            while (right >= 1) {
                if (right == 6) right = 5
                for (vert in 0 until size) for (j in 0..1) {
                    val x = right - j
                    val upward = (right + 1) and 2 == 0
                    val y = if (upward) size - 1 - vert else vert
                    if (!fixed[y][x] && i < data.size * 8) {
                        dark[y][x] = (data[i ushr 3] ushr (7 - (i and 7))) and 1 == 1
                        i++
                    }
                }
                right -= 2
            }
        }

        private fun applyMask(mask: Int) {
            for (y in 0 until size) for (x in 0 until size) {
                val invert = when (mask) {
                    0 -> (x + y) % 2 == 0
                    1 -> y % 2 == 0
                    2 -> x % 3 == 0
                    3 -> (x + y) % 3 == 0
                    4 -> (x / 3 + y / 2) % 2 == 0
                    5 -> x * y % 2 + x * y % 3 == 0
                    6 -> (x * y % 2 + x * y % 3) % 2 == 0
                    else -> ((x + y) % 2 + x * y % 3) % 2 == 0
                }
                if (invert && !fixed[y][x]) dark[y][x] = !dark[y][x]
            }
        }

        /** Runs, 2x2 blocks and dark balance: enough to steer clear of the worst masks. */
        private fun penalty(): Int {
            var result = 0
            for (horizontal in listOf(true, false)) for (a in 0 until size) {
                var run = 0
                var last = false
                for (b in 0 until size) {
                    val v = if (horizontal) dark[a][b] else dark[b][a]
                    if (b > 0 && v == last) run++ else {
                        if (run >= 5) result += run - 2
                        run = 1
                        last = v
                    }
                }
                if (run >= 5) result += run - 2
            }
            for (y in 0 until size - 1) for (x in 0 until size - 1) {
                val c = dark[y][x]
                if (c == dark[y][x + 1] && c == dark[y + 1][x] && c == dark[y + 1][x + 1]) result += 3
            }
            val darkCount = dark.sumOf { row -> row.count { it } }
            val total = size * size
            result += ((Math.abs(darkCount * 20 - total * 10) + total - 1) / total - 1).coerceAtLeast(0) * 10
            return result
        }
    }
}
