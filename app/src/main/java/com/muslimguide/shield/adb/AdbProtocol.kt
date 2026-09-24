package com.muslimguide.shield.adb

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * ADB Protocol constants and packet definitions.
 * Follows the official Android Open Source Project (AOSP) ADB protocol specification.
 */
object AdbProtocol {
    const val A_SYNC: Int = 0x434e5953
    const val A_CNXN: Int = 0x4e584e43
    const val A_OPEN: Int = 0x4e45504f
    const val A_OKAY: Int = 0x59414b4f
    const val A_CLSE: Int = 0x45534c43
    const val A_WRTE: Int = 0x45545257
    const val A_AUTH: Int = 0x48545541

    const val AUTH_TYPE_TOKEN: Int = 1
    const val AUTH_TYPE_SIGNATURE: Int = 2
    const val AUTH_TYPE_RSAPUBLICKEY: Int = 3

    const val A_VERSION: Int = 0x01000000
    const val MAX_PAYLOAD: Int = 4096

    const val DEFAULT_CONNECT_PORT: Int = 5555
    const val LOCALHOST: String = "127.0.0.1"

    /**
     * Represents a standard 24-byte ADB message header.
     */
    data class AdbMessage(
        val command: Int,
        val arg0: Int,
        val arg1: Int,
        val dataLength: Int,
        val dataCrc: Int,
        val magic: Int,
        val payload: ByteArray = ByteArray(0)
    ) {
        fun toByteArray(): ByteArray {
            val buffer = ByteBuffer.allocate(24 + payload.size).order(ByteOrder.LITTLE_ENDIAN)
            buffer.putInt(command)
            buffer.putInt(arg0)
            buffer.putInt(arg1)
            buffer.putInt(dataLength)
            buffer.putInt(dataCrc)
            buffer.putInt(magic)
            if (payload.isNotEmpty()) {
                buffer.put(payload)
            }
            return buffer.array()
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as AdbMessage
            return command == other.command && arg0 == other.arg0 && arg1 == other.arg1 &&
                    dataLength == other.dataLength && dataCrc == other.dataCrc && magic == other.magic &&
                    payload.contentEquals(other.payload)
        }

        override fun hashCode(): Int {
            var result = command
            result = 31 * result + arg0
            result = 31 * result + arg1
            result = 31 * result + dataLength
            result = 31 * result + dataCrc
            result = 31 * result + magic
            result = 31 * result + payload.contentHashCode()
            return result
        }

        companion object {
            fun create(command: Int, arg0: Int, arg1: Int, payload: ByteArray = ByteArray(0)): AdbMessage {
                var checksum = 0
                for (b in payload) {
                    checksum += (b.toInt() and 0xFF)
                }
                return AdbMessage(
                    command = command,
                    arg0 = arg0,
                    arg1 = arg1,
                    dataLength = payload.size,
                    dataCrc = checksum,
                    magic = command xor -0x1,
                    payload = payload
                )
            }

            fun parse(headerBytes: ByteArray, payload: ByteArray = ByteArray(0)): AdbMessage {
                val buffer = ByteBuffer.wrap(headerBytes).order(ByteOrder.LITTLE_ENDIAN)
                val cmd = buffer.int
                val arg0 = buffer.int
                val arg1 = buffer.int
                val len = buffer.int
                val crc = buffer.int
                val magic = buffer.int
                return AdbMessage(cmd, arg0, arg1, len, crc, magic, payload)
            }
        }
    }
}
