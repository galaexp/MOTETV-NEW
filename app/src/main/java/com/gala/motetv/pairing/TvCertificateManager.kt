package com.gala.motetv.pairing

import com.gala.motetv.core.logging.TvLogger
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.math.BigInteger
import java.net.Socket
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Date
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509KeyManager
import javax.net.ssl.X509TrustManager

data class TvIdentity(
    val keyPair: KeyPair,
    val certificate: X509Certificate
)

class TvCertificateManager {

    companion object {
        private const val RSA_KEY_SIZE = 2048
        private const val CERT_VALIDITY_DAYS = 3650L // 10 years

        @Volatile
        private var sharedIdentity: TvIdentity? = null

        fun getOrCreateSharedIdentity(): TvIdentity {
            return sharedIdentity ?: synchronized(this) {
                sharedIdentity ?: generateIdentity().also { sharedIdentity = it }
            }
        }

        fun generateIdentity(commonName: String = "MoteTV"): TvIdentity {
            val keyPairGen = KeyPairGenerator.getInstance("RSA")
            keyPairGen.initialize(RSA_KEY_SIZE, SecureRandom())
            val keyPair = keyPairGen.generateKeyPair()

            val cert = createSelfSignedX509Certificate(keyPair, commonName)
            TvLogger.i(TvLogger.TAG_TLS, "Generated RSA-2048 keypair and self-signed X.509 certificate (CN=$commonName)")
            return TvIdentity(keyPair, cert)
        }

        /**
         * Creates a valid DER-encoded self-signed X.509 v3 certificate.
         */
        fun createSelfSignedX509Certificate(
            keyPair: KeyPair,
            commonName: String = "MoteTV"
        ): X509Certificate {
            val serialNumber = BigInteger(64, SecureRandom()).abs()
            val notBefore = Date(System.currentTimeMillis() - 24 * 3600 * 1000L) // Yesterday
            val notAfter = Date(System.currentTimeMillis() + CERT_VALIDITY_DAYS * 24 * 3600 * 1000L)

            val derCert = generateX509DerBytes(
                serialNumber = serialNumber,
                notBefore = notBefore,
                notAfter = notAfter,
                commonName = commonName,
                publicKey = keyPair.public.encoded,
                privateKey = keyPair.private
            )

            val certFactory = CertificateFactory.getInstance("X.509")
            return certFactory.generateCertificate(ByteArrayInputStream(derCert)) as X509Certificate
        }

        private fun generateX509DerBytes(
            serialNumber: BigInteger,
            notBefore: Date,
            notAfter: Date,
            commonName: String,
            publicKey: ByteArray,
            privateKey: PrivateKey
        ): ByteArray {
            // TBSCertificate ASN.1 structure:
            // [0] Version (v3 = 2)
            // SerialNumber (INTEGER)
            // SignatureAlgorithm (sha256WithRSAEncryption: 1.2.840.113549.1.1.11)
            // Issuer (Name: CN=commonName)
            // Validity (notBefore, notAfter: UTCTime)
            // Subject (Name: CN=commonName)
            // SubjectPublicKeyInfo (publicKey bytes)

            val tbsStream = ByteArrayOutputStream()

            // Version: [0] { INTEGER 2 } (v3)
            val versionDer = encodeExplicitTag(0, encodeInteger(BigInteger.valueOf(2)))
            tbsStream.write(versionDer)

            // SerialNumber
            tbsStream.write(encodeInteger(serialNumber))

            // SignatureAlgorithm: sha256WithRSAEncryption
            val sha256RsaOid = byteArrayOf(
                0x2a.toByte(), 0x86.toByte(), 0x48.toByte(), 0x86.toByte(),
                0xf7.toByte(), 0x0d.toByte(), 0x01.toByte(), 0x01.toByte(), 0x0b.toByte()
            )
            val sigAlgDer = encodeSequence(encodeOid(sha256RsaOid) + encodeNull())
            tbsStream.write(sigAlgDer)

            // Issuer: SEQUENCE { SET { SEQUENCE { OID(2.5.4.3), UTF8String(commonName) } } }
            val nameDer = encodeName(commonName)
            tbsStream.write(nameDer)

            // Validity: SEQUENCE { UTCTime(notBefore), UTCTime(notAfter) }
            val validityDer = encodeValidity(notBefore, notAfter)
            tbsStream.write(validityDer)

            // Subject: same as Issuer
            tbsStream.write(nameDer)

            // SubjectPublicKeyInfo: already encoded in keyPair.public.encoded
            tbsStream.write(publicKey)

            val tbsCertificate = encodeSequence(tbsStream.toByteArray())

            // Sign TBSCertificate
            val signer = Signature.getInstance("SHA256withRSA")
            signer.initSign(privateKey)
            signer.update(tbsCertificate)
            val signatureBytes = signer.sign()

            // BitString with 0 unused bits
            val bitStringSignature = encodeBitString(signatureBytes)

            // Final Certificate: SEQUENCE { tbsCertificate, signatureAlgorithm, signatureValue }
            val finalCertStream = ByteArrayOutputStream()
            finalCertStream.write(tbsCertificate)
            finalCertStream.write(sigAlgDer)
            finalCertStream.write(bitStringSignature)

            return encodeSequence(finalCertStream.toByteArray())
        }

        private fun encodeSequence(content: ByteArray): ByteArray = encodeTag(0x30, content)

        private fun encodeSet(content: ByteArray): ByteArray = encodeTag(0x31, content)

        private fun encodeExplicitTag(tagNumber: Int, content: ByteArray): ByteArray =
            encodeTag(0xA0 or (tagNumber and 0x1F), content)

        private fun encodeTag(tag: Int, content: ByteArray): ByteArray {
            val out = ByteArrayOutputStream()
            out.write(tag)
            val length = content.size
            if (length < 128) {
                out.write(length)
            } else if (length < 256) {
                out.write(0x81)
                out.write(length)
            } else if (length < 65536) {
                out.write(0x82)
                out.write((length shr 8) and 0xFF)
                out.write(length and 0xFF)
            } else {
                out.write(0x83)
                out.write((length shr 16) and 0xFF)
                out.write((length shr 8) and 0xFF)
                out.write(length and 0xFF)
            }
            out.write(content)
            return out.toByteArray()
        }

        private fun encodeInteger(value: BigInteger): ByteArray {
            return encodeTag(0x02, value.toByteArray())
        }

        private fun encodeOid(oidBytes: ByteArray): ByteArray {
            return encodeTag(0x06, oidBytes)
        }

        private fun encodeNull(): ByteArray {
            return byteArrayOf(0x05, 0x00)
        }

        private fun encodeUtf8String(str: String): ByteArray {
            return encodeTag(0x0C, str.toByteArray(Charsets.UTF_8))
        }

        private fun encodePrintableString(str: String): ByteArray {
            return encodeTag(0x13, str.toByteArray(Charsets.US_ASCII))
        }

        private fun encodeBitString(data: ByteArray): ByteArray {
            val out = ByteArray(data.size + 1)
            out[0] = 0x00 // 0 unused bits
            System.arraycopy(data, 0, out, 1, data.size)
            return encodeTag(0x03, out)
        }

        private fun encodeName(commonName: String): ByteArray {
            val cnOid = byteArrayOf(0x55, 0x04, 0x03) // 2.5.4.3 commonName
            val rdnSequence = encodeSequence(encodeOid(cnOid) + encodeUtf8String(commonName))
            val rdnSet = encodeSet(rdnSequence)
            return encodeSequence(rdnSet)
        }

        private fun encodeValidity(notBefore: Date, notAfter: Date): ByteArray {
            val format = java.text.SimpleDateFormat("yyMMddHHmmss'Z'", java.util.Locale.US).apply {
                timeZone = java.util.TimeZone.getTimeZone("UTC")
            }
            val b1 = format.format(notBefore).toByteArray(Charsets.US_ASCII)
            val b2 = format.format(notAfter).toByteArray(Charsets.US_ASCII)
            val utcTime1 = encodeTag(0x17, b1)
            val utcTime2 = encodeTag(0x17, b2)
            return encodeSequence(utcTime1 + utcTime2)
        }
    }

    /**
     * Captures the TV's peer X.509 certificate during TLS handshake.
     */
    var capturedPeerCertificate: X509Certificate? = null
        private set

    fun createSslContext(identity: TvIdentity): SSLContext {
        val keyManager = object : X509KeyManager {
            override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String> =
                arrayOf("motetv-client")

            override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?): String =
                "motetv-client"

            override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null

            override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? = null

            override fun getCertificateChain(alias: String?): Array<X509Certificate> =
                arrayOf(identity.certificate)

            override fun getPrivateKey(alias: String?): PrivateKey = identity.keyPair.private
        }

        val trustManager = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                // Client trust not evaluated on client side
            }

            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                if (!chain.isNullOrEmpty()) {
                    capturedPeerCertificate = chain[0]
                    TvLogger.i(
                        TvLogger.TAG_TLS,
                        "Captured TV Server Certificate during TLS handshake: Subject=${chain[0].subjectDN.name}"
                    )
                }
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }

        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(
            arrayOf<KeyManager>(keyManager),
            arrayOf<TrustManager>(trustManager),
            SecureRandom()
        )
        return sslContext
    }
}
