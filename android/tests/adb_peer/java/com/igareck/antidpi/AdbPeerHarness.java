package com.igareck.antidpi;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.security.cert.*;
import java.security.spec.*;
import java.lang.reflect.*;
import java.util.*;
import io.github.muntashirakon.adb.AdbStream;
import org.json.*;

/** Production AdbClient/libadb/JNI against a separate OpenSSL/AOSP-shaped peer. */
public final class AdbPeerHarness {
 static AdbIdentity identity(Path folder)throws Exception{
  Files.createDirectories(folder);Path key=folder.resolve("fixture-private.der"),cert=folder.resolve("fixture-cert.der");
  if(!Files.exists(key)){AdbIdentity i=AdbIdentity.generate();Files.write(key,i.privateKey.getEncoded());Files.write(cert,i.certificate.getEncoded());return i;}
  PrivateKey privateKey=KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(Files.readAllBytes(key)));X509Certificate certificate=(X509Certificate)CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(Files.readAllBytes(cert)));Constructor<AdbIdentity> c=AdbIdentity.class.getDeclaredConstructor(PrivateKey.class,X509Certificate.class);c.setAccessible(true);return c.newInstance(privateKey,certificate);
 }
 public static void main(String[] args)throws Exception{
  String mode=args[0];int port=Integer.parseInt(args[1]);AdbIdentity identity=identity(Path.of(args[2]));String providers=Arrays.toString(Security.getProviders());boolean rejected=false;
  try(AdbClient client=new AdbClient(identity)){
   if(!client.getHostAddress().equals("127.0.0.1")||client.getTimeout()!=12000)throw new AssertionError("Production client escaped local/timeout constraints");
   if(mode.equals("pair")||mode.equals("wrong-code")||mode.equals("bad-point")){
    try{if(!client.pair("127.0.0.1",port,mode.equals("wrong-code")?"654321":"123456"))throw new AssertionError("Pairing false");}
    catch(Exception expected){if(mode.equals("pair"))throw expected;rejected=true;}
    if(!mode.equals("pair")&&!rejected)throw new AssertionError("Invalid pairing accepted");
   }else if(mode.equals("plain-rejected")){
    try{client.connectLocal(port);}catch(Exception expected){rejected=true;}
    if(!rejected)throw new AssertionError("Plain ADB accepted");
   }else{
    if(!client.connectLocal(port))throw new AssertionError("TLS ADB connect false");
    try(AdbStream stream=client.openStream("exec:sh")){
     String script=AdbBootstrap.script("/data/app/test/base.apk","/data/app/test/lib/arm64",10371,WorkerChannel.token(),UUID.randomUUID().toString(),true);OutputStream out=stream.openOutputStream();out.write(script.getBytes("UTF-8"));out.flush();
     InputStream input=stream.openInputStream();byte[] b=new byte[128];int count=input.read(b);if(count<=0||!new String(b,0,count,"UTF-8").contains("IGARECK_FIXTURE_OK"))throw new AssertionError("ADB stream failed");
    }
   }
  }
  if(!providers.equals(Arrays.toString(Security.getProviders())))throw new AssertionError("ADB altered global TLS/crypto providers");
  System.out.println(new JSONObject().put("passed",true).put("mode",mode).put("invalid_pairing_rejected",rejected).put("global_tls_providers_unchanged",true));
 }
}
