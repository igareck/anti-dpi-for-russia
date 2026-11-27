/* Copyright (C) 2026 Igor Krauch. SPDX-License-Identifier: GPL-3.0-or-later */
package com.igareck.antidpi;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public final class MainActivity extends Activity {
    private static final int ACTIVATION = 41;
    private static final String[] GROUP_KEYS = {"GOLDEN","SILVER","FAVORITES","ALL"};
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ScreenUi ui = new ScreenUi(this);
    private final Set<String> favorites = new HashSet<>();
    private List<Profiles.Profile> choices, visible = Collections.emptyList();
    private DashboardView dashboard;
    private SharedPreferences prefs;
    private Profiles.Profile selected;
    private String selectedId, port, query, quic, discord;
    private int group;
    private boolean pendingStart, udp, discordEnabled;
    private long startRequestedAt = -2000;
    private String lastUiState = "";
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            show(false);
            handler.postDelayed(this, 600);
        }
    };

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        ui.applyTheme();
        PacketBridge.reattachSaved();
        try {
            choices = Profiles.all(this);
        } catch (Exception e) {
            AppLog.error("Load profiles", e);
            showError(e.getMessage());
            return;
        }
        prefs = ScreenUi.preferences(this);
        loadSelection();
        port = saved == null ? prefs.getString("port", "1080") : saved.getString("port", "1080");
        query = saved == null ? "" : saved.getString("search", "");
        pendingStart = saved != null && saved.getBoolean("pending_start");
        udp = prefs.getBoolean("udp_enabled", false);
        discordEnabled = ProxyOptions.discordEnabled(udp, prefs.getBoolean("discord_enabled", false));
        quic = prefs.getString("quic_profile", "Q1");
        if (!quic.matches("Q[1-5]")) quic = "Q1";
        discord = prefs.getString("discord_profile", "D1");
        if (!discord.matches("D[1-8]")) discord = "D1";
        filterProfiles();
        try {
            dashboard = new DashboardView(this, this::dispatch);
            setContentView(dashboard);
            dashboard.requestApplyInsets();
        } catch (RuntimeException e) {
            AppLog.error("Dashboard initialization", e);
            dashboardUnavailable();
        }
    }

    void dashboardUnavailable() {
        showError(UiLanguage.choose(this, "Не удалось открыть главное меню. Проверьте Android System WebView.",
            "Could not open the main screen. Check Android System WebView."));
    }

    private void showError(String message) {
        new AlertDialog.Builder(this).setMessage(ui.en(message))
            .setPositiveButton(ui.en("Закрыть"), (dialog, which) -> finish()).show();
    }

    private void loadSelection() {
        ProxyOptions.migrate(prefs);
        selectedId = TcpIds.canonical(prefs.getString("profile", "T20"));
        Set<String> valid = new HashSet<>();
        for (Profiles.Profile profile : choices) valid.add(profile.id);
        if (!valid.contains(selectedId)) selectedId = "T20";
        for (String old : prefs.getStringSet("favorites", Collections.emptySet())) {
            String id = TcpIds.canonical(old);
            if (valid.contains(id)) favorites.add(id);
        }
        int defaultGroup = Integer.parseInt(selectedId.substring(1)) <= 45 ? 0 : 1;
        group = prefs.getInt("catalogue_group", defaultGroup);
        if (prefs.getInt("settings_schema",1) < 6) {
            group = group == 4 ? 2 : group == 5 ? 3 : group >= 0 && group <= 1 ? group : defaultGroup;
        }
        group = Math.max(0, Math.min(GROUP_KEYS.length - 1, group));
        prefs.edit().putString("profile", selectedId).putStringSet("favorites", new HashSet<>(favorites))
            .putInt("catalogue_group", group).putInt("settings_schema", 6).apply();
    }

    private void dispatch(String data) {
        if (data == null || data.length() > 1024 || isFinishing()) return;
        try {
            JSONObject message = new JSONObject(data);
            switch (message.optString("action")) {
                case "ready": show(true); return;
                case "dropdown":
                    if (dashboard != null) dashboard.dropdownChanged(message.optBoolean("open"));
                    return;
                case "github": ui.openUrl("https://github.com/igareck/anti-dpi-for-russia"); return;
                case "vpn-configs": ui.openUrl("https://github.com/igareck/vpn-configs-for-russia"); return;
                case "telegram": ui.openUrl("https://t.me/igareq"); return;
                case "websites": open(WebsiteActivity.class); return;
                case "instructions": open(InstructionsActivity.class); return;
                case "logs": open(LogsActivity.class); return;
                case "module": openActivation(false); return;
                case "start":
                    dashboard.pressFeedback();
                    startProxy();
                    break;
                case "stop":
                    if (canStop()) dashboard.pressFeedback();
                    stopProxy();
                    break;
                case "favorite":
                    if (selected != null) {
                        if (!favorites.remove(selected.id)) favorites.add(selected.id);
                        prefs.edit().putStringSet("favorites", new HashSet<>(favorites)).apply();
                        filterProfiles();
                    }
                    show(true);
                    return;
                case "language":
                    UiLanguage.toggle(this);
                    ProxyService.refreshNotificationLanguage();
                    filterProfiles();
                    show(true);
                    return;
                case "edit":
                    show(edit(message));
                    return;
                default: return;
            }
            show(false);
        } catch (JSONException e) {
            AppLog.error("Dashboard command", e);
        }
    }

    private boolean edit(JSONObject message) {
        String value = message.optString("value");
        switch (message.optString("field")) {
            case "port":
                if (value.length() <= 5) port = value;
                break;
            case "search":
                if (value.length() <= 200) {
                    query = value;
                    filterProfiles();
                    return true;
                }
                break;
            case "group":
                int newGroup = message.optInt("value", -1);
                if (newGroup >= 0 && newGroup < GROUP_KEYS.length) {
                    group = newGroup;
                    prefs.edit().putInt("catalogue_group", group).apply();
                    filterProfiles();
                    return true;
                }
                break;
            case "profiles":
                for (Profiles.Profile profile : visible) {
                    if (profile.id.equals(value) && ProfileStatus.unavailable(profile).isEmpty()) {
                        select(profile);
                        AppLog.line("SELECT", "Selected profile=" + selectedId + "; active=" + ProxyService.activeId);
                        break;
                    }
                }
                break;
            case "udp":
                udp = message.optBoolean("value");
                discordEnabled = ProxyOptions.discordEnabled(udp, discordEnabled);
                prefs.edit().putBoolean("udp_enabled", udp).putBoolean("discord_enabled", discordEnabled).apply();
                break;
            case "discordEnabled":
                discordEnabled = ProxyOptions.discordEnabled(udp, message.optBoolean("value"));
                prefs.edit().putBoolean("discord_enabled", discordEnabled).apply();
                break;
            case "quic":
                if (udp && value.matches("Q[1-5]")) {
                    quic = value;
                    prefs.edit().putString("quic_profile", quic).apply();
                }
                break;
            case "discord":
                if (discordEnabled && value.matches("D[1-8]")) {
                    discord = value;
                    prefs.edit().putString("discord_profile", discord).apply();
                }
                break;
            default: break;
        }
        return false;
    }

    private String strategyText(String text) {
        return UiLanguage.english(this) ? StrategyText.english(text) : text;
    }

    private void filterProfiles() {
        visible = UiLanguage.english(this)
            ? ProfileFilter.select(choices, GROUP_KEYS[group], query, favorites, profile -> StrategyText.english(profile.title))
            : ProfileFilter.select(choices, GROUP_KEYS[group], query, favorites);
        selected = null;
        Profiles.Profile fallback = null;
        for (Profiles.Profile profile : visible) {
            if (!ProfileStatus.unavailable(profile).isEmpty()) continue;
            if (profile.id.equals(selectedId)) {
                select(profile);
                return;
            }
            if (fallback == null) fallback = profile;
        }
        if (fallback != null) select(fallback);
    }

    private void select(Profiles.Profile profile) {
        selected = profile;
        if (!profile.id.equals(selectedId)) {
            selectedId = profile.id;
            prefs.edit().putString("profile", selectedId).apply();
        }
    }

    private JSONArray profileOptions() throws JSONException {
        JSONArray options = new JSONArray();
        for (Profiles.Profile profile : visible) {
            String blocked = ProfileStatus.unavailable(profile);
            String title = TcpIds.display(profile.id) + " · " + strategyText(profile.title);
            options.put(option(profile.id, blocked.isEmpty() ? title : title + " · " + ui.en(blocked))
                .put("disabled", !blocked.isEmpty()));
        }
        return options;
    }

    private static JSONObject option(String value, String label) throws JSONException {
        return new JSONObject().put("value", value).put("label", label);
    }

    private JSONArray options(String prefix, String... labels) throws JSONException {
        JSONArray result = new JSONArray();
        for (int i = 0; i < labels.length; i++) {
            String id = prefix.isEmpty() ? Integer.toString(i) : prefix + (i + 1);
            result.put(option(id, strategyText(labels[i])));
        }
        return result;
    }

    private void show(boolean includeOptions) {
        if (dashboard == null) return;
        try {
            String current = ProxyService.state;
            String hint = ProxyService.failure == null ? "" : ProxyService.failure;
            boolean moduleActive = PacketBridge.hasActiveWorker();
            if (moduleActive && ProxyService.ACTIVATION_REQUIRED.equals(current)) {
                current = "Остановлен";
                hint = "";
            }
            boolean active = "Работает".equals(current);
            boolean starting = launching(current);
            boolean busy = TcpStrategyScan.busy() || LogsActivity.isTesting();
            String appearance = active ? "running" : starting ? "starting" :
                "Остановлен".equals(current) ? "stopped" : "Ошибка".equals(current) ? "error" :
                ProxyService.ACTIVATION_REQUIRED.equals(current) ? "activation-required" : "transition";
            String state = ui.en(current);
            if (active) state += " • " + ProxyOptions.appliedLabel(ProxyService.activeId, ProxyService.activeUdp,
                ProxyService.activeQuicId, ProxyService.activeDiscordId).replace(" · ", " • ") + " • 127.0.0.1:" + ProxyService.activePort;
            if (hint.isEmpty() && active && selected != null && selectionChanged()) {
                hint = "Выбор изменён. Нажмите «Применить».";
            }
            JSONObject payload = new JSONObject()
                .put("english", UiLanguage.english(this)).put("dark", ui.isDark()).put("port", port).put("query", query)
                .put("group", Integer.toString(group)).put("profile", selected == null ? "" : selected.id)
                .put("profileAvailable", selected != null).put("favorite", selected != null && favorites.contains(selected.id))
                .put("description", selected == null ? ui.en("Нет совпадений. Измените группу или поиск.") : strategyText(selected.description))
                .put("udp", udp).put("quic", quic).put("discord", discord).put("discordEnabled", discordEnabled)
                .put("status", appearance).put("statusText", state).put("hint", ui.en(hint))
                .put("module", moduleActive).put("moduleStatus", ui.en(EmbeddedAdb.status()))
                .put("canStart", selected != null && !busy && !starting && !"Останавливается".equals(current)
                    && SystemClock.elapsedRealtime() - startRequestedAt > 400)
                .put("canStop", canStop());
            String uiState = payload.toString();
            if (!includeOptions && uiState.equals(lastUiState)) return;
            if (includeOptions) payload.put("groups", options("", "Golden · 45", "Silver · 439", ui.en("Избранные"), ui.en("Все · 484")))
                .put("profiles", profileOptions()).put("quics", options("Q", QuicProfiles.NAMES))
                .put("discords", options("D", DiscordProfiles.NAMES));
            dashboard.publish(payload.toString());
            lastUiState = uiState;
        } catch (JSONException e) {
            AppLog.error("Dashboard state", e);
        }
    }

    private boolean canStop() {
        return !TcpStrategyScan.busy() && (PacketBridge.hasPendingCleanup() || ProxyService.hasRunRequest()
            || ProxyService.running || launching(ProxyService.state) || "Ошибка".equals(ProxyService.state));
    }

    private static boolean launching(String state) {
        return "Запускается".equals(state) || state.startsWith("Восстанавливается");
    }

    private boolean selectionChanged() {
        return !selected.id.equals(ProxyService.activeId)
            || !Integer.toString(ProxyService.activePort).equals(port.trim())
            || udp != ProxyService.activeUdp
            || (udp && !quic.equals(ProxyService.activeQuicId))
            || !(discordEnabled ? discord : "D0").equals(ProxyService.activeDiscordId);
    }

    private void open(Class<? extends Activity> activity) {
        startActivity(new Intent(this, activity));
    }

    private void openActivation(boolean launch) {
        pendingStart = launch;
        startActivityForResult(new Intent(this, ActivationActivity.class), ACTIVATION);
    }

    private void startProxy() {
        if (TcpStrategyScan.busy() || LogsActivity.isTesting() || launching(ProxyService.state)
                || "Останавливается".equals(ProxyService.state) || SystemClock.elapsedRealtime() - startRequestedAt <= 400) return;
        try {
            int chosenPort = AdbBootstrap.port(port, false);
            Profiles.validate(chosenPort);
            if (selected == null) {
                ui.toast("Выберите TCP-стратегию");
                return;
            }
            String blocked = ProfileStatus.unavailable(selected);
            if (!blocked.isEmpty()) {
                ui.toast(blocked);
                return;
            }
            if (!PacketBridge.permitted()) {
                openActivation(true);
                return;
            }
            prefs.edit().putString("port", Integer.toString(chosenPort)).putString("profile", selected.id).apply();
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 20);
            startForegroundService(new Intent(this, ProxyService.class).setAction(ProxyService.START)
                .putExtra("profile", selected.id).putExtra("port", chosenPort).putExtra("verbose", prefs.getBoolean("verbose",false))
                .putExtra("udp", udp).putExtra("quic", quic).putExtra("discord_enabled", discordEnabled)
                .putExtra("discord", discordEnabled ? discord : "D0"));
            startRequestedAt = SystemClock.elapsedRealtime();
        } catch (Exception e) {
            AppLog.error("Start requested", e);
            ui.toast(e.getMessage());
        }
    }

    private void stopProxy() {
        if (!canStop()) return;
        AppLog.line("USER", "Stop requested");
        startService(new Intent(this, ProxyService.class).setAction(ProxyService.STOP));
    }

    @Override public void onBackPressed() {
        if (dashboard == null || !dashboard.dismissDropdown()) super.onBackPressed();
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == ACTIVATION) {
            boolean launch = pendingStart;
            pendingStart = false;
            if (launch && result == RESULT_OK) startProxy();
        }
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putBoolean("pending_start", pendingStart);
        out.putString("port", port);
        out.putString("search", query);
    }

    @Override protected void onResume() {
        super.onResume();
        if (ui.applyTheme() && dashboard != null) {
            dashboard.setDark(ui.isDark());
            show(false);
        }
        ServiceRestoration.visible(this);
        handler.post(refresh);
    }

    @Override protected void onPause() {
        handler.removeCallbacks(refresh);
        if (dashboard != null) dashboard.dismissDropdown();
        super.onPause();
    }

    @Override protected void onDestroy() {
        if (dashboard != null) {
            dashboard.destroy();
            dashboard = null;
        }
        super.onDestroy();
    }
}
