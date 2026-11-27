package com.igareck.antidpi;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.*;
import org.json.JSONObject;

/** Production DoH against self-contained TLS peers. */
public final class DohHarness {
    private static final String SITE = "www.youtube.com";
    private static final byte[] ADDRESS = {9, 9, 9, 9};
    private static int checks;

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        SSLContext tls = YoutubeHarness.context(args[0]);
        byte[] question = DohMessage.question(SITE);
        DohMessage.Answer answer = DohMessage.answer(response(question, 0, ADDRESS, true), SITE);
        check(answer.ttl == 25 && Arrays.equals(answer.addresses.get(0), ADDRESS), "Compressed CNAME or TTL");
        reject(response(question, 0, ADDRESS, false), "discord.com");
        reject(response(question, 3, null, false), SITE);
        reject(response(question, 0, new byte[]{0, 0, 0, 0}, false), SITE);
        reject(response(question, 0, new byte[]{127, 0, 0, 1}, false), SITE);
        reject(Arrays.copyOf(response(question, 0, ADDRESS, false), question.length + 5), SITE);

        try (Fixture negative = new Fixture(tls, 3, 0, false);
             Fixture positive = new Fixture(tls, 0, 180, true);
             ScanDoh resolver = new ScanDoh(Arrays.asList(negative.endpoint(SITE), positive.endpoint(SITE)),
                 Collections.emptyList(), tls.getSocketFactory())) {
            YoutubeProbe.Cancellation token = new YoutubeProbe.Cancellation();
            check(Arrays.equals(resolver.resolve(SITE, token, deadline()), ADDRESS), "Negative answer won the race");
            check(negative.requests.get() == 1 && positive.requests.get() == 1, "Parallel HTTPS peers not queried");
            check(Arrays.equals(resolver.resolve(SITE, token, deadline()), ADDRESS) && positive.requests.get() == 1,
                "Cache repeated a DNS query before TTL expiry");
            check(negative.failure == null && positive.failure == null, "Unexpected HTTPS request");
        }

        try (Fixture fixture = new Fixture(tls, 0, 0, false);
             ScanDoh resolver = new ScanDoh(Collections.singletonList(fixture.endpoint("m.youtube.com")),
                 Collections.emptyList(), tls.getSocketFactory())) {
            try {
                resolver.resolve(SITE, new YoutubeProbe.Cancellation(), deadline());
                throw new AssertionError("Accepted a wrong-name TLS certificate");
            } catch (UnknownHostException expected) {
                check(fixture.requests.get() == 0, "Query sent before TLS hostname verification");
            }
        }

        try (Fixture fixture = new Fixture(tls, 0, 1500, false);
             ScanDoh resolver = new ScanDoh(Collections.singletonList(fixture.endpoint(SITE)),
                 Collections.emptyList(), tls.getSocketFactory())) {
            YoutubeProbe.Cancellation token = new YoutubeProbe.Cancellation();
            FutureTask<byte[]> lookup = new FutureTask<>(() -> resolver.resolve(SITE, token, deadline()));
            Thread reader = new Thread(lookup, "cancel-test");
            reader.setDaemon(true);
            reader.start();
            check(fixture.request.await(2, TimeUnit.SECONDS), "HTTPS request not observed");
            token.cancel();
            try {
                lookup.get(700, TimeUnit.MILLISECONDS);
                throw new AssertionError("Cancelled lookup returned an address");
            } catch (ExecutionException expected) {
                check(expected.getCause() instanceof InterruptedIOException, "Cancellation misclassified");
            }
        }
        System.out.println(new JSONObject().put("passed", true).put("checks", checks));
    }

    private static long deadline() { return System.nanoTime() + TimeUnit.SECONDS.toNanos(5); }

    private static void reject(byte[] data, String host) throws Exception {
        try {
            DohMessage.answer(data, host);
            throw new AssertionError("Accepted an invalid DNS answer");
        } catch (IOException expected) { checks++; }
    }

    private static byte[] response(byte[] question, int code, byte[] address, boolean alias) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(buffer)) {
            out.writeShort(0);
            out.writeShort(0x8180 | code);
            out.writeShort(1);
            out.writeShort(address == null ? 0 : alias ? 2 : 1);
            out.writeInt(0);
            out.write(question, 12, question.length - 12);
            if (address != null) {
                int owner = 0xc00c;
                if (alias) {
                    byte[] name = {5, 'a', 'l', 'i', 'a', 's', (byte) 0xc0, 12};
                    owner = 0xc000 | (buffer.size() + 12);
                    out.writeShort(0xc00c);
                    out.writeShort(5);
                    out.writeShort(1);
                    out.writeInt(25);
                    out.writeShort(name.length);
                    out.write(name);
                }
                out.writeShort(owner);
                out.writeShort(1);
                out.writeShort(1);
                out.writeInt(60);
                out.writeShort(4);
                out.write(address);
            }
        }
        return buffer.toByteArray();
    }

    private static final class Fixture implements Closeable {
        final SSLServerSocket server;
        final CountDownLatch request = new CountDownLatch(1);
        final AtomicInteger requests = new AtomicInteger();
        volatile Throwable failure;
        volatile SSLSocket client;

        Fixture(SSLContext context, int code, int delay, boolean chunked) throws IOException {
            server = (SSLServerSocket) context.getServerSocketFactory().createServerSocket(
                0, 8, InetAddress.getByName("127.0.0.1"));
            Thread receiver = new Thread(() -> serve(code, delay, chunked), "doh-fixture");
            receiver.setDaemon(true);
            receiver.start();
        }

        ScanDoh.Endpoint endpoint(String host) {
            return new ScanDoh.Endpoint(host, server.getLocalPort(), "127.0.0.1");
        }

        private void serve(int code, int delay, boolean chunked) {
            try (SSLSocket socket = (SSLSocket) server.accept()) {
                client = socket;
                socket.setSoTimeout(2500);
                DataInputStream in = new DataInputStream(socket.getInputStream());
                if (!"POST /dns-query HTTP/1.1".equals(YoutubeProbe.line(in))) throw new AssertionError("Wrong request");
                Map<String, String> headers = YoutubeProbe.headers(in);
                if (!DohMessage.CONTENT_TYPE.equals(headers.get("accept"))) throw new AssertionError("Wrong DNS content type");
                byte[] actual = new byte[Integer.parseInt(headers.get("content-length"))];
                in.readFully(actual);
                if (!Arrays.equals(actual, DohMessage.question(SITE))) throw new AssertionError("Wrong DNS question");
                requests.incrementAndGet();
                request.countDown();
                Thread.sleep(delay);
                byte[] body = response(actual, code, code == 0 ? ADDRESS : null, false);
                OutputStream out = socket.getOutputStream();
                String framing = chunked ? "Transfer-Encoding: chunked" : "Content-Length: " + body.length;
                out.write(("HTTP/1.1 200 OK\r\nContent-Type: " + DohMessage.CONTENT_TYPE + "\r\n" + framing + "\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
                if (chunked) out.write((Integer.toHexString(body.length) + "\r\n").getBytes(StandardCharsets.US_ASCII));
                out.write(body);
                if (chunked) out.write("\r\n0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                out.flush();
            } catch (IOException expected) {
                // The certificate and cancellation tests terminate the connection.
            } catch (Throwable e) {
                failure = e;
            }
        }

        public void close() throws IOException {
            server.close();
            YoutubeProbe.close(client);
        }
    }
}
