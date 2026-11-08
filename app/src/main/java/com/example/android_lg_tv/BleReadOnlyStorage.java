package com.example.android_lg_tv;

import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.util.Log;
import java.util.UUID;

public class BleReadOnlyStorage {
    private static final String TAG = "BleReadOnlyStorage";
    private static volatile BleReadOnlyStorage instance;

    // --- КОНСТАНТЫ ЖЕСТКОЙ КАРТЫ УСТРОЙСТВА CC2340R5 ---
    public static final UUID SERVICE_UUID = UUID.fromString("0000FFF0-0000-1000-8000-00805F9B34FB");
    public static final UUID VOLUME_CHAR_UUID = UUID.fromString("0000FFF1-0000-1000-8000-00805F9B34FB");

    // --- ЗАЩИЩЕННАЯ ЗОНА (КАРТА УСТРОЙСТВА) ---
    private boolean isMapFixed = false;
    private BluetoothGattService fixedService = null;
    private BluetoothGattCharacteristic fixedVolumeCharacteristic = null;

    // --- ДИНАМИЧЕСКАЯ ЗОНА (ТРАНСПОРТ) ---
    private BluetoothGatt currentGatt = null;
//    private boolean isTransportReady = false;

    private BleReadOnlyStorage() {}

    /**
     * Внутренний потокобезопасный метод получения инстанса.
     * Защищен общим локом класса BleReadOnlyStorage.class.
     */
    public static BleReadOnlyStorage getInstance() {
        if (instance == null) {
            synchronized (BleReadOnlyStorage.class) {
                if (instance == null) {
                    instance = new BleReadOnlyStorage();
                }
            }
        }
        return instance;
    }

    // =========================================================================
    // === ОРИГИНАЛЬНЫЙ СТАТИЧЕСКИЙ ИНТЕРФЕЙС ===
    // =========================================================================

    /**
     * ФАЗА 1: Разовая жесткая фиксация дескрипторов в памяти.
     */
    public static boolean fixDeviceMap(BluetoothGatt gatt) {
        synchronized (BleReadOnlyStorage.class) {
            BleReadOnlyStorage s = getInstance();
            if (s.isMapFixed) {
                return bindCurrentServices(gatt);
              //  s.currentGatt = gatt;
              //  return true;
            }
            if (gatt == null) return false;

            BluetoothGattService service = gatt.getService(SERVICE_UUID);
            if (service != null) {
                BluetoothGattCharacteristic characteristic = service.getCharacteristic(VOLUME_CHAR_UUID);
                if (characteristic != null) {
                    //characteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
                    s.fixedService = service;
                    s.fixedVolumeCharacteristic = characteristic;
                    s.currentGatt = gatt;
        //            s.isTransportReady = true;
                    s.isMapFixed = true; // Защита включена
                    Log.d(TAG, "Карта устройства успешно зафиксирована.");
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * Обновление ссылки на транспортный канал при реконнектах.
     */
/*    public static void updateTransport(BluetoothGatt gatt) {
        synchronized (BleReadOnlyStorage.class) {
            BleReadOnlyStorage s = getInstance();
            s.currentGatt = gatt;
            if (gatt != null && s.isMapFixed) {
                s.isTransportReady = true;
            }
        }
    }
*/
    /**
     * Проверка фиксации карты.
     */
    public static boolean isMapFixed() {
        synchronized (BleReadOnlyStorage.class) {
            return getInstance().isMapFixed;
        }
    }

    /**
     * Привязываем новый сокет к хранилищу
     */
     public static boolean bindCurrentServices(BluetoothGatt gatt) {
        synchronized (BleReadOnlyStorage.class) {
            if (gatt == null) return false;
            BleReadOnlyStorage s = getInstance();

            // СВЯЗКА СОКЕТА: Просто обновляем динамическую ссылку на транспорт
            if (s.fixedVolumeCharacteristic != null) {
                s.currentGatt = gatt;
                Log.d(TAG, "[STORAGE-HOTSWAP] Живой сокет привязан к эталонной карте дескрипторов. discoverServices() пропущен.");
                // ЖЕСТКО ВЫСТАВЛЯЕМ РЕЖИМ БЕЗ ПОДТВЕРЖДЕНИЯ ДЛЯ ХАРАКТЕРИСТИКИ
                s.fixedVolumeCharacteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
                return true;
            }
            return false;
        }
    }

    /**
     * Сброс транспорта при дисконнекте.
     */

    public static void invalidateTransport() {
        synchronized (BleReadOnlyStorage.class) {
            if (instance != null) {
                instance.currentGatt = null;
          //      instance.isTransportReady = false;
                Log.d(TAG, "Транспортный канал очищен.");
            }
        }
    }

    /**
     * Сброс защиты карты.
     */
    public static void resetDeviceMap() {
        synchronized (BleReadOnlyStorage.class) {
            if (instance != null) {
                instance.isMapFixed = false;
                instance.fixedService = null;
                instance.fixedVolumeCharacteristic = null;
                instance.currentGatt = null;
            //    instance.isTransportReady = false;
                Log.d(TAG, "Карта устройства сброшена.");
            }
        }
    }

    // --- СТАТИЧЕСКИЕ ГЕТТЕРЫ ДЛЯ СКОРОСТНОГО ДОСТУПА ---

    public static BluetoothGattCharacteristic getVolumeCharacteristic() {
        synchronized (BleReadOnlyStorage.class) {
            return getInstance().fixedVolumeCharacteristic;
        }
    }

    public static BluetoothGatt getCurrentGatt() {
        synchronized (BleReadOnlyStorage.class) {
            return getInstance().currentGatt;
        }
    }
/*
    public static boolean isTransportReady() {
        synchronized (BleReadOnlyStorage.class) {
            return instance != null && instance.isTransportReady;
        }
    }
*/
    /**
     * Тотальное физическое уничтожение синглтона.
     * За счет synchronized(BleReadOnlyStorage.class) ни один фоновый метод выше
     * не сможет выполниться одновременно с зачисткой памяти. Гонка потоков невозможна.
     */
    public static void destroyInstance() {
        synchronized (BleReadOnlyStorage.class) {
            if (instance != null) {
                instance.currentGatt = null;
                instance.fixedVolumeCharacteristic = null;
                instance.fixedService = null;
     //           instance.isTransportReady = false;
                instance = null;
                Log.d(TAG, "Синглтон хранилища успешно стерт из оперативной памяти.");
            }
        }
    }
}
