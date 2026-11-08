package com.example.android_lg_tv;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;

public class DeviceStorageUtil {
    private static final String PREFS_NAME = "BleDevicePrefs";
    private static final String KEY_DEVICE_LIST = "all_registered_devices";

    // Единая модель данных для всего проекта
    public static class LabBoard {
        public final String id;
        public String name;
        public String mac;

        public LabBoard(String id, String name, String mac) {
            this.id = id;
            this.name = name;
            this.mac = mac;
        }

        @Override
        public String toString() {
            return name + " [" + mac + "]";
        }
    }

    // Очистка и жесткая проверка MAC-адреса (возвращает null, если адрес невалиден)
    public static String validateAndFormatMac(String rawMac) {
        if (rawMac == null) return null;
        String cleanMac = rawMac.trim().toUpperCase().replace(":", "").replace("-", "");

        if (!cleanMac.matches("^[0-9A-F]{12}$")) {
            return null; // Сигнал ошибки валидации
        }

        // Автоформатирование: сборка идеального MAC-адреса
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 12; i += 2) {
            sb.append(cleanMac.substring(i, i + 2));
            if (i < 10) sb.append(":");
        }
        return sb.toString();
    }

    // Централизованная загрузка списка устройств из JSON
    public static ArrayList<LabBoard> loadDevices(Context context) {
        ArrayList<LabBoard> list = new ArrayList<>();
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String jsonString = prefs.getString(KEY_DEVICE_LIST, null);

        if (jsonString == null || jsonString.isEmpty()) {
            // Восстановление оригинальной конфигурации стендов в одной точке
            list.add(new LabBoard("d1", "CC2340R5_Dev_Kit", "84:72:93:3C:5A:08"));
            list.add(new LabBoard("d2", "CC2340R5_with_SMT_LED", "84:72:93:F3:A8:14"));
            list.add(new LabBoard("d3", "CC2340R5_with_TH_LED", "84:72:93:F3:A8:2F"));
            list.add(new LabBoard("d4", "CC2340R5_BredBoard", "84:72:93:F3:AA:75"));
            saveDevices(context, list);
        } else {
            try {
                JSONArray jsonArray = new JSONArray(jsonString);
                for (int i = 0; i < jsonArray.length(); i++) {
                    JSONObject obj = jsonArray.getJSONObject(i);
                    list.add(new LabBoard(
                            obj.getString("id"),
                            obj.getString("name"),
                            obj.getString("mac")
                    ));
                }
            } catch (JSONException e) {
                Log.e("DeviceStorageUtil", "Ошибка парсинга JSON", e);
            }
        }
        return list;
    }

    // Централизованное сохранение списка устройств в JSON
    public static void saveDevices(Context context, ArrayList<LabBoard> list) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        JSONArray jsonArray = new JSONArray();
        try {
            for (LabBoard board : list) {
                JSONObject obj = new JSONObject();
                obj.put("id", board.id);
                obj.put("name", board.name);
                obj.put("mac", board.mac);
                jsonArray.put(obj);
            }
            prefs.edit().putString(KEY_DEVICE_LIST, jsonArray.toString()).apply();
        } catch (JSONException e) {
            Log.e("DeviceStorageUtil", "Ошибка сборки JSON", e);
        }
    }
}
