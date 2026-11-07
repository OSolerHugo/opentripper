package com.opentripper;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.UUID;

@SuppressLint("MissingPermission")
public class TripperBridge {

    public static final UUID SERVICE_UUID = UUID.fromString("01FF0100-BA5E-F4EE-5CA1-EB1E5E4B1CE0");
    public static final UUID CHAR_UUID = UUID.fromString("01FF0101-BA5E-F4EE-5CA1-EB1E5E4B1CE0");
    public static final UUID CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    public static final String DEVICE_NAME = "RE_DISP";

    public enum State { DISCONNECTED, SCANNING, CONNECTING, WAITING_PIN, CONNECTED }

    public interface Listener {
        void onStateChanged(State state);
        void onLog(String line, String type);
    }

    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final BluetoothManager bluetoothManager;
    private final SharedPreferences prefs;

    private Listener listener;
    private State state = State.DISCONNECTED;

    private BluetoothGattServer gattServer;
    private BluetoothGatt bluetoothGatt;
    private BluetoothGattCharacteristic writeCharacteristic;
    private int writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT;

    private boolean scanning = false;
    private final ArrayDeque<byte[]> sendQueue = new ArrayDeque<>();
    private boolean writePending = false;
    private Runnable keepaliveRunnable;

    private byte[] activeScreenPacket = null;
    private String activeScreenDesc = "PING_FW";

    public TripperBridge(Context ctx, Listener l) {
        this.context = ctx.getApplicationContext();
        this.listener = l;
        this.bluetoothManager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        this.prefs = context.getSharedPreferences("opentripper_prefs", Context.MODE_PRIVATE);
    }

    public void setListener(Listener l) {
        this.listener = l;
    }

    private void log(String msg, String type) {
        if (listener != null) {
            mainHandler.post(() -> listener.onLog(msg, type));
        }
    }

    private void setState(State s) {
        this.state = s;
        if (listener != null) {
            mainHandler.post(() -> listener.onStateChanged(s));
        }
    }

    public State getState() {
        return state;
    }

    public boolean isPaired() {
        return prefs.getBoolean("paired", false);
    }

    public void setPaired(boolean paired) {
        prefs.edit().putBoolean("paired", paired).apply();
    }

    // --- 1. GATT Server Local (O canal de volta obrigatório para o Pod) ---
    private void startGattServer() {
        if (gattServer != null) return;
        BluetoothGattServer server = bluetoothManager.openGattServer(context, gattServerCallback);
        if (server == null) {
            log("Erro: openGattServer() retornou null", "err");
            return;
        }
        gattServer = server;
        BluetoothGattService service = new BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY);
        BluetoothGattCharacteristic characteristic = new BluetoothGattCharacteristic(
                CHAR_UUID,
                BluetoothGattCharacteristic.PROPERTY_WRITE | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                BluetoothGattCharacteristic.PERMISSION_WRITE
        );
        service.addCharacteristic(characteristic);
        server.addService(service);
        log("GATT Server local aberto (Canal de volta pronto)", "sys");
    }

    private void stopGattServer() {
        if (gattServer != null) {
            gattServer.close();
            gattServer = null;
        }
    }

    private final BluetoothGattServerCallback gattServerCallback = new BluetoothGattServerCallback() {
        @Override
        public void onConnectionStateChange(BluetoothDevice device, int status, int newState) {
            String est = newState == BluetoothProfile.STATE_CONNECTED ? "conectou" : "desconectou";
            log("[GATT Server] Pod " + est + " no canal de volta (status=" + status + ")", "sys");
        }

        @Override
        public void onCharacteristicWriteRequest(BluetoothDevice device, int requestId,
                                                  BluetoothGattCharacteristic characteristic,
                                                  boolean preparedWrite, boolean responseNeeded,
                                                  int offset, byte[] value) {
            byte[] bytes = value != null ? value : new byte[0];
            log("RX [Pod -> Celular]: " + TripperProtocol.toHexString(bytes), "rx");
            if (responseNeeded && gattServer != null) {
                try {
                    gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, new byte[0]);
                } catch (Exception e) {
                    log("Erro sendResponse: " + e.getMessage(), "err");
                }
            }
        }
    };

    // --- 2. Conexão Cliente BLE ---
    public void startConnect() {
        if (scanning || bluetoothGatt != null) return;
        startGattServer();

        BluetoothAdapter adapter = bluetoothManager.getAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            log("Bluetooth do celular está desligado!", "err");
            return;
        }

        BluetoothLeScanner scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) {
            log("BluetoothLeScanner indisponível", "err");
            return;
        }

        ScanFilter filter = new ScanFilter.Builder().setDeviceName(DEVICE_NAME).build();
        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();

        scanning = true;
        setState(State.SCANNING);
        log("Buscando Tripper Pod (RE_DISP)...", "info");

        scanner.startScan(Collections.singletonList(filter), settings, scanCallback);

        // Timeout de scan em 15 segundos
        mainHandler.postDelayed(() -> {
            if (scanning) {
                scanner.stopScan(scanCallback);
                scanning = false;
                setState(State.DISCONNECTED);
                log("Tempo limite: RE_DISP não encontrado em 15s. Desligue e religue a chave da moto.", "err");
            }
        }, 15000);
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            if (!scanning) return;
            scanning = false;
            BluetoothAdapter adapter = bluetoothManager.getAdapter();
            if (adapter != null && adapter.getBluetoothLeScanner() != null) {
                adapter.getBluetoothLeScanner().stopScan(this);
            }

            BluetoothDevice dev = result.getDevice();
            log("Encontrado: " + dev.getName() + " (" + dev.getAddress() + ") - Conectando...", "info");
            setState(State.CONNECTING);
            bluetoothGatt = dev.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
        }

        @Override
        public void onScanFailed(int errorCode) {
            scanning = false;
            setState(State.DISCONNECTED);
            log("Scan BLE falhou, código: " + errorCode, "err");
        }
    };

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                log("Conectado! Descobrindo serviços...", "info");
                gatt.discoverServices();
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                log("⚠️ Desconectado do Tripper Pod (status=" + status + ")", "err");
                cleanConnection();
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            BluetoothGattService service = gatt.getService(SERVICE_UUID);
            if (service == null) {
                log("Serviço NXP não encontrado!", "err");
                return;
            }
            BluetoothGattCharacteristic ch = service.getCharacteristic(CHAR_UUID);
            if (ch == null) {
                log("Característica NXP não encontrada!", "err");
                return;
            }
            writeCharacteristic = ch;
            int props = ch.getProperties();
            writeType = (props & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
                    ? BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                    : BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT;

            gatt.setCharacteristicNotification(ch, true);
            BluetoothGattDescriptor cccd = ch.getDescriptor(CCCD_UUID);
            if (cccd != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                } else {
                    cccd.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                    gatt.writeDescriptor(cccd);
                }
            }

            log("✅ Canal pronto! Aguardando 200ms de acomodação...", "tx");

            // Pausa de 200ms confirmada pelo WazeTripper
            mainHandler.postDelayed(TripperBridge.this::performHandshake, 200);
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            writePending = false;
            pumpQueue();
        }
    };

    private void performHandshake() {
        if (isPaired()) {
            log("Dispositivo já pareado: Enviando Reconexão rápida...", "info");
            enqueuePacket(TripperProtocol.HANDSHAKE_RESUME, "RESUME");
            startKeepalive();
            mainHandler.postDelayed(() -> {
                enqueuePacket(TripperProtocol.buildSetTimeNowPacket(), "SET TIME");
                mainHandler.postDelayed(() -> {
                    enqueuePacket(TripperProtocol.PING_FW, "PING_FW");
                    enqueuePacket(TripperProtocol.PING_FW, "PING_FW");
                    setState(State.CONNECTED);
                    log("✅ Sessão estabelecida e ativa!", "tx");
                }, 150);
            }, 200);
        } else {
            log("Novo dispositivo: Solicitando PIN na tela...", "info");
            enqueuePacket(TripperProtocol.HANDSHAKE_SHOW_PIN, "SHOW PIN");
            startKeepalive();
            setState(State.WAITING_PIN);
            log("👉 Digite o PIN que apareceu na tela da moto!", "sys");
        }
    }

    public void sendPin(String pin) {
        if (pin == null || pin.trim().isEmpty()) return;
        enqueuePacket(TripperProtocol.buildPinPacket(pin.trim()), "CONFIRM PIN (" + pin + ")");
        setPaired(true);
        setState(State.CONNECTED);
        mainHandler.postDelayed(() -> {
            enqueuePacket(TripperProtocol.buildSetTimeNowPacket(), "SET TIME");
        }, 300);
    }

    public void syncTime() {
        enqueuePacket(TripperProtocol.buildSetTimeNowPacket(), "SET TIME");
    }

    public void setNavManeuver(int maneuver, int distanceM, String name) {
        byte[] pkt = TripperProtocol.buildNavPacket(maneuver, distanceM);
        activeScreenPacket = pkt;
        activeScreenDesc = name + " (" + distanceM + "m)";
        enqueuePacket(pkt, "NAV " + activeScreenDesc);
    }

    public void setCompass(byte direction, String name) {
        byte[] pkt = TripperProtocol.buildCompassPacket(direction);
        activeScreenPacket = pkt;
        activeScreenDesc = "Bússola " + name;
        enqueuePacket(pkt, "COMPASS " + name);
    }

    public void setCallOverlay() {
        byte[] pkt = TripperProtocol.buildCallIconPacket();
        activeScreenPacket = pkt;
        activeScreenDesc = "Ícone Ligação";
        enqueuePacket(pkt, "CALL OVERLAY");
    }

    public void setExplorerIcon(int iconByte) {
        byte[] pkt = TripperProtocol.buildNavPacket(iconByte, 100);
        activeScreenPacket = pkt;
        activeScreenDesc = String.format(java.util.Locale.US, "0x%02X - %s", iconByte & 0xFF, TripperProtocol.getIconName(iconByte));
        enqueuePacket(pkt, "EXPLORER " + activeScreenDesc);
    }

    private void startKeepalive() {
        stopKeepalive();
        keepaliveRunnable = new Runnable() {
            @Override
            public void run() {
                if (bluetoothGatt != null && state == State.CONNECTED) {
                    if (activeScreenPacket != null) {
                        enqueuePacket(activeScreenPacket, "LOOP " + activeScreenDesc);
                    } else {
                        enqueuePacket(TripperProtocol.PING_FW, "PING_FW");
                    }
                }
                mainHandler.postDelayed(this, 1800);
            }
        };
        mainHandler.postDelayed(keepaliveRunnable, 1800);
    }

    private void stopKeepalive() {
        if (keepaliveRunnable != null) {
            mainHandler.removeCallbacks(keepaliveRunnable);
            keepaliveRunnable = null;
        }
    }

    private synchronized void enqueuePacket(byte[] pkt, String desc) {
        if (bluetoothGatt == null || writeCharacteristic == null) return;
        sendQueue.add(pkt);
        pumpQueue();
    }

    private synchronized void pumpQueue() {
        if (writePending || sendQueue.isEmpty() || bluetoothGatt == null || writeCharacteristic == null) return;
        byte[] pkt = sendQueue.poll();
        if (pkt == null) return;

        writeCharacteristic.setValue(pkt);
        writeCharacteristic.setWriteType(writeType);
        writePending = true;
        boolean ok = bluetoothGatt.writeCharacteristic(writeCharacteristic);
        if (ok) {
            log("TX: " + TripperProtocol.toHexString(pkt), "tx");
        } else {
            writePending = false;
            log("Falha ao escrever no BLE", "err");
        }
    }

    public void disconnect() {
        cleanConnection();
        stopGattServer();
    }

    private void cleanConnection() {
        stopKeepalive();
        sendQueue.clear();
        writePending = false;
        if (bluetoothGatt != null) {
            bluetoothGatt.disconnect();
            bluetoothGatt.close();
            bluetoothGatt = null;
        }
        writeCharacteristic = null;
        activeScreenPacket = null;
        activeScreenDesc = "PING_FW";
        setState(State.DISCONNECTED);
    }
}
