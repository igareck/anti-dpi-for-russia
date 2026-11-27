"""Check the Android 11+ resource-table installation requirements."""
import json
import pathlib
import struct
import sys
import zipfile


def elf_load_alignment(data, name):
    if data[:4] != b"\x7fELF" or data[4] not in (1, 2) or data[5] not in (1, 2):
        raise ValueError("Invalid native ELF: " + name)
    order = "<" if data[5] == 1 else ">"
    if data[4] == 2:
        phoff = struct.unpack_from(order + "Q", data, 32)[0]
        entsize, count = struct.unpack_from(order + "HH", data, 54)
        width, offset_at, address_at, alignment_at = "Q", 8, 16, 48
        minimum = 56
    else:
        phoff = struct.unpack_from(order + "I", data, 28)[0]
        entsize, count = struct.unpack_from(order + "HH", data, 42)
        width, offset_at, address_at, alignment_at = "I", 4, 8, 28
        minimum = 32
    if entsize < minimum or phoff + entsize * count > len(data):
        raise ValueError("Invalid ELF program headers: " + name)
    alignments = []
    for index in range(count):
        start = phoff + index * entsize
        if struct.unpack_from(order + "I", data, start)[0] != 1:
            continue
        alignment = struct.unpack_from(order + width, data, start + alignment_at)[0]
        offset = struct.unpack_from(order + width, data, start + offset_at)[0]
        address = struct.unpack_from(order + width, data, start + address_at)[0]
        if alignment < 16384 or alignment & (alignment - 1) or (address - offset) % alignment:
            raise ValueError("Native ELF is not 16 KiB page compatible: " + name)
        alignments.append(alignment)
    if not alignments:
        raise ValueError("Native ELF has no LOAD segments: " + name)
    return alignments


def check(path):
    with zipfile.ZipFile(path) as apk:
        failed = apk.testzip()
        if failed is not None:
            raise ValueError("ZIP CRC failure: " + failed)
        resources = apk.getinfo("resources.arsc")
        if resources.compress_type != zipfile.ZIP_STORED:
            raise ValueError("resources.arsc must be stored without compression")
        with open(path, "rb") as stream:
            stream.seek(resources.header_offset)
            header = stream.read(30)
        if len(header) != 30 or header[:4] != b"PK\x03\x04":
            raise ValueError("Invalid ZIP local header for resources.arsc")
        name_length, extra_length = struct.unpack_from("<HH", header, 26)
        data_offset = resources.header_offset + 30 + name_length + extra_length
        if data_offset % 4:
            raise ValueError("resources.arsc must be aligned to a 4-byte boundary")
        expected = {"lib/" + abi + "/" + library
                    for abi in ("arm64-v8a", "armeabi-v7a", "x86_64")
                    for library in ("libnfqws2.so", "libspake2.so", "libconscrypt_jni.so")}
        libraries = {name for name in apk.namelist() if name.startswith("lib/") and name.endswith(".so")}
        if libraries != expected:
            raise ValueError("Unexpected native ABI/library set: " + repr(libraries ^ expected))
        elf = {name: elf_load_alignment(apk.read(name), name) for name in sorted(libraries)}
        return {"apk": str(path), "zip_crc": "OK", "resources_compression": "STORED",
                "resources_offset": data_offset, "resources_alignment": 4,
                "native_elf_load_alignment": elf}


if __name__ == "__main__":
    print(json.dumps(check(pathlib.Path(sys.argv[1])), ensure_ascii=False))
