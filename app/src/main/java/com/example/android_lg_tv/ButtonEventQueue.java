package com.example.android_lg_tv;

import java.util.concurrent.LinkedBlockingQueue;

public class ButtonEventQueue {
    public static final byte TYPE_PRESS = 0x01;
    public static final byte TYPE_RELEASE = 0x02;

    // Внутренний неизменяемый класс-контейнер события тача
    public static class VolumeEvent {
        public final byte type;       // TYPE_PRESS или TYPE_RELEASE
        public final byte cmdCode;    // Именной билет кнопки (0x02 = Плюс, 0x03 = Минус)
        public final long timeMs;     // Точное 64-битное системное время смартфона long

        public VolumeEvent(byte type, byte cmdCode, long timeMs) {
            this.type = type;
            this.cmdCode = cmdCode;
            this.timeMs = timeMs;
        }
    }

    // Переводим блокирующую FIFO-очередь на хранение объектов событий
    private final LinkedBlockingQueue<VolumeEvent> queue = new LinkedBlockingQueue<>(10);

    // Сигнатуру метода НЕ ТРОГАЕМ, чтобы не сломать вызовы в MainActivity!
    public boolean putEvent(byte eventType, byte buttonCode, long sysTimeMs) {
        // Убираем усечение и битовые маски. Пишем точное время long в структуру
        VolumeEvent event = new VolumeEvent(eventType, buttonCode, sysTimeMs);

        // Мгновенный асинхронный запуск в UI-потоке смартфона
        return queue.offer(event);
    }

    // Порт чтения для воркера теперь возвращает чистый объект
    public VolumeEvent takeEvent() throws InterruptedException {
        return queue.take(); // Блокирующее ожидание в воркере без нагрузки на процессор
    }

    public void clearQueue() {
        if (this.queue != null) {
            this.queue.clear(); // Начисто выметаем застрявшие пакеты из очереди
        }
    }
}
