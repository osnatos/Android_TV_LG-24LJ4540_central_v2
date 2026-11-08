package com.example.android_lg_tv;

import android.content.Context;
import android.content.SharedPreferences;
import android.widget.EditText;

public class ChannelCache {
    private static final String PREF_NAME = "RemoteControlPrefs";
    private static final String KEY_LAST_CHANNEL = "last_successful_channel";

    /**
     * Сохраняет успешно отправленный канал в память телефона
     */
    public static void save(Context context, String channel) {
        if (context == null || channel == null) return;

        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        prefs.edit().putString(KEY_LAST_CHANNEL, channel.trim()).apply();
    }

    /**
     * Читает последний канал и подставляет его в EditText с курсором в конце
     */
    public static void loadAndSet(Context context, EditText channelEditText) {
        if (context == null || channelEditText == null) return;

        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        String lastChannel = prefs.getString(KEY_LAST_CHANNEL, "");

        if (!lastChannel.isEmpty()) {
            channelEditText.setText(lastChannel);
            // Переносим курсор в конец строки, чтобы пользователю было удобно стирать
            channelEditText.setSelection(lastChannel.length());
        }
    }
}

