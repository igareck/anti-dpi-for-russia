/* Copyright (C) 2026 Igor Krauch. SPDX-License-Identifier: GPL-3.0-or-later */
package com.igareck.antidpi;
import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.*;
import java.io.*;
import java.nio.charset.StandardCharsets;

/** A single pending fixed bootstrap script, readable only by the Android shell UID. */
public final class UsbActivationProvider extends ContentProvider {
 @Override public boolean onCreate(){return true;}
 @Override public ParcelFileDescriptor openFile(Uri uri,String mode)throws FileNotFoundException{
  if(Binder.getCallingUid()!=2000||!"r".equals(mode)||!"/start".equals(uri.getPath()))throw new SecurityException("USB bootstrap requires shell UID 2000");
  try{
   String script=EmbeddedAdb.usbScript();ParcelFileDescriptor[] pipe=ParcelFileDescriptor.createPipe();
   new Thread(()->{try(OutputStream out=new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])){out.write(script.getBytes(StandardCharsets.UTF_8));}catch(IOException ignored){}},"adb-usb-script").start();return pipe[0];
  }catch(Exception e){throw new FileNotFoundException("Сначала нажми «Запуск через USB» в Igareck; одноразовая команда действует во время ожидания.");}
 }
 @Override public String getType(Uri uri){return "text/x-shellscript";}
 @Override public Cursor query(Uri uri,String[] projection,String selection,String[] args,String order){throw new UnsupportedOperationException();}
 @Override public Uri insert(Uri uri,ContentValues values){throw new UnsupportedOperationException();}
 @Override public int delete(Uri uri,String selection,String[] args){throw new UnsupportedOperationException();}
 @Override public int update(Uri uri,ContentValues values,String selection,String[] args){throw new UnsupportedOperationException();}
}
