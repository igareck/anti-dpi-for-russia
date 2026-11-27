#!/usr/bin/env python3
"""Rebuild pinned LGPL SPAKE2 JNI from bundled source for Android or host checks."""
import argparse,pathlib,subprocess
p=argparse.ArgumentParser();p.add_argument('--ndk',type=pathlib.Path);p.add_argument('--host',action='store_true');p.add_argument('--jni-include',type=pathlib.Path);p.add_argument('--output',type=pathlib.Path,default=pathlib.Path('build'));a=p.parse_args()
r=pathlib.Path(__file__).resolve().parents[1];src=r/'vendor/spake2-java/android/src/main/cpp';out=a.output.resolve()
targets={'host':None} if a.host else {'arm64-v8a':'aarch64-linux-android30','armeabi-v7a':'armv7a-linux-androideabi30','x86_64':'x86_64-linux-android30'}
for abi,triple in targets.items():
 obj=out/'spake2-obj'/abi;obj.mkdir(parents=True,exist_ok=True);dest=out/'stage/lib'/abi/'libspake2.so';dest.parent.mkdir(parents=True,exist_ok=True)
 if a.host:
  if not a.jni_include:p.error('--host requires --jni-include pointing to JDK include')
  cc,cxx='cc','c++';extra=['-D_DEFAULT_SOURCE','-I'+str(a.jni_include.resolve()),'-I'+str(a.jni_include.resolve()/'linux')];link=[]
 else:
  if not a.ndk:p.error('Android build requires --ndk')
  bin=a.ndk.resolve()/'toolchains/llvm/prebuilt/linux-x86_64/bin';cc=str(bin/(triple+'-clang'));cxx=str(bin/(triple+'-clang++'));extra=['-fvisibility=hidden'];link=['-nostdlib++','-Wl,-z,max-page-size=16384','-Wl,-z,common-page-size=16384']
 common=['-O2','-fPIC','-ffile-prefix-map='+str(r)+'=.','-I'+str(src/'spake2-c/include'),*extra];objects=[]
 for index,file in enumerate([src/'spake2-c/sha512.c',src/'spake2-c/spake2.c',src/'spake2_jni.cpp']):
  target=obj/(str(index)+'.o');cpp=file.suffix=='.cpp';subprocess.run([cxx if cpp else cc,*common,*(['-std=c++17','-fno-exceptions','-fno-rtti'] if cpp else ['-std=c11']),'-c',str(file),'-o',str(target)],check=True);objects.append(str(target))
 subprocess.run([cxx,'-shared',*link,*objects,'-o',str(dest)],check=True)
 print('BUILT',abi,'SPAKE2 JNI',dest,flush=True)
