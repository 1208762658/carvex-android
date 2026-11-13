package io.github.iamr0s.carvex

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.AdvertisingSetCallback
import android.bluetooth.le.AdvertisingSetParameters
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import io.github.iamr0s.carvex.sessioncore.BridgeStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * BLE 拓扑观测：判定 866F 的无线 CarPlay 发现层到底是哪一边在广播。
 *
 * 两种拓扑都真实存在，靠猜没有意义：
 *  - A 车机广播、手机扫描（MFi 主流形态）
 *  - B 手机广播、车机扫描后自己连进来
 *
 * 这里刻意做成常驻、顺序、多轮，且不参与任何连接决策：
 *
 *  1. 不按固定 MAC 过滤。MIB3 的 BLE 地址可能是 random 且会轮换，硬过滤只会得到假的
 *     `hits=0`。全量扫描，命中目标就单独标一行，同时用设备名和 CarPlay 相关 UUID 兜底。
 *  2. 两个 128-bit UUID 共 32 字节，超过 legacy advertising 的 31 字节上限，所以优先走
 *     extended advertising set（不受这条限制）；不支持时退回 legacy 只放一个 UUID，另一个
 *     塞进 scan response，避免"started ok 但内容被截断"的假阳性。
 *  3. 扫描和广播顺序执行而不是并发，部分芯片上两者并发会抢射频，容易出现两边都空的假阴性。
 *  4. B 方向必须看 GATT 层：车机若作为 central 连进来，先出现 GATT 连接/读请求，之后才轮到
 *     RFCOMM accept。不能拿"RFCOMM 没 accept"反推 BLE 没发生。
 *  5. 每次开跑先打一行两个权限的 grant 状态，否则权限被拒导致的 `hits=0` /
 *     `advertise failed` 会被误读成拓扑结论。
 */
object BleTopologyProbe {
    private const val TAG = "BELE"
    private const val SCAN_MS = 12_000L
    private const val ADV_MS = 30_000L
    private const val IDLE_MS = 20_000L
    private const val ROUNDS = 1

    private val CARPLAY_HINTS = listOf(
        "carplay", "vw bt", "apple", "5384",
    )
    private val WANTED_UUIDS = listOf(
        "00000000-deca-fade-deca-deafdecacafe",
        "00000000-deca-fade-deca-deafdecacaff",
        "02030302-1d19-415f-86f2-22a2106a0a77",
        "ec884348-cd41-40a2-9727-575d50bf1fd3",
        "2d8d2466-e14d-451c-88bc-7301abea291a",
    )

    private val started = java.util.concurrent.atomic.AtomicBoolean(false)
    private val watchingSince = java.util.concurrent.atomic.AtomicBoolean(false)

    fun start(context: Context) {
        if (!started.compareAndSet(false, true)) return
        val app = context.applicationContext
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            val manager = app.getSystemService(BluetoothManager::class.java)
            val adapter = manager?.adapter
            if (adapter == null) {
                log("adapter unavailable")
                return@launch
            }
            log(
                "perm scan=" + granted(app, Manifest.permission.BLUETOOTH_SCAN) +
                    " advertise=" + granted(app, Manifest.permission.BLUETOOTH_ADVERTISE) +
                    " connect=" + granted(app, Manifest.permission.BLUETOOTH_CONNECT)
            )
            val target: Any? = null
            for (round in 1..ROUNDS) {
                if (!scope.isActive) break
                if (!adapter.isEnabled) {
                    log("round=$round skipped: adapter off")
                    delay(IDLE_MS)
                    continue
                }
                // A：全量扫描，看车机是否在广播
                log("round=$round stage=A scan start ${SCAN_MS}ms")
                scanOnce(manager, adapter, target)
                log("round=$round stage=A scan stopped")
                delay(IDLE_MS)
                // B：手机广播 + GATT server，看车机是否作为 central 连进来
                log("round=$round stage=B advertise+gatt start ${ADV_MS}ms")
                advertiseOnce(app, manager, adapter)
                log("round=$round stage=B advertise+gatt stopped")
                delay(IDLE_MS)
            }
            log("all rounds done")
        }
    }

    /**
     * 车机 ACL 一有动静就扫一轮。
     *
     * 开机跑一轮只能测"待命"，而刚开机 / 车门开 / 用户点中控那一瞬才是车机可能广播的
     * 窗口，手动开 App 往往已经晚了。这里挂 ACL 连接/断开广播：车机 HFP 一接上或一断，
     * 立刻扫 12 秒，把那个瞬间抓下来。
     */
    fun watchCarActivity(context: Context) {
        if (!watchingSince.compareAndSet(false, true)) return
        val app = context.applicationContext
        val filter = android.content.IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED)
        }
        runCatching {
            app.registerReceiver(object : android.content.BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    val action = intent.action ?: return
                    val name = runCatching {
                        (intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE))?.name
                    }.getOrNull()
                    log("acl event action=" + action.substringAfterLast('.') + " peer=" + (name ?: "<none>"))
                    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                    scope.launch {
                        val manager = app.getSystemService(BluetoothManager::class.java) ?: return@launch
                        val adapter = manager.adapter ?: return@launch
                        if (!adapter.isEnabled) return@launch
                        log("acl-triggered scan start 12000ms")
                        scanOnce(manager, adapter, null)
                    }
                }
            }, filter)
            log("car activity watcher armed")
        }.onFailure { log("car activity watcher failed: " + (it.message ?: "")) }
    }

    private fun granted(context: Context, permission: String): Boolean =
        if (Build.VERSION.SDK_INT < 31) true
        else context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun log(message: String) = BridgeStore.log("", "$TAG $message")

    private suspend fun scanOnce(manager: BluetoothManager, adapter: android.bluetooth.BluetoothAdapter, target: Any?) {
        val scanner = runCatching { adapter.bluetoothLeScanner }.getOrNull()
        if (scanner == null) {
            log("scan unavailable (scanner null: BLUETOOTH_SCAN not granted?)")
            return
        }
        val total = AtomicInteger(0)
        val named = ConcurrentLinkedQueue<String>()
        val targetHits = AtomicInteger(0)
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                total.incrementAndGet()
                val device = result.device ?: return
                val address = runCatching { device.address }.getOrDefault("")
                val name = runCatching { result.scanRecord?.deviceName }.getOrNull()
                val services = runCatching {
                    result.scanRecord?.serviceUuids?.mapNotNull { it.uuid.toString() } ?: emptyList()
                }.getOrDefault(emptyList())
                val manufacturer = runCatching {
                    result.scanRecord?.manufacturerSpecificData?.let { data ->
                        (0 until data.size()).joinToString(",") { i ->
                            "0x" + Integer.toHexString(data.keyAt(i) + 0x10000).substring(1) + "=" +
                                runCatching {
                                    data.valueAt(i).take(12).joinToString("") { "%02x".format(it) }
                                }.getOrDefault("")
                        }
                    }
                }.getOrNull()

                val wanted = services.filter { it.lowercase() in WANTED_UUIDS }
                val lookedWanted = name?.let { n -> CARPLAY_HINTS.any { n.lowercase().contains(it) } } == true
                val isTarget = wanted.isNotEmpty() || lookedWanted
                if (isTarget) {
                    targetHits.incrementAndGet()
                    log(
                        "scan HIT addr=$address rssi=${result.rssi} name=${name ?: "<none>"} " +
                            "wantedUuids=$wanted services=${services.size} mfr=${manufacturer ?: "<none>"}"
                    )
                } else if (named.size < 40 && !name.isNullOrBlank()) {
                    named.add("${name}(rssi=${result.rssi})")
                }
            }

            override fun onScanFailed(errorCode: Int) {
                log("scan failed code=$errorCode (2=SCAN_FAILED_APPLICATION_REGISTRATION_FAILED 3=INTERNAL 4=FEATURE_UNSUPPORTED)")
            }
        }
        runCatching { scanner.startScan(cb) }.onFailure { log("scan start exception: " + (it.message ?: "")) }
        delay(SCAN_MS)
        runCatching { scanner.stopScan(cb) }
        log("scan result total=$total targetHits=$targetHits")
        if (named.isNotEmpty()) log("scan names=" + named.take(24).joinToString(" | "))
    }

    private suspend fun advertiseOnce(context: Context, manager: BluetoothManager, adapter: android.bluetooth.BluetoothAdapter) {
        // GATT server：车机作为 central 连进来时的证据来源
        var serverRef: BluetoothGattServer? = null
        val serverCallback = object : BluetoothGattServerCallback() {
            override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
                val stateName = when (newState) {
                    android.bluetooth.BluetoothProfile.STATE_CONNECTED -> "CONNECTED"
                    android.bluetooth.BluetoothProfile.STATE_DISCONNECTED -> "DISCONNECTED"
                    android.bluetooth.BluetoothProfile.STATE_CONNECTING -> "CONNECTING"
                    android.bluetooth.BluetoothProfile.STATE_DISCONNECTING -> "DISCONNECTING"
                    else -> newState.toString()
                }
                log("gatt peer $stateName addr=" + runCatching { device.address }.getOrDefault("") + " status=$status")
            }

            override fun onCharacteristicReadRequest(
                device: BluetoothDevice, requestId: Int, offset: Int, characteristic: BluetoothGattCharacteristic,
            ) {
                log("gatt read from " + runCatching { device.address }.getOrDefault("") + " char=" + characteristic.uuid)
                runCatching {
                    serverRef?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, ByteArray(0))
                }
            }
        }
        val server: BluetoothGattServer? = runCatching { manager.openGattServer(context, serverCallback) }.getOrNull()
        serverRef = server
        if (server == null) {
            log("gatt server unavailable (openGattServer returned null)")
        } else {
            runCatching {
                val service = BluetoothGattService(
                    UUID.fromString("00000000-deca-fade-deca-deafdecacafe"),
                    BluetoothGattService.SERVICE_TYPE_PRIMARY,
                )
                listOf(
                    "00002a00-0000-1000-8000-00805f9b34fb",
                    "02030302-1d19-415f-86f2-22a2106a0a77",
                ).forEach { uuid ->
                    service.addCharacteristic(
                        BluetoothGattCharacteristic(
                            UUID.fromString(uuid),
                            BluetoothGattCharacteristic.PROPERTY_READ,
                            BluetoothGattCharacteristic.PERMISSION_READ,
                        )
                    )
                }
                server.addService(service)
            }.onFailure { log("gatt addService failed: " + (it.message ?: "")) }
        }

        val advertiser = runCatching { adapter.bluetoothLeAdvertiser }.getOrNull()
        if (advertiser == null) {
            log("advertiser unavailable (BLUETOOTH_ADVERTISE not granted or unsupported)")
            runCatching { server?.close() }
            return
        }

        // 三个 128-bit UUID = 48 字节，只有 extended 放得下。
        // 第三个是 02030302-...-0a77：Apple 的 CarPlay BLE 服务标识（research/
        // protocol/bluetooth_rfcomm/analysis/01-discovery-and-eir.md 的 A2）。此前
        // 它只出现在扫描白名单和 GATT characteristic 里，从未进过广播包，所以 866F
        // 即使扫到这台手机也看不出它支持 CarPlay——这很可能就是"车机不自动切到
        // CarPlay 页、要驾驶员手动点"的原因：真 iPhone 的 EIR 里有这条标识。
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .addServiceUuid(ParcelUuid(UUID.fromString(WANTED_UUIDS[0])))
            .addServiceUuid(ParcelUuid(UUID.fromString(WANTED_UUIDS[1])))
            .addServiceUuid(ParcelUuid(UUID.fromString(WANTED_UUIDS[2])))
            .addServiceUuid(ParcelUuid(UUID.fromString(WANTED_UUIDS[3])))
            .addServiceUuid(ParcelUuid(UUID.fromString(WANTED_UUIDS[4])))
            .build()

        val supportsExtended = Build.VERSION.SDK_INT >= 26 &&
            runCatching { adapter.isLeExtendedAdvertisingSupported }.getOrDefault(false)

        // iPhone 的 EIR 是五条 128-bit UUID（research/protocol/bluetooth_rfcomm/analysis/
        // 01-discovery-and-eir.md 的 A1/A2/B1/B2/C1），20046 只放了三条，缺 B2 和 C1。
        // 80 字节约 5 条 UUID，扩展广播上限 240 字节放得下。
        log("adv payload uuids=["+WANTED_UUIDS.joinToString(",")+
            "] count="+WANTED_UUIDS.size+" carplayUuidIncluded=true")

        var handle: Any? = null
        if (supportsExtended) {
            val params = AdvertisingSetParameters.Builder()
                .setConnectable(true)
                .setLegacyMode(false)
                .setInterval(AdvertisingSetParameters.INTERVAL_MIN)
                .setTxPowerLevel(AdvertisingSetParameters.TX_POWER_HIGH)
                .build()
            val cb = object : AdvertisingSetCallback() {
                override fun onAdvertisingSetStarted(set: android.bluetooth.le.AdvertisingSet?, txPower: Int, status: Int) {
                    log("adv set started status=$status txPower=$txPower (0=SUCCESS 1=TOO_LARGE 2=TOO_MANY 3=INTERNAL 4=FEATURE_UNSUPPORTED)")
                }
                override fun onAdvertisingEnabled(set: android.bluetooth.le.AdvertisingSet?, enable: Boolean, status: Int) {
                    log("adv set enabled=$enable status=$status")
                }
                override fun onAdvertisingSetStopped(set: android.bluetooth.le.AdvertisingSet?) {
                    log("adv set stopped")
                }
            }
            runCatching { advertiser.startAdvertisingSet(params, data, null, null, null, cb) }
                .onFailure { log("adv set exception: " + (it.message ?: "")) }
            handle = cb
        } else {
            // 退到 legacy：31 字节上限只够放一个 128-bit UUID，所以把最能决定"车机是否
        // 认它作 CarPlay 设备"的那一个（02030302-...-0a77）放进主包，cafe 放 scan response。
            // legacy 主包只有 31 字节：设备名 + 128-bit UUID 会超，名字必须让位。
            val legacyData = AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .addServiceUuid(ParcelUuid(UUID.fromString(WANTED_UUIDS[2])))
                .build()
            val scanResponse = AdvertiseData.Builder()
                .addServiceUuid(ParcelUuid(UUID.fromString(WANTED_UUIDS[0])))
                .build()
            val settings = AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .setConnectable(true)
                .build()
            val cb = object : AdvertiseCallback() {
                override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                    log("adv legacy started ok (one uuid in adv, one in scan response)")
                }
                override fun onStartFailure(errorCode: Int) {
                    log("adv legacy failed code=$errorCode (1=TOO_LARGE 2=TOO_MANY 3=INTERNAL)")
                }
            }
            runCatching { advertiser.startAdvertising(settings, legacyData, scanResponse, cb) }
                .onFailure { log("adv legacy exception: " + (it.message ?: "")) }
            handle = cb
        }

        // 必须真正等待：之前用 Handler.postDelayed 后函数立刻返回，外层那行 "stopped"
        // 只差 14 毫秒就打印出来，日志把"播满 30 秒"写成了"起播即停"。
        val advertisingSince = System.currentTimeMillis()
        kotlinx.coroutines.delay(ADV_MS/2)
        log("adv heartbeat extended=$supportsExtended heldMs=${System.currentTimeMillis()-advertisingSince} (no gatt peer yet means the car is not scanning us)")
        kotlinx.coroutines.delay(ADV_MS/2)
        runCatching {
            when (handle) {
                is AdvertisingSetCallback -> advertiser.stopAdvertisingSet(handle as AdvertisingSetCallback)
                is AdvertiseCallback -> advertiser.stopAdvertising(handle as AdvertiseCallback)
            }
        }
        runCatching { server?.close() }
        log("adv closed extended=$supportsExtended heldMs=${System.currentTimeMillis()-advertisingSince}")
    }
}
