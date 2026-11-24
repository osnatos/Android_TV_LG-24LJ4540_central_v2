package com.example.android_lg_tv;

//import static androidx.core.content.ContentProviderCompat.requireContext;

import android.app.Application;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Menu;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import java.util.HashMap;
import java.util.Map;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.example.android_lg_tv.databinding.ActivityMainBinding;

public class MainActivity extends AppCompatActivity {
    public static MainActivity instance;
    public static final long VOLUME_WATCHDOG_TIMEOUT_MS = 15000; // 15sec
    public static final long BLE_STABILIZATION_DELAY_MS = 350;  // Natan
//    public static final long BLE_BoardSleepMode_DELAY_MS = 50;  // Natan

    private static final byte NEC_code = 0x04;
    //   private static final byte cmdCode_VolPlus = 0x02;
    //   private static final byte cmdCode_VolMinus = 0x03;
    private static byte cmdCode;
    public static volatile boolean isVolumeButtonPressed = false;
    public static final int MUTE_Type = 1;
    public static final int Volume_Type = 2;
    public static final int ChannelDialog_Type = 3;
    public static volatile int Group_Type = MUTE_Type;
    public static int VolumeLock = 3;
    public static volatile boolean isM100_Flag = false;
    public static volatile boolean isBleBusy = false;
    public static volatile boolean BoardSleepMode = false;

    // Переменные для пошаговой очереди отправки каналов
    private String pendingChannelQueue = "";
    private int currentChannelQueueIndex = 0;
    private static final byte SERV_ADDRESS = (byte) 0xFD;
    private static final byte SERV_CMD_SLEEP = (byte) 0x01; // Уход в сон (2 сек)
    private static final byte SERV_CMD_WAKE = (byte) 0x02; // Разгон эфира (210 мс)

    // Таймер простоя смартфона (120 000 мс = 2 минуты)
    //  private static final long APP_IDLE_TIMEOUT_MS = 120000; // 120 000 мс = 2 минуты
    private static final long APP_IDLE_TIMEOUT_MS = 30000; // 30 000 мс = 30 sec
//    private static final long APP_IDLE_TIMEOUT_MS = 180000; // 180 000 мс = 3 минуты
    private final Runnable idleRunnable = new Runnable() {
        @Override
        public void run() {
            Log.d(TAG, "Таймер простоя смартфона истек. Перевод платы в режим сна...");

            // 1. Предварительно взводим флаг сна пульта
            if (manager != null) {
                manager.isBoardSleeping = true; //
            }
            isBleBusy = false;  // Если последняя активность не зачистила флаг
            BoardSleepMode = true; //

            // 2. Стреляем сервисной командой сна 0x01 (она сама взведет disconnectEnable и закроет GATT)
            sendServiceCommand(SERV_CMD_SLEEP); //
        }
    };

    // --- БЛОК ДЛЯ ДЕКОДЕРА КАНАЛОВ ---
    private static final long CH_DEBOUNCE_DELAY = 400; // Антидребезг (300-500 мс)
    public static final long SEQUENTIAL_DELAY = 400; // Интервал между цифрами (300-500 мс)
    private long lastChannelClickTime = 0;

    // Карта НЕХ-команд для декодера HOT MAGIC HD
    public static final Map<Character, Byte> DECODER_DIGIT_MAP = new HashMap<>();

    static {
        DECODER_DIGIT_MAP.put('1', (byte) 0x19);
        DECODER_DIGIT_MAP.put('2', (byte) 0x1A);
        DECODER_DIGIT_MAP.put('3', (byte) 0x1B);
        DECODER_DIGIT_MAP.put('4', (byte) 0x1C);
        DECODER_DIGIT_MAP.put('5', (byte) 0x1D);
        DECODER_DIGIT_MAP.put('6', (byte) 0x1E);
        DECODER_DIGIT_MAP.put('7', (byte) 0x1F);
        DECODER_DIGIT_MAP.put('8', (byte) 0x20);
        DECODER_DIGIT_MAP.put('9', (byte) 0x21);
        DECODER_DIGIT_MAP.put('0', (byte) 0x22);
    }

    private void executeSequentialTransmission(String channelString) {
        if (channelString == null || channelString.isEmpty()) return;
        BleRemoteControlManager.getInstance().startBackgroundChannelQueue(channelString);
    }

    private ActivityMainBinding binding;

    // Переменная живет здесь, пока идет замер
    private BleIntervalDetector bleIntervalDetector = null;
    private BleRemoteControlManager manager;
    private static final int ALL_PERMISSIONS_CODE = 1;
    private static final String TAG = "MainActivity";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private static Application sApplication;
    private TextView bond_status;

    // Компоненты 3-байтового конвейера громкости
    private ButtonEventQueue volumeQueue;
    private PacketProcessingWorker volumeWorker;

    public static Application getApp() {
        return sApplication;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        sApplication = getApplication(); //

        // 1. Инициализация разметки и системного тулбара
        binding = ActivityMainBinding.inflate(getLayoutInflater()); //
        setContentView(binding.getRoot()); //
        instance = this;


        setSupportActionBar(binding.toolbar); //
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); //

        findViewById(R.id.button_ch_number).setOnClickListener(v -> openChannelInputDialog()); //
        //   binding.buttonChNumber.setOnClickListener(v -> openChannelInputDialog());
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle("TV-LG and HOT decoder"); //
        }

        bond_status = binding.bondStatus; //

        // 3. Инициализируем BLE менеджер
        manager = BleRemoteControlManager.getInstance(); //

        // ====== ИНТЕГРАЦИЯ АСИНХРОННОГО КОНВЕЙЕРА ГРОМКОСТИ ======
        Log.d(TAG, "ЯДРО: Развертывание асинхронного конвейера громкости...");
        volumeQueue = new ButtonEventQueue();

        volumeQueue.clearQueue(); // Стерилизуем FIFO перед запуском треда

        volumeWorker = new PacketProcessingWorker(
                volumeQueue,
                handler,
                manager,
                MainActivity.this
        );
        volumeWorker.start(); // Запуск фонового автомата
        Log.d(TAG, "ЯДРО: Фоновый воркер громкости успешно запущен и переведен в режим ожидания.");
        // НАШ ПОСЛЕДНИЙ МОСТ: Передаем ссылку на запущенный воркер внутрь менеджера!
        manager.setVolumeWorker(volumeWorker);
        // ====== КОНЕЦ ИНТЕГРАЦИИ ======

        // Коллбек синхронизации UI
        manager.setUiCallback(new BleRemoteControlManager.BleUiCallback() { //
            @Override
            public void onOperationComplete() {
                Log.d(TAG, "onOperationComplete");

                switch (Group_Type) {

                    case MUTE_Type:
                        Log.d(TAG, "MUTE_Type (SingleShotCommand)");
        //                isBleBusy = false;           // Освобождаем только после дисконнект
        //                setAllButtonsEnabled(true);  // Освобождаем только после дисконнект ???
        //                RestartIdleTimer();  // Перезапустить Таймер простоя смартфона (2мин)
                        break;

                    case Volume_Type:
                        if (isM100_Flag) {
                            Log.d(TAG, "M100_Flag");
                            isM100_Flag = false;
                            restartDisconnectionTimer(volumeWatchdogTimerRunnable);
                            handler.removeCallbacks(idleRunnable);
                            handler.postDelayed(idleRunnable, APP_IDLE_TIMEOUT_MS);

                        } else {
                            Log.d(TAG, "Volume_Type");
                            isBleBusy = false;
                            setAllButtonsEnabled(true); //
                            Group_Type = 0; //
                            Log.d(TAG, "Транзакция громкости успешно закрыта. Выход из коллбека.");
                            restartDisconnectionTimer(volumeWatchdogTimerRunnable);
                            handler.removeCallbacks(idleRunnable);
                            handler.postDelayed(idleRunnable, APP_IDLE_TIMEOUT_MS);
                        }
                        break;

                    case ChannelDialog_Type:
                        Log.d(TAG, "ChannelDialog_Type");

                        // Проверяем, остались ли еще цифры в нашей очереди
                        if (currentChannelQueueIndex < pendingChannelQueue.length()) { //
                            // Берем следующую по порядку цифру
                            char digitChar = pendingChannelQueue.charAt(currentChannelQueueIndex);
                            currentChannelQueueIndex++; // Сдвигаем указатель для следующего шага

                            Byte cmdByte = DECODER_DIGIT_MAP.get(digitChar);
                            final byte commandHex = (cmdByte != null) ? cmdByte : (byte) 0x22;

                            // Делаем стандартную межсимвольную паузу от момента готовности BLE-канала
                            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                                byte[] payload = new byte[]{(byte) 0xFE, commandHex};
                                Log.d(TAG, "Конвейер очереди отправил цифру: " + digitChar);
                                manager.sendSingleShotCommand(payload);
                            }, SEQUENTIAL_DELAY); // 400 мс интервала между цифрами
                        } else {
                            // Если вся строка успешно отправилась — полностью освобождаем интерфейс и очищаем буфер
                 //           isBleBusy = false;           // Освобождаем только после дисконнект
                //            setAllButtonsEnabled(true);  // Освобождаем только после дисконнект ???
                            RestartIdleTimer();  // Перезапустить Таймер простоя смартфона (2мин) ???
                            pendingChannelQueue = "";
                            Group_Type = 0; //
                            Log.d(TAG, "Вся очередь номера канала успешно передана на пульт!");
                        }
                        RestartIdleTimer();  // Перезапустить Таймер простоя смартфона (2мин)
                        break;
                    default:
                        Log.w(TAG, "Group_Type =" + Group_Type);
                        break;
                }
            }
            @Override
            public void onCriticalError() {
                handler.removeCallbacks(volumeWatchdogTimerRunnable); //
                // АВАРИЙНЫЙ ЩИТ ГРОМКОСТИ: Снимаем замки и возвращаем яркость кнопкам пульта при любой аварии
                isBleBusy = false; //
                isVolumeButtonPressed = false;
                //MarkerStart = false;
                setAllButtonsEnabled(true); //

                pendingChannelQueue = ""; //
                BleRemoteControlManager.getInstance().set_DisconnectEnable(true); //
                Log.e(TAG, "Критическая ошибка BLE во время работы очереди. Интерфейс принудительно очищен."); //
            }
            @Override
            public void onToast(String Message) {
                Toast.makeText(MainActivity.getApp(), Message, Toast.LENGTH_LONG).show();
            }
            @Override
            public void onConnectionTimeMsec(long ConnectionTimeMsec) {
                runOnUiThread(() -> {
    //                 android.widget.Toast.makeText(MainActivity.this, "Длительность соединения = "
    //                        + ConnectionTimeMsec + "мсек", android.widget.Toast.LENGTH_LONG).show();
                    if (bond_status != null) {
                      if(ConnectionTimeMsec == -1) {
                          bond_status.setText("Статус: Вне зоны / Выкл.");
                          bond_status.setBackgroundColor(ContextCompat.
                                    getColor(MainActivity.this, R.color.holo_orange_light));
                          isBleBusy = false;
                          setAllButtonsEnabled(true);
                          manager.FastAdvMode = false;
                      }
                      else if(ConnectionTimeMsec <= 1000) {
                      //    bond_status.setText("Статус: Активен (100мс)");
                          bond_status.setText("Cоединен. ( "  + ConnectionTimeMsec + " мс )");
                          bond_status.setBackgroundColor(ContextCompat.
                                  getColor(MainActivity.this, R.color.blue_light_color));
                          manager.FastAdvMode = true;

                      } else {
                    //      bond_status.setText("Статус: Сон (2 сек)");
                          bond_status.setText("Cоединен. ( "  + ConnectionTimeMsec + " мс )");
                          bond_status.setBackgroundColor(ContextCompat.
                                  getColor(MainActivity.this, R.color.colorDeepPurple));
                          manager.FastAdvMode = false;

                      }
                    }
                });
            }
            @Override
            public void onRunAdvertisingDiagnostic() {
               String currentMac = BleRemoteControlManager.getInstance().getCurrentDeviceName(); //
                if (currentMac != null && !currentMac.isEmpty()) {
                    runManualAdvertisingDiagnostic(currentMac); //
                }

            }
            @Override
            public void onDisconnect() {
                if (bond_status != null) {
                    bond_status.setText("Статус: Дисконнект");
                    bond_status.setBackgroundColor(ContextCompat.
                            getColor(MainActivity.this, R.color.holo_orange_light));

                    handler.postDelayed(() -> {
                        if (manager.FastAdvMode) {
                            bond_status.setText("Статус: Активен (100мс)");
                            bond_status.setBackgroundColor(ContextCompat.
                                    getColor(MainActivity.this, R.color.blue_light_color));
                        } else {
                            bond_status.setText("Статус: Сон (2 сек)");
                            bond_status.setBackgroundColor(ContextCompat.
                                    getColor(MainActivity.this, R.color.colorDeepPurple));
                        }
                    }, 500);
                }
            }


        });  // End off manager.setUiCallback

        // Назначаем слушатели тачей
        binding.buttonMute.setOnTouchListener(standardTouchListener);
        binding.buttonON.setOnTouchListener(standardTouchListener); //
        binding.btnHotOn.setOnTouchListener(standardTouchListener); //
        binding.btnPause.setOnTouchListener(standardTouchListener); //
        binding.btnResume.setOnTouchListener(standardTouchListener); //
        binding.buttonChMinus.setOnTouchListener(standardTouchListener); //
        binding.buttonChPlus.setOnTouchListener(standardTouchListener); //
        binding.buttonEsc.setOnTouchListener(standardTouchListener); //
        binding.buttonSelect.setOnTouchListener(standardTouchListener); //

        binding.buttonVolPlus.setOnTouchListener(volumeTouchListener); //
        binding.buttonVolMinus.setOnTouchListener(volumeTouchListener); //

        PermissionHelper.checkAndRequestBlePermissions(this); //
        isBleBusy = false; //

        // НАЧАЛО ИНТЕГРАЦИИ: СЧИТЫВАНИЕ ПОСЛЕДНЕЙ РАБОЧЕЙ СЕССИИ И ИНИЦИАЛИЗАЦИЯ ПЛАШКИ
        boolean hasLastSavedDevice = manager.initializeLastSavedDevice(); //
        if (binding.tvStatusStrip != null) { //
            binding.tvStatusStrip.setText(manager.getCurrentDeviceStatusString()); //
        }

        if (!hasLastSavedDevice) {
            android.util.Log.d("MainActivity", "Первый запуск: в памяти пусто. Автоматически открываем выбор."); //
            android.content.Intent intent = new android.content.Intent(MainActivity.this, SelectDeviceActivity.class); //
            startActivity(intent); //
        } else {
            android.util.Log.d("MainActivity", "Успешный холодный старт пульта. Поднята последняя рабочая сессия."); //
        }

    } // Конец onCreate

    @Override
    protected void onResume() {
        super.onResume();
        android.util.Log.d("MainActivity", "onResume: пульт вернулся в фокус.");

        // АКТИВАЦИЯ ТАЙМЕРА: запускаем отсчет простоя при входе
        handler.removeCallbacks(idleRunnable);
        handler.postDelayed(idleRunnable, APP_IDLE_TIMEOUT_MS);

        /* ================================================================= */
        /* TEMPORARY TEST: Delayed service wakeup injection (1.5s delay)    */
        /* ================================================================= */
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                //    android.util.Log.d("MainActivity", "Executing 1.5s delayed test wake-up call...");
                android.util.Log.d("MainActivity", "Executing 750ms delayed test wake up call...");

                sendServiceCommand(SERV_CMD_WAKE); // Fires raw 0xFD 0x02 directly to GATT bypassing queues
            }
        }, 750); // 1500 ms window allows the phone to securely scan and catch the 2-sec slow adv

        if (binding.tvStatusStrip != null && manager != null) {
            binding.tvStatusStrip.setText(manager.getCurrentDeviceStatusString());
            android.util.Log.d("MainActivity", "Плашка статуса успешно обновлена на лету.");
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        android.util.Log.d("MainActivity", "onPause: Перехват закрытия. Динамический взвод будильника..."); //

        String dynamicTargetMac = BleRemoteControlManager.getInstance().getCurrentDeviceName(); //
        if (dynamicTargetMac == null || dynamicTargetMac.isEmpty()) { //
            android.util.Log.w("MainActivity", "onPause: Текущий МАС пуст, взвод отменен."); //
            return; //
        }

        Intent alarmIntent = new Intent(this, BleAlarmReceiver.class); //
        alarmIntent.putExtra("TARGET_MAC_STRING", dynamicTargetMac); //
        android.app.PendingIntent pendingIntent = android.app.PendingIntent.getBroadcast( //
                this, 99, alarmIntent, //
                android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE //
        );

        android.app.AlarmManager alarmManager = (android.app.AlarmManager) getSystemService(Context.ALARM_SERVICE); //
        if (alarmManager != null) { //
            long triggerTime = System.currentTimeMillis() + 1000; //
            try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) { //
                    android.util.Log.w("MainActivity", "Точные будильники запрещены ОС. Взводим безопасный резервный метод."); //
                    alarmManager.setAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent); //
                } else {
                    alarmManager.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent); //
                    android.util.Log.d("MainActivity", "Точный будильник зафиксирован в ОС для MAC: " + dynamicTargetMac); //
                }
            } catch (SecurityException e) { //
                android.util.Log.e("MainActivity", "SecurityException заблокирован. Мягкий откат на неточный взвод:", e); //
                alarmManager.setAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent); //
            }
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        android.util.Log.d("MainActivity", "onStop: приложение ушло в фоновые задачи."); //
        handler.removeCallbacks(idleRunnable); //
    }

    @Override
    protected void onDestroy() {
        // Жестко тушим фоновый воркер конвейера при уничтожении приложения
        if (volumeWorker != null) {
            Log.d(TAG, "onDestroy: Остановка фоного воркера громкости...");
            volumeWorker.shutdown();
        }
        super.onDestroy();
        instance = null; // Освобождаем память, когда Activity закрывается
    }
    private void showAboutListDialog() {
        String versionName = "1.5";
        try {
            versionName = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
        }

        // Добавляем второй пункт для запуска диагностики рекламы
        final String[] items = new String[] {
                "Версия приложения: " + versionName,
                "Диагностика рекламы BLE (Интервал)"
        };

        androidx.appcompat.app.AlertDialog.Builder builder = new androidx.appcompat.app.AlertDialog.Builder(this);
        builder.setTitle("Информационное меню");
        builder.setItems(items, (dialog, which) -> {
            if (which == 0) {
                android.widget.Toast.makeText(getApplicationContext(), "Сборка: LG_TV_TEST_CC2340_2026",
                        android.widget.Toast.LENGTH_SHORT).show();
            } else if (which == 1) {
                // === КЛИК НА ДИАГНОСТИКУ ===
                // Получаем текущий MAC-адрес платы из синглтона BLE-менеджера
                String currentMac = BleRemoteControlManager.getInstance().getCurrentDeviceName();

                if (currentMac == null || currentMac.isEmpty() || "XX:XX:XX:XX:XX:XX".equals(currentMac)) {
                    android.widget.Toast.makeText(getApplicationContext(), "Ошибка: Стенд не выбран или MAC пуст!",
                            android.widget.Toast.LENGTH_LONG).show();
                } else {
                    // Запускаем диагностику прямо из Мэйн
                    runManualAdvertisingDiagnostic(currentMac);
                }
            }
        });

        builder.setPositiveButton("Закрыть", (dialog, which) -> dialog.dismiss());
        androidx.appcompat.app.AlertDialog dialog = builder.create();
        dialog.show();
    }

    private long lastClickTime = 0;
    private static final long CLICK_DEBOUNCE_MS = 300;
    private final View.OnTouchListener standardTouchListener = new View.OnTouchListener() {
        @Override
        public boolean onTouch(View v, MotionEvent event) {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                if (isBleBusy) return false;
                RestartIdleTimer(); // Взвести таймер для отправки сервисной команды 01 (реклама 2сек)
                long currentTime = SystemClock.elapsedRealtime();
                if (currentTime - lastClickTime < CLICK_DEBOUNCE_MS) {
                    return false;
                }
                lastClickTime = currentTime;

                int id = v.getId();
                byte[] singleShotData = new byte[2];

                if (id == R.id.button_esc) {
                    singleShotData[0] = (byte) 0xFE;
                    singleShotData[1] = 0x0C;
                    Log.d(TAG, "ESC: Выстрел по касанию.");
                } else if (id == R.id.button_select) {
                    singleShotData[0] = (byte) 0xFE;
                    singleShotData[1] = 0x14;
                    Log.d(TAG, "SELECT: Выстрел по касанию.");
                } else if (id == R.id.button_mute) {
                    singleShotData[0] = NEC_code;
                    singleShotData[1] = 0x09;
                    Log.d(TAG, "MUTE: Выстрел по касанию.");
                } else if (id == R.id.button_ON) {
                    singleShotData[0] = NEC_code;
                    singleShotData[1] = 0x08;
                    Log.d(TAG, "ON TV: Выстрел по касанию.");
                } else if (id == R.id.btn_hot_on) {
                    singleShotData[0] = (byte) 0xFE;
                    singleShotData[1] = 0x00;
                    Log.d(TAG, "ON HOT: Выстрел по касанию.");
                } else if (id == R.id.btn_pause) {
                    singleShotData[0] = (byte) 0xFE;
                    singleShotData[1] = 0x73;
                    Log.d(TAG, "ПАУЗА: Выстрел по касанию.");
                } else if (id == R.id.btnResume) {
                    singleShotData[0] = (byte) 0xFE;
                    singleShotData[1] = 0x72;
                    Log.d(TAG, "ПРОДОЛЖИТЬ: Выстрел по касанию.");
                } else if (id == R.id.button_ch_minus) {
                    singleShotData[0] = (byte) 0xFE;
                    singleShotData[1] = 0x09;
                    Log.d(TAG, "CH Minus: Выстрел по касанию.");
                } else if (id == R.id.button_ch_plus) {
                    singleShotData[0] = (byte) 0xFE;
                    singleShotData[1] = 0x08;
                    Log.d(TAG, "CH Plus: Выстрел по касанию.");
                } else {
                    return false;
                }
                manager.set_DisconnectEnable(true);
                isBleBusy = true;
                setAllButtonsEnabled(false);
                Group_Type = MUTE_Type;
                handler.removeCallbacks(volumeWatchdogTimerRunnable);

                if (BleRemoteControlManager.getInstance().isBoardSleeping) {
                    manager.pendingMutePayload = singleShotData;
                    BleRemoteControlManager.getInstance().ResumeAfterTimeout();
                 } else {
                    manager.sendSingleShotCommand(singleShotData);
                }

                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        setAllButtonsEnabled(true);
                        isBleBusy = false;
                    }
                }, 400);
                return true;
            }
            return false;
        }
    };

    /**
     * ГИБРИДНЫЙ КОНВЕЙЕРНЫЙ СЛУШАТЕЛЬ ГРОМКОСТИ С ФИКСАЦИЕЙ
     */
    private final View.OnTouchListener volumeTouchListener = new View.OnTouchListener() {
        @Override
        public boolean onTouch(View v, MotionEvent event) {
            final int id = v.getId();
            // Атомарный "именной билет" кнопки: 0x02 для Плюса, 0x03 для Минуса
            //      final byte cmdCode = (id == R.id.button_vol_plus) ? (byte) 0x02 : (byte) 0x03;
            cmdCode = (id == R.id.button_vol_plus) ? (byte) 0x02 : (byte) 0x03;
 //           manager.setcurrentTransactionType(2);  // Natan
  //          handler.removeCallbacks(idleRunnable);
  //          handler.postDelayed(idleRunnable, APP_IDLE_TIMEOUT_MS); // Перезапустить Таймер простоя смартфона (2мин)
             switch (event.getAction()) {
                 case MotionEvent.ACTION_DOWN:
                    //       if(isBleBusy) return false;
                    RestartIdleTimer();  // Перезапустить Таймер простоя смартфона (2мин)
                    if (VolumeLock > 0) {
                        Log.d("ACTION_DOWN", "VolumeLock = " + VolumeLock);
                        return false;
                    }
                    Group_Type = Volume_Type;
                    manager.setcurrentTransactionType(2);  // Natan
                    // ШАГ 1: Лог и проверка интерфейсного замка на самом входе
                    Log.d("UI_TEST", " [ТАЧ ГРОМКОСТЬ] Клик зафиксирован! Текущий статус MainActivity.isBleBusy = " + isBleBusy);
                    // ШАГ 2: Обновление таймеров и визуального фокуса кнопки
             //       handler.removeCallbacks(idleRunnable);
             //       handler.postDelayed(idleRunnable, APP_IDLE_TIMEOUT_MS); // Перезапустить Таймер простоя смартфона (2мин)
                    restartDisconnectionTimer(volumeWatchdogTimerRunnable); // Перезапустить Таймер disconnect (15сек)

                    v.setPressed(true);
                    isBleBusy = true;
                    disableExceptOne(id); // Блокируем остальные кнопки

                    isVolumeButtonPressed = true;

                    if(manager.isBoardSleeping) {
                         manager.setcurrentTransactionType(2);
                         manager.pendingMutePayload = null;
                         volumeWorker.setColdStartFlag(false);
                         handler.removeCallbacks(volumeWatchdogTimerRunnable);
                         Log.d("ACTION_DOWN", "isBoardSleeping = " + manager.isBoardSleeping);
                     }

                         // ШАГ 3: ОРИГИНАЛЬНАЯ ЗАПИСЬ ФФ — Патрон 0x01 легально падает в коробку!
                    volumeQueue.putEvent(ButtonEventQueue.TYPE_PRESS, cmdCode, android.os.SystemClock.elapsedRealtime());
                    Log.i(TAG, " [UI-ТРИГГЕР] Запись ФФ выполнена.");

                    VolumeLock = 1; // закрыть DOUN на 400 мс

                    handler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            if (!isVolumeButtonPressed) { // Если кнопка не нажата
                                writeButtonUp(v, cmdCode);
                                VolumeLock = 0; // освободить оба DOUN и UP
                            } else { // Если кнопка нажата
                                VolumeLock = 2; // ждать чтобы кнопку отпустили
                            }
                        }
                    }, 400);
                    break;
                case MotionEvent.ACTION_UP:
                    RestartIdleTimer();  // Перезапустить Таймер простоя смартфона (2мин)
                    restartDisconnectionTimer(volumeWatchdogTimerRunnable); // Перезапустить Таймер disconnect (15сек)
                    if (VolumeLock == 1) {
                        Log.d("ACTION_UP", "VolumeLock = " + VolumeLock);
                        isVolumeButtonPressed = false;
                        v.setPressed(false);
                        VolumeLock = 3;
                    } else if (VolumeLock == 2) {
                        Log.d("ACTION_UP", "VolumeLock = " + VolumeLock);
                        writeButtonUp(v, cmdCode);
                        VolumeLock = 0;
                    } else if (VolumeLock == 3) {
                        Log.d("ACTION_UP", "Блокирован. VolumeLock = " + VolumeLock);

                    }
                    break;

/*                case MotionEvent.ACTION_CANCEL: // <<< СИСТЕМНЫЙ СБРОС (Капкан Android)
                    Log.w(TAG, "[ТАЧ] ВНИМАНИЕ! Android попытался принудительно прервать удержание через ACTION_CANCEL!");
                    //    return true;
*/
                 case MotionEvent.ACTION_CANCEL:
                     Log.w(TAG, "[ТАЧ] ACTION_CANCEL: обработка как освобождение кнопки громкости.");
                     RestartIdleTimer();
                     restartDisconnectionTimer(volumeWatchdogTimerRunnable);
                     if (VolumeLock == 1) {
                        isVolumeButtonPressed = false;
                        v.setPressed(false);
                        VolumeLock = 3;
                     } else if (VolumeLock == 2) {
                       writeButtonUp(v, cmdCode);
                       VolumeLock = 0;
                     } else if (VolumeLock == 3) {
                       Log.d(TAG, "[ACTION_CANCEL] Освобождение уже ожидается от отложенного обработчика.");
                     }
                     break;
            }
            return true;
        }
    };

    public void setAllButtonsEnabled(boolean enabled) {
        android.view.ViewGroup rootView = binding.main;
        if (rootView == null) return;

        for (int i = 0; i < rootView.getChildCount(); i++) {
            View child = rootView.getChildAt(i);
            if (child.getId() == R.id.appbar_layout || child.getId() == R.id.textClock
                                          || child.getId() == R.id.bond_status) {
                continue;
            }
            child.setClickable(enabled);
            child.setAlpha(enabled ? 1.0f : 0.5f);
            child.setEnabled(enabled);
        }
    }

    private void disableExceptOne(int currentButtonId) {
        android.view.ViewGroup rootView = binding.main;
        if (rootView == null) return;

        for (int i = 0; i < rootView.getChildCount(); i++) {
            android.view.View child = rootView.getChildAt(i);
            if (child instanceof android.widget.Button || child.isClickable()) {
                if (child.getId() == currentButtonId) {
                    child.setClickable(true);
                    child.setEnabled(true);
                    child.setAlpha(1.0f);
                } else {
                    child.setClickable(false);
                    child.setEnabled(false);
                    child.setAlpha(0.5f);
                }
            }
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(android.view.MenuItem item) {
        int id = item.getItemId();

        // 1. Открываем окно со списком плат
        if (id == R.id.action_select_device) {
            android.util.Log.d(TAG, "Тулбар: открываем выбор стенда.");
            android.content.Intent intent = new android.content.Intent(MainActivity.this, SelectDeviceActivity.class);
            startActivity(intent);
            return true;
        }

        // 2. Быстрое добавление платы прямо с главного экрана
        if (id == R.id.action_add_device) {
            android.util.Log.d(TAG, "Тулбар: быстрый запуск добавления устройства.");
            showFastAddDeviceDialog();
            return true;
        }

        // 3. Информационное окно
        if (id == R.id.action_about) {
            showAboutListDialog();
            return true;
        }

        // 4. НОВЫЙ ПУНКТ: Запуск диагностики прямо из Тулбара!
        if (id == R.id.action_ble_diagnostic) {
            android.util.Log.d(TAG, "Тулбар: Запуск диагностики рекламы BLE");

            // Получаем текущий MAC-адрес из синглтона менеджера
            String currentMac = BleRemoteControlManager.getInstance().getCurrentDeviceName();

            // Проверяем адрес на пустоту
            if (currentMac == null || currentMac.isEmpty() || "XX:XX:XX:XX:XX:XX".equals(currentMac)) {
                android.widget.Toast.makeText(getApplicationContext(),
                        "Ошибка: Стенд не выбран или МАС пуст!",
                        android.widget.Toast.LENGTH_LONG).show();
            } else {
                // Передаем адрес в ваш готовый метод диагностики
                runManualAdvertisingDiagnostic(currentMac);
            }
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == ALL_PERMISSIONS_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Log.d(TAG, "BLE Permissions гарантированно получены.");
            } else {
                Log.e(TAG, "Критическая ошибка: Пользователь отказал в BLE правах!");
            }
        }
    }

    private void sendSingleChannelCommand(byte commandHex) {
        handler.removeCallbacks(idleRunnable);
        handler.postDelayed(idleRunnable, APP_IDLE_TIMEOUT_MS);

        long currentTime = System.currentTimeMillis();
        if (currentTime - lastChannelClickTime < CH_DEBOUNCE_DELAY) {
            Log.w(TAG, "CH Click blocked by debounce. Time delta: " + (currentTime - lastChannelClickTime) + "ms");
            return;
        }
        lastChannelClickTime = currentTime;

        manager.set_DisconnectEnable(true);  // Natan

        byte[] payload = new byte[]{(byte) 0xFE, commandHex};
        Log.d(TAG, String.format("Sending SingleShot -> Address: 0x%02X, Command: 0x%02X", payload[0], payload[1]));
        BleRemoteControlManager.getInstance().sendSingleShotCommand(payload);
    }

    private void sendServiceCommand(byte serviceCommandHex) {
        if (isBleBusy) {
            Log.e(TAG, "Отправка сервисного пакета не прошла");
            return;
        }
        byte[] servicePayload = new byte[]{SERV_ADDRESS, serviceCommandHex};
        Log.d(TAG, String.format("Отправка сервисного пакета -> Address: 0x%02X, Command: 0x%02X",
                                                             servicePayload[0], servicePayload[1]));
        manager.set_DisconnectEnable(true);
        if (serviceCommandHex == SERV_CMD_WAKE) {
            manager.set_Connected(false);
            manager.FastAdvMode = (true);
        } else { // serviceCommandHex = SERV_CMD_SLEEP
            manager.FastAdvMode = (false);
        }

        isBleBusy = true;
        setAllButtonsEnabled(false);
        BleRemoteControlManager.getInstance().sendSingleShotCommand(servicePayload); //

        // Разблокируем интерфейс смартфона через стандартную (400 мс)
        handler.postDelayed(new Runnable() { //
            @Override
            public void run() {
                setAllButtonsEnabled(true); //
                isBleBusy = false; //
            }
        }, 400); //
    } // <-- ЗАКРЫВАЕТСЯ МЕТОД sendServiceCommand

    /**
     * Открытие кастомного диалога набора номера канала
     */
    private void openChannelInputDialog() { //
        // ЖЕСТКИЙ ЗАМЕНЯЕМЫЙ ЗАМОК: Если транспорт занят - игнорируем команду
        if (isBleBusy) { //
            Log.w(TAG, "[UI-LOCK] Отмена открытия диалога: BLE-транспорт занят."); //
            return; //
        }

        Dialog dialog = new Dialog(this); //
        dialog.setContentView(R.layout.dialog_channel_pad); //
        dialog.setCancelable(false); //
        TextView tvDisplay = dialog.findViewById(R.id.tv_channel_display); //
        StringBuilder inputBuffer = new StringBuilder(); //

        // Инициализация SharedPreferences и загрузка кэша
        android.content.SharedPreferences prefs = getSharedPreferences("RemoteControlPrefs", MODE_PRIVATE); //
        String lastChannel = prefs.getString("last_successful_channel", ""); //
        if (!lastChannel.isEmpty()) { //
            inputBuffer.append(lastChannel); //
            tvDisplay.setText(lastChannel); //
        }

        // ОБНОВЛЕННЫЙ СЛУШАТЕЛЬ: Схлопывание нулей на лету на экране
        View.OnClickListener digitClickListener = v -> { //
            String digit = v.getTag().toString(); //

            // КРАЙНИЙ СЛУЧАЙ 1: Если на экране уже горит одиночный "0"
            if (inputBuffer.length() == 1 && inputBuffer.charAt(0) == '0') { //
                if (digit.equals("0")) { //
                    return; //
                } else {
                    inputBuffer.setLength(0); //
                }
            }

            // КРАЙНИЙ СЛУЧАЙ 2: Ограничение на максимальную длину (например, 3 символа)
            if (inputBuffer.length() < 3) { //
                inputBuffer.append(digit); //
                tvDisplay.setText(inputBuffer.toString()); //
            }
        }; //

        int[] digitButtonIds = { //
                R.id.btn_pad_0, R.id.btn_pad_1, R.id.btn_pad_2, R.id.btn_pad_3, //
                R.id.btn_pad_4, R.id.btn_pad_5, R.id.btn_pad_6, R.id.btn_pad_7, //
                R.id.btn_pad_8, R.id.btn_pad_9 //
        }; //

        for (int id : digitButtonIds) { //
            dialog.findViewById(id).setOnClickListener(digitClickListener); //
        }

        dialog.findViewById(R.id.btn_pad_clear).setOnClickListener(v -> { //
            inputBuffer.setLength(0); //
            tvDisplay.setText(""); //
        }); //

        dialog.findViewById(R.id.btn_pad_cancel).setOnClickListener(v -> dialog.dismiss()); //

        dialog.findViewById(R.id.btn_pad_send).setOnClickListener(v -> { //
            if (inputBuffer.length() > 0) { //
                String channelToSend = inputBuffer.toString(); //
                // Сохраняем в память чистый результат
                prefs.edit().putString("last_successful_channel", channelToSend).apply();

                // Гасим 15-секундный сторожевой таймер громкости
                handler.removeCallbacks(volumeWatchdogTimerRunnable);

                // Переводим воркер громкости в холодный старт, так как сокет закроется после очереди каналов
                if (volumeWorker != null) {
                    volumeWorker.forceColdStart();
                }

                Group_Type = ChannelDialog_Type;
                // Перезапустить таймер перевода рекламы платы на 2сек
                handler.removeCallbacks(idleRunnable);
                handler.postDelayed(idleRunnable, APP_IDLE_TIMEOUT_MS);
                manager.runAdvertisingDiagnosticFlag = true;  //  in BoardSleepMode only ???
                executeSequentialTransmission(channelToSend);
                dialog.dismiss(); //
            } else {
                isBleBusy = false;  // Natan
            }
        }); //

        dialog.show(); //
    } // <-- ЗАКРЫВАЕТСЯ МЕТОД openChannelInputDialog

    /**
     * Очищает строку номера канала от ведущих нулей (Edge Case).
     */
    private String cleanLeadingZeros(String input) { //
        if (input == null) return "0"; //
        String trimmed = input.trim(); //
        if (trimmed.isEmpty()) return "0"; //
        return trimmed.replaceFirst("^0+(?!$)", ""); //
    } // <-- ЗАКРЫВАЕТСЯ МЕТОД cleanLeadingZeros

    private void writeButtonUp(View v, byte cmdCode) {
        RestartTimers(); // Рестарт таймеры 15сек и 2мин
        // Пишем в FIFO: Тип 2 (СТОП), "Именной билет" кнопки, Точное время отрыва пальца
        Log.d( TAG, "[UI-ТРИГГЕР] Запись в FiFo освобождение кнопки выполнена.");
        volumeQueue.putEvent(ButtonEventQueue.TYPE_RELEASE, cmdCode, android.os.SystemClock.elapsedRealtime());
        v.setPressed(false);
        isVolumeButtonPressed = false;
        setAllButtonsEnabled(true);
        //   isBleBusy = false;
    }

    private final Runnable volumeWatchdogTimerRunnable = new Runnable() {
        @Override
        public void run() {
            if (isVolumeButtonPressed) {
                Log.w("volumeWatchdogTimer", "Максимальное время нажатия кнопки больше чем 108мс х 100 = 10.8сек");
                volumeQueue.putEvent(ButtonEventQueue.TYPE_RELEASE, cmdCode, android.os.SystemClock.elapsedRealtime());
                isVolumeButtonPressed = false;
                setAllButtonsEnabled(true);
                VolumeLock = 0;
            } else {
                Log.w(TAG, "[VOLUME_IDLE] 15 секунд бездействия после последней пары пакетов. Автоматическое закрытие канала.");
            }
            // Сбрасываем тип группы
            Group_Type = 0;

            // 1. Сбрасываем память планировщика воркера, чтобы он не стрелял в null GATT
            if (volumeWorker != null) {
                volumeWorker.forceColdStart();
            }

            // 2. Очищаем FIFO-очередь от случайного мусора перед дисконнектом
            if (volumeQueue != null) {
                volumeQueue.clearQueue();
            }

            manager.runAdvertisingDiagnosticFlag = true;

            // 3. Активируем дисконнект на BLE-менеджере
            if (manager != null) {
                manager.DisconnectAndClose();
                bond_status.setText("Статус: Активен (100мс)");
                bond_status.setBackgroundColor(ContextCompat.
                                     getColor(MainActivity.this, R.color.blue_light_color));
            }
        }

    };

    public void restartDisconnectionTimer(Runnable volumeWatchdogTimerRunnable) {
        handler.removeCallbacks(volumeWatchdogTimerRunnable); //
        // Заводим его заново ровно на 15 секунд вперед от этого момента
        Log.d(TAG, "[VOLUME_IDLE] Канал активен. Таймер отключения перенесен на "
                + VOLUME_WATCHDOG_TIMEOUT_MS / 1000 + "сек вперед.");
        handler.postDelayed(volumeWatchdogTimerRunnable, VOLUME_WATCHDOG_TIMEOUT_MS);
    }
    private void showFastAddDeviceDialog() {
        androidx.appcompat.app.AlertDialog.Builder builder = new androidx.appcompat.app.AlertDialog.Builder(this);
        builder.setTitle("Быстрое добавление платы");

        android.widget.LinearLayout layout = new android.widget.LinearLayout(this);
        layout.setOrientation(android.widget.LinearLayout.VERTICAL);
        layout.setPadding(60, 40, 60, 20);

        final android.widget.EditText etName = new android.widget.EditText(this);
        etName.setHint("Имя (например, CC2340R5_Fast)");
        layout.addView(etName);

        final android.widget.EditText etMac = new android.widget.EditText(this);
        etMac.setHint("MAC-адрес (XX:XX:XX:XX:XX:XX)");
        layout.addView(etMac);

        builder.setView(layout);
        builder.setPositiveButton("Сохранить", null); // Перехватываем управление в OnShowListener
        builder.setNegativeButton("Отмена", (dialog, which) -> dialog.dismiss());

        androidx.appcompat.app.AlertDialog mainDialog = builder.create();

        mainDialog.setOnShowListener(dialogInterface -> {
            android.widget.Button buttonSave = mainDialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE);
            buttonSave.setOnClickListener(view -> {
                final String name = etName.getText().toString().trim();
                String rawMac = etMac.getText().toString();

                if (name.isEmpty() || rawMac.trim().isEmpty()) {
                    android.widget.Toast.makeText(MainActivity.this, "Поля не могут быть пустыми!", android.widget.Toast.LENGTH_SHORT).show();
                    return;
                }

                // 1. Вызываем централизованный валидатор
                final String formattedMac = DeviceStorageUtil.validateAndFormatMac(rawMac);
                if (formattedMac == null) {
                    android.widget.Toast.makeText(MainActivity.this, "Неверный формат MAC!", android.widget.Toast.LENGTH_LONG).show();
                    return;
                }

                // 2. Диалог верификации (вызываем строго через контекст mainDialog, фиксируя капкан сквозных кликов)
                new androidx.appcompat.app.AlertDialog.Builder(mainDialog.getContext())
                        .setTitle("Верификация данных")
                        .setMessage("Записать плату со следующими параметрами?\n\n" +
                                "Имя: " + name + "\n" +
                                "MAC: " + formattedMac)
                        .setPositiveButton("Записать в базу", (confirmDialog, whichConfirm) -> {
                            // Загружаем актуальную базу, добавляем элемент и сохраняем через синглтон-утиль
                            java.util.ArrayList<DeviceStorageUtil.LabBoard> list = DeviceStorageUtil.loadDevices(MainActivity.this);
                            list.add(new DeviceStorageUtil.LabBoard(String.valueOf(System.currentTimeMillis()), name, formattedMac));
                            DeviceStorageUtil.saveDevices(MainActivity.this, list);

                            android.widget.Toast.makeText(MainActivity.this, "Плата '" + name + "' успешно добавлена!", android.widget.Toast.LENGTH_SHORT).show();
                            mainDialog.dismiss();
                        })
                        .setNegativeButton("Исправить / Назад", (confirmDialog, whichConfirm) -> {
                            confirmDialog.dismiss();
                            etMac.requestFocus();
                            etMac.selectAll(); // Выделение под режим трекпада клавиатуры

                            android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                            if (imm != null) {
                                imm.showSoftInput(etMac, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
                            }
                        })
                        .setCancelable(false)
                        .show();
            });

        });

        mainDialog.show();
    }
    public void RestartTimers() {
        handler.removeCallbacks(idleRunnable);
        handler.postDelayed(idleRunnable, APP_IDLE_TIMEOUT_MS); // Перезапустить Таймер простоя смартфона (2мин)
        restartDisconnectionTimer(volumeWatchdogTimerRunnable); // Перезапустить Таймер disconnect (15сек)
    }
    public void RestartIdleTimer() {
        if (handler != null && idleRunnable != null) {
            handler.removeCallbacks(idleRunnable);
            handler.postDelayed(idleRunnable, APP_IDLE_TIMEOUT_MS); // Перезапустить Таймер простоя смартфона (2мин)\
            Log.d("TIMER_SYNC", "Активность зафиксирована. " +
                    "Таймер простоя смартфона перезапущен на " + APP_IDLE_TIMEOUT_MS / 1000 + "сек");
        }
    }
public void runManualAdvertisingDiagnostic(String macAddress) {
    //   MainActivity.isBleBusy = false; // ???? Возвращаем свободу интерфейсу пульта
    if (isBleBusy) {
        android.widget.Toast.makeText(this, "BLE транспорт занят, подождите...", android.widget.Toast.LENGTH_SHORT).show();
        Log.d("AdvertisingDiagnostic", "BLE транспорт занят");
        return;
    }

    isBleBusy = true;
    setAllButtonsEnabled(false);
    if (bond_status != null) {
       bond_status.setText("Диагностика эфира...");
       bond_status.setBackgroundColor(ContextCompat.
                                         getColor(MainActivity.this, R.color.orange_color));
    }

    //    android.widget.Toast.makeText(this, "Запуск экспресс-теста эфира (7 сек)...",
    //                                                     android.widget.Toast.LENGTH_SHORT).show();
    // Получаем текущий MAC-адрес платы из синглтона BLE-менеджера
    String currentMac = BleRemoteControlManager.getInstance().getCurrentDeviceName();
    // Просто создаем класс детектора и запускаем его контракт
    BleIntervalDetector detector = new BleIntervalDetector();
    detector.setMacAddress(currentMac);
    detector.startDetection(new BleIntervalDetector.DetectionListener() {
        // detector.startDetection(macAddress,BleIntervalDetector.DetectionListener)   {
        @Override
        public void onFastAdvertisingDetected() {
            runOnUiThread(() -> {
    //            android.widget.Toast.makeText(MainActivity.this,
    //                    "Плата АКТИВНА (Быстрый режим: ~100мс)", android.widget.Toast.LENGTH_LONG).show();
                if (bond_status != null) {
                    bond_status.setText("Статус: Активен (100мс)");
                    bond_status.setBackgroundColor(ContextCompat.
                            getColor(MainActivity.this, R.color.blue_light_color));
                }
                BleRemoteControlManager.getInstance().FastAdvMode = true;
                BleRemoteControlManager.getInstance().isBoardSleeping = false;
                BoardSleepMode = false;
                isBleBusy = false;
                setAllButtonsEnabled(true);
            });
        }

        @Override
        public void onSlowAdvertisingDetected() {
            runOnUiThread(() -> {
    //            android.widget.Toast.makeText(
    //                    MainActivity.this, "Плата в режиме СНА (Энергосбережение: ~2сек)",
    //                    android.widget.Toast.LENGTH_LONG).show();
                if (bond_status != null) {
                    bond_status.setText("Статус: Сон (2 сек)");
                    bond_status.setBackgroundColor(ContextCompat.
                            getColor(MainActivity.this, R.color.colorDeepPurple));
                }
                BleRemoteControlManager.getInstance().FastAdvMode = false;
                BleRemoteControlManager.getInstance().isBoardSleeping = true;
                BoardSleepMode = true;
                isBleBusy = false;
                setAllButtonsEnabled(true);
            });
        }

        @Override
        public void onDeviceNotFound() {
            runOnUiThread(() -> {
//                android.widget.Toast.makeText(
//                        MainActivity.this, "Устройство НЕ НАЙДЕНО в эфире!",
//                        android.widget.Toast.LENGTH_LONG).show();
                if (bond_status != null) {
                    bond_status.setText("Статус: Вне зоны / Выключен");
                    bond_status.setBackgroundColor(ContextCompat.
                            getColor(MainActivity.this, R.color.holo_orange_light));
                }
                isBleBusy = false;
                setAllButtonsEnabled(true);
            });
        }
    });
}
} // <-- САМАЯ ПОСЛЕДНЯЯ ФИГУРНАЯ СКОБКА, КОТОРАЯ ЗАКРЫВАЕТ КЛАСС MainActivity!
