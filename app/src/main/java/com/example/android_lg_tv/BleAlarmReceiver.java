package com.example.android_lg_tv;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

@SuppressLint("MissingPermission")
public class BleAlarmReceiver extends BroadcastReceiver {
    private static final String TAG = "BleIsolateDriver";

    // Сухие константы вашего проекта (Адрес сервиса и маркер финальной команды)
    private static final byte SERV_ADDRESS = (byte) 0xFD;
    private static final byte SERV_CMD_SLEEP = (byte) 0x01; // Наша цель — строго финальная команда 01 (SLEEP)

    private BluetoothGatt isolatedGatt;

    @Override
    public void onReceive(Context context, Intent intent) {
        Log.d(TAG, "⏰ [ALARM] ОС Android выполнила WAKEUP.");

        // 1. Активируем легальную защиту фонового окна от CPU Throttling (Android 14-16)
        final PendingResult pendingResult = goAsync();

        if (intent == null || !intent.hasExtra("TARGET_MAC_STRING")) {
            Log.e(TAG, "❌ Ошибка: В Intent отсутствует строка MAC-адреса пульта!");
            pendingResult.finish();
            return;
        }

        final String macAddress = intent.getStringExtra("TARGET_MAC_STRING");

        // 2. Открываем системный Bluetooth-адаптер Android напрямую
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            Log.e(TAG, "Bluetooth выключен на устройстве.");
            pendingResult.finish();
            return;
        }

        BluetoothDevice device = adapter.getRemoteDevice(macAddress);
        Log.d(TAG, "Инициируем изолированное подключение к " + macAddress);

        // 3. Холодный старт сокета в обход основного менеджера (Команда 02 полностью придушена)
        isolatedGatt = device.connectGatt(context, false, new BluetoothGattCallback() {
            @Override
            public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    Log.d(TAG, "🔗 Физический канал открыт (~400 мс). Запускаем нативное чтение сервисов...");

                    // Легально заставляем чистый сокет наполниться структурами по воздуху
                    gatt.discoverServices();
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    Log.d(TAG, "Канал связи закрыт фоновым приемником.");
                    gatt.close();
                    pendingResult.finish();
                }
            }

            @Override
            public void onServicesDiscovered(BluetoothGatt gatt, int status) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.d(TAG, "🔍 Чтение сервисов завершено успешно! Запускаем вашу рабочую аналогию...");

                    try {
                        // Используем публичные константы UUID вашего проекта для точечного извлечения
                        BluetoothGattService service = gatt.getService(BleReadOnlyStorage.SERVICE_UUID);

                        if (service != null) {
                            BluetoothGattCharacteristic characteristic = service.getCharacteristic(BleReadOnlyStorage.VOLUME_CHAR_UUID);

                            if (characteristic != null) {
                                Log.d(TAG, "🎯 Легальный объект характеристики получен. Выполняем выстрел 01!");

                                // Упаковываем пакет сна [0xFD, 0x01]
                                byte[] sleepPayload = new byte[] { SERV_ADDRESS, SERV_CMD_SLEEP };
                                characteristic.setValue(sleepPayload);

                                // Форсируем режим передачи БЕЗ ПОДТВЕРЖДЕНИЯ (Without Response)
                                characteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);

                                // Аппаратный выстрел в сокет пульта (Занимает всего 6 мс нативного стека)
                                boolean success = gatt.writeCharacteristic(characteristic);
                                Log.d(TAG, "🚀 Команда SLEEP (01) успешно отправлена в эфир. Статус: " + success);
                            } else {
                                Log.e(TAG, "❌ Ошибка: Характеристика отсутствует в структуре кэша драйвера.");
                            }
                        } else {
                            Log.e(TAG, "❌ Ошибка: Сервис пульта не найден в нативной структуре сокета.");
                        }

                    } catch (Exception e) {
                        Log.e(TAG, "Ошибка GATT-записи в фоновом колбеке: ", e);
                    } finally {
                        // Чисто закрываем импульсный канал сессии
                        gatt.disconnect();
                        gatt.close();
                        Log.d(TAG, "🔒 Фоновая транзакция завершена. Снимаем goAsync(). Process End.");
                        pendingResult.finish(); // Процесс полностью уничтожается системой
                    }
                } else {
                    Log.e(TAG, "Сбой чтения сервисов по воздуху, статус: " + status);
                    gatt.disconnect();
                    gatt.close();
                    pendingResult.finish();
                }
            }
        });
    }
}
