/* Copyright (C) 2026 Igor Krauch. SPDX-License-Identifier: GPL-3.0-or-later */
package com.igareck.antidpi;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import javax.net.ssl.*;

/** One encrypted resolver and TTL cache per website scan. */
final class ScanDoh implements Closeable {
    static final long LOOKUP_NS = TimeUnit.SECONDS.toNanos(8);
    private static final long PEER_NS = TimeUnit.MILLISECONDS.toNanos(2200);
    private static final Endpoint GOOGLE = new Endpoint("dns.google", "8.8.8.8", "8.8.4.4");
    private static final Endpoint CLOUDFLARE = new Endpoint("cloudflare-dns.com", "1.1.1.1", "1.0.0.1");
    private static final Endpoint DNS_SB = new Endpoint("doh.dns.sb", "185.222.222.222", "45.11.45.11");
    private static final Endpoint ALI = new Endpoint("dns.alidns.com", "223.5.5.5", "223.6.6.6");
    private static final List<Endpoint> BOOTSTRAP = Arrays.asList(GOOGLE, CLOUDFLARE, DNS_SB, ALI);
    private static final List<Endpoint> PROVIDERS = Arrays.asList(
        GOOGLE, new Endpoint("family.adguard-dns.com", "94.140.14.15", "94.140.15.16"),
        CLOUDFLARE, new Endpoint("dns.cloudflare.com", "1.1.1.1", "1.0.0.1"),
        new Endpoint("doh.opendns.com"), new Endpoint("dns.hostux.net"),
        new Endpoint("blank.dnsforge.de"), DNS_SB, ALI,
        new Endpoint("doh.pub", "1.12.12.12", "120.53.53.53"), new Endpoint("doh.360.cn")
    );

    static final class Endpoint {
        final String host;
        final int port;
        final String[] addresses;

        Endpoint(String host, String... addresses) { this(host, 443, addresses); }
        Endpoint(String host, int port, String... addresses) {
            this.host = host;
            this.port = port;
            this.addresses = addresses;
        }
    }

    private static final class Cached {
        final DohMessage.Answer answer;
        final long expiry;

        Cached(DohMessage.Answer answer) {
            this.answer = answer;
            expiry = System.nanoTime() + (answer == null
                ? TimeUnit.SECONDS.toNanos(3) : TimeUnit.SECONDS.toNanos(answer.ttl));
        }

        boolean fresh() { return expiry - System.nanoTime() > 0; }
    }

    private final List<Endpoint> servers;
    private final List<Endpoint> seeds;
    private final SSLSocketFactory factory;
    private final ExecutorService workers;
    private final Map<String, Cached> cache = new HashMap<>();
    private final Map<String, Cached> bootstrapCache = new ConcurrentHashMap<>();

    ScanDoh() { this(PROVIDERS, BOOTSTRAP, (SSLSocketFactory) SSLSocketFactory.getDefault()); }

    ScanDoh(List<Endpoint> servers, List<Endpoint> seeds, SSLSocketFactory factory) {
        this.servers = servers;
        this.seeds = seeds;
        this.factory = factory;
        workers = Executors.newFixedThreadPool(servers.size(), task -> {
            Thread thread = new Thread(task, "scan-doh");
            thread.setDaemon(true);
            return thread;
        });
    }

    synchronized byte[] resolve(String host, YoutubeProbe.Cancellation cancel, long deadline) throws IOException {
        cancel.check();
        Cached hit = cache.get(host);
        if (hit != null && hit.fresh()) {
            if (hit.answer != null) return hit.answer.addresses.get(0);
            throw unavailable(host);
        }
        byte[] question = DohMessage.question(host);
        long started = System.nanoTime();
        long until = Math.min(deadline, started + LOOKUP_NS);
        YoutubeProbe.Cancellation race = new YoutubeProbe.Cancellation();
        Closeable stopRace = race::cancel;
        cancel.attach(stopRace);
        CompletionService<DohMessage.Answer> completed = new ExecutorCompletionService<>(workers);
        Map<Future<DohMessage.Answer>, Endpoint> pending = new LinkedHashMap<>();
        List<String> errors = new ArrayList<>();
        try {
            for (Endpoint server : servers) {
                Future<DohMessage.Answer> future = completed.submit(() -> {
                    try {
                        return query(server, question, host, race, until);
                    } catch (IOException e) {
                        throw new IOException(server.host + "=" + e.getClass().getSimpleName()
                            + " (" + e.getMessage() + ")", e);
                    }
                });
                pending.put(future, server);
            }
            int remaining = pending.size();
            while (remaining > 0) {
                cancel.check();
                long left = until - System.nanoTime();
                if (left <= 0) break;
                Future<DohMessage.Answer> next = completed.poll(Math.min(left, TimeUnit.MILLISECONDS.toNanos(100)), TimeUnit.NANOSECONDS);
                if (next == null) continue;
                remaining--;
                try {
                    DohMessage.Answer result = next.get();
                    AppLog.line("SCAN_DOH", "host=" + host + "; resolver=" + pending.get(next).host
                        + "; addresses=" + result.addresses.size()
                        + "; elapsed_ms=" + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
                    cache.put(host, new Cached(result));
                    return result.addresses.get(0);
                } catch (ExecutionException e) {
                    errors.add(e.getCause() instanceof IOException ? e.getCause().getMessage() : "Resolver error");
                }
            }
            cancel.check();
            cache.put(host, new Cached(null));
            AppLog.line("SCAN_DOH", "host=" + host + "; no IPv4; replies=" + errors.size()
                + "/" + servers.size() + "; " + String.join("; ", errors));
            throw unavailable(host);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("CANCELLED");
        } finally {
            race.cancel();
            cancel.detach(stopRace);
            for (Future<?> future : pending.keySet()) future.cancel(true);
        }
    }

    private static UnknownHostException unavailable(String host) {
        return new UnknownHostException("DoH не вернул пригодный IPv4 для " + host);
    }

    private DohMessage.Answer query(Endpoint server, byte[] question, String host,
                                    YoutubeProbe.Cancellation cancel, long deadline) throws IOException {
        List<byte[]> addresses = new ArrayList<>();
        for (String address : server.addresses) addresses.add(numericAddress(address));
        if (addresses.isEmpty()) addresses = bootstrap(server.host, cancel, deadline);
        IOException last = null;
        for (byte[] address : addresses) {
            cancel.check();
            try {
                return exchange(server, address, question, host, cancel, Math.min(deadline, System.nanoTime() + PEER_NS));
            } catch (IOException e) {
                last = e;
            }
        }
        throw last == null ? unavailable(host) : last;
    }

    private List<byte[]> bootstrap(String host, YoutubeProbe.Cancellation cancel, long deadline) throws IOException {
        Cached remembered = bootstrapCache.get(host);
        if (remembered != null && remembered.fresh()) return remembered.answer.addresses;
        byte[] question = DohMessage.question(host);
        for (Endpoint seed : seeds) {
            cancel.check();
            if (deadline - System.nanoTime() <= 0) break;
            try {
                DohMessage.Answer answer = exchange(seed, numericAddress(seed.addresses[0]), question, host,
                    cancel, Math.min(deadline, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(1400)));
                bootstrapCache.put(host, new Cached(answer));
                return answer.addresses;
            } catch (IOException ignored) {
                // Try another encrypted bootstrap server.
            }
        }
        throw new UnknownHostException("Encrypted bootstrap unavailable");
    }

    private DohMessage.Answer exchange(Endpoint server, byte[] address, byte[] question, String host,
                                        YoutubeProbe.Cancellation cancel, long deadline) throws IOException {
        cancel.check();
        try (Socket raw = new Socket()) {
            cancel.attach(raw);
            ScheduledFuture<?> clock = YoutubeProbe.CLOCK.schedule(
                () -> YoutubeProbe.close(raw), Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            try {
                raw.connect(new InetSocketAddress(InetAddress.getByAddress(address), server.port), YoutubeProbe.left(deadline));
                try (SSLSocket tls = YoutubeProbe.httpsSocket(raw, server.host, server.port, factory)) {
                    tls.setSoTimeout(YoutubeProbe.left(deadline));
                    tls.startHandshake();
                    cancel.check();
                    OutputStream out = tls.getOutputStream();
                    String header = "POST /dns-query HTTP/1.1\r\nHost: " + server.host
                        + "\r\nAccept: " + DohMessage.CONTENT_TYPE + "\r\nContent-Type: " + DohMessage.CONTENT_TYPE
                        + "\r\nAccept-Encoding: identity\r\nContent-Length: " + question.length + "\r\nConnection: close\r\n\r\n";
                    out.write(header.getBytes(StandardCharsets.US_ASCII));
                    out.write(question);
                    out.flush();
                    BufferedInputStream in = new BufferedInputStream(tls.getInputStream());
                    String status = YoutubeProbe.line(in);
                    if (!status.matches("HTTP/1\\.[01] 200( .*)?")) throw new IOException("DoH HTTP response not 200");
                    Map<String, String> headers = YoutubeProbe.headers(in);
                    String type = headers.getOrDefault("content-type", "").split(";", 2)[0].trim();
                    if (!DohMessage.CONTENT_TYPE.equalsIgnoreCase(type)) throw new IOException("Invalid DoH content type");
                    return DohMessage.answer(body(in, headers), host);
                }
            } finally {
                clock.cancel(false);
                cancel.detach(raw);
            }
        }
    }

    private static byte[] body(InputStream input, Map<String, String> headers) throws IOException {
        DataInputStream in = new DataInputStream(input);
        String encoding = headers.getOrDefault("content-encoding", "identity");
        if (!encoding.equalsIgnoreCase("identity")) throw new IOException("Unsupported DoH encoding");
        String transfer = headers.getOrDefault("transfer-encoding", "");
        if (!transfer.isEmpty()) {
            if (!transfer.equalsIgnoreCase("chunked")) throw new IOException("Unsupported DoH transfer encoding");
            ByteArrayOutputStream result = new ByteArrayOutputStream();
            for (;;) {
                int size = size(YoutubeProbe.line(in).split(";", 2)[0].trim(), 16, DohMessage.MAX_BYTES - result.size());
                if (size == 0) return result.toByteArray();
                byte[] chunk = new byte[size];
                in.readFully(chunk);
                result.write(chunk);
                if (in.readUnsignedByte() != '\r' || in.readUnsignedByte() != '\n') throw new IOException("Invalid DoH chunk");
            }
        }
        if (headers.containsKey("content-length")) {
            byte[] result = new byte[size(headers.get("content-length"), 10, DohMessage.MAX_BYTES)];
            in.readFully(result);
            return result;
        }
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        for (int count; (count = in.read(buffer)) != -1;) {
            if (count > DohMessage.MAX_BYTES - result.size()) throw new IOException("DoH body too large");
            result.write(buffer, 0, count);
        }
        return result.toByteArray();
    }

    private static int size(String value, int radix, int max) throws IOException {
        try {
            int size = Integer.parseInt(value, radix);
            if (size >= 0 && size <= max) return size;
        } catch (NumberFormatException ignored) {}
        throw new IOException("Invalid DoH body size");
    }

    private static byte[] numericAddress(String address) {
        String[] words = address.split("\\.", -1);
        if (words.length != 4) throw new IllegalArgumentException("DoH bootstrap requires numeric IPv4");
        byte[] value = new byte[4];
        for (int i = 0; i < 4; i++) {
            int octet = Integer.parseInt(words[i]);
            if (octet < 0 || octet > 255) throw new IllegalArgumentException("Invalid IPv4 octet");
            value[i] = (byte) octet;
        }
        return value;
    }

    @Override public void close() { workers.shutdownNow(); }
}
