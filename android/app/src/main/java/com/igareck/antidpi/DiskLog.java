/* Copyright (C) 2026 Igor Krauch. SPDX-License-Identifier: GPL-3.0-or-later */
package com.igareck.antidpi;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** File IO belongs to this worker, never to the native stdout reader. */
public final class DiskLog implements Closeable {
    private static final class Item {
        String line;
        File parent, result;
        boolean stop;
        IOException error;
        CountDownLatch done;
    }
    private final File directory;
    private final ArrayBlockingQueue<Item> queue = new ArrayBlockingQueue<>(8192);
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Thread worker;
    private BufferedOutputStream stream;
    private long bytes;
    private int serial;
    private final int maxFiles,maxBytes;
    private volatile IOException lastError;

    public DiskLog(File directory) throws IOException {this(directory,5,2*1024*1024);}
    public DiskLog(File directory,int maxFiles,int maxBytes) throws IOException {
        this.directory = directory;this.maxFiles=maxFiles;this.maxBytes=maxBytes;
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create log directory");
        worker = new Thread(this::loop, "diagnostic-writer");
        worker.setDaemon(true);
        worker.start();
    }
    public void append(String line) {
        if (closed.get()) return;
        Item item = new Item(); item.line = line;
        if (!queue.offer(item)) dropped.incrementAndGet();
    }
    public long droppedLines() { return dropped.get(); }
    public String error() { return lastError == null ? "" : lastError.toString(); }

    /** Snapshot completes before a slow external ZIP destination is opened. */
    public File snapshot(File cacheParent) throws IOException {
        Item item = new Item(); item.parent = cacheParent; item.done = new CountDownLatch(1);
        submit(item);
        if (item.error != null) throw item.error;
        return item.result;
    }
    private void submit(Item item) throws IOException {
        if (closed.get() && !item.stop) throw new IOException("Log writer is closed");
        if (!worker.isAlive()) throw new IOException("Log writer stopped", lastError);
        try {
            if (!queue.offer(item, 2, TimeUnit.SECONDS)) throw new IOException("Log queue is busy");
            if (!item.done.await(15, TimeUnit.SECONDS)) throw new IOException("Log snapshot timed out");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); throw new IOException("Log request interrupted", e);
        }
    }
    private void loop() {
        try {
            while (true) {
                Item item = queue.poll(250, TimeUnit.MILLISECONDS);
                if (item == null) { safeFlush(); continue; }
                try {
                    if (item.line != null) write(item.line);
                    else if (item.stop) { flush(); break; }
                    else { flush(); item.result = copySnapshot(item.parent); }
                } catch (IOException error) {
                    lastError = error; item.error = error;
                } finally { if (item.done != null) item.done.countDown(); }
                if (queue.isEmpty()) safeFlush();
            }
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        finally { try { if (stream != null) stream.close(); } catch (IOException e) { lastError = e; } }
    }
    private void write(String line) throws IOException {
        byte[] data = (line + "\n").getBytes(StandardCharsets.UTF_8);
        if (stream == null || bytes + data.length > maxBytes) rotate();
        stream.write(data); bytes += data.length;
    }
    private void flush() throws IOException { if (stream != null) stream.flush(); }
    private void safeFlush() { try { flush(); } catch (IOException e) { lastError = e; } }
    private void rotate() throws IOException {
        if (stream != null) stream.close();
        File target = new File(directory, "session_" + System.currentTimeMillis() + "_" + (++serial) + ".log");
        stream = new BufferedOutputStream(new FileOutputStream(target), 65536); bytes = 0;
        File[] files = directory.listFiles((d,n) -> n.endsWith(".log"));
        if (files != null) {
            // Sort by modification time to support both date-based and epoch-based log names.
            Arrays.sort(files, Comparator.comparingLong(File::lastModified).thenComparing(File::getName));
            for (int i = 0; i < files.length - maxFiles; i++) files[i].delete();
        }
    }
    private File copySnapshot(File parent) throws IOException {
        File target = new File(parent, "igareck-export-" + UUID.randomUUID());
        if (!target.mkdirs()) throw new IOException("Cannot create export snapshot");
        try {
            File[] files = directory.listFiles((d,n) -> n.endsWith(".log"));
            if (files != null) for (File file : files) {
                try (InputStream in = new FileInputStream(file);
                     OutputStream out = new FileOutputStream(new File(target, file.getName()))) {
                    byte[] buffer = new byte[32768]; int n;
                    while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
                }
            }
            return target;
        } catch (IOException e) { removeSnapshot(target); throw e; }
    }
    public static void removeSnapshot(File directory) {
        File[] files = directory.listFiles();
        if (files != null) for (File file : files) file.delete();
        directory.delete();
    }
    @Override public void close() throws IOException {
        if (!closed.compareAndSet(false, true)) return;
        Item item = new Item(); item.stop = true; item.done = new CountDownLatch(1);
        submit(item);
        if (item.error != null) throw item.error;
    }
}
