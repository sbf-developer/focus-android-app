package com.focus.android.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.focus.android.FocusApp
import com.focus.android.MainActivity
import com.focus.android.R
import com.focus.android.util.isDomainBlocked
import com.focus.android.util.normalizeDomain
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean

class FocusVpnService : VpnService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var vpnInterface: ParcelFileDescriptor? = null
    private var packetJob: Job? = null
    private var domainTrackJob: Job? = null
    private val running = AtomicBoolean(false)

    @Volatile
    private var activeDomain: String? = null

    private val repository by lazy { (application as FocusApp).repository }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopVpn()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                startForeground(NOTIFICATION_ID, buildNotification())
                startVpn()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopVpn()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onRevoke() {
        serviceScope.launch {
            repository.setBlockingEnabled(false)
        }
        stopVpn()
        stopSelf()
        super.onRevoke()
    }

    private fun startVpn() {
        if (running.get()) return

        try {
            val builder = Builder()
                .setSession("Focus")
                .setMtu(MTU)
                .addAddress(VPN_ADDRESS, 32)
                .addRoute(VPN_ADDRESS, 32)
                .addDnsServer(VPN_ADDRESS)
                .setBlocking(true)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                builder.setMetered(false)
            }

            vpnInterface = builder.establish()
            if (vpnInterface == null) {
                Log.e(TAG, "VPN establish returned null")
                stopSelf()
                return
            }

            running.set(true)
            instanceRunning = true
            startDomainTracking()
            startPacketLoop()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start VPN", e)
            stopSelf()
        }
    }

    private fun startPacketLoop() {
        packetJob?.cancel()
        packetJob = serviceScope.launch {
            val fd = vpnInterface ?: return@launch
            val input = FileInputStream(fd.fileDescriptor)
            val output = FileOutputStream(fd.fileDescriptor)
            val buffer = ByteArray(MTU)

            val upstreamSocket = DatagramSocket()
            protect(upstreamSocket)

            while (isActive && running.get()) {
                try {
                    val length = input.read(buffer)
                    if (length <= 0) {
                        delay(10)
                        continue
                    }

                    val packet = buffer.copyOf(length)
                    val query = DnsPacketHandler.extractDnsQuery(packet, length)

                    if (query == null) {
                        continue
                    }

                    val domain = normalizeDomain(query.domain)
                    if (domain.isNotEmpty()) {
                        activeDomain = domain
                        repository.setCurrentDomain(domain)
                    }

                    val blocklist = repository.getBlocklistSet()
                    if (isDomainBlocked(domain, blocklist)) {
                        val nx = DnsPacketHandler.buildNxDomainResponse(query)
                        val response = DnsPacketHandler.wrapDnsResponse(packet, length, nx)
                        output.write(response)
                        continue
                    }

                    val forwardPacket = DnsPacketHandler.buildForwardPacket(packet, length)
                    val dnsOffset = length - query.rawQuery.size
                    val sendPacket = DatagramPacket(
                        query.rawQuery,
                        query.rawQuery.size,
                        InetAddress.getByName(UPSTREAM_DNS),
                        DNS_PORT,
                    )
                    upstreamSocket.send(sendPacket)

                    val recvBuffer = ByteArray(MTU)
                    val recvPacket = DatagramPacket(recvBuffer, recvBuffer.size)
                    upstreamSocket.soTimeout = 3000
                    try {
                        upstreamSocket.receive(recvPacket)
                        val dnsResponse = recvBuffer.copyOf(recvPacket.length)
                        val response = DnsPacketHandler.wrapDnsResponse(packet, length, dnsResponse)
                        output.write(response)
                    } catch (_: Exception) {
                        val nx = DnsPacketHandler.buildNxDomainResponse(query)
                        val response = DnsPacketHandler.wrapDnsResponse(packet, length, nx)
                        output.write(response)
                    }
                } catch (e: Exception) {
                    if (running.get()) {
                        Log.w(TAG, "Packet loop error", e)
                        delay(50)
                    }
                }
            }

            upstreamSocket.close()
        }
    }

    private fun startDomainTracking() {
        domainTrackJob?.cancel()
        domainTrackJob = serviceScope.launch {
            while (isActive && running.get()) {
                val domain = activeDomain
                if (!domain.isNullOrEmpty()) {
                    repository.incrementDomainTime(domain, 1)
                }
                delay(1000)
            }
        }
    }

    private fun stopVpn() {
        running.set(false)
        instanceRunning = false
        packetJob?.cancel()
        domainTrackJob?.cancel()
        activeDomain = null
        repository.setCurrentDomain(null)

        try {
            vpnInterface?.close()
        } catch (_: Exception) {
            /* ignore */
        }
        vpnInterface = null
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_vpn_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.channel_vpn_desc)
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.vpn_notification_title))
            .setContentText(getString(R.string.vpn_notification_text))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    companion object {
        private const val TAG = "FocusVpnService"
        const val ACTION_STOP = "com.focus.android.vpn.STOP"
        private const val CHANNEL_ID = "focus_vpn"
        private const val NOTIFICATION_ID = 1

        private const val VPN_ADDRESS = "10.0.0.2"
        private const val UPSTREAM_DNS = "8.8.8.8"
        private const val DNS_PORT = 53
        private const val MTU = 1500

        @Volatile
        var instanceRunning: Boolean = false
            private set

        fun start(context: Context) {
            val intent = Intent(context, FocusVpnService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, FocusVpnService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
