/* Copyright (C) 2026 Igor Krauch. SPDX-License-Identifier: GPL-3.0-or-later */
package com.igareck.antidpi;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** IPv4 DNS messages for HTTPS transport only. */
final class DohMessage {
    static final String CONTENT_TYPE = "application/dns-message";
    static final int MAX_BYTES = 65535;

    static final class Answer {
        final List<byte[]> addresses;
        final long ttl;

        Answer(List<byte[]> addresses, long ttl) {
            this.addresses = addresses;
            this.ttl = ttl;
        }
    }

    static byte[] question(String host) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(64);
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeShort(0);      // RFC 8484 recommends a zero transaction ID.
            out.writeShort(0x100);  // Recursion desired.
            out.writeShort(1);
            out.write(new byte[6]);
            if (host.length() > 253) throw new IOException("Invalid DNS name");
            for (String label : host.split("\\.", -1)) {
                if (!label.matches("[A-Za-z0-9_-]{1,63}")) throw new IOException("Invalid DNS label");
                out.writeByte(label.length());
                out.writeBytes(label);
            }
            out.writeByte(0);
            out.writeShort(1); // A
            out.writeShort(1); // IN
        }
        return bytes.toByteArray();
    }

    static Answer answer(byte[] payload, String host) throws IOException {
        if (payload.length > MAX_BYTES) throw new IOException("DNS response too large");
        Reader in = new Reader(payload);
        int id = in.u16(), flags = in.u16(), questions = in.u16(), count = in.u16();
        in.u16();
        in.u16();
        if (id != 0 || (flags & 0xfa0f) != 0x8000 || questions != 1)
            throw new IOException("DNS response status " + (flags & 15));
        if (!host.equalsIgnoreCase(in.name()) || in.u16() != 1 || in.u16() != 1)
            throw new IOException("DNS response question mismatch");

        Map<String, List<byte[]>> addresses = new HashMap<>();
        Map<String, String> aliases = new HashMap<>();
        Map<String, Long> lifetimes = new HashMap<>();
        for (int i = 0; i < count; i++) {
            String owner = in.name();
            int type = in.u16(), recordClass = in.u16();
            long ttl = ((long) in.u16() << 16) | in.u16();
            int length = in.u16();
            int end = in.position + length;
            if (end > payload.length) throw new IOException("Truncated DNS answer");
            if (recordClass == 1 && type == 1 && length == 4) {
                byte[] address = new byte[4];
                System.arraycopy(payload, in.position, address, 0, length);
                if (isPublic(address)) {
                    List<byte[]> ips = addresses.computeIfAbsent(owner, ignored -> new ArrayList<>());
                    if (ips.size() < 4) ips.add(address);
                    lifetimes.merge(owner, ttl, Math::min);
                }
            } else if (recordClass == 1 && type == 5) {
                String alias = in.name();
                if (in.position != end || alias.isEmpty()) throw new IOException("Invalid DNS alias");
                aliases.put(owner, alias);
                lifetimes.merge(owner, ttl, Math::min);
            }
            in.position = end;
        }
        String name = host.toLowerCase(Locale.ROOT);
        long ttl = 300;
        for (int i = 0; name != null && i < 16; i++) {
            ttl = Math.min(ttl, lifetimes.getOrDefault(name, ttl));
            List<byte[]> ips = addresses.get(name);
            if (ips != null) return new Answer(ips, ttl);
            name = aliases.get(name);
        }
        throw new IOException("No public IPv4 answer");
    }

    private static boolean isPublic(byte[] address) {
        int a = address[0] & 255, b = address[1] & 255, c = address[2] & 255;
        if (a == 0 || a == 10 || a == 127 || a >= 224) return false;
        if ((a == 100 && b >= 64 && b <= 127) || (a == 169 && b == 254)) return false;
        if ((a == 172 && b >= 16 && b <= 31) || (a == 192 && b == 168)) return false;
        if (a == 192 && b == 0 && (c == 0 || c == 2)) return false;
        if (a == 198 && (b == 18 || b == 19 || (b == 51 && c == 100))) return false;
        return a != 203 || b != 0 || c != 113;
    }

    private static final class Reader {
        final byte[] bytes;
        int position;

        Reader(byte[] bytes) { this.bytes = bytes; }

        int u8() throws IOException {
            if (position >= bytes.length) throw new IOException("Truncated DNS message");
            return bytes[position++] & 255;
        }

        int u16() throws IOException { return (u8() << 8) | u8(); }

        String name() throws IOException {
            int resume = -1;
            StringBuilder output = new StringBuilder();
            for (int steps = 0; steps < 128; steps++) {
                int size = u8();
                if (size == 0) {
                    if (resume != -1) position = resume;
                    return output.toString().toLowerCase(Locale.ROOT);
                }
                if ((size & 0xc0) == 0xc0) {
                    int offset = ((size & 63) << 8) | u8();
                    if (offset >= position - 2) throw new IOException("Invalid DNS compression");
                    if (resume == -1) resume = position;
                    position = offset;
                    continue;
                }
                if (size > 63 || output.length() + size >= 254) throw new IOException("Invalid DNS name");
                if (output.length() != 0) output.append('.');
                for (int i = 0; i < size; i++) {
                    int c = u8();
                    if (c <= ' ' || c > 126 || c == '.') throw new IOException("Invalid DNS name");
                    output.append((char) c);
                }
            }
            throw new IOException("DNS name too complex");
        }
    }

    private DohMessage() {}
}
