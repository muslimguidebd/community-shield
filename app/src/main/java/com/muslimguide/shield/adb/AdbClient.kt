package com.muslimguide.shield.adb

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Socket client to communicate with the Android ADB daemon.
 * Handles the full handshake and shell stream execution.
 */
class AdbClient(
    private val host: String = AdbProtocol.LOCALHOST,
    private val port: Int = AdbProtocol.DEFAULT_CONNECT_PORT,
    private val crypto: AdbCrypto
) {
    private var socket: Socket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null

    private var localId: Int = 1
    private var remoteId: Int = 0

    suspend fun connect(timeoutMs: Int = 5000): Boolean = withContext(Dispatchers.IO) {
        try {
            val sock = Socket()
            sock.connect(InetSocketAddress(host, port), timeoutMs)
            sock.soTimeout = 10000
            socket = sock
            inputStream = sock.getInputStream()
            outputStream = sock.getOutputStream()

            // Send A_CNXN (Connection request)
            val banner = "host::\u0000".toByteArray(Charsets.UTF_8)
            val cnxnMsg = AdbProtocol.AdbMessage.create(
                command = AdbProtocol.A_CNXN,
                arg0 = AdbProtocol.A_VERSION,
                arg1 = AdbProtocol.MAX_PAYLOAD,
                payload = banner
            )
            sendMessage(cnxnMsg)

            // Read response
            var response = readMessage()

            // If response is A_AUTH, authenticate
            if (response.command == AdbProtocol.A_AUTH) {
                if (response.arg0 == AdbProtocol.AUTH_TYPE_TOKEN) {
                    val token = response.payload
                    val signedToken = crypto.signToken(token)

                    val authMsg = AdbProtocol.AdbMessage.create(
                        command = AdbProtocol.A_AUTH,
                        arg0 = AdbProtocol.AUTH_TYPE_SIGNATURE,
                        arg1 = 0,
                        payload = signedToken
                    )
                    sendMessage(authMsg)

                    response = readMessage()

                    // If still A_AUTH, send public key
                    if (response.command == AdbProtocol.A_AUTH) {
                        val pubKeyPayload = crypto.getAdbPublicKey()
                        val pubKeyMsg = AdbProtocol.AdbMessage.create(
                            command = AdbProtocol.A_AUTH,
                            arg0 = AdbProtocol.AUTH_TYPE_RSAPUBLICKEY,
                            arg1 = 0,
                            payload = pubKeyPayload
                        )
                        sendMessage(pubKeyMsg)
                        response = readMessage()
                    }
                }
            }

            response.command == AdbProtocol.A_CNXN
        } catch (e: Exception) {
            disconnect()
            false
        }
    }

    suspend fun executeCommand(command: String): String = withContext(Dispatchers.IO) {
        val inStream = inputStream ?: throw IllegalStateException("Not connected to ADB")
        val outStream = outputStream ?: throw IllegalStateException("Not connected to ADB")

        val currentLocalId = localId++
        val destination = "shell:$command\u0000".toByteArray(Charsets.UTF_8)

        // Send A_OPEN
        val openMsg = AdbProtocol.AdbMessage.create(
            command = AdbProtocol.A_OPEN,
            arg0 = currentLocalId,
            arg1 = 0,
            payload = destination
        )
        sendMessage(openMsg)

        val output = StringBuilder()

        while (true) {
            val msg = readMessage()
            when (msg.command) {
                AdbProtocol.A_OKAY -> {
                    remoteId = msg.arg0
                }
                AdbProtocol.A_WRTE -> {
                    if (msg.payload.isNotEmpty()) {
                        output.append(String(msg.payload, Charsets.UTF_8))
                    }
                    // Acknowledge with A_OKAY
                    val okayMsg = AdbProtocol.AdbMessage.create(
                        command = AdbProtocol.A_OKAY,
                        arg0 = currentLocalId,
                        arg1 = msg.arg0
                    )
                    sendMessage(okayMsg)
                }
                AdbProtocol.A_CLSE -> {
                    // Close stream
                    val clseMsg = AdbProtocol.AdbMessage.create(
                        command = AdbProtocol.A_CLSE,
                        arg0 = currentLocalId,
                        arg1 = msg.arg0
                    )
                    sendMessage(clseMsg)
                    break
                }
                else -> {
                    break
                }
            }
        }

        output.toString().trim()
    }

    fun disconnect() {
        try {
            socket?.close()
        } catch (_: Exception) {}
        socket = null
        inputStream = null
        outputStream = null
    }

    private fun sendMessage(msg: AdbProtocol.AdbMessage) {
        outputStream?.write(msg.toByteArray())
        outputStream?.flush()
    }

    private fun readMessage(): AdbProtocol.AdbMessage {
        val inStream = inputStream ?: throw IllegalStateException("Input stream is null")
        val headerBytes = ByteArray(24)
        readFully(inStream, headerBytes)

        val tempMsg = AdbProtocol.AdbMessage.parse(headerBytes)
        val payload = if (tempMsg.dataLength > 0) {
            val payloadBytes = ByteArray(tempMsg.dataLength)
            readFully(inStream, payloadBytes)
            payloadBytes
        } else {
            ByteArray(0)
        }

        return AdbProtocol.AdbMessage.parse(headerBytes, payload)
    }

    private fun readFully(inStream: InputStream, buffer: ByteArray) {
        var bytesRead = 0
        while (bytesRead < buffer.size) {
            val count = inStream.read(buffer, bytesRead, buffer.size - bytesRead)
            if (count < 0) {
                throw IllegalStateException("Unexpected EOF while reading ADB message")
            }
            bytesRead += count
        }
    }
}
