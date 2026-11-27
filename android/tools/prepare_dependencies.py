"""Verify vendored SDK artifacts and extract only their classes.jar for compilation."""
import pathlib,hashlib,json,zipfile
root=pathlib.Path(__file__).resolve().parents[1];out=root/'build/deps';out.mkdir(parents=True,exist_ok=True)
for old in out.glob('*.jar'):old.unlink()
for item in json.loads((root/'DEPENDENCIES.json').read_text()):
    path=root/item['file'] if 'source_artifact' in item else root/'deps'/item['file']
    assert hashlib.sha256(path.read_bytes()).hexdigest()==item['sha256'],path.name
    if 'source_artifact' in item:continue
    if path.suffix=='.aar':
        with zipfile.ZipFile(path) as z:
            (out/(path.stem+'.jar')).write_bytes(z.read('classes.jar'))
            for name in z.namelist():
                bits=name.split('/')
                if len(bits)==3 and bits[0]=='jni' and bits[1] in ('arm64-v8a','armeabi-v7a','x86_64') and bits[2].endswith('.so'):
                    target=root/'build/stage/lib'/bits[1]/bits[2];target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(z.read(name))
    else:(out/path.name).write_bytes(path.read_bytes())
print('Pinned SDK dependencies verified; no runtime downloads')
