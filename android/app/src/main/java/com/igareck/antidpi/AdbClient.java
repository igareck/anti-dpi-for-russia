/* Copyright (C) 2026 Igor Krauch. SPDX-License-Identifier: GPL-3.0-or-later */
package com.igareck.antidpi;
import android.content.Context;
import android.os.Build;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.util.concurrent.TimeUnit;
import io.github.muntashirakon.adb.AbsAdbConnectionManager;

/** ADB is used only on this device and only for the fixed service bootstrap. */
final class AdbClient extends AbsAdbConnectionManager {
 private final AdbIdentity identity;
 AdbClient(Context context,boolean createIdentity)throws Exception{this(AdbIdentity.load(context,createIdentity));}
 AdbClient(AdbIdentity supplied){identity=supplied;setApi(Build.VERSION.SDK_INT);setHostAddress("127.0.0.1");setTimeout(12,TimeUnit.SECONDS);setThrowOnUnauthorised(true);}
 @Override protected PrivateKey getPrivateKey(){return identity.privateKey;}
 @Override protected Certificate getCertificate(){return identity.certificate;}
 @Override protected String getDeviceName(){return "Igareck_AntiDPI";}
 boolean connectLocal(int port)throws Exception{
  boolean connected=connect("127.0.0.1",port);
  if(connected&&!getAdbConnection().isTlsConnection()){disconnect();throw new java.io.IOException("Нужно TLS-подключение беспроводной отладки Android 11+");}
  return connected;
 }
}
