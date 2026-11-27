package com.example.android_lg_tv;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

/** Measures advertising intervals for one explicitly selected board MAC. */
public class BleIntervalDetector {
    private static final String TAG = "BleIntervalDetector";
    private static final long SCAN_TIMEOUT_MS = 7000;

    private String targetMac;
    private long lastPacketTimestamp;
    private int totalPacketsReceived;
    private boolean isFastAdvertisingDetected;
    private boolean finished;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable timeoutRunnable;
    private BluetoothLeScanner activeScanner;
    private DetectionListener listener;

    public interface DetectionListener {
        void onFastAdvertisingDetected();
        void onSlowAdvertisingDetected();
        void onUnknownAdvertisingDetected();
        void onDeviceNotFound();
        void onScanError(int errorCode, String description);
    }

    public void setMacAddress(String macAddress) {
        targetMac = macAddress;
    }

    public void startDetection(DetectionListener detectionListener) {
        stopScanningInternal();
        listener = detectionListener;
        lastPacketTimestamp = 0;
        totalPacketsReceived = 0;
        isFastAdvertisingDetected = false;
        finished = false;

        if (targetMac == null || targetMac.trim().isEmpty()) {
            finishWithError(-1, "Не задан MAC-адрес платы");
            return;
        }

        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            finishWithError(-2, "Bluetooth-адаптер недоступен");
            return;
        }
        try {
            if (!adapter.isEnabled()) {
                finishWithError(-3, "Bluetooth выключен");
                return;
            }
            activeScanner = adapter.getBluetoothLeScanner();
            if (activeScanner == null) {
                finishWithError(-4, "BLE-сканер недоступен");
                return;
            }
            ScanSettings settings = new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .build();
            activeScanner.startScan(null, settings, scanCallback);
            Log.d(TAG, "Сканирование запущено для MAC " + targetMac);
        } catch (SecurityException e) {
            finishWithError(-5, "Нет разрешения на BLE-сканирование: " + e.getMessage());
            return;
        } catch (RuntimeException e) {
            finishWithError(-6, "Не удалось запустить BLE-сканирование: " + e.getMessage());
            return;
        }

        timeoutRunnable = () -> {
            if (finished) return;
            stopScanningInternal();
            finished = true;
            DetectionListener callback = listener;
            if (callback == null) return;
            if (isFastAdvertisingDetected) {
                callback.onFastAdvertisingDetected();
            } else if (totalPacketsReceived > 0) {
                // Наличие пакетов само по себе не доказывает интервал 2 секунды.
                // Медленный режим подтверждается только измеренным интервалом 1800–2200/3800–4200 мс.
                Log.i(TAG, "Реклама обнаружена, но интервал не удалось классифицировать; пакетов: " + totalPacketsReceived);
                callback.onUnknownAdvertisingDetected();
            } else {
                Log.i(TAG, "За время сканирования рекламные пакеты выбранного MAC не обнаружены");
                callback.onDeviceNotFound();
            }
        };
        handler.postDelayed(timeoutRunnable, SCAN_TIMEOUT_MS);
    }

    private void finishWithError(int errorCode, String description) {
        if (finished) return;
        finished = true;
        stopScanningInternal();
        Log.e(TAG, description + " (code=" + errorCode + ")");
        DetectionListener callback = listener;
        if (callback != null) callback.onScanError(errorCode, description);
    }

    private void stopScanningInternal() {
        if (timeoutRunnable != null) {
            handler.removeCallbacks(timeoutRunnable);
            timeoutRunnable = null;
        }
        BluetoothLeScanner scanner = activeScanner;
        activeScanner = null;
        if (scanner != null) {
            try {
                scanner.stopScan(scanCallback);
            } catch (SecurityException | IllegalStateException e) {
                Log.w(TAG, "Не удалось штатно остановить BLE-сканирование", e);
            }
        }
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            if (finished || result == null || result.getDevice() == null) return;
            String address = result.getDevice().getAddress();
            if (address == null || !targetMac.equalsIgnoreCase(address)) return;

            long now = SystemClock.elapsedRealtime();
            totalPacketsReceived++;
            if (lastPacketTimestamp != 0) {
                long delta = now - lastPacketTimestamp;
                Log.d(TAG, "Пакет выбранной платы | интервал: " + delta + " мс");
                if (delta >= 70 && delta <= 150) {
                    isFastAdvertisingDetected = true;
                    finished = true;
                    stopScanningInternal();
                    DetectionListener callback = listener;
                    if (callback != null) callback.onFastAdvertisingDetected();
                    return;
                }
                if ((delta >= 1800 && delta <= 2200) || (delta >= 3800 && delta <= 4200)) {
                    finished = true;
                    stopScanningInternal();
                    DetectionListener callback = listener;
                    if (callback != null) callback.onSlowAdvertisingDetected();
                    return;
                }
            }
            lastPacketTimestamp = now;
        }

        @Override
        public void onScanFailed(int errorCode) {
            finishWithError(errorCode, "Android сообщил об ошибке BLE-сканирования");
        }
    };
}
