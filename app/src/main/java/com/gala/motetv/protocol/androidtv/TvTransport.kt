package com.gala.motetv.protocol.androidtv

import java.io.Closeable
import java.security.cert.X509Certificate

/**
 * Interface abstracting the mutual TLS transport connection to an Android TV.
 */
interface TvTransport : Closeable {
    val isConnected: Boolean
    val serverCertificate: X509Certificate?
    val frameReader: ProtoFrameReader?
    val frameWriter: ProtoFrameWriter?

    suspend fun connect()
}
