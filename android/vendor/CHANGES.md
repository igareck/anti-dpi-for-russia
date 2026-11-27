# Packet backend source provenance

The bundled nfqws2, Lua and lwIP sources use the upstream revisions recorded
in PACKET_SOURCE_LOCK.json. The Android adapter is under native/nfqws2.

Build the Android native libraries using tools/build_nfqws2.py and NDK r28c.
NATIVE_LIBRARIES_SHA256.json records the hashes of the nine native libraries
shipped with Android v1.0.
