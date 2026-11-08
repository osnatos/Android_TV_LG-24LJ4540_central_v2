package com.example.android_lg_tv;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import java.util.ArrayList;
import java.util.List;

public class PermissionHelper {

    public static final int BLE_PERMISSION_REQUEST_CODE = 101;

    /**
     * Проверяет и при необходимости запрашивает разрешения для BLE.
     * @return true, если все разрешения уже выданы, false — если запущен запрос к пользователю.
     */
    public static boolean checkAndRequestBlePermissions(Activity activity) {
        // На старых Android (ниже 6.0 / API 23) Runtime-разрешения не нужны
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true;
        }

        List<String> permissionsNeeded = new ArrayList<String>();

        // Сценарий А: Android 12 и выше (API 31+) — нужны новые BLUETOOTH_SCAN и CONNECT
        if (Build.VERSION.SDK_INT >= 31) { // Build.VERSION_CODES.S
            if (ContextCompat.checkSelfPermission(activity, "android.permission.BLUETOOTH_SCAN") != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add("android.permission.BLUETOOTH_SCAN");
            }
            if (ContextCompat.checkSelfPermission(activity, "android.permission.BLUETOOTH_CONNECT") != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add("android.permission.BLUETOOTH_CONNECT");
            }
        }
        // Сценарий Б: Android от 6.0 до 11 — обязательно нужно разрешение на Местоположение (Location)
        else {
            if (ContextCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.ACCESS_FINE_LOCATION);
            }
        }

        // Если есть невыданные разрешения — запрашиваем их у пользователя
        if (!permissionsNeeded.isEmpty()) {
            // Конвертируем List в массив String (классический синтаксис Java 7)
            String[] permissionsArray = permissionsNeeded.toArray(new String[permissionsNeeded.size()]);
            ActivityCompat.requestPermissions(activity, permissionsArray, BLE_PERMISSION_REQUEST_CODE);
            return false; // Разрешения еще не получены, ждем ответа пользователя
        }

        return true; // Все разрешения уже успешно выданы!
    }
}
