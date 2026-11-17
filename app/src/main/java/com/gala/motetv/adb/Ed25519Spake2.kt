package com.gala.motetv.adb

import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Pure-Kotlin BoringSSL-compatible SPAKE2 over Edwards25519 (`spake25519`).
 * Implements RFC 8032 curve arithmetic + BoringSSL `SPAKE2_CTX` key schedule so Android 11+
 * Wireless Debugging (`adb pair`) runs natively inside MoteTV without native `.so` binaries.
 */
object Ed25519Spake2 {

    enum class Role {
        ALICE_CLIENT,
        BOB_SERVER
    }

    private val P: BigInteger = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19))
    private val L: BigInteger = BigInteger.ONE.shiftLeft(252)
        .add(BigInteger("27742317777372353535851937790883648493"))
    private val D: BigInteger = BigInteger.valueOf(-121665)
        .multiply(BigInteger.valueOf(121666).modInverse(P))
        .mod(P)
    private val TWO_D: BigInteger = D.shiftLeft(1).mod(P)
    private val SQRT_M1: BigInteger = BigInteger.valueOf(2)
        .modPow(P.subtract(BigInteger.ONE).shiftRight(2), P)
    private val P_MINUS_5_DIV_8: BigInteger = P.subtract(BigInteger.valueOf(5)).shiftRight(3)

    // RFC 8032 base point B (compressed hex starts with 586666...)
    private val BASE_BYTES: ByteArray = hexToBytes(
        "5866666666666666666666666666666666666666666666666666666666666666"
    )

    // BoringSSL kEd25519PointM and kEd25519PointN (RFC 9382 / spake25519.cc)
    internal val POINT_M_BYTES: ByteArray = hexToBytes(
        "d048032c6ea0b6d697ddc2e86bda85a33adac920f1bf18e1b0c6d166a5cecdaf"
    )
    internal val POINT_N_BYTES: ByteArray = hexToBytes(
        "d3bfb518f44f3430f29d0c92af503865a1ed3281dc69b35dd868ba85f886c4ab"
    )

    internal data class Point(
        val x: BigInteger,
        val y: BigInteger,
        val z: BigInteger,
        val t: BigInteger
    ) {
        fun negate(): Point = Point(P.subtract(x).mod(P), y, z, P.subtract(t).mod(P))

        fun add(other: Point): Point {
            val a = y.subtract(x).mod(P).multiply(other.y.subtract(other.x).mod(P)).mod(P)
            val b = y.add(x).mod(P).multiply(other.y.add(other.x).mod(P)).mod(P)
            val c = t.multiply(TWO_D).mod(P).multiply(other.t).mod(P)
            val d = z.shiftLeft(1).mod(P).multiply(other.z).mod(P)
            val e = b.subtract(a).mod(P)
            val f = d.subtract(c).mod(P)
            val g = d.add(c).mod(P)
            val h = b.add(a).mod(P)
            return Point(
                x = e.multiply(f).mod(P),
                y = g.multiply(h).mod(P),
                z = f.multiply(g).mod(P),
                t = e.multiply(h).mod(P)
            )
        }

        fun scalarMul(scalar: BigInteger): Point {
            var res = IDENTITY
            var q = this
            val s = scalar.mod(L)
            for (bit in 0 until s.bitLength()) {
                if (s.testBit(bit)) {
                    res = res.add(q)
                }
                q = q.add(q)
            }
            return res
        }

        fun isIdentity(): Boolean {
            val zInv = z.modInverse(P)
            val nx = x.multiply(zInv).mod(P)
            val ny = y.multiply(zInv).mod(P)
            return nx == BigInteger.ZERO && ny == BigInteger.ONE
        }

        fun encode(): ByteArray {
            val zInv = z.modInverse(P)
            val nx = x.multiply(zInv).mod(P)
            val ny = y.multiply(zInv).mod(P)
            val out = bigIntToLittleEndian32(ny)
            if (nx.testBit(0)) {
                out[31] = (out[31].toInt() or 0x80).toByte()
            }
            return out
        }

        companion object {
            val IDENTITY = Point(BigInteger.ZERO, BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO)
        }
    }

    private val BASE_POINT: Point by lazy { decodePoint(BASE_BYTES)!! }
    private val POINT_M: Point by lazy { decodePoint(POINT_M_BYTES)!! }
    private val POINT_N: Point by lazy { decodePoint(POINT_N_BYTES)!! }

    internal fun decodePoint(bytes: ByteArray): Point? {
        if (bytes.size != 32) return null
        val xParity = (bytes[31].toInt() ushr 7) and 1
        val yBytes = bytes.copyOf()
        yBytes[31] = (yBytes[31].toInt() and 0x7F).toByte()
        val y = littleEndianToBigInt(yBytes)
        if (y >= P) return null

        val y2 = y.multiply(y).mod(P)
        val u = y2.subtract(BigInteger.ONE).mod(P)
        val v = D.multiply(y2).mod(P).add(BigInteger.ONE).mod(P)
        if (v == BigInteger.ZERO) return null

        val v3 = v.pow(3).mod(P)
        val v7 = v3.multiply(v3).mod(P).multiply(v).mod(P)
        val uv3 = u.multiply(v3).mod(P)
        val uv7 = u.multiply(v7).mod(P)
        var x = uv3.multiply(uv7.modPow(P_MINUS_5_DIV_8, P)).mod(P)

        val vx2 = v.multiply(x).mod(P).multiply(x).mod(P)
        if (vx2 != u) {
            if (vx2 == P.subtract(u).mod(P)) {
                x = x.multiply(SQRT_M1).mod(P)
            } else {
                return null
            }
        }
        val finalVx2 = v.multiply(x).mod(P).multiply(x).mod(P)
        if (finalVx2 != u) return null
        if (x == BigInteger.ZERO && xParity == 1) return null

        val actualParity = if (x.testBit(0)) 1 else 0
        if (actualParity != xParity) {
            x = P.subtract(x).mod(P)
        }

        val t = x.multiply(y).mod(P)
        return Point(x, y, BigInteger.ONE, t)
    }

    /**
     * Stateful BoringSSL `SPAKE2_CTX` equivalent.
     */
    class Spake2Session(
        private val role: Role,
        private val myName: ByteArray,
        private val theirName: ByteArray,
        private val random: SecureRandom = SecureRandom()
    ) {
        private var state = 0 // 0 = init, 1 = generated, 2 = finished
        private var myPrivateScalar: BigInteger = BigInteger.ZERO
        private var passwordScalarBytes: ByteArray = ByteArray(32)
        private var passwordScalar: BigInteger = BigInteger.ZERO
        private var myMessage: ByteArray = ByteArray(0)

        fun generateMessage(password: ByteArray): ByteArray {
            check(state == 0) { "SPAKE2 generateMessage already invoked" }

            // BoringSSL reduces SHA512(password) as 64-byte little-endian integer mod L
            val sha512 = MessageDigest.getInstance("SHA-512").digest(password)
            val wFull = littleEndianToBigInt(sha512)
            passwordScalar = wFull.mod(L)
            passwordScalarBytes = bigIntToLittleEndian32(passwordScalar)

            // Random clamped Ed25519 scalar (8 | scalar prevents small-subgroup attacks)
            val randBytes = ByteArray(32)
            random.nextBytes(randBytes)
            randBytes[0] = (randBytes[0].toInt() and 248).toByte()
            randBytes[31] = (randBytes[31].toInt() and 63).toByte()
            randBytes[31] = (randBytes[31].toInt() or 64).toByte()
            myPrivateScalar = littleEndianToBigInt(randBytes).mod(L)

            val blindBase = if (role == Role.ALICE_CLIENT) POINT_M else POINT_N
            val publicPoint = BASE_POINT.scalarMul(myPrivateScalar)
                .add(blindBase.scalarMul(passwordScalar))

            myMessage = publicPoint.encode()
            state = 1
            return myMessage.copyOf()
        }

        fun processMessage(theirMessage: ByteArray): ByteArray {
            check(state == 1) { "SPAKE2 processMessage called in invalid state=$state" }
            require(theirMessage.size == 32) { "Expected 32-byte SPAKE2 message, got ${theirMessage.size}" }

            val theirPoint = decodePoint(theirMessage)
                ?: throw IllegalArgumentException("Invalid Ed25519 point in peer SPAKE2 message")

            val unblindBase = if (role == Role.ALICE_CLIENT) POINT_N else POINT_M
            val unblinded = theirPoint.add(unblindBase.scalarMul(passwordScalar).negate())
            val sharedPoint = unblinded.scalarMul(myPrivateScalar)
            if (sharedPoint.isIdentity()) {
                throw IllegalArgumentException("Degenerate identity point encountered in SPAKE2 exchange")
            }

            val kBytes = sharedPoint.encode()

            val (aliceName, bobName, aliceMsg, bobMsg) = if (role == Role.ALICE_CLIENT) {
                Quad(myName, theirName, myMessage, theirMessage)
            } else {
                Quad(theirName, myName, theirMessage, myMessage)
            }

            val sha512 = MessageDigest.getInstance("SHA-512")
            updateWithLengthPrefix(sha512, aliceName)
            updateWithLengthPrefix(sha512, bobName)
            updateWithLengthPrefix(sha512, aliceMsg)
            updateWithLengthPrefix(sha512, bobMsg)
            updateWithLengthPrefix(sha512, kBytes)
            updateWithLengthPrefix(sha512, passwordScalarBytes)

            state = 2
            return sha512.digest()
        }

        private data class Quad(
            val a: ByteArray,
            val b: ByteArray,
            val c: ByteArray,
            val d: ByteArray
        )

        private fun updateWithLengthPrefix(md: MessageDigest, data: ByteArray) {
            val lenBuf = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            lenBuf.putLong(data.size.toLong())
            md.update(lenBuf.array())
            md.update(data)
        }
    }

    private fun littleEndianToBigInt(bytes: ByteArray): BigInteger {
        val be = bytes.reversedArray()
        return BigInteger(1, be)
    }

    private fun bigIntToLittleEndian32(value: BigInteger): ByteArray {
        val be = value.toByteArray()
        val out = ByteArray(32)
        // Copy from LSB (end of be) into out[0..31]
        var dst = 0
        for (src in be.indices.reversed()) {
            if (dst >= 32) break
            out[dst++] = be[src]
        }
        return out
    }

    private fun hexToBytes(hex: String): ByteArray {
        val len = hex.length / 2
        return ByteArray(len) { i ->
            hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }
}
