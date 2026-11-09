package io.github.iamr0s.carvex.ui.model

import android.content.Context
import com.baidu.carlife.service.CarlifeConnectService
import io.github.iamr0s.carvex.R
import io.github.iamr0s.carvex.Permissions
import io.github.iamr0s.carvex.sessioncore.BridgeStore
import io.github.iamr0s.carvex.sessioncore.BondedDevices
import io.github.iamr0s.carvex.sessioncore.ConnectionMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Boundary between the DeviceApp-style UI and the real bridge implementation.
 *
 * The old implementation used a delay and random numbers here. The bridge has one real
 * connection entry point today (the Bluetooth/iAP2 session in [CarlifeConnectService]), so the
 * transport deliberately exposes the protocol/carrier mode rather than a raw radio name. Session progress and diagnostics are read
 * from [BridgeStore] by [DeviceViewModel].
 */
interface DeviceTransport {
    suspend fun scan(): List<Device>
    suspend fun connect(device: Device, mode: ConnectionMode)
    suspend fun disconnect(device: Device)
    /** The current bridge does not expose a synthetic ping operation. */
    suspend fun ping(device: Device): Int = error("Session ping is not available")
}

class RealDeviceTransport(private val context: Context) : DeviceTransport {
    companion object {
        /** Modes backed by the current CarPlay bootstrap adapter. */
        val supportedModes: Set<ConnectionMode> = setOf(ConnectionMode.CARPLAY_BLUETOOTH)

        /** Compatibility view used by the existing settings UI. */
        val supportedMethods: Set<Method> = Method.entries.filterTo(linkedSetOf()) { it.mode in supportedModes }
    }

    override suspend fun scan(): List<Device> = withContext(Dispatchers.IO) {
        BondedDevices.list(context).map { paired ->
            Device(
                id = paired.address.uppercase().hashCode(),
                name = paired.name,
                signal = Signal.Unknown,
                address = paired.address.uppercase(),
            )
        }
    }

    override suspend fun connect(device: Device, mode: ConnectionMode) {
        check(mode in supportedModes) {
            "${mode.displayName} is not supported by the active bridge session"
        }
        check(Permissions.granted(context)) { context.getString(R.string.required_permission_error) }
        withContext(Dispatchers.IO) {
            // v14.16 (2026-11-04): wireless CarPlay depends on Wi-Fi (the 866F opens its own
            // hotspot; RTSP video/audio runs over it after the Bluetooth iAP2 handshake). A
            // Wi-Fi-off phone therefore always fails right after handshake with an EOF that
            // reads like a protocol bug. Detect it at the connect entry and fail fast with a
            // clear message instead of letting the user discover it from the session log.
            val wifi = context.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
            if (wifi?.isWifiEnabled == false) {
                throw IllegalStateException(context.getString(R.string.wifi_required_for_wireless_carplay))
            }
            // SessionController reads this target when the service starts. The service itself
            // owns the real Bluetooth/iAP2 handshake and foreground lifecycle.
            BridgeStore.configure(BridgeStore.options.value.copy(target = device.address.uppercase()))
            CarlifeConnectService.connect(context)
        }
    }

    override suspend fun disconnect(device: Device) = withContext(Dispatchers.IO) {
        CarlifeConnectService.disconnect(context)
    }
}
