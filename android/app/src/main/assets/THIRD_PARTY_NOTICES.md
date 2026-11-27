Igareck AntiDPI Android · v1.0
Copyright (C) 2026 Igor Krauch (github.com/igareck).
The application, catalogue integration and TAP/SOCKS adapters are licensed
under GPL-3.0-or-later. Corresponding application source and build scripts are
in the source archive. See LICENSE and licenses/GPL-3.0.txt.
The launcher icon, Start / Apply button art and original dashboard callouts
were supplied with the project design. The dashboard is available in Russian
and English.

Packet engine

• nfqws2 from bol-van/zapret2, MIT, commit
  038c97e5819df10f8e8c22a2f1fffe57f2f7379b. See licenses/Zapret2-MIT.txt.
  The Android port uses TAP for packet I/O and includes the upstream packet
  processing, connection tracking, reassembly and Lua strategy execution.
• Lua 5.4.9, MIT. See licenses/Lua-MIT.txt.
• lwIP 2.2.1 (STABLE-2_2_1_RELEASE), BSD-3-Clause.
  See licenses/lwIP-BSD.txt and vendor/lwip/COPYING in the source archive.
• Upstream helper sources in the nfqws2 build include kavl (MIT), uthash
  (BSD-1-Clause) and musl-derived code (MIT). See licenses/kavl-MIT.txt,
  licenses/uthash-BSD.txt and licenses/musl-MIT.txt.
• Desktop catalogue and ActualTLS compatibility guard from
  igareck/anti-dpi-for-russia, commit 67c57d9e332b224c57c4f27c3087e3fcdc9f0482.
  Catalogue 20261023_public_v1, 484 TCP profiles.

Android activation and TLS

• Shizuku-API context bootstrap fragment, MIT, Copyright (c) 2021 RikkaW.
  See licenses/Shizuku-API-MIT.txt.
• android-testtap-egress, MIT, commit
  8147d45f75a01f06afa2a6a0f9a22cec6d639ba9. See licenses/TestTap-MIT.txt.
• AndroidX annotations 1.3.0, Apache-2.0. See licenses/AndroidX-Apache-2.0.txt.
• libadb-android 3.1.1, commit c849886ebc6d48e7b46d967e78a6bb65c90c3b74,
  GPL-3.0-or-later OR Apache-2.0. See licenses/libadb-LICENSE.txt.
  Its sources also include BSD-3-Clause code by Cameron Gutman and Sam Palmer
  and MIT code by Google Inc. See licenses/libadb-BSD-3-Clause.txt and
  licenses/libadb-MIT.txt. Modified sources are in vendor/libadb-android.
• SPAKE2 Java/JNI 2.2.1, commit 7615ddd680b990e14513ebb66eac4cb0dbf82464,
  with spake2-c commit 0d15933e5ba3e662cb01245a7ac0dc9fca3eac31, LGPL-3.0.
  Native primitives retain their own notices for ISC, MIT, Apache-2.0,
  LGPL-2.1-or-later and public-domain code. See licenses/SPAKE2-LGPL-3.0.txt,
  licenses/SPAKE2-native-NOTICE.txt and the license texts it references.
  Sources are in vendor/spake2-java. The build supports recompiling all three
  native ABIs using tools/build_spake2.py.
• Conscrypt Android 2.5.3, Apache-2.0, with BoringSSL.
  See licenses/Conscrypt-Apache-2.0.txt, licenses/Conscrypt-NOTICE.txt and
  licenses/BoringSSL-LICENSE.txt. Source https://github.com/google/conscrypt/tree/2.5.3
• Bouncy Castle bcprov/bcpkix/bcutil 1.81, MIT. See licenses/BouncyCastle-MIT.txt.
  Source https://github.com/bcgit/bc-java/tree/r1rv81

Dependency versions and hashes are listed in DEPENDENCIES.json. Pinned native
and vendored Java sources are included in the source archive. Karing, Exclave,
v2rayNG, FlClash, Orbot and InviZible Pro are separately installed clients
described in the app guides.
