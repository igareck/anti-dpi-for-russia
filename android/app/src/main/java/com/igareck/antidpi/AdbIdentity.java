/* Copyright (C) 2026 Igor Krauch. SPDX-License-Identifier: GPL-3.0-or-later */
package com.igareck.antidpi;
import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;
import android.util.Base64;
import java.io.*;
import java.math.BigInteger;
import java.security.*;
import java.security.cert.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.json.*;

/** Per-install ADB identity. Private RSA bytes are encrypted by Android Keystore. */
final class AdbIdentity {
 private static final String ALIAS=AppIdentity.PACKAGE_NAME+".adb.identity.aes.v1";
 final PrivateKey privateKey;final X509Certificate certificate;
 private AdbIdentity(PrivateKey key,X509Certificate certificate){this.privateKey=key;this.certificate=certificate;}
 static boolean exists(Context context){File base=new File(context.getFilesDir(),"adb_identity.enc");return base.isFile()||new File(base.getPath()+".bak").isFile();}
 private static SecretKey wrappingKey()throws Exception{
  KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);
  if(store.containsAlias(ALIAS))return (SecretKey)store.getKey(ALIAS,null);
  KeyGenerator generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
  generator.init(new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
   .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setRandomizedEncryptionRequired(true).build());
  return generator.generateKey();
 }
 static synchronized AdbIdentity load(Context context,boolean create)throws Exception{
  AtomicFile file=new AtomicFile(new File(context.getFilesDir(),"adb_identity.enc"));
  if(file.getBaseFile().isFile()||new File(file.getBaseFile().getPath()+".bak").isFile()){
   if(file.getBaseFile().length()>32768)throw new IOException("Invalid encrypted identity size");
   byte[] plaintext;
   try(InputStream in=file.openRead()){plaintext=IdentityEnvelope.open(in,wrappingKey(),ALIAS);}
   try{
    JSONObject value=new JSONObject(new String(plaintext,"UTF-8"));byte[] key=Base64.decode(value.getString("key"),Base64.NO_WRAP);
    PrivateKey privateKey;try{privateKey=KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(key));}finally{Arrays.fill(key,(byte)0);}
    X509Certificate certificate=(X509Certificate)CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(Base64.decode(value.getString("certificate"),Base64.NO_WRAP)));
    certificate.verify(certificate.getPublicKey());
    if(!((java.security.interfaces.RSAPrivateKey)privateKey).getModulus().equals(((java.security.interfaces.RSAPublicKey)certificate.getPublicKey()).getModulus()))throw new IOException("Identity key/certificate mismatch");
    return new AdbIdentity(privateKey,certificate);
   }finally{Arrays.fill(plaintext,(byte)0);}
  }
  if(!create)throw new IOException("Сначала выполни сопряжение ADB");
  AdbIdentity identity=generate();
  byte[] plaintext=new JSONObject().put("key",Base64.encodeToString(identity.privateKey.getEncoded(),Base64.NO_WRAP)).put("certificate",Base64.encodeToString(identity.certificate.getEncoded(),Base64.NO_WRAP)).toString().getBytes("UTF-8");
  FileOutputStream raw=null;
  try{
   byte[] envelope=IdentityEnvelope.seal(wrappingKey(),ALIAS,plaintext);
   raw=file.startWrite();raw.write(envelope);raw.flush();file.finishWrite(raw);raw=null;
  }finally{Arrays.fill(plaintext,(byte)0);if(raw!=null)file.failWrite(raw);}
  return identity;
 }
 static AdbIdentity generate()throws Exception{
  KeyPairGenerator generator=KeyPairGenerator.getInstance("RSA");generator.initialize(2048,new SecureRandom());java.security.KeyPair pair=generator.generateKeyPair();
  long now=System.currentTimeMillis();X500Name name=new X500Name("CN=Igareck AntiDPI ADB");
  X509Certificate certificate=new JcaX509CertificateConverter().getCertificate(new JcaX509v3CertificateBuilder(name,new BigInteger(128,new SecureRandom()),new Date(now-86400000L),new Date(now+315360000000L),name,pair.getPublic()).build(new JcaContentSignerBuilder("SHA256withRSA").build(pair.getPrivate())));
  certificate.verify(pair.getPublic());
  return new AdbIdentity(pair.getPrivate(),certificate);
 }
}
