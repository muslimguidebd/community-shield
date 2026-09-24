package com.muslimguide.shield.adb

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import com.muslimguide.shield.R
import java.net.Inet4Address
import java.net.NetworkInterface

class AdbWirelessNotificationService : Service() {

    companion object {
        private const val TAG = "AdbWirelessService"
        const val CHANNEL_ID = "adb_wireless_high_priority_v3"
        const val NOTIFICATION_ID = 2001

        const val SERVICE_TYPE_PAIRING = "_adb-tls-pairing._tcp"
        const val SERVICE_TYPE_CONNECT = "_adb-tls-connect._tcp"

        const val ACTION_START_SCANNING = "com.muslimguide.shield.START_ADB_SCANNING"
        const val ACTION_STOP_SERVICE = "com.muslimguide.shield.STOP_ADB_SERVICE"
        const val ACTION_SEND_PAIRING_CODE = "com.muslimguide.shield.SEND_PAIRING_CODE"
        const val ACTION_ADB_STATUS_UPDATE = "com.muslimguide.shield.ADB_STATUS_UPDATE"

        const val EXTRA_STATUS_MESSAGE = "extra_status_message"
        const val EXTRA_STATUS_SUCCESS = "extra_status_success"
        const val EXTRA_PAIRING_CODE = "pairing_code"
        const val EXTRA_PAIRING_PORT = "pairing_port"
        const val EXTRA_CONNECT_PORT = "extra_connect_port"

        @Volatile
        var pendingPairingPort: String? = null
        @Volatile
        var pendingPairingHost: String? = null

        fun start(context: Context) {
            val intent = Intent(context, AdbWirelessNotificationService::class.java).apply {
                action = ACTION_START_SCANNING
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, AdbWirelessNotificationService::class.java).apply {
                action = ACTION_STOP_SERVICE
            }
            context.startService(intent)
        }
    }

    private lateinit var notificationManager: NotificationManager
    private lateinit var adbController: AdbNativeController
    private var nsdManager: NsdManager? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var localIpAddress: String = "127.0.0.1"

    private var isSearchingForPairing = true
    private var isResolving = false
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NotificationManager::class.java)
        adbController = AdbNativeController(this)
        nsdManager = getSystemService(Context.NSD_SERVICE) as? NsdManager

        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        multicastLock = wifiManager?.createMulticastLock("AdbMdnsLock")?.apply {
            setReferenceCounted(true)
            try { acquire() } catch (_: Exception) {}
        }

        localIpAddress = getLocalIpAddress()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) return START_NOT_STICKY

        val action = intent.action
        if (ACTION_STOP_SERVICE == action) {
            stopScanningAndSelf()
            return START_NOT_STICKY
        }

        val pairingCode = intent.getStringExtra(EXTRA_PAIRING_CODE)
        val pairingPort = intent.getStringExtra(EXTRA_PAIRING_PORT) ?: pendingPairingPort

        if (!pairingCode.isNullOrBlank()) {
            handlePairingProcess(pairingCode.trim(), pairingPort)
        } else {
            isSearchingForPairing = true
            startInitialForeground()
            startMdnsDiscovery(SERVICE_TYPE_PAIRING)
        }
        return START_STICKY
    }

    private fun startInitialForeground() {
        val stopIntent = Intent(this, AdbWirelessNotificationService::class.java).apply {
            action = ACTION_STOP_SERVICE
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ওয়্যারলেস ডিবাগিং স্ক্যান করা হচ্ছে...")
            .setContentText("Developer Options এ 'Pair device with pairing code' চাপুন")
            .setSmallIcon(R.drawable.ic_radar_rounded)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "বাতিল", stopPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    private fun showInputNotification(port: String, host: String?) {
        pendingPairingPort = port
        if (!host.isNullOrBlank()) {
            pendingPairingHost = host
        }

        val remoteInput = RemoteInput.Builder(EXTRA_PAIRING_CODE)
            .setLabel("৬ ডিজিটের পেয়ারিং কোড লিখুন")
            .build()

        val pairingIntent = Intent(this, AdbWirelessReceiver::class.java).apply {
            action = ACTION_SEND_PAIRING_CODE
            putExtra(EXTRA_PAIRING_PORT, port)
        }
        val pairingPendingIntent = PendingIntent.getBroadcast(
            this, 2, pairingIntent,
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = Intent(this, AdbWirelessNotificationService::class.java).apply {
            action = ACTION_STOP_SERVICE
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 3, stopIntent,
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val inputAction = NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_send, "পেয়ার করুন", pairingPendingIntent
        ).addRemoteInput(remoteInput).build()

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("পেয়ারিং পোর্ট পাওয়া গেছে: $port")
            .setContentText("স্ক্রিনে প্রদর্শিত ৬ ডিজিটের কোড দিন")
            .setSmallIcon(R.drawable.ic_lock)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setVibrate(longArrayOf(0, 250, 100, 250))
            .addAction(inputAction)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "বাতিল", stopPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(false)
            .build()

        startForeground(NOTIFICATION_ID, notification)
        broadcastStatus("পেয়ারিং পোর্ট $port পাওয়া গেছে। নোটিফিকেশনে কোড দিন।", false)
    }

    private var bestServiceName: String? = null

    private fun getServiceVersionNumber(name: String): Int {
        val regex = """\((\d+)\)""".toRegex()
        return regex.find(name)?.groupValues?.get(1)?.toIntOrNull() ?: 1
    }

    private val foundServices = mutableListOf<NsdServiceInfo>()
    private var resolveRunnable: Runnable? = null

    private fun startMdnsDiscovery(serviceType: String) {
        stopMdnsDiscovery()
        foundServices.clear()
        resolveRunnable?.let { mainHandler.removeCallbacks(it) }

        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(service: NsdServiceInfo) {
                Log.d(TAG, "onServiceFound: ${service.serviceName}, type: ${service.serviceType}")
                synchronized(foundServices) {
                    foundServices.removeAll { it.serviceName == service.serviceName }
                    foundServices.add(service)
                }

                // Debounce by 700ms to allow all mDNS announcements (e.g. (2), (3)) to arrive,
                // then resolve the highest numbered instance
                resolveRunnable?.let { mainHandler.removeCallbacks(it) }
                resolveRunnable = Runnable {
                    val bestService = synchronized(foundServices) {
                        foundServices.maxByOrNull { getServiceVersionNumber(it.serviceName) }
                    } ?: return@Runnable

                    Log.d(TAG, "Selected best mDNS instance to resolve: ${bestService.serviceName}")
                    tryResolveService(bestService, serviceType)
                }
                mainHandler.postDelayed(resolveRunnable!!, 700)
            }

            override fun onServiceLost(s: NsdServiceInfo) {
                Log.d(TAG, "onServiceLost: ${s.serviceName}")
                synchronized(foundServices) {
                    foundServices.removeAll { it.serviceName == s.serviceName }
                }
            }
            override fun onDiscoveryStarted(s: String) {
                Log.d(TAG, "onDiscoveryStarted: $s")
            }
            override fun onDiscoveryStopped(s: String) {
                Log.d(TAG, "onDiscoveryStopped: $s")
            }
            override fun onStartDiscoveryFailed(s: String, errorCode: Int) {
                Log.e(TAG, "onStartDiscoveryFailed: $errorCode")
            }
            override fun onStopDiscoveryFailed(s: String, errorCode: Int) {
                Log.e(TAG, "onStopDiscoveryFailed: $errorCode")
            }
        }

        try {
            nsdManager?.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        } catch (e: Exception) {
            Log.e(TAG, "discoverServices exception", e)
        }
    }

    private fun tryResolveService(service: NsdServiceInfo, serviceType: String) {
        if (isSearchingForPairing) {
            try {
                nsdManager?.resolveService(service, object : NsdManager.ResolveListener {
                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        val port = serviceInfo.port.toString()
                        val host = serviceInfo.host?.hostAddress ?: localIpAddress
                        Log.d(TAG, "onServiceResolved (Pairing): $serviceType on $host:$port (${serviceInfo.serviceName})")

                        mainHandler.post {
                            stopMdnsDiscovery()
                            showInputNotification(port, host)
                        }
                    }

                    override fun onResolveFailed(s: NsdServiceInfo, errorCode: Int) {
                        Log.w(TAG, "onResolveFailed for ${s.serviceName}: errorCode=$errorCode")
                    }
                })
            } catch (e: Exception) {
                Log.e(TAG, "resolveService error", e)
            }
        } else {
            // For Connect service: resolve and attempt connection
            tryConnectCandidate(service)
        }
    }

    private fun tryConnectCandidate(service: NsdServiceInfo) {
        try {
            nsdManager?.resolveService(service, object : NsdManager.ResolveListener {
                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    val port = serviceInfo.port.toString()
                    val host = serviceInfo.host?.hostAddress ?: localIpAddress
                    val portInt = serviceInfo.port
                    if (portInt <= 0) return

                    Log.d(TAG, "onServiceResolved (Connect): on $host:$port (${serviceInfo.serviceName})")

                    Thread {
                        broadcastStatus("$ adb connect $host:$port", false, portInt)
                        var connected = adbController.connect(host, port)
                        if (!connected && host != "127.0.0.1") {
                            try { Thread.sleep(300) } catch (_: Exception) {}
                            connected = adbController.connect("127.0.0.1", port)
                        }

                        if (connected) {
                            stopMdnsDiscovery()
                            getSharedPreferences("muslim_community", Context.MODE_PRIVATE)
                                .edit()
                                .putBoolean("pref_adb_connected", true)
                                .putInt("pref_adb_port", portInt)
                                .apply()

                            val connectedNotif = NotificationCompat.Builder(this@AdbWirelessNotificationService, CHANNEL_ID)
                                .setContentTitle("ADB সফলভাবে সংযুক্ত হয়েছে")
                                .setContentText("অ্যাপে গিয়ে কমান্ডগুলো সম্পন্ন করুন")
                                .setSmallIcon(R.drawable.ic_lock)
                                .setAutoCancel(true)
                                .build()
                            startForeground(NOTIFICATION_ID, connectedNotif)

                            broadcastStatus("✓ ADB সফলভাবে সংযুক্ত হয়েছে ($host:$port)", false, portInt)
                            broadcastStatus("$ কমান্ড এক্সিকিউটর প্রস্তুত। নিচের ধাপগুলো সম্পন্ন করুন:", false, portInt)
                        } else {
                            Log.w(TAG, "Connect to $host:$port failed, waiting for next candidate...")
                        }
                    }.start()
                }

                override fun onResolveFailed(s: NsdServiceInfo, errorCode: Int) {
                    Log.w(TAG, "Connect resolve failed for ${s.serviceName}: $errorCode")
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "tryConnectCandidate error", e)
        }
    }

    private fun stopMdnsDiscovery() {
        isResolving = false
        if (nsdManager != null && discoveryListener != null) {
            try {
                nsdManager?.stopServiceDiscovery(discoveryListener)
            } catch (_: Exception) {}
            discoveryListener = null
        }
    }

    private fun handlePairingProcess(code: String, portOverride: String? = null) {
        val loading = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("পেয়ারিং হচ্ছে...")
            .setContentText("কোড: $code যাচাই করা হচ্ছে")
            .setSmallIcon(R.drawable.ic_radar_rounded)
            .setProgress(0, 0, true)
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, loading)

        Thread {
            val port = portOverride ?: pendingPairingPort ?: "5555"
            val host = pendingPairingHost ?: localIpAddress
            Log.d(TAG, "Executing pair on $host:$port with code: $code")
            broadcastStatus("কোড $code দিয়ে $host:$port এ পেয়ার করা হচ্ছে...", false)

            val paired = adbController.pair(host, port, code)

            if (paired) {
                isSearchingForPairing = false
                broadcastStatus("পেয়ারিং সফল হয়েছে! কানেক্ট করা হচ্ছে...", false)
                mainHandler.post {
                    startMdnsDiscovery(SERVICE_TYPE_CONNECT)
                }
            } else {
                showResult("পেয়ারিং ব্যর্থ হয়েছে। কোড সঠিক কিনা দেখুন।", false)
            }
        }.start()
    }

    private fun showResult(msg: String, isSuccess: Boolean, port: Int = 0) {
        val result = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(msg)
            .setSmallIcon(if (isSuccess) R.drawable.ic_lock else android.R.drawable.stat_notify_error)
            .setAutoCancel(true)
            .setTimeoutAfter(7000)
            .build()
        notificationManager.notify(NOTIFICATION_ID, result)

        broadcastStatus(msg, isSuccess, port)

        if (isSuccess) {
            Thread {
                try { Thread.sleep(3000) } catch (_: Exception) {}
                stopScanningAndSelf()
            }.start()
        }
    }

    private fun broadcastStatus(message: String, isSuccess: Boolean, port: Int = 0) {
        val intent = Intent(ACTION_ADB_STATUS_UPDATE).apply {
            putExtra(EXTRA_STATUS_MESSAGE, message)
            putExtra(EXTRA_STATUS_SUCCESS, isSuccess)
            if (port > 0) {
                putExtra(EXTRA_CONNECT_PORT, port)
            }
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    private fun stopScanningAndSelf() {
        stopMdnsDiscovery()
        stopForeground(true)
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "ADB ওয়্যারলেস স্ট্যাটাস",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "ADB ওয়্যারলেস কানেকশন ও পেয়ারিং নোটিফিকেশন"
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 250, 100, 250)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun getLocalIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val intf = interfaces.nextElement()
                val addrs = intf.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        return addr.hostAddress ?: "127.0.0.1"
                    }
                }
            }
        } catch (_: Exception) {}
        return "127.0.0.1"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopMdnsDiscovery()
        try {
            if (multicastLock?.isHeld == true) {
                multicastLock?.release()
            }
        } catch (_: Exception) {}
        super.onDestroy()
    }
}
