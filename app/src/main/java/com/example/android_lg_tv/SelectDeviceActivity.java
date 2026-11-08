package com.example.android_lg_tv;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
//import android.view.View;
import android.view.inputmethod.InputMethodManager;
//import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import java.util.ArrayList;

public class SelectDeviceActivity extends AppCompatActivity {

    public static final String PREFS_NAME = "BleDevicePrefs";
    public static final String KEY_MAC = "selected_mac_address";
    public static final String KEY_NAME = "selected_device_name";

    // Использование сквозной переиспользуемой модели из утиль-класса
    private ArrayList<DeviceStorageUtil.LabBoard> boardList;
    private ListView lvDevices;
    private int currentSelectedIndex = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_select_device);

        lvDevices = findViewById(R.id.lvDevices);

        // Загрузка через единую точку доступа
        boardList = DeviceStorageUtil.loadDevices(this);

        ArrayAdapter<DeviceStorageUtil.LabBoard> adapter = new ArrayAdapter<>(
                this,
                R.layout.item_device,
                R.id.tvDeviceName,
                boardList
        );
        lvDevices.setAdapter(adapter);

        syncVisualFocus();

        // --- ЛОГИКА КЛИКА ПО СТРОКЕ (SELECT DEVICE) ---
        lvDevices.setOnItemClickListener((parent, view, position, id) -> {
            currentSelectedIndex = position;
            Log.d("SelectDevice", "Инженер выбрал в списке: " + boardList.get(position).name);
        });

        // --- ЛОГИКА ДОЛГОГО КЛИКА (РЕДАКТИРОВАНИЕ / УДАЛЕНИЕ) ---
        lvDevices.setOnItemLongClickListener((parent, view, position, id) -> {
            DeviceStorageUtil.LabBoard selectedBoard = boardList.get(position);

            new AlertDialog.Builder(SelectDeviceActivity.this)
                    .setTitle("Управление платой")
                    .setItems(new String[]{"Редактировать устройство", "Удалить устройство"}, (dialog, which) -> {
                        if (which == 0) {
                            showDeviceDialog(selectedBoard);
                        } else {
                            if (boardList.size() <= 1) {
                                Toast.makeText(SelectDeviceActivity.this, "Запрещено удалять последнюю плату!", Toast.LENGTH_SHORT).show();
                                return;
                            }
                            boardList.remove(position);
                            DeviceStorageUtil.saveDevices(SelectDeviceActivity.this, boardList);
                            ((ArrayAdapter<?>) lvDevices.getAdapter()).notifyDataSetChanged();
                            syncVisualFocus();
                            Toast.makeText(SelectDeviceActivity.this, "Устройство удалено", Toast.LENGTH_SHORT).show();
                        }
                    })
                    .show();
            return true;
        });
    }

    // Восстановление фокуса активной BLE-сессии
    private void syncVisualFocus() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String savedMac = prefs.getString(KEY_MAC, null);

        currentSelectedIndex = 0;
        if (savedMac != null) {
            for (int i = 0; i < boardList.size(); i++) {
                if (boardList.get(i).mac.equalsIgnoreCase(savedMac)) {
                    currentSelectedIndex = i;
                    break;
                }
            }
        }
        lvDevices.setSelection(currentSelectedIndex);
        lvDevices.setItemChecked(currentSelectedIndex, true);
    }

    // Монолитный диалог с автоформатированием, верификацией и режимом трекпада
    private void showDeviceDialog(final DeviceStorageUtil.LabBoard boardToEdit) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(boardToEdit == null ? "Добавить устройство" : "Редактировать устройство");

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(60, 40, 60, 20);

        final EditText etName = new EditText(this);
        etName.setHint("Имя (например, CC2340R5_Lab)");
        if (boardToEdit != null) etName.setText(boardToEdit.name);
        layout.addView(etName);

        final EditText etMac = new EditText(this);
        etMac.setHint("MAC-адрес (XX:XX:XX:XX:XX:XX)");
        if (boardToEdit != null) etMac.setText(boardToEdit.mac);
        layout.addView(etMac);

        builder.setView(layout);
        builder.setPositiveButton("Сохранить", null); // Перехват OnShowListener
        builder.setNegativeButton("Отмена", (dialog, which) -> dialog.dismiss());
        builder.setCancelable(false); // Защита от тапов мимо экрана

        AlertDialog mainDialog = builder.create();

        mainDialog.setOnShowListener(dialogInterface -> {
            android.widget.Button buttonSave = mainDialog.getButton(AlertDialog.BUTTON_POSITIVE);
            buttonSave.setOnClickListener(view -> {
                final String name = etName.getText().toString().trim();
                String rawMac = etMac.getText().toString();

                if (name.isEmpty() || rawMac.trim().isEmpty()) {
                    Toast.makeText(SelectDeviceActivity.this, "Поля ввода не могут быть пустыми!", Toast.LENGTH_SHORT).show();
                    return;
                }

                // Централизованная «всеядная» очистка и валидация
                final String formattedMac = DeviceStorageUtil.validateAndFormatMac(rawMac);
                if (formattedMac == null) {
                    Toast.makeText(SelectDeviceActivity.this, "Неверный формат MAC!", Toast.LENGTH_LONG).show();
                    return;
                }

                // ВТОРОЙ КОНТУР: Вызов верификации строго через локальный контекст mainDialog.getContext()
                new AlertDialog.Builder(mainDialog.getContext())
                        .setTitle("Подтверждение изменений")
                        .setMessage("Сохранить плату со следующими параметрами?\n\n" +
                                "Имя: " + name + "\n" +
                                "MAC: " + formattedMac)
                        .setPositiveButton("Да, сохранить", (confirmDialog, whichConfirm) -> {
                            if (boardToEdit == null) {
                                // Режим ADD
                                boardList.add(new DeviceStorageUtil.LabBoard(String.valueOf(System.currentTimeMillis()), name, formattedMac));
                            } else {
                                // Режим EDIT
                                for (DeviceStorageUtil.LabBoard b : boardList) {
                                    if (b.id.equals(boardToEdit.id)) {
                                        b.name = name;
                                        b.mac = formattedMac;
                                        break;
                                    }
                                }
                            }

                            // Сохранение изменений в JSON
                            DeviceStorageUtil.saveDevices(SelectDeviceActivity.this, boardList);
                            ((ArrayAdapter<?>) lvDevices.getAdapter()).notifyDataSetChanged();
                            syncVisualFocus();
                            mainDialog.dismiss(); // Закрываем основное окно только здесь
                        })
                        .setNegativeButton("Исправить / Назад", (confirmDialog, whichConfirm) -> {
                            confirmDialog.dismiss();
                            etMac.requestFocus();
                            etMac.selectAll(); // Выделение под режим трекпада (пробел)

                            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                            if (imm != null) {
                                imm.showSoftInput(etMac, InputMethodManager.SHOW_IMPLICIT);
                            }
                        })
                        .setCancelable(false)
                        .show();
            });
        });

        mainDialog.show();
    }

    // Сохранение сессии при выходе и бесшовный перезапуск GATT-транспорта
    @Override
    protected void onPause() {
        super.onPause();
        if (boardList != null && currentSelectedIndex >= 0 && currentSelectedIndex < boardList.size()) {
            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            String oldMac = prefs.getString(KEY_MAC, "");

            DeviceStorageUtil.LabBoard finalSelectedBoard = boardList.get(currentSelectedIndex);

            if (!finalSelectedBoard.mac.equalsIgnoreCase(oldMac)) {
                SharedPreferences.Editor editor = prefs.edit();
                editor.putString(KEY_MAC, finalSelectedBoard.mac);
                editor.putString(KEY_NAME, finalSelectedBoard.name);
                editor.apply();

                Log.d("SelectDevice", "onPause: Смена платы на: " + finalSelectedBoard.name);
                BleRemoteControlManager.getInstance().setTargetMacAddress(finalSelectedBoard.mac);
            }
        }
    }
}
