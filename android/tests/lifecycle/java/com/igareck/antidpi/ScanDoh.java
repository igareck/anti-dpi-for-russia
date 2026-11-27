package com.igareck.antidpi;

/** The service lifecycle harness is isolated from external DNS. */
final class ScanDoh implements java.io.Closeable {
    static final long LOOKUP_NS = 1_000_000_000L;
    byte[] resolve(String host, YoutubeProbe.Cancellation cancel, long deadline) throws java.io.IOException {
        cancel.check();
        return new byte[]{9,9,9,9};
    }
    public void close() {}
}
