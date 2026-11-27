package com.example.android_lg_tv;

import static com.example.android_lg_tv.MainActivity.BoardSleepMode;
import static com.example.android_lg_tv.MainActivity.VolumeLock;
import static com.example.android_lg_tv.MainActivity.isBleBusy;
import android.annotation.SuppressLint;
import android.app.Application;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothProfile;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
//import android.widget.Toast;

@SuppressLint("MissingPermission")
public class BleRemoteControlManager {
    private static volatile long startConnectTime = -1;
    //    public boolean FastAdvMode = false; // Флаг фиксирует, что плата находится в режиме 100мс рекламы

    // Флаг фиксирует, что плата находится в режиме 2-секундной рекламы после простоя
    // Взводит idleRunnable таймер. Сбрасывает STATE_CONNECTED
    public boolean isBoardSleeping = false;

    //   public byte[] pendingMutePayload = new byte[2];
    private boolean WakeUpCommand = false;
    public boolean runAdvertisingDiagnosticFlag = false;

    // Разрешение автоматического запуска BLE-рекламной диагностики; ручной запуск не затрагивается.
    public static volatile boolean AUTO_ADVERTISING_DIAGNOSTIC_ENABLED = false;

    private PacketProcessingWorker volumeWorker;

    public void setVolumeWorker(PacketProcessingWorker worker) {
        this.volumeWorker = worker;
    }

    // Изолированный фоновый таймер метронома конвейера
    private final Handler mBackgroundQueueHandler = new Handler(Looper.getMainLooper());
    private Runnable mBackgroundQueueRunnable;

    // Автономные буферы очереди
    private String mBgChannelQueue = "";
    private int mBgChannelIndex = 0;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private static final String TAG = "BleRemoteControlManager";
    private static BleRemoteControlManager instance;
    private final Application app;

    private static String BOARD_MAC = null;
    private static String BOARD_NAME = null;

    private BluetoothDevice mTargetDevice;
    private boolean disconnectEnable = false;
    private volatile boolean isGattConnected = false;
    public byte[] pendingMutePayload = null;
    private byte[] pendingVolumePayload = null;
    private int currentTransactionType = 0; // 1 = MUTE, 2 = ГРОМКОСТЬ

    public interface BleUiCallback {
        void onOperationComplete();

        void onCriticalError();

        void onToast(String Message);

        void onConnectionTimeMsec(long ConnectionTimeMsec);
        void onRunAdvertisingDiagnostic();

        void onDisconnect();
    }

    private BleUiCallback uiCallback;

    public static synchronized BleRemoteControlManager getInstance() {
        if (instance == null) instance = new BleRemoteControlManager();
        return instance;
    }

    private BleRemoteControlManager() {
        this.app = MainActivity.getApp();
    }

    public void setUiCallback(BleUiCallback c) {
        this.uiCallback = c;
    }

    /** Returns the current GATT connection state for diagnostic gating. */
    public boolean isGattConnected() {
        return isGattConnected;
    }

    public void setTargetMacAddress(String macAddress) {
        if (BOARD_MAC != null && BOARD_MAC.equalsIgnoreCase(macAddress)) {
            return;
        }
        BOARD_MAC = macAddress;
        if (BOARD_MAC != null && !BOARD_MAC.isEmpty()) {
            BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
            if (a != null) {
                try {
                    this.isGattConnected = false;
                    BleReadOnlyStorage.invalidateTransport();

                    BluetoothGatt oldGatt = BleReadOnlyStorage.getCurrentGatt();
                    if (oldGatt != null) {
                        Log.d(TAG, "Закрытие старой сессии для прошлого MAC...");
                        oldGatt.disconnect();
                        oldGatt.close();
                    }
                    BleReadOnlyStorage.destroyInstance();
                    Log.d(TAG, "Синглтон хранилища успешно стерт из оперативной памяти.");
                } catch (Exception e) {
                    Log.e(TAG, "Ошибка при уничтожении хранилища: " + e.getMessage());
                }
                this.mTargetDevice = a.getRemoteDevice(BOARD_MAC);
                Log.d(TAG, "Целевое устройство успешно обновлено: " + BOARD_MAC);
            }
        }
    }

    public void set_DisconnectEnable(boolean d) {
        this.disconnectEnable = d;
    }

    public void set_Connected(boolean Connected) {
        // Управляется автоматом состояний конвейера
        //       isGattConnected = Connected;
    }

    public boolean initializeLastSavedDevice() {
        if (this.app != null) {
            android.content.SharedPreferences prefs =
                    this.app.getSharedPreferences("BleDevicePrefs", android.content.Context.MODE_PRIVATE);
            String savedMac = prefs.getString("selected_mac_address", null);
            String savedName = prefs.getString("selected_device_name", null);

            if (savedMac == null || savedMac.isEmpty()) {
                BOARD_MAC = null;
                BOARD_NAME = null;
                this.mTargetDevice = null;
                return false;
            }

            BOARD_MAC = savedMac;
            BOARD_NAME = savedName != null ? savedName : "Неизвестный стенд";

            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter != null) {
                this.mTargetDevice = adapter.getRemoteDevice(BOARD_MAC);
                Log.d(TAG, "Успешно поднята последняя сессия: " + BOARD_NAME + " [" + BOARD_MAC + "]");
                return true;
            }
        }
        return false;
    }

    public String getCurrentDeviceStatusString() {
        if (this.app != null) {
            android.content.SharedPreferences prefs =
                    this.app.getSharedPreferences("BleDevicePrefs", android.content.Context.MODE_PRIVATE);
            String savedMac = prefs.getString("selected_mac_address", null);
            String savedName = prefs.getString("selected_device_name", null);

            if (savedMac == null || savedMac.isEmpty()) {
                return "Device not defined";
            }
            return "DEVICE: " + savedName + " " + savedMac;
        }
        return "App is null";
    }

    public String getCurrentDeviceName() {
        return BOARD_MAC;
    }

    public synchronized void sendSingleShotCommand(byte[] payload) {
        Log.d(TAG, "sendSingleShotCommand вызван. Проверка payload: " + (payload != null ? payload.length : "null"));
        if (payload == null || payload.length != 2) return;

        this.currentTransactionType = 1;
        this.pendingMutePayload = payload;

        BluetoothGatt gatt = BleReadOnlyStorage.getCurrentGatt();
        if (gatt != null && isGattConnected) {
            Log.d(TAG, "MUTE (Горячий старт): Отправка.");
            gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH);
            executeMuteWrite(gatt);
        } else {
            Log.d(TAG, "MUTE (Холодный старт): [initiateConnection]");
            initiateConnection(gatt);
        }
    }

    public synchronized void sendVolumePacketDirectlyFromWorker(byte[] payload, boolean isStartPacket) {
        if (payload == null || payload.length != 3) return;
        this.currentTransactionType = 2; // Фиксируем транзакцию ГРОМКОСТИ
        this.pendingVolumePayload = payload;
        Boolean markerStop;

        if (payload[2] == 0) markerStop = true;
        else markerStop = false;

        BluetoothGatt gatt = BleReadOnlyStorage.getCurrentGatt();
        if (gatt == null) {
            Log.e(TAG, " [ВОРКЕР ТРАНСПОРТ] Ссылка на сокет GATT == null. Запись отменена.");
            return;
        }

        MainActivity.isBleBusy = true; // блокируем UI на время физического залпа в эфир
        Log.i(TAG, "isGattConnected = " + isGattConnected);
        if (isGattConnected) {
            BluetoothGattCharacteristic characteristic = BleReadOnlyStorage.getVolumeCharacteristic();
            if (characteristic != null) {
                characteristic.setValue(payload);
                boolean success = gatt.writeCharacteristic(characteristic);
                if (success) {
                    Log.d(TAG, " [ВОРКЕР ТРАНСПОРТ] Прямой выстрел характеристики. Статус: " + success);
                    MainActivity.Group_Type = MainActivity.Volume_Type;
                    if (markerStop) {
                        Log.d(TAG, " [ВОРКЕР ТРАНСПОРТ] ОСВОБОЖДАЕМ замок интерфейса сразу после выстрела!");
                        MainActivity.isBleBusy = false;
                        MainActivity.isM100_Flag = false;
                    } else { // marker = 100
                        MainActivity.isM100_Flag = true;
                    }
                    notifyUiComplete();

                } else {
                    Log.d(TAG, " [ВОРКЕР ТРАНСПОРТ] Ошибка записи BLE Громкость: " + success);
                    MainActivity.isBleBusy = false;
                    notifyUiError();
                }

            } else {
                Log.e(TAG, " [ВОРКЕР ТРАНСПОРТ] Характеристика не найдена в памяти хранилища.");
                MainActivity.isBleBusy = false; // Освобождаем UI при ошибке дескриптора
                notifyUiError();
            }
        } else {
            Log.e(TAG, " [ВОРКЕР ТРАНСПОРТ] Сокет не подключен. Запись заблокирована.");
            MainActivity.isBleBusy = false; // Освобождаем UI при обрыве связи
            notifyUiError();
        }
    }

   public void initiateConnection(BluetoothGatt gatt) {
        if (isGattConnected) {
            Log.w(TAG, " [initiateConnection] isGattConnected = true");
            return;
        }

        // === ЖЕЛЕЗНАЯ ЗАЧИСТКА ПРИЗРАКОВ: Уничтожаем прошлые сокеты перед новым стартом ===
        BluetoothGatt oldGatt = BleReadOnlyStorage.getCurrentGatt();
        if (oldGatt != null) {
            Log.w(TAG, " [ЯДРО] Попытка зачистки старого сокета(gatt) в памяти.");
            try {
                oldGatt.disconnect();
                oldGatt.close();
            } catch (Exception e) {
                Log.e(TAG, "Ошибка зачистки старого сокета: ", e);
            }
        }
        // Теперь с абсолютно чистого листа поднимаем агрессивный Direct Connect на 100-мс рекламу платы
        Log.d(TAG, " [INIT] Запуск чистого нативного connectGatt с нуля.");


        startConnectTime = android.os.SystemClock.elapsedRealtime();
        //mBluetoothGatt = mTargetDevice.connectGatt(app, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
        mTargetDevice.connectGatt(app, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
    }

   public void startBackgroundChannelQueue(String channelString) {
       if (channelString == null || channelString.isEmpty()) return;
       Log.d(TAG, "4 [ФОН] Запуск изолированного конвейера каналов: " + channelString);
       mBackgroundQueueHandler.removeCallbacks(mBackgroundQueueRunnable);
       this.mBgChannelQueue = channelString;
       this.mBgChannelIndex = 0;
       set_DisconnectEnable(false);

       char firstDigit = channelString.charAt(0);
       Byte cmdByte = MainActivity.DECODER_DIGIT_MAP.get(firstDigit);
       final byte commandHex = (cmdByte != null) ? cmdByte : (byte) 0x22;
       byte[] payload = new byte[]{(byte) 0xFE, commandHex};

       // ОБЯЗАТЕЛЬНО: обновляем тип транзакции и буфер 1-й цифры до ветвления!
       this.currentTransactionType = 1;
       this.pendingMutePayload = payload;

       BluetoothGatt gatt = BleReadOnlyStorage.getCurrentGatt();
       if (gatt != null && isGattConnected) {
           Log.d(TAG, "[ФОН-ГОРЯЧИЙ СТАРТ] Канал открыт. Запускаем полную серию цифр: " + channelString);
           gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH);
           sendChannelNumber(gatt);
       } else {
           Log.d(TAG, "[ФОН-СТАРТ] Выбрасываем цифру 1: " + firstDigit);
           this.sendSingleShotCommand(payload);
       }
   }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(final BluetoothGatt gatt, int status, int newState) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Системный сбой радиоканала: status = " + status + ", newState = " + newState);
                notifyUiToast("IR бластер не отвечает");
                notifyUionConnectionTimeMsec(-1);  // Не удалось связаться
                runAdvertisingDiagnosticFlag = false;
                isGattConnected = false;
                BleReadOnlyStorage.invalidateTransport();
                if (gatt != null) {
                    try {
                        gatt.disconnect();
                        gatt.close();
                    } catch (Exception ignored) {
                    }
                }
                disconnectEnable = false;
                isBleBusy = false;
                return;
            }

            if (newState == BluetoothProfile.STATE_CONNECTED) {

                long currentTime = android.os.SystemClock.elapsedRealtime();
                long TimeForConnection = currentTime - startConnectTime;
                Log.d("BleRemoteControlManager", "currentTime = " + currentTime);
                Log.d("BleRemoteControlManager", "startConnectTime = " + startConnectTime);
                Log.d("BleRemoteControlManager", "TimeForConnection = " + TimeForConnection);

                notifyUionConnectionTimeMsec(TimeForConnection);

                Log.d(TAG, "CONNECTED");
                isGattConnected = true;
                isBleBusy = true;
     //           notifyUiToast("CONNECTED");
                // === КОНТРОЛЬНЫЙ ПЕРЕХВАТ ДЛЯ ПРОВЕРКИ: Выводим тип транзакции в Logcat ===
                Log.d("CHECK_TRANS", "!!! КРИТИЧЕСКАЯ ТОЧКА !!! currentTransactionType в момент CONNECTED равен: " + currentTransactionType);

                if (BleReadOnlyStorage.isMapFixed()) {
                    BleReadOnlyStorage.fixDeviceMap(gatt);
                    if (gatt != null) {
                       if (mBgChannelQueue != null && !mBgChannelQueue.isEmpty()) {
                            Log.d(TAG, "[GATT-СТАРТ] Обнаружена фоновая очередь: " + mBgChannelQueue);
                            waitChannelNumberSend(gatt);
                       } else if (currentTransactionType == 2) {
                            if (isBoardSleeping && WakeUpCommand) {
                                isBoardSleeping = false; // Сбрасываем флаг сна, плата сейчас проснется
                                WakeUpCommand = false;   // Сбрасываем флаг сервисной команды 02
                                Log.d(TAG, "[ЯДРО] Нативный шлюз открыт. Выполняем выстрел сервисной команды 0x02.");
                                send_WakeUpCommand(gatt);
                            } else {
                                Log.i(TAG, " [GATT-СТАРТ] Ветка ГРОМКОСТЬ: Нативный коннект пойман.");
                                if (volumeWorker != null) {
                                    volumeWorker.notifyTransportIsReady(); // Будим воркер из чистого wait()
                                    Log.d(TAG, " [GATT-СТАРТ] Сигнал пробуждения гарантированно доставлен на монитор transportLock.");
                                } else {
                                    Log.e(TAG, " [GATT-СТАРТ] КРИТ: volumeWorker == null");
                                }
                            }
                       } else {  // currentTransactionType = 1
                            Log.d(TAG, "Горячий старт: используем зафиксированную карту пульта.");
                            processPendingTransactions(gatt);
                        }
                    }
                } else {
                    Log.d(TAG, "Discover Services");
                    gatt.discoverServices();
                }
            }
            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                isGattConnected = false;
                isBleBusy = false;  // Natan
                VolumeLock = 0;  // Natan
                Log.d(TAG, "DISCONNECTED.");
                notifyUionDisconnect();

                if(runAdvertisingDiagnosticFlag) {
                    runAdvertisingDiagnosticFlag = false;
                    if (AUTO_ADVERTISING_DIAGNOSTIC_ENABLED) {
                        notifyUionRunAdvertisingDiagnostic();
                    }
                }
                else if(BoardSleepMode) {
                    BoardSleepMode = false;
                    if (AUTO_ADVERTISING_DIAGNOSTIC_ENABLED) {
                        notifyUionRunAdvertisingDiagnostic();
                    }
                }
/*
                if(currentTransactionType == 1) {
                    if ((MainActivity.instance != null) && FastAdvMode) {
           //             MainActivity.instance.RestartIdleTimer();  // Взведет еще раз после сервисной 01
                    }
                }
*/
               else if (volumeWorker != null) {
                    // Холодный старт взводим всегда — это полностью безопасно для воркера
                    volumeWorker.forceColdStart();

                    // Аварию и сброс мониторов вызываем ТОЛЬКО если упала реальная громкость (2),
                    // а легальные шлейфы от сервисных команд (1) — пропускаем мимо!
                    if (currentTransactionType == 2) {
                        volumeWorker.resetTransportStatus();
                    } else {
                        Log.d(TAG, " [МЕНЕДЖЕР] Легальный разрыв сервисной команды/MUTE. Конвейер громкости не трогаем.");
                    }
               }

                // === БЕЗУСЛОВНАЯ ЗОНА ОЧИСТКИ ФЛАГОВ ДЛЯ ВСЕХ ТРАНЗАКЦИЙ ===
                // Физически уничтожаем дескриптор в памяти через Handler
                mainHandler.postDelayed(() -> {
                    if (gatt != null) {
                        gatt.close();
                        Log.d(TAG, "gatt closed");
                    }
                }, 20);  // Natan 200

                // Гарантированно возвращаем свободу кнопкам пульта в UI!
                disconnectEnable = false;
                MainActivity.isBleBusy = false; // Возвращаем свободу интерфейсу пульта
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (!BleReadOnlyStorage.isMapFixed()) {
                    if (BleReadOnlyStorage.fixDeviceMap(gatt)) {
                        Log.d(TAG, "Карта устройства успешно зафиксирована при первом старте. Отправка пакета.");
                        isGattConnected = true;
                        //   executeWriteCommand(gatt);
                        executeMuteWrite(gatt);
                    } else {
                        Log.e(TAG, "Не удалось зафиксировать карту: Характеристика не найдена.");
                        notifyUiError();
                    }
                } else {
                    Log.d(TAG, "onServicesDiscovered. Горячий старт: используем зафиксированную карту пульта");
                    isGattConnected = true;
                    executeMuteWrite(gatt);
                }
            } else {
                Log.e(TAG, "Системный сбой discoverServices, статус: " + status);
                notifyUiError();
            }
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            Log.d("CHECK_BLE", "=== onCharacteristicWrite OC === status: " + status + " | disconnectEnable = " + disconnectEnable);
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "onCharacteristicWrite. Данные получены...");
            }
        }
    }; // BluetoothGatt gattCallback!

     private void processPendingTransactions(final BluetoothGatt gatt) {
        Log.d(TAG, "Запуск задержки 500 мс для стабилизации буферов сокета...");
        mainHandler.postDelayed(() -> {
            Log.d(TAG, "Пауза завершена. Отправка стабилизированного корректного пакета");
            executeMuteWrite(gatt);
        }, 300);  // Natan 500
    }
    private void notifyUiComplete() {
        if (uiCallback != null) {
            mainHandler.post(() -> uiCallback.onOperationComplete());
        }
    }

    private void notifyUiError() {
        if (uiCallback != null) {
            mainHandler.post(() -> uiCallback.onCriticalError());
        }
    }

    private void notifyUiToast(String Message) {
        if (uiCallback != null) {
            mainHandler.post(() -> uiCallback.onToast(Message));
        }
    }

     private void notifyUionConnectionTimeMsec(long ConnectionTimeMsec) {
        if (uiCallback != null) {
            mainHandler.post(() -> uiCallback.onConnectionTimeMsec(ConnectionTimeMsec));
        }
    }
    private void notifyUionRunAdvertisingDiagnostic() {
        // Центральная защита: при выключенной автоматической диагностике
        // не передаём событие в UI и не запускаем сканирование.
        if (!AUTO_ADVERTISING_DIAGNOSTIC_ENABLED || uiCallback == null) {
            return;
        }
        mainHandler.post(() -> uiCallback.onRunAdvertisingDiagnostic());
    }
    private void notifyUionDisconnect() {
        if (uiCallback != null) {
            mainHandler.post(() -> uiCallback.onDisconnect());
        }
    }

    private void executeMuteWrite(BluetoothGatt gatt) {
        BluetoothGattCharacteristic characteristic = BleReadOnlyStorage.getVolumeCharacteristic();
        if (characteristic != null && pendingMutePayload != null && pendingMutePayload.length == 2) {
            characteristic.setValue(pendingMutePayload);
            boolean status = gatt.writeCharacteristic(characteristic);
            if (status) {
                Log.d(TAG, "Запись BLE Mute: Отдано в буфер ОС Android.");
                notifyUiComplete();
                if (disconnectEnable) {
                    Log.d(TAG, "disconnectEnable == true. Выполняем легальный разрыв связи.");
                    Disconnect(gatt);
                }
            } else {
                Log.d(TAG, "Ошибка записи writeCharacteristic");
                notifyUiError();
            }
        } else {
            Log.e(TAG, "Ошибка executeMuteWrite: неверный пакет или характеристика");
            notifyUiError();
        }
    }

    private synchronized void executeIsolatedBackgroundSequence(final BluetoothGatt gatt) {
        if (mBgChannelIndex >= mBgChannelQueue.length()) {
            Log.d(TAG, "4 [ФОН-ФИНАЛ] Очередь полностью улетела в эфир. Освобождаем мост.");
            set_DisconnectEnable(true);
            Disconnect(gatt);
            mBgChannelQueue = "";
            mBgChannelIndex = 0;
            notifyUiComplete();
            return;
        }

        char digitChar = mBgChannelQueue.charAt(mBgChannelIndex);
        Byte cmdByte = MainActivity.DECODER_DIGIT_MAP.get(digitChar);
        final byte commandHex = (cmdByte != null) ? cmdByte : (byte) 0x22;
        byte[] payload = new byte[]{(byte) 0xFE, commandHex};

        Log.d(TAG, String.format(" [ФОН-ЭКСПРЕСС] Отправка в радиоканал: %c [%d/%d].", digitChar, (mBgChannelIndex + 1), mBgChannelQueue.length()));

        BluetoothGattCharacteristic characteristic = BleReadOnlyStorage.getVolumeCharacteristic();
        if (characteristic != null) {
            characteristic.setValue(payload);
            boolean success = gatt.writeCharacteristic(characteristic);
            Log.d(TAG, "[ФОН-ЭКСПРЕСС] Нативная запись в GATT статус: " + success);
        } else {
            Log.e(TAG, "[ФОН-КРИТ] Характеристика не найдена! Аварийный выход.");
            notifyUiError();
            return;
        }
        mBgChannelIndex++;
        mBackgroundQueueHandler.removeCallbacks(mBackgroundQueueRunnable);
        mBackgroundQueueHandler.postDelayed(mBackgroundQueueRunnable, MainActivity.SEQUENTIAL_DELAY);
    }

 private void Disconnect(final BluetoothGatt gatt) {
     mainHandler.postDelayed(() -> {
         if ((gatt != null) && disconnectEnable) {
             Log.w(TAG, "[GATT] Сработал таймаут 550мс. Линк закрыт.");
             gatt.disconnect();
         }

         // Разблокируем UI строго ПОСЛЕ реального дисконнекта ---
         MainActivity.isBleBusy = false;
         // Здесь же можно вернуть кнопкам яркость: setAllButtonsEnabled(true);
         if (MainActivity.instance != null) {
             MainActivity.instance.setAllButtonsEnabled(true);
    //         MainActivity.instance.RestartIdleTimer();  // Перезапустить Таймер простоя смартфона (2мин)  будет перезапускаться в цикле !!!
         }
      }, 550);
 }

    private void waitChannelNumberSend(final BluetoothGatt gatt) {
        mainHandler.postDelayed(() -> {
            if (gatt != null) {
                Log.d(TAG, " [КОНВЕЙЕР] Ожидание 850мс завершено.");
                sendChannelNumber(gatt);
            }
        }, 850);
    }

    private void sendChannelNumber(final BluetoothGatt gatt) {
        executeMuteWrite(gatt);
        if (mBgChannelQueue != null && !mBgChannelQueue.isEmpty() && mBgChannelIndex == 0) {
            mBackgroundQueueHandler.removeCallbacks(mBackgroundQueueRunnable);
            mBackgroundQueueRunnable = () -> executeIsolatedBackgroundSequence(gatt);
            mBgChannelIndex = 1;
            mBackgroundQueueHandler.postDelayed(mBackgroundQueueRunnable, MainActivity.SEQUENTIAL_DELAY);
        }
    }

    public void setcurrentTransactionType(int type) {
        currentTransactionType = type;
    }

    public void DisconnectAndClose() {
        try {
            // 1. Принудительно гасим логический статус соединения
            this.isGattConnected = false;

            // 2. Извлекаем и жестко зачищаем старый дескриптор
            BluetoothGatt oldGatt = BleReadOnlyStorage.getCurrentGatt();
            if (oldGatt != null) {
                Log.w(TAG, "[ТАЙМАУТ ПРОСТОЯ] Легальное уничтожение сокета GATT...");
                oldGatt.disconnect();
                oldGatt.close();
            }

        } catch (Exception e) {
            Log.e(TAG, "Ошибка при очистке сокета по таймауту простоя: " + e.getMessage());
        }
    }

    public void ResumeAfterTimeout() {
        Log.d(TAG, "ResumeAfterTimeout (Холодный старт): [Connection]");
        startConnectTime = android.os.SystemClock.elapsedRealtime();
        mTargetDevice.connectGatt(app, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
        WakeUpCommand = true;
        runAdvertisingDiagnosticFlag = true;
    }

    public boolean send_WakeUpCommand(BluetoothGatt gatt) {
        boolean status;

        byte[] servicePayload = new byte[]{(byte) 0xFD, 0x02};
        Log.d(TAG, "Отправка сервисного пакета -> Address: 0xFD, Command: 0x02");
        disconnectEnable = false;
        BluetoothGattCharacteristic characteristic = BleReadOnlyStorage.getVolumeCharacteristic();
        if (characteristic != null) { //
            characteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE); // нужно?
            characteristic.setValue(servicePayload);
            status = gatt.writeCharacteristic(characteristic); // Отправка сервисной команды 02
            if (status)
            { // Если отправка сервисной команды нормально, то отправка отложенной команды
                if (currentTransactionType == 2) {  // Нажата кнопка громкости
                    mainHandler.postDelayed(() -> {
                        if (gatt != null) {
                            Log.d(TAG, "[Освободить VolumeWorker] Ожидание 850мс завершено.");
                            isGattConnected = true;
                            disconnectEnable = false;
                            isBleBusy = false;
                            if (volumeWorker != null) {
                                volumeWorker.setColdStartFlag(false); // есть соединение
            //                    notifyUiWorkerReleased(); // setAllButtonsEnabled(true) in MainActivity
                                volumeWorker.notifyTransportIsReady(); // Будим воркер из чистого wait() для отправки М100
                                Log.d(TAG, " [GATT-СТАРТ] Сигнал пробуждения гарантированно доставлен на монитор transportLock.");
                            } else {
                                Log.e(TAG, " [GATT-СТАРТ] КРИТ: volumeWorker == null");
                            }
                        }
                    }, 850);  // Нужно дать время для отправки сервисной команды
                }
                else { // (currentTransactionType == 1)  // Нажата кнопка типа MUTE
                    mainHandler.postDelayed(() -> {
                        if (gatt != null) {
                            Log.d(TAG, " [Запись отложенной команды] Ожидание 850мс завершено.");
                            isGattConnected = true;
                            disconnectEnable = true;

                            Log.d(TAG, "pendingMutePayload = " + pendingMutePayload);
                            Log.d(TAG, "currentTransactionType = " + currentTransactionType);
                            sendSingleShotCommand(pendingMutePayload);
                         }
                    }, 850);
                }  // end of (currentTransactionType == 1)
            } else { // Ошибка отправки сервисной команды 02
                Log.e(TAG, "[send_WakeUpCommand] Ошибка отправки сервисной команды 02.");
                isBleBusy = false;
            }
            return status;
        } else {
            Log.e(TAG, "[send_WakeUpCommand] characteristic == null");
            return false;
        }
    } // End off send_WakeUpCommand
} // End off BleRemoteControlManager


