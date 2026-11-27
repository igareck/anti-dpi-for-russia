/* Copyright (C) 2026 Igor Krauch. SPDX-License-Identifier: GPL-3.0-or-later */
package com.igareck.antidpi;
import java.io.*;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;

/** Bounded authenticated at-rest format, separate from the Android key provider. */
final class IdentityEnvelope {
 static byte[] seal(SecretKey key,String owner,byte[] plaintext)throws Exception{
  if(plaintext.length>16000)throw new IOException("Identity payload too large");
  Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key);cipher.updateAAD(owner.getBytes("UTF-8"));byte[] encrypted=cipher.doFinal(plaintext),iv=cipher.getIV();
  if(iv.length!=12)throw new IOException("Unexpected identity IV");ByteArrayOutputStream buffer=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(buffer);out.writeInt(1);out.writeInt(iv.length);out.write(iv);out.writeInt(encrypted.length);out.write(encrypted);return buffer.toByteArray();
 }
 static byte[] open(InputStream source,SecretKey key,String owner)throws Exception{
  DataInputStream in=new DataInputStream(source);if(in.readInt()!=1)throw new IOException("Unsupported identity format");int size=in.readInt();if(size!=12)throw new IOException("Invalid identity IV");byte[] iv=new byte[size];in.readFully(iv);size=in.readInt();if(size<16||size>16384)throw new IOException("Invalid identity payload");byte[] encrypted=new byte[size];in.readFully(encrypted);if(in.read()!=-1)throw new IOException("Unexpected identity data");
  Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key,new GCMParameterSpec(128,iv));cipher.updateAAD(owner.getBytes("UTF-8"));return cipher.doFinal(encrypted);
 }
 private IdentityEnvelope(){}
}
