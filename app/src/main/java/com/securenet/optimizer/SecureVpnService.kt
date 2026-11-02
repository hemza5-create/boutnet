package com.securenet.optimizer

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.Executors

object VpnState {
    val running = mutableStateOf(false)
    val blocked = mutableIntStateOf(0)
}

class SecureVpnService : VpnService() {
    private var tun: ParcelFileDescriptor? = null
    @Volatile private var active = false
    private var firewall = true
    private val pool = Executors.newFixedThreadPool(8)
    private val lock = Any()

    private val blocklist = setOf(
        "wicar.org",
        "testsafebrowsing.appspot.com",
        "coinhive.com",
        "coin-hive.com",
        "crypto-loot.com"
    )

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            stopVpn()
            return START_NOT_STICKY
        }

        firewall = intent?.getBooleanExtra("fw", true) ?: true
        val servers = if (intent?.getStringExtra("dns") == "google") {
            listOf("8.8.8.8", "8.8.4.4")
        } else {
            listOf("1.1.1.1", "1.0.0.1")
        }
        startVpn(servers)
        return START_STICKY
    }

    private fun startVpn(servers: List<String>) {
        stopVpn()
        val builder = Builder().setSession("SecureNet")
            .addAddress("10.77.0.2", 32)
            .addDnsServer(servers[0])
        servers.forEach { builder.addRoute(it, 32) }

        tun = builder.establish() ?: return
        active = true
        VpnState.running.value = true

        val fd = tun!!.fileDescriptor
        Thread {
            val inp = FileInputStream(fd)
            val out = FileOutputStream(fd)
            val buf = ByteArray(32767)
            try {
                while (active) {
                    val n = inp.read(buf)
                    if (n > 0) {
                        val pkt = buf.copyOf(n)
                        pool.execute { handle(pkt, out) }
                    }
                }
            } catch (_: Exception) {
            }
            VpnState.running.value = false
        }.start()
    }

    private fun handle(pkt: ByteArray, out: FileOutputStream) {
        try {
            if ((pkt[0].toInt() shr 4) != 4 || pkt[9].toInt() != 17) return
            val ihl = (pkt[0].toInt() and 15) * 4
            if (pkt.size < ihl + 20) return

            val dport = ((pkt[ihl + 2].toInt() and 255) shl 8) or (pkt[ihl + 3].toInt() and 255)
            if (dport != 53) return

            val q = pkt.copyOfRange(ihl + 8, pkt.size)
            val name = qname(q)
            val reply = if (firewall && isBlocked(name)) {
                VpnState.blocked.intValue++
                q.copyOf().also {
                    it[2] = (it[2].toInt() or 0x80).toByte()
                    it[3] = 0x83.toByte()
                }
            } else {
                forward(q, InetAddress.getByAddress(pkt.copyOfRange(16, 20)))
            }

            if (reply != null) synchronized(lock) {
                out.write(wrap(pkt, ihl, reply))
            }
        } catch (_: Exception) {
        }
    }

    private fun isBlocked(name: String) = blocklist.any { name == it || name.endsWith(".$it") }

    private fun qname(q: ByteArray): String {
        val sb = StringBuilder()
        var i = 12
        while (i < q.size) {
            val len = q[i].toInt() and 255
            if (len == 0 || i + 1 + len > q.size) break
            if (sb.isNotEmpty()) sb.append('.')
            sb.append(String(q, i + 1, len))
            i += len + 1
        }
        return sb.toString().lowercase()
    }

    private fun forward(q: ByteArray, server: InetAddress): ByteArray? {
        DatagramSocket().use { socket ->
            protect(socket)
            socket.soTimeout = 3000
            socket.send(DatagramPacket(q, q.size, server, 53))
            val response = DatagramPacket(ByteArray(4096), 4096)
            socket.receive(response)
            return response.data.copyOf(response.length)
        }
    }

    private fun wrap(pkt: ByteArray, ihl: Int, reply: ByteArray): ByteArray {
        val length = 28 + reply.size
        val out = ByteArray(length)
        out[0] = 0x45
        out[2] = (length shr 8).toByte()
        out[3] = length.toByte()
        out[6] = 0x40
        out[8] = 64
        out[9] = 17

        System.arraycopy(pkt, 16, out, 12, 4)
        System.arraycopy(pkt, 12, out, 16, 4)

        var sum = 0
        for (i in 0 until 20 step 2) {
            sum += ((out[i].toInt() and 255) shl 8) or (out[i + 1].toInt() and 255)
        }
        while (sum shr 16 != 0) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }
        val checksum = sum.inv() and 0xFFFF
        out[10] = (checksum shr 8).toByte()
        out[11] = checksum.toByte()

        out[20] = pkt[ihl + 2]
        out[21] = pkt[ihl + 3]
        out[22] = pkt[ihl]
        out[23] = pkt[ihl + 1]

        val udpLength = 8 + reply.size
        out[24] = (udpLength shr 8).toByte()
        out[25] = udpLength.toByte()
        System.arraycopy(reply, 0, out, 28, reply.size)
        return out
    }

    private fun stopVpn() {
        active = false
        try {
            tun?.close()
        } catch (_: Exception) {
        }
        tun = null
        VpnState.running.value = false
        stopSelf()
    }

    override fun onDestroy() {
        stopVpn()
        pool.shutdownNow()
        super.onDestroy()
    }
}
