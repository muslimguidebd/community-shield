package com.muslimguide.shield.adb

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.PrintStream
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

/**
 * Controller executing native libadb.so commands for pairing, connection,
 * and remote shell execution on Android 11+ Wireless Debugging.
 */
class AdbNativeController(private val context: Context) {

    companion object {
        private const val TAG = "AdbNativeController"
    }

    val adbPath: String by lazy { getExecutableAdbPath() }

    private fun getExecutableAdbPath(): String {
        val nativeDirFile = File(context.applicationInfo.nativeLibraryDir, "libadb.so")
        if (nativeDirFile.exists() && nativeDirFile.canExecute()) {
            return nativeDirFile.absolutePath
        }

        val internalFile = File(context.filesDir, "libadb.so")
        if (internalFile.exists() && internalFile.length() > 0) {
            internalFile.setExecutable(true, false)
            if (internalFile.canExecute()) {
                return internalFile.absolutePath
            }
        }

        // Fallback: Extract directly from APK
        try {
            val apkFile = File(context.applicationInfo.sourceDir)
            if (apkFile.exists()) {
                ZipFile(apkFile).use { zip ->
                    val abi = Build.SUPPORTED_ABIS.firstOrNull { it.contains("arm64") }
                        ?: Build.SUPPORTED_ABIS.firstOrNull { it.contains("arm") }
                        ?: "arm64-v8a"

                    val entry = zip.getEntry("lib/$abi/libadb.so")
                        ?: zip.getEntry("lib/arm64-v8a/libadb.so")
                        ?: zip.getEntry("lib/armeabi-v7a/libadb.so")

                    if (entry != null) {
                        zip.getInputStream(entry).use { input ->
                            internalFile.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                        internalFile.setReadable(true, false)
                        internalFile.setExecutable(true, false)
                        Log.d(TAG, "Extracted libadb.so to ${internalFile.absolutePath} from APK")
                        return internalFile.absolutePath
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting libadb.so from APK", e)
        }

        return nativeDirFile.absolutePath
    }

    /**
     * Pairs the device via ADB TLS pairing.
     */
    fun pair(ip: String, port: String, code: String): Boolean {
        return try {
            val targetHost = if (ip == "127.0.0.1" || ip == "localhost") "localhost" else ip
            val pb = ProcessBuilder(adbPath, "pair", "$targetHost:$port")
            pb.environment()["HOME"] = context.filesDir.absolutePath
            pb.environment()["TMPDIR"] = context.cacheDir.absolutePath
            val p = pb.start()

            // Allow adb pair process to initialize prompt
            Thread.sleep(350)

            val ps = PrintStream(p.outputStream)
            ps.println(code.trim())
            ps.flush()

            p.waitFor(10, TimeUnit.SECONDS)
            val stdout = p.inputStream.bufferedReader().use { it.readText() }
            val stderr = p.errorStream.bufferedReader().use { it.readText() }
            val exitCode = try { p.exitValue() } catch (_: Exception) { -1 }
            val output = "$stdout $stderr"

            Log.d(TAG, "pair -> ip: $ip, port: $port, exitCode: $exitCode, stdout: $stdout, stderr: $stderr")
            exitCode == 0 || output.contains("Successfully paired", ignoreCase = true)
        } catch (e: Exception) {
            Log.e(TAG, "pair error", e)
            false
        }
    }

    /**
     * Connects to the device on the active ADB connect port.
     */
    fun connect(ip: String, port: String): Boolean {
        return try {
            val serverPb = ProcessBuilder(adbPath, "start-server")
            serverPb.environment()["HOME"] = context.filesDir.absolutePath
            serverPb.environment()["TMPDIR"] = context.cacheDir.absolutePath
            serverPb.start().waitFor(5, TimeUnit.SECONDS)

            val targetIps = if (ip == "127.0.0.1" || ip == "localhost") {
                val wifiIp = getLocalWifiIp()
                if (!wifiIp.isNullOrBlank() && wifiIp != "127.0.0.1") {
                    listOf("127.0.0.1", "localhost", wifiIp)
                } else {
                    listOf("127.0.0.1", "localhost")
                }
            } else {
                listOf(ip, "127.0.0.1", "localhost")
            }

            var isSuccess = false
            for (targetIp in targetIps) {
                val pb = ProcessBuilder(adbPath, "connect", "$targetIp:$port")
                pb.environment()["HOME"] = context.filesDir.absolutePath
                pb.environment()["TMPDIR"] = context.cacheDir.absolutePath
                val p = pb.start()
                p.waitFor(5, TimeUnit.SECONDS)

                val stdout = p.inputStream.bufferedReader().use { it.readText() }
                val stderr = p.errorStream.bufferedReader().use { it.readText() }
                val output = "$stdout $stderr"
                Log.d(TAG, "connect -> ip: $targetIp, port: $port, output: $output")

                if (output.contains("connected to", ignoreCase = true) &&
                    !output.contains("failed", ignoreCase = true) &&
                    !output.contains("refused", ignoreCase = true)
                ) {
                    isSuccess = true
                    break
                }
            }

            isSuccess || !getLocalDeviceSerial().isNullOrBlank()
        } catch (e: Exception) {
            Log.e(TAG, "connect error", e)
            false
        }
    }

    private fun getLocalWifiIp(): String? {
        return try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.isLoopback || !iface.isUp) continue
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (addr is java.net.Inet4Address && !addr.isLoopbackAddress) {
                        return addr.hostAddress
                    }
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Gets the first local or active device serial from 'adb devices'.
     * Prevents 'more than one device/emulator' error when multiple entries exist.
     */
    fun getLocalDeviceSerial(): String? {
        return try {
            val pb = ProcessBuilder(adbPath, "devices")
            pb.environment()["HOME"] = context.filesDir.absolutePath
            pb.environment()["TMPDIR"] = context.cacheDir.absolutePath
            val p = pb.start()
            val lines = p.inputStream.bufferedReader().use { it.readLines() }
            p.waitFor(5, TimeUnit.SECONDS)
            val deviceLines = lines.filterNot { it.contains("List of devices attached") }
                .filter { it.contains("device") && !it.contains("offline") && !it.contains("unauthorized") }
                .map { it.split(Regex("\\s+")).first().trim() }
                .filter { it.isNotBlank() }

            deviceLines.firstOrNull { it.startsWith("127.0.0.1") || it.startsWith("localhost") }
                ?: deviceLines.firstOrNull()
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Installs an APK via ADB streaming install.
     */
    fun installApk(apkPath: String): Pair<Boolean, String> {
        return try {
            val serial = getLocalDeviceSerial()
            val cmdList = if (!serial.isNullOrBlank()) {
                listOf(adbPath, "-s", serial, "install", "-r", "-t", apkPath)
            } else {
                listOf(adbPath, "install", "-r", "-t", apkPath)
            }
            val pb = ProcessBuilder(cmdList)
            pb.environment()["HOME"] = context.filesDir.absolutePath
            pb.environment()["TMPDIR"] = context.cacheDir.absolutePath
            val p = pb.start()

            val stdout = p.inputStream.bufferedReader().use { it.readText() }
            val stderr = p.errorStream.bufferedReader().use { it.readText() }
            p.waitFor(30, TimeUnit.SECONDS)

            val output = "$stdout $stderr".trim()
            val success = output.contains("Success", ignoreCase = true) || p.exitValue() == 0
            Log.d(TAG, "installApk -> output: $output, success: $success")
            Pair(success, output)
        } catch (e: Exception) {
            Log.e(TAG, "installApk error", e)
            Pair(false, e.message ?: "Install error")
        }
    }

    /**
     * Executes a remote shell command on the connected ADB daemon.
     */
    fun executeShell(command: String): String {
        return try {
            val serial = getLocalDeviceSerial()
            val cmdList = if (!serial.isNullOrBlank()) {
                listOf(adbPath, "-s", serial, "shell", command)
            } else {
                listOf(adbPath, "shell", command)
            }
            val pb = ProcessBuilder(cmdList)
            pb.environment()["HOME"] = context.filesDir.absolutePath
            pb.environment()["TMPDIR"] = context.cacheDir.absolutePath
            val p = pb.start()
            val output = p.inputStream.bufferedReader().use { it.readText() }
            val error = p.errorStream.bufferedReader().use { it.readText() }
            p.waitFor(20, TimeUnit.SECONDS)
            val result = if (output.isNotBlank()) output else error
            Log.d(TAG, "executeShell '$command' on $serial -> $result")
            result.trim()
        } catch (e: Exception) {
            Log.e(TAG, "executeShell error", e)
            "Error: ${e.message}"
        }
    }
}
