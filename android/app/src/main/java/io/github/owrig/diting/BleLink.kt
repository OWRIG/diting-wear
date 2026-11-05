package io.github.owrig.diting

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import java.util.UUID

/**
 * 和谛听设备的蓝牙连接：扫描 → 连接 → 协商 MTU → 订阅电池和音频通知 → 断线自动重连。
 * 调用前必须已经获得蓝牙权限。
 */
@SuppressLint("MissingPermission")
class BleLink(
    private val context: Context,
    private val onAudio: (seq: Int, opus: ByteArray) -> Unit,
    private val onBattery: (mv: Int) -> Unit,
    private val onState: (LinkState, String?) -> Unit,
) {
    private val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private val main = Handler(Looper.getMainLooper())
    private val prefs = context.getSharedPreferences("ble", Context.MODE_PRIVATE)
    private var gatt: BluetoothGatt? = null
    private var running = false
    private val pendingCcc = ArrayDeque<BluetoothGattCharacteristic>()

    fun start() {
        running = true
        val saved = prefs.getString(KEY_ADDR, null)
        if (saved != null) {
            connect(adapter.getRemoteDevice(saved), auto = true)
        } else {
            scan()
        }
    }

    fun stop() {
        running = false
        runCatching { adapter.bluetoothLeScanner?.stopScan(scanCb) }
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        onState(LinkState.STOPPED, null)
    }

    /** 忘记已配对的设备，下次重新扫描 */
    fun forget() = prefs.edit().remove(KEY_ADDR).apply()

    private fun scan() {
        onState(LinkState.SCANNING, null)
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(SVC_UUID)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        adapter.bluetoothLeScanner?.startScan(listOf(filter), settings, scanCb)
    }

    private val scanCb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            adapter.bluetoothLeScanner?.stopScan(this)
            prefs.edit().putString(KEY_ADDR, result.device.address).apply()
            connect(result.device, auto = false)
        }

        override fun onScanFailed(errorCode: Int) {
            onState(LinkState.SCANNING, "扫描失败 ($errorCode)，5 秒后重试")
            main.postDelayed({ if (running) scan() }, 5000)
        }
    }

    private fun connect(device: BluetoothDevice, auto: Boolean) {
        onState(if (auto) LinkState.RECONNECTING else LinkState.CONNECTING, null)
        gatt?.close()
        // autoConnect=true：设备不在身边时由系统在后台等待，回来后自动连上，不耗电
        gatt = device.connectGatt(context, auto, gattCb, BluetoothDevice.TRANSPORT_LE)
    }

    private val gattCb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            Log.i(TAG, "connection state=$newState status=$status")
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                onState(LinkState.CONNECTING, g.device.name)
                g.requestMtu(247)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                g.close()
                if (gatt == g) gatt = null
                if (running) {
                    onState(LinkState.RECONNECTING, null)
                    main.postDelayed({ if (running && gatt == null) connect(g.device, auto = true) }, 1000)
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            Log.i(TAG, "mtu=$mtu")
            g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_BALANCED)
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val svc = g.getService(SVC_UUID)
            if (svc == null) {
                onState(LinkState.CONNECTING, "设备上没有谛听服务，固件对吗？")
                return
            }
            pendingCcc.clear()
            svc.getCharacteristic(BATT_UUID)?.let { pendingCcc.add(it) }
            svc.getCharacteristic(AUDIO_UUID)?.let { pendingCcc.add(it) }
            svc.getCharacteristic(BATT_UUID)?.let { g.readCharacteristic(it) }
        }

        // 读完电池初值后，再依次打开通知（BLE 同一时间只能有一个操作）
        @Deprecated("Deprecated in Java")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            if (Build.VERSION.SDK_INT < 33) handleRead(g, c, c.value)
        }

        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            handleRead(g, c, value)
        }

        private fun handleRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray?) {
            if (c.uuid == BATT_UUID && value != null && value.size >= 2) onBattery(u16(value, 0))
            enableNextNotify(g)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            if (d.characteristic.uuid == AUDIO_UUID) onState(LinkState.STREAMING, g.device.name)
            enableNextNotify(g)
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33) handleNotify(c.uuid, c.value)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            handleNotify(c.uuid, value)
        }
    }

    private fun enableNextNotify(g: BluetoothGatt) {
        val c = pendingCcc.removeFirstOrNull() ?: return
        g.setCharacteristicNotification(c, true)
        val d = c.getDescriptor(CCC_UUID) ?: return
        val v = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        if (Build.VERSION.SDK_INT >= 33) {
            g.writeDescriptor(d, v)
        } else {
            @Suppress("DEPRECATION")
            d.value = v
            @Suppress("DEPRECATION")
            g.writeDescriptor(d)
        }
    }

    private fun handleNotify(uuid: UUID, v: ByteArray?) {
        if (v == null) return
        when (uuid) {
            AUDIO_UUID -> if (v.size > 2) onAudio(u16(v, 0), v.copyOfRange(2, v.size))
            BATT_UUID -> if (v.size >= 2) onBattery(u16(v, 0))
        }
    }

    private fun u16(b: ByteArray, i: Int) = (b[i].toInt() and 0xff) or ((b[i + 1].toInt() and 0xff) shl 8)

    companion object {
        private const val TAG = "BleLink"
        private const val KEY_ADDR = "device_address"
        val SVC_UUID: UUID = UUID.fromString("5a1d0001-7e3c-4b8a-9f00-524947000000")
        val AUDIO_UUID: UUID = UUID.fromString("5a1d0002-7e3c-4b8a-9f00-524947000000")
        val BATT_UUID: UUID = UUID.fromString("5a1d0003-7e3c-4b8a-9f00-524947000000")
        val CCC_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
