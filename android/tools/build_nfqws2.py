"""Build actual pinned nfqws2 + Lua + lwIP; no runtime or build-time downloads."""
from pathlib import Path
from concurrent.futures import ThreadPoolExecutor
import argparse,subprocess,os
ROOT=Path(__file__).resolve().parents[1]
def build(out,ndk=None,host=False,abi=None,sanitize=False):
 nfq=ROOT/'vendor/nfqws2/nfq2';lua=ROOT/'vendor/lua';lwip=ROOT/'vendor/lwip/src';native=ROOT/'native/nfqws2'
 mapping={'arm64-v8a':'aarch64-linux-android30','armeabi-v7a':'armv7a-linux-androideabi30','x86_64':'x86_64-linux-android30'}
 targets=[('host',None)] if host else [(abi,mapping[abi])] if abi else list(mapping.items())
 for arch,target in targets:
  compiler=Path(os.environ.get('CC','cc')) if host else ndk/'toolchains/llvm/prebuilt/linux-x86_64/bin/clang'
  objdir=out/'obj'/arch/'nfqws2';objdir.mkdir(parents=True,exist_ok=True)
  dest=out/'stage/lib'/arch;dest.mkdir(parents=True,exist_ok=True)
  common=[str(compiler),'-std=gnu99','-O1' if sanitize else '-O2','-g' if host else '-g0','-fPIE','-ffunction-sections','-fdata-sections','-fno-ident',
    '-DIGARECK_TAP','-DLUA_USE_LINUX','-DLUA_USE_READLINE=0','-Wall','-Wextra','-Wno-unused-parameter','-Wno-unused-function','-Wno-sign-compare','-Wno-unused-but-set-variable',
    '-I'+str(lua),'-I'+str(native),'-I'+str(nfq),'-I'+str(lwip/'include'),'-ffile-prefix-map='+str(ROOT)+'=.',
    '-D_DEFAULT_SOURCE']
  if target:common+=['--target='+target]
  if host:common+=['-DIGARECK_HOST_TEST','-I'+str(native/'hostcompat')]
  if sanitize:common+=['-fsanitize=address,undefined','-fno-omit-frame-pointer']
  sources=list(nfq.glob('*.c'))+list((nfq/'crypto').glob('*.c'))
  if not host:sources+=list((nfq/'andr').glob('*.c'))
  sources+=list(native.glob('*.c'))
  sources += [p for p in lua.glob('*.c') if p.name not in ('lua.c','luac.c','onelua.c','ltests.c')]
  sources += list((lwip/'core').glob('*.c'))+list((lwip/'core/ipv4').glob('*.c'))
  # All original upstream files are still present; unused platform paths are GC'd.
  def compile_one(pair):
   i,source=pair;obj=objdir/(str(i)+'_'+source.stem+'.o')
   extra=['-Dmain=nfqws2_main'] if source==nfq/'nfqws.c' else []
   command=common+extra+['-c',str(source),'-o',str(obj)]
   r=subprocess.run(command,capture_output=True,text=True)
   if r.returncode:raise RuntimeError(str(source.relative_to(ROOT))+'\n'+r.stderr)
   warning_file=obj.with_suffix('.warnings')
   if r.stderr:warning_file.write_text(r.stderr)
   elif warning_file.exists():warning_file.unlink()
   return obj
  with ThreadPoolExecutor(max_workers=max(1,int(os.environ.get("IGARECK_BUILD_JOBS","4")))) as pool:objects=list(pool.map(compile_one,enumerate(sources)))
  binary=dest/'libnfqws2.so';cmd=[str(compiler)]+(['--target='+target] if target else [])
  cmd+=['-pie','-Wl,--gc-sections','-Wl,-z,max-page-size=16384','-Wl,-z,common-page-size=16384',*map(str,objects),'-lz','-lm','-ldl']
  if host:cmd+=['-pthread','-Wl,-l:libcap.so.2']
  else:cmd+=['-llog']
  if sanitize:cmd+=['-fsanitize=address,undefined']
  subprocess.run(cmd+['-o',str(binary)],check=True)
  if not host:subprocess.run([str(compiler.with_name('llvm-strip')),'--strip-unneeded',str(binary)],check=True)
  print('BUILT',arch,'nfqws2 + Lua + lwIP',binary,flush=True)
if __name__=='__main__':
 p=argparse.ArgumentParser();p.add_argument('--host',action='store_true');p.add_argument('--sanitize',action='store_true');p.add_argument('--abi');p.add_argument('--ndk',type=Path);p.add_argument('--output',type=Path,default=ROOT/'build')
 a=p.parse_args();build(a.output,a.ndk,a.host,a.abi,a.sanitize)
