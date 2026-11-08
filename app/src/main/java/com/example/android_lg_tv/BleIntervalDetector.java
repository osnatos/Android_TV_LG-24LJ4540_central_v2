package com.example.android_lg_tv;

//import android.annotation.SuppressLint;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

public class BleIntervalDetector {
    private static final String TAG = "BleIntervalDetector";
    private String TARGET_MAC = null;
    private static final long SCAN_TIMEOUT_MS = 7000; // 7 секунд на проверку

    private long lastPacketTimestamp = 0;
    private int totalPacketsReceived = 0;
    private boolean isFastAdvertisingDetected = false;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable timeoutRunnable;

    public interface DetectionListener {
        void onFastAdvertisingDetected();
        void onSlowAdvertisingDetected();
        void onDeviceNotFound();
    }

    private DetectionListener listener;

public void startDetection(DetectionListener listener) {
 //   TARGET_MAC = mac_address;
    if (timeoutRunnable != null) {
        handler.removeCallbacks(timeoutRunnable);
    }

    this.listener = listener;
    this.lastPacketTimestamp = 0;
    this.totalPacketsReceived = 0;
    this.isFastAdvertisingDetected = false;

    // -------------------------------------------------------------------------
    // АВТОНОМНЫЙ ЕДИНСТВЕННЫЙ СТАРТ СИСТЕМНОГО ЧИПА BLUETOOTH
    // -------------------------------------------------------------------------
    android.bluetooth.BluetoothAdapter adapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter();
    android.bluetooth.le.BluetoothLeScanner scanner = (adapter != null) ? adapter.getBluetoothLeScanner() : null;

    if (scanner != null) {
        try {
            android.bluetooth.le.ScanSettings settings = new android.bluetooth.le.ScanSettings.Builder()
                    .setScanMode(android.bluetooth.le.ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .build();

            // Передаем null вместо фильтров чипа, привязывая наш scanCallback
            scanner.startScan(null, settings, scanCallback);
            Log.d(TAG, ">>> Автономный старт: Аппаратный чип запущен без фильтров. Слушаем эфир. <<<");
        } catch (SecurityException e) {
            Log.e(TAG, "Ошибка прав доступа при старте сканирования", e);
        }
    }
    // -------------------------------------------------------------------------

    // 7-секундный таймер
    timeoutRunnable = () -> {
        stopScanningInternal(); // Выключит сканер на аппаратном уровне!

        if (isFastAdvertisingDetected) {
            if (listener != null) listener.onFastAdvertisingDetected();
        } else if (totalPacketsReceived > 0) {
            Log.i(TAG, ">>> ВЕРДИКТ: Реклама МЕДЛЕННАЯ (пакетов: " + totalPacketsReceived + ") <<<");
            if (listener != null) listener.onSlowAdvertisingDetected();
        } else {
            Log.i(TAG, ">>> ВЕРДИКТ: РЕКЛАМЫ НЕТ ВООБЩЕ <<<");
            if (listener != null) listener.onDeviceNotFound();
        }
    };
    handler.postDelayed(timeoutRunnable, SCAN_TIMEOUT_MS);
}

    private void stopScanningInternal() {
        handler.removeCallbacks(timeoutRunnable);

        // -------------------------------------------------------------------------
        // ГАРАНТИРОВАННОЕ АППАРАТНОЕ УНИЧТОЖЕНИЕ СКАНЕРА ПРИ ОСТАНОВКЕ
        // -------------------------------------------------------------------------
        try {
            android.bluetooth.BluetoothAdapter adapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter();
            if (adapter != null && adapter.getBluetoothLeScanner() != null) {
                adapter.getBluetoothLeScanner().stopScan(scanCallback);
                Log.d(TAG, ">>> Чистый стоп: Аппаратный callback сканера удален из системы. <<<");
            }
        } catch (SecurityException ignored) {}
        // -------------------------------------------------------------------------
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            super.onScanResult(callbackType, result);

            if (TARGET_MAC.equalsIgnoreCase(result.getDevice().getAddress())) {
                long currentTimestamp = SystemClock.elapsedRealtime();
                totalPacketsReceived++;

                if (lastPacketTimestamp != 0) {
                    long delta = currentTimestamp - lastPacketTimestamp;
                    Log.d(TAG, "Пакет | Дельта: " + delta + " мс");

                    // Проверяем на 100мс + дельта (джиттер)
                    if (delta >= 70 && delta <= 150) {
                        isFastAdvertisingDetected = true;
                        handler.removeCallbacks(timeoutRunnable); // Отменяем таймаут
                        stopScanningInternal(); // Останавливаем сканер, цель достигнута

                        Log.i(TAG, ">>> ВЕРДИКТ: БЫСТРАЯ РЕКЛАМА (100 мс) <<<");
                        if (listener != null) listener.onFastAdvertisingDetected();
                        return;
                    }
                    // Защита от джиттера для медленной 2-секундной рекламы.
                    // Если дельта близка к 2 секундам (сон) или к 4 секундам (сон с одним пропущенным пакетом),
                    // мы гарантированно фиксируем, что плата находится в режиме сна, не дожидаясь конца 7 секунд!
                    if ((delta >= 1800 && delta <= 2200) || (delta >= 3800 && delta <= 4200)) {
                        handler.removeCallbacks(timeoutRunnable);
                        stopScanningInternal();

                        Log.i(TAG, ">>> ВЕРДИКТ: МЕДЛЕННАЯ РЕКЛАМА (2000 мс / пропуск пакета зафиксирован) <<<");
                        if (listener != null) listener.onSlowAdvertisingDetected();
                        return;
                    }
                }
                lastPacketTimestamp = currentTimestamp;
            }
        }
    };

//    public ScanCallback getScanCallback() { return scanCallback; }

    public void setMacAddress(String MacAddr) {
       TARGET_MAC = MacAddr;
    }

}


