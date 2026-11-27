"""Assemble compiled resources, DEX and native executables; no local metadata."""
import pathlib, sys, zipfile
out=pathlib.Path(sys.argv[1])
entries={}
with zipfile.ZipFile(out/'resources.apk') as src:
    for n in src.namelist():
        if not n.endswith('/'): entries[n]=src.read(n)
for p in sorted((out/'dex').glob('*.dex')):entries[p.name]=p.read_bytes()
for abi in ('arm64-v8a','armeabi-v7a','x86_64'):
    for library in ('libnfqws2.so','libspake2.so','libconscrypt_jni.so'):
        p=out/'stage'/'lib'/abi/library
        if not p.is_file(): raise RuntimeError('Missing native backend: '+str(p))
        entries[p.relative_to(out/'stage').as_posix()]=p.read_bytes()
with zipfile.ZipFile(out/'unsigned.apk','w',compression=zipfile.ZIP_DEFLATED,compresslevel=9) as dest:
    for name, data in sorted(entries.items()):
        z=zipfile.ZipInfo(name,(2026,11,6,0,0,0))
        # Android 11+ rejects targetSdk >= 30 APKs with compressed resource tables.
        # zipalign in build.sh subsequently aligns this stored entry to 4 bytes.
        z.compress_type=zipfile.ZIP_STORED if name=='resources.arsc' else zipfile.ZIP_DEFLATED
        z.create_system=3;z.external_attr=(0o100644 << 16)
        dest.writestr(z,data)
