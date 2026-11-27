/* Copyright (C) 2026 Igor Krauch. SPDX-License-Identifier: GPL-3.0-or-later */
package com.igareck.antidpi;

import android.content.Context;
import android.os.Build;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.zip.*;

public final class AppLog {
    public static final String VERSION = "1.0";
    private static final Object LOCK = new Object();
    private static final ArrayDeque<String> TAIL = new ArrayDeque<>();
    private static final SimpleDateFormat TIME = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
    private static Context context;
    private static volatile DiskLog disk;
    private static volatile DiskLog events;
    private static long generation;
    public static void init(Context c) {
        synchronized (LOCK) {
            if (context != null) return;
            context = c.getApplicationContext();ProfileStatus.init(context);PacketBridge.init(context);
            for(WebTarget target:WebTarget.values())TcpStrategyScan.forTarget(target).init(context.getFilesDir());
            TIME.setTimeZone(TimeZone.getTimeZone("UTC"));
            File logs = logDirectory(c);
            try { disk = new DiskLog(new File(logs, "engine"));
                events=new DiskLog(new File(logs,"events"),24,512*1024); }
            catch (IOException e) { android.util.Log.e("IgareckAntiDPI", "Cannot open log writer", e); }
        }
        line("APP", "Igareck AntiDPI Android " + VERSION + "; author: Igor Krauch (github.com/igareck)");
        line("DEVICE", "Android=" + Build.VERSION.RELEASE + "; SDK=" + Build.VERSION.SDK_INT
            + "; manufacturer=" + Build.MANUFACTURER + "; model=" + Build.MODEL
            + "; ABIs=" + Arrays.toString(Build.SUPPORTED_ABIS));
        line("PRIVACY", "Payloads and subscription credentials are not collected. Destination hosts/IPs can appear in verbose engine logs.");
    }
    private static File logDirectory(Context context) {
        File directory = new File(context.getFilesDir(), "logs");
        if (new File(directory, "engine").isDirectory()) return directory;
        // Preserve the diagnostic history of an upgraded installation.
        File[] existing = directory.listFiles(path -> new File(path, "engine").isDirectory()
            && new File(path, "events").isDirectory());
        return existing != null && existing.length == 1 ? existing[0] : directory;
    }
    public static void line(String channel, String message) {
        String safe = message == null ? "null" : message.replace('\r', ' ').replace('\n', ' ');
        if (safe.contains("bytes:")) safe = safe.substring(0, safe.indexOf("bytes:")) + "[payload omitted]";
        boolean scanResult=channel.endsWith("_RESULT")&&(channel.equals("TRAFFIC_RESULT")
            ||WebTarget.fromKey(channel.substring(0,channel.length()-7).toLowerCase(Locale.ROOT))!=null);
        int limit=scanResult?16384:(channel.equals("NFQ_METRICS")||channel.equals("UDP_EVENT"))?4096:2000;
        if (safe.length() > limit) safe = safe.substring(0, limit) + " [truncated]";
        String text;
        synchronized (LOCK) {
            text = TIME.format(new Date()) + " [" + channel + "] " + safe;
            TAIL.addLast(text);
            while (TAIL.size() > 180) TAIL.removeFirst();
            generation++;
        }
        DiskLog target = disk;
        if (target != null) target.append(text);
        DiskLog compact=events;if(compact!=null&&!channel.equals("ENGINE"))compact.append(text);
    }
    public static void error(String where, Throwable error) {
        StringWriter sw = new StringWriter(); error.printStackTrace(new PrintWriter(sw));
        for (String line : sw.toString().split("\n")) line("ERROR", where + ": " + line);
    }
    public static long generation() { synchronized (LOCK) { return generation; } }
    public static String tail() {
        synchronized (LOCK) {
            ArrayDeque<String> selected = new ArrayDeque<>();
            int length = 0;
            Iterator<String> it = TAIL.descendingIterator();
            while (it.hasNext() && length < 22000) {
                String s = it.next(); selected.addFirst(s); length += s.length() + 1;
            }
            StringBuilder result = new StringBuilder();
            for (String s : selected) result.append(s).append('\n');
            return result.toString();
        }
    }
    public static String writerState() {
        DiskLog target = disk;
        return target == null ? "writer unavailable" :
            "dropped_verbose_lines=" + target.droppedLines() + "; disk_error=" + target.error()+
            "; event_dropped_lines="+(events==null?-1:events.droppedLines())+"; event_error="+(events==null?"unavailable":events.error());
    }
    public static void exportTo(OutputStream destination) throws IOException {
        DiskLog target = disk;
        if (target == null) throw new IOException("Log writer unavailable");
        ProxyService.requestFlowSnapshot();
        File snapshot = target.snapshot(context.getCacheDir());
        try (ZipOutputStream zip = new ZipOutputStream(destination)) {
            File[] files = snapshot.listFiles();
            if (files != null) {
                Arrays.sort(files, Comparator.comparing(File::getName));
                for (File f : files) {
                    zip.putNextEntry(new ZipEntry("logs/" + f.getName()));
                    try (InputStream in = new FileInputStream(f)) { copy(in, zip); }
                    zip.closeEntry();
                }
            }
            for (String asset : new String[]{"profiles.json", "BUILD_INFO.json", "README_ANDROID_RU.txt", "THIRD_PARTY_NOTICES.txt", "nfqws2/catalogue.json","nfqws2/quic_catalogue.json","nfqws2/discord_catalogue.json"}) {
                zip.putNextEntry(new ZipEntry(asset));
                try (InputStream in = context.getAssets().open(asset)) { copy(in, zip); }
                zip.closeEntry();
            }
            DiskLog compact=events;
            if(compact!=null){File eventSnapshot=compact.snapshot(context.getCacheDir());
                try{File[] saved=eventSnapshot.listFiles();if(saved!=null)for(File f:saved){
                    zip.putNextEntry(new ZipEntry("events/"+f.getName()));try(InputStream in=new FileInputStream(f)){copy(in,zip);}zip.closeEntry();
                }}finally{DiskLog.removeSnapshot(eventSnapshot);}
            }
            put(zip,"profile_evidence.json",ProfileStatus.summary());
            put(zip,"packet_state.json",PacketBridge.snapshot());
            put(zip,"activation_state.json",EmbeddedAdb.snapshot());
            put(zip, "runtime_state.json", ProxyService.snapshot());
            put(zip, "diagnostics.json", Diagnostics.summary());
            for(WebTarget website:WebTarget.values())put(zip,website.key+"_results.json",TcpStrategyScan.forTarget(website).summary());
            put(zip, "logging_state.txt", writerState() + "\n");
            put(zip, "test_result.txt", "User observation:\nVPN client / version:\nVPN node transport:\n"
                + "Carrier / network:\nProfile and TTL:\nResult and time:\n"
                + "Do not attach subscription URLs, UUIDs, passwords or private keys.\n");
        } finally { DiskLog.removeSnapshot(snapshot); }
    }
    private static void put(ZipOutputStream out, String name, String text) throws IOException {
        out.putNextEntry(new ZipEntry(name)); out.write(text.getBytes(StandardCharsets.UTF_8)); out.closeEntry();
    }
    public static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[32768]; int n;
        while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
    }
    private AppLog() {}
}
