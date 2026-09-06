package nl.yannick.b04cbridge

object Protocol {
    const val SERVICE = "6e400001-b5a3-f393-e0a9-e50e24dcca9e"
    const val WRITE = "6e400002-b5a3-f393-e0a9-e50e24dcca9e"
    const val NOTIFY = "6e400003-b5a3-f393-e0a9-e50e24dcca9e"
    val AES_KEY = "2CTDU40qNyCgTjb1".toByteArray(Charsets.US_ASCII)

    const val STRAIGHT = 1
    const val LEFT = 2
    const val RIGHT = 3
    const val SLIGHT_LEFT = 4
    const val SLIGHT_RIGHT = 5
    const val SHARP_LEFT = 6
    const val SHARP_RIGHT = 7
    const val UTURN = 8
    const val ARRIVE = 10

    // Op de laptop-rig codes 1..20 stuk voor stuk naar het display gestuurd en afgelezen.
    // 1..8 en 10 hebben elk hun eigen pijl; 9, 11..18 en 20 tonen allemaal de omkeerpijl.
    // Een onbekende code mag dus nooit doorlekken: dan krijgt de fietser "keer om" te zien
    // terwijl hij rechtdoor moet. Alles wat we niet kennen wordt daarom rechtdoor.
    private val KNOWN_MANEUVERS = setOf(STRAIGHT, LEFT, RIGHT, SLIGHT_LEFT, SLIGHT_RIGHT, SHARP_LEFT, SHARP_RIGHT, UTURN, ARRIVE)

    fun safeManeuver(code: Int): Int = if (code in KNOWN_MANEUVERS) code else STRAIGHT

    private var seq = 0

    fun frame(target: Int, sub: Int, param: Int, payload: ByteArray): ByteArray {
        val pre = ByteArray(7 + payload.size)
        pre[0] = 0x55
        pre[1] = 0xAA.toByte()
        pre[2] = payload.size.toByte()
        pre[3] = 0x11
        pre[4] = target.toByte()
        pre[5] = sub.toByte()
        pre[6] = param.toByte()
        payload.copyInto(pre, 7)
        val sum = pre.sumOf { it.toInt() and 0xff }
        val cs1 = (0xFE - (sum and 0xff)) and 0xff
        val cs2 = (0x100 - ((sum shr 8) and 0xff)) and 0xff
        return pre + byteArrayOf(cs1.toByte(), cs2.toByte())
    }

    fun readChallenge() = frame(0x10, 0x01, 0x00, byteArrayOf(0x04))
    fun auth(cipher: ByteArray) = frame(0x10, 0x20, 0x00, cipher)

    fun syncTime(epoch: Long): ByteArray {
        val p = ByteArray(4) { i -> ((epoch shr (8 * i)) and 0xff).toByte() }
        return frame(0x10, 0x02, 0x3E, p)
    }

    fun nav(dist: Int, man: Int, total: Int): ByteArray =
        navDetailed(dist, man, 0, STRAIGHT, 0, STRAIGHT, total)

    fun navDetailed(
        currentDist: Int,
        currentMan: Int,
        nextDist: Int,
        nextMan: Int,
        nextNextDist: Int,
        nextNextMan: Int,
        total: Int
    ): ByteArray {
        fun u24(value: Int): ByteArray {
            val v = value.coerceIn(0, 0xFFFFFF)
            return byteArrayOf(
                (v and 255).toByte(),
                ((v shr 8) and 255).toByte(),
                ((v shr 16) and 255).toByte()
            )
        }
        fun u32(value: Int): ByteArray {
            val v = value.coerceAtLeast(0).toLong()
            return byteArrayOf(
                (v and 255).toByte(),
                ((v shr 8) and 255).toByte(),
                ((v shr 16) and 255).toByte(),
                ((v shr 24) and 255).toByte()
            )
        }

        val payload = byteArrayOf((seq++ and 255).toByte(), 0x02) +
            u24(currentDist) + byteArrayOf(safeManeuver(currentMan).toByte()) +
            u24(nextDist) + byteArrayOf(safeManeuver(nextMan).toByte()) +
            u24(nextNextDist) + byteArrayOf(safeManeuver(nextNextMan).toByte()) +
            u32(total)

        return frame(0xF1, 0x03, 0x00, payload)
    }

    fun stopNav() = frame(0xF1, 0x02, 0x02, byteArrayOf(0x00))
}
