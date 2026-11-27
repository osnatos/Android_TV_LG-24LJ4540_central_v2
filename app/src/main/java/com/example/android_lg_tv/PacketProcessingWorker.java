package com.example.android_lg_tv;

import static com.example.android_lg_tv.MainActivity.BoardSleepMode;
import android.os.Handler;
import android.util.Log;

public class PacketProcessingWorker extends Thread {
    private static final long HoldingWindowTime = 400;
    private static final String TAG = "VolumeWorker";
    private final ButtonEventQueue volumeQueue;
    private final Handler mainHandler;
    private final BleRemoteControlManager manager;
    private final MainActivity activity;

    // Единый монитор синхронизации потоков Bluetooth
    private final Object transportLock = new Object();

    private volatile boolean isRunning = true;
    private volatile boolean isTransportWarmedUp = false;

    // --- ПАМЯТЬ КОНТЕКСТА ПЛАНИРОВЩИКА TRANSMISSION_TIME_SCHEDULER ---
    private long dynamicShift = 0;       // Текущее смещение времени для конвейера
    private long lastGattExecuteTime = 0; // Точное системное время ПОСЛЕДНЕГО физического выстрела
    private boolean isFirstPair = true;   // Флаг: работаем ли мы с самой первой парой (Холодный старт)
    private byte lastCmdCode = 0;         // Код текущей активной кнопки
    private long realConnectTime = -1;

    public PacketProcessingWorker(ButtonEventQueue volumeQueue, Handler mainHandler,
                                  BleRemoteControlManager manager, MainActivity activity) {
        this.volumeQueue = volumeQueue;
        this.mainHandler = mainHandler;
        this.manager = manager;
        this.activity = activity;
    }

@Override
public void run() {
    Log.i(TAG, "[ЯДРО] Фоновый воркер громкости успешно запущен и переведен в режим ожидания.");
    while (isRunning) {
         try {
            // Шаг 1: Извлекаем объект события из асинхронного порта чтения FIFO
            ButtonEventQueue.VolumeEvent rawEvent = volumeQueue.takeEvent();
            if (rawEvent == null) {
                continue;
            }

            realConnectTime = -1; // Фиксируем статус холодного старта
            boolean isStartPacket = (rawEvent.type == ButtonEventQueue.TYPE_PRESS);
            Log.i(TAG, "isStartPacket = " + isStartPacket);
            Log.i(TAG, "isFirstPair = " + isFirstPair);

            // Шаг 2: Если физической связи нет - это ХОЛОДНЫЙ СТАРТ первой пары сессии!
            if (isFirstPair) {
                if (isStartPacket) {
                    Log.w(TAG, " [ВОРКЕР ЯДРО] Обнаружен Холодный старт кнопки: " + rawEvent.cmdCode);
                    // Сохраняем isFirstPair=true до расчёта времени первого M=100.

                    // Напрямую пинаем менеджер на запуск нативного Direct Connect с нуля
                    manager.initiateConnection(BleReadOnlyStorage.getCurrentGatt());

                    // ОЖИДАНИЕ: Спим чистым сном на transportLock до notifyAll() от менеджера
                    synchronized (transportLock) {
                        Log.d(TAG, " [ВОРКЕР ЯДРО] Поток уходит в чистый wait() до получения CONNECTED от ОС...");
                        transportLock.wait();
                    }
                }

                // ГАРАНТИРОВАННО СТЕРИЛЬНЫЙ ПРОГРЕВ СОКЕТА ПОСЛЕ РЕАЛЬНОГО ПОДКЛЮЧЕНИЯ
                try {
                    Log.d(TAG, " [ВОРКЕР ЯДРО] Коллбэк коннекта получен! Начинаем прогрев сокета на "
                            + MainActivity.BLE_STABILIZATION_DELAY_MS + " мс...");
                    Thread.sleep(MainActivity.BLE_STABILIZATION_DELAY_MS);
                } catch (InterruptedException e) {
                    Log.w(TAG, "Прогрев воркера был прерван.");
                }

                Log.d(TAG, " [ВОРКЕР ЯДРО] Прогрев завершен! Канал полностью зафиксирован и стабилизирован.");
                // isFirstPair остаётся true до расчёта времени первого M=100 в планировщике.

            } else if (BoardSleepMode) {
                if(isStartPacket) {
                  manager.ResumeAfterTimeout(); // Запустить соединение
                  // ОЖИДАНИЕ: Спим чистым сном на transportLock до notifyAll() от менеджера
                  synchronized (transportLock) {
                    Log.d(TAG, " [ВОРКЕР ЯДРО] BoardSleepMode уходит в чистый wait() " +
                                                             "до завершения отправки сервисной 02");
                    transportLock.wait();  // Будим после завершения отправки сервисной команды 02
                  }
                }

                 Log.d(TAG, " [ВОРКЕР ЯДРО] Прогрев выхода из BoardSleepMode завершен! " +
                                                  "Канал полностью зафиксирован и стабилизирован.");
           //         BoardSleepMode = false;

            } else {
              // Защита от мусора: пакет отпускания пришел на закрытый канал - игнорируем
            //    if (!isStartPacket && isFirstPair) {
            //        continue;
            //    }
            }
            realConnectTime = android.os.SystemClock.elapsedRealtime();
            // !!! ВЫЗЫВАЕМ ПЛАНИРОВЩИК АБСОЛЮТНОГО ЦЕЛЕВОГО ВРЕМЕНИ ВЫСТРЕЛА !!!
            TransmissionTask task = transmissionTimeScheduler(rawEvent, realConnectTime);
            if (task == null) {
                continue;
            }
            Log.d(TAG, " [ВОРКЕР ЯДРО] Планирование времени выстрела. isStartPacket= " + isStartPacket);
            // === ШАГ 3 С КОРРЕКЦИЕЙ: Выверяем время по единой оси elapsedRealtime ===
            long currentTime = android.os.SystemClock.elapsedRealtime();
            Log.d(TAG, " [ВОРКЕР ЯДРО] Планирование времени выстрела. currentTime= " + currentTime);
            Log.d(TAG, " [ВОРКЕР ЯДРО] Планирование времени выстрела. task.targetTimeMs= " + task.targetTimeMs);

            // Окончательный чистый сон Шага 3 по скорректированному целевому времени
            long sleepTime;
            if (currentTime < task.targetTimeMs) {
                sleepTime = task.targetTimeMs - currentTime;
                Log.d(TAG, "[ВОРКЕР ТРАНСПОРТ] Защита канала активирована. Сон треда на " + sleepTime + " мс...");
                Thread.sleep(sleepTime);
            }
            else {
                if(currentTime > task.targetTimeMs) {
                    Log.i(TAG, " [ВОРКЕР ЯДРО] currentTime > task.targetTimeMs");
                    Log.d(TAG, " [ВОРКЕР ЯДРО] currentTime = " + currentTime + " task.targetTimeMs = " + task.targetTimeMs);
                }
            }

            // ПРЯМОЙ ВЫСТРЕЛ В ХАРАКТЕРИСТИКУ ХРАНИЛИЩА (Передаем строго 3 очищенных байта!)
            manager.sendVolumePacketDirectlyFromWorker(task.packet, isStartPacket);

        } catch (InterruptedException e) {
            Log.w(TAG, " [ВОРКЕР ЯДРО] Фоновый поток воркера принудительно прерван ОС Android.");
            break; // Легальный и чистый выход из цикла при onDestroy приложения
        } catch (Exception e) {
            Log.e(TAG, "[КРИТ] Ошибка в цикле воркера громкости: ", e);
        }
    }
    Log.i(TAG, "[ЯДРО] Фоновый воркер громкости полностью завершил работу и стерт из ОЗУ.");
}


    /**
     * ПЛАНИРОВЩИК ВРЕМЕНИ ПЕРЕДАЧИ КОМАНД ГРОМКОСТИ В РАДИОКАНАЛ (TransmissionTimeScheduler)
     * Рассчитывает абсолютное целевое время выстрела с учетом динамического смещения.
     */
    private TransmissionTask transmissionTimeScheduler(ButtonEventQueue.VolumeEvent event, long realConnectTime) {
        long targetTimeMs;
        int marker;

        if (event.type == ButtonEventQueue.TYPE_PRESS) { // ACTION_DOWN
            if(BoardSleepMode) { // Natan
                dynamicShift = realConnectTime - event.timeMs;
                targetTimeMs = realConnectTime + 1;  // Natan
            }
            else if (realConnectTime != -1 && isFirstPair) {
                // Сценарий А: Холодный старт – вычисляем стартовое смещение
                dynamicShift = realConnectTime - event.timeMs;
                targetTimeMs = realConnectTime;
                isFirstPair = false;
            } else {
                // +++ Сценарий Б: Новое нажатие на уже Горячем канале! +++
                // Берем реальное текущее время тача процессора и полностью обнуляем сдвиг прошлого!
                targetTimeMs = event.timeMs;
                dynamicShift = 0;
            }

            marker = 100;
            lastGattExecuteTime = targetTimeMs;
            lastCmdCode = event.cmdCode;

            Log.d(TAG, String.format(" [ПЛАНИРОВЩИК] Старт M=100. Целевое время: %d. Динамический сдвиг: %d мс", targetTimeMs, dynamicShift));

        } else if (event.type == ButtonEventQueue.TYPE_RELEASE) { // ACTION_UP
            // ACTION_UP сохраняет собственное время события. Не прибавляем к нему
            // dynamicShift: это смещение подключения, а не задержка отпускания пальца.
            // STOP допускается не раньше чем через 400 мс после запланированного M=100.
            long earliestStopTimeMs = lastGattExecuteTime + HoldingWindowTime;
            targetTimeMs = Math.max(event.timeMs, earliestStopTimeMs);

            if (BoardSleepMode) { // Плата после выхода из 2-секундной рекламы
                Log.d(TAG, "TimeScheduler,ACTION_UP, BoardSleepMode; STOP=" + targetTimeMs);
                BoardSleepMode = false;
            }

            marker = 0; // Маркер конца (M=0)
            lastGattExecuteTime = targetTimeMs; // Запоминаем время выстрела Стопа

            if (isFirstPair) {
                isFirstPair = false; // Первая пара успешно улетела, канал переходит в горячий режим
            }

            Log.d(TAG, String.format(" [ПЛАНИРОВЩИК] Стоп M=0. Целевое время: %d. Защита окна удержания выполнена.", targetTimeMs));
        } else {
            Log.e(TAG,"Неизвестный TYPE of event");
            return null; // Неизвестный патрон
        }

        // Собираем чистый 3-байтовый аппаратный пакет пульта строго по ТЗ
        byte[] transmitPacket = new byte[] {
                0x04,          // NEC code пульта
                lastCmdCode,   // Код активной команды (0x02 или 0x03)
                (byte) marker  // Маркер (100 или 0)
        };

        return new TransmissionTask(transmitPacket, targetTimeMs);
    }

    /**
     * Контейнер задачи передачи данных на выходе из планировщика
     */
    private static class TransmissionTask {
        public final byte[] packet;     // Чистые 3 байта пульта {0x04, cmdCode, marker}
        public final long targetTimeMs; // Абсолютная миллисекунда системного времени для выстрела

        public TransmissionTask(byte[] packet, long targetTimeMs) {
            this.packet = packet;
            this.targetTimeMs = targetTimeMs;
        }
    }

    /**
     * Метод легального пробуждения воркера из wait() по сигналу от менеджера BLE.
     * Замкнут строго на transportLock, что полностью ликвидирует Deadlock потоков.
     */
    public void notifyTransportIsReady() {
        synchronized (transportLock) {
            Log.d(TAG, "СИГНАЛ: Железная отмашка доставлена на инстанс воркера!");
            isTransportWarmedUp = true;
            transportLock.notifyAll(); // Гарантированно будим поток, уснувший на строке 58
        }
    }

    public void resetTransportStatus() {
        synchronized (transportLock) {
            Log.e(TAG, "АВАРИЯ: Принудительный вывод воркера из ожидания из-за зависания ОС Android.");
            isTransportWarmedUp = false;
            transportLock.notifyAll(); // Вырываем поток из вечного wait(), чтобы не висел в памяти
        }
    }

    public void shutdown() {
        isRunning = false;
        synchronized (transportLock) {
            transportLock.notifyAll();
        }
        this.interrupt();
    }
    // Добавить внутрь класса PacketProcessingWorker
    public void forceColdStart() {
        this.isFirstPair = true;
        Log.d("VolumeWorker", "[ВОРКЕР ЯДРО] Получен UI-сигнал сброса. Конвейер переведен в режим ХОЛОДНОГО СТАРТА.");
    }
    public void setColdStartFlag(boolean flag) {
        this.isFirstPair = flag;
        if (flag) {
            Log.d("VolumeWorker", "[ВОРКЕР ЯДРО] VolumeWorker переведен в режим ХОЛОДНОГО СТАРТА.");
        } else {
            Log.d("VolumeWorker", "[ВОРКЕР ЯДРО] VolumeWorker переведен в режим ГОРЯЧЕГО СТАРТА.");

        }
    }
 }
