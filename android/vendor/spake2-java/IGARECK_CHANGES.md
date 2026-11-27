Java/native source pinned to spake2-java 2.2.1 (7615ddd680b990e14513ebb66eac4cb0dbf82464), native submodule spake2-c 0d15933e5ba3e662cb01245a7ac0dc9fca3eac31.
JNI failure paths retain the context for the owning Java destroy() method, avoiding a double free. No SPAKE2 algorithm change. All three JNI ABIs are rebuilt by tools/build_spake2.py.
The JNI wrapper explicitly includes stdint.h for portable integer declarations. The host-only verification build enables _DEFAULT_SOURCE. Android builds use NDK r28c and 16 KiB ELF page alignment.
