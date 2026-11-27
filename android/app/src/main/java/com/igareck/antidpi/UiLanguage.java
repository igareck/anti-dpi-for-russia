/* Copyright (C) 2026 Igor Krauch. SPDX-License-Identifier: GPL-3.0-or-later */
package com.igareck.antidpi;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** The UI language is independent of both Android's language and the packet worker. */
final class UiLanguage {
    private static final String PREFS = "com.igareck.antidpi.MainActivity";
    private static final String ENGLISH = "english_ui";
    private static volatile Words englishWords;

    static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static boolean english(Context context) {
        return preferences(context).getBoolean(ENGLISH, false);
    }

    static void toggle(Context context) {
        preferences(context).edit().putBoolean(ENGLISH, !english(context)).apply();
    }

    static String choose(Context context, String russian, String english) {
        return english(context) ? english : russian;
    }

    static String text(Context context, String russian) {
        if (russian == null || !english(context) || russian.chars().noneMatch(c -> c >= 'Ѐ' && c <= 'ӿ')) {
            return russian;
        }
        Words words = loadEnglish(context);
        String[] lines = russian.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String translated = words.exact.get(lines[i]);
            if (translated == null) {
                translated = lines[i];
                for (Map.Entry<String,String> phrase : words.fragments.entrySet()) {
                    translated = translated.replace(phrase.getKey(), phrase.getValue());
                }
            }
            lines[i] = translated;
        }
        return String.join("\n", lines);
    }

    private static synchronized Words loadEnglish(Context context) {
        if (englishWords != null) return englishWords;
        Words words = new Words();
        try (BufferedReader input = new BufferedReader(new InputStreamReader(
                context.getAssets().open("ui-en.tsv"), StandardCharsets.UTF_8))) {
            for (String row; (row = input.readLine()) != null; ) {
                if (row.isEmpty() || row.startsWith("#")) continue;
                String[] fields = row.split("\t", 3);
                Map<String,String> entries = fields[0].equals("phrase") ? words.fragments : words.exact;
                entries.put(fields[1], fields[2]);
            }
        } catch (IOException e) {
            AppLog.error("English UI strings", e);
        }
        englishWords = words;
        return englishWords;
    }

    private static final class Words {
        final Map<String,String> exact = new LinkedHashMap<>();
        final Map<String,String> fragments = new LinkedHashMap<>();
    }

    private UiLanguage() {}
}
