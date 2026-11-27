package com.igareck.antidpi;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import javax.crypto.*;
import org.json.*;

/** Production validation, bootstrap MAC and authenticated identity envelope. */
public final class ActivationHarness {
 static int checks;interface Call {void run()throws Exception;}
 static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
 static void reject(Call call,String message)throws Exception{boolean failed=false;try{call.run();}catch(Exception expected){failed=true;}check(failed,message);}
 public static void main(String[] args)throws Exception{
  check(AdbBootstrap.pairingCode("012345").equals("012345"),"Leading zero lost");check(AdbBootstrap.pairingCode("123 456").equals("123456"),"Displayed code groups rejected");
  for(String invalid:new String[]{"", "12345", "1234567", "12345a", "$(id)", "12\u0000345"})reject(()->AdbBootstrap.pairingCode(invalid),"Invalid code accepted");
  check(AdbBootstrap.port("",true)==0&&AdbBootstrap.port("1024",false)==1024&&AdbBootstrap.port("65535",false)==65535,"Port boundaries");
  check(AdbBootstrap.port("035556",false)==35556,"Leading-zero manual port rejected");
  for(String invalid:new String[]{"0","1023","65536","-1","1e4","192.168.0.2:5555","22;id"})reject(()->AdbBootstrap.port(invalid,false),"Invalid port accepted");
  reject(()->AdbBootstrap.quote("path\ncommand"),"Newline in script path accepted");
  String cap=WorkerChannel.token(),nonce=UUID.randomUUID().toString(),epoch=UUID.randomUUID().toString();String descriptor=new JSONObject().put("protocol",18).put("port",12345).put("epoch",epoch).put("pid",1234).put("application_id",AppIdentity.PACKAGE_NAME).toString();
  JSONObject good=AdbBootstrap.verify(cap,nonce,nonce,descriptor,AdbBootstrap.proof(cap,nonce,descriptor));check(good.getString("capability").equals(cap),"Authenticated descriptor missing private token");
  reject(()->AdbBootstrap.verify(cap,nonce,UUID.randomUUID().toString(),descriptor,AdbBootstrap.proof(cap,nonce,descriptor)),"Stale bootstrap nonce accepted");
  reject(()->AdbBootstrap.verify(cap,nonce,nonce,descriptor,"0".repeat(64)),"Forged bootstrap accepted");
  reject(()->AdbBootstrap.verify(cap,nonce,nonce,descriptor.replace("12345","23456"),AdbBootstrap.proof(cap,nonce,descriptor)),"Changed bootstrap endpoint accepted");
  for(String key:new String[]{"pid","protocol","application_id"}){
   JSONObject bad=new JSONObject(descriptor);bad.put(key,key.equals("pid")?-1:key.equals("protocol")?16:"other.package");String text=bad.toString();reject(()->AdbBootstrap.verify(cap,nonce,nonce,text,AdbBootstrap.proof(cap,nonce,text)),"Foreign worker descriptor accepted");
  }
  String script=AdbBootstrap.script("/data/app/quoted ' $(touch SHOULD_NOT_EXIST)/base.apk","/data/app/libs",10371,cap,nonce,true);
  String header=script.substring(0,script.indexOf('{'));check(!header.contains(cap)&&script.contains("wait \"$!\"")&&script.contains("<<'IGARECK_"),"Bootstrap leaked token to argv or did not wait for detached worker");
  check(script.contains("'\"'\"'")&&script.contains("/system/bin/toybox setsid -w /system/bin/app_process64"),"Shell quoting, detached wrapper or ABI selection failed");
  JSONObject body=new JSONObject(script.substring(script.indexOf('{'),script.indexOf('}')+1));check(body.getString("capability").equals(cap)&&body.getString("bootstrap_nonce").equals(nonce)&&body.getInt("protocol")==18,"Bootstrap stdin changed");
  KeyGenerator generator=KeyGenerator.getInstance("AES");generator.init(256);SecretKey key=generator.generateKey(),wrong=generator.generateKey();byte[] secret="private-rsa-key-material-fixture".getBytes(StandardCharsets.UTF_8);
  byte[] sealed=IdentityEnvelope.seal(key,"app-owner",secret),second=IdentityEnvelope.seal(key,"app-owner",secret);check(!Arrays.equals(sealed,second),"Identity encryption reused IV");check(!new String(sealed,StandardCharsets.ISO_8859_1).contains(new String(secret,StandardCharsets.UTF_8)),"Identity stored plaintext");
  check(Arrays.equals(secret,IdentityEnvelope.open(new ByteArrayInputStream(sealed),key,"app-owner")),"Persisted identity did not decrypt");
  reject(()->IdentityEnvelope.open(new ByteArrayInputStream(sealed),wrong,"app-owner"),"Wrong Android wrapping key accepted");reject(()->IdentityEnvelope.open(new ByteArrayInputStream(sealed),key,"other-owner"),"Foreign app associated data accepted");
  byte[] corrupt=sealed.clone();corrupt[corrupt.length-1]^=1;reject(()->IdentityEnvelope.open(new ByteArrayInputStream(corrupt),key,"app-owner"),"Corrupt private key envelope accepted");
  reject(()->IdentityEnvelope.open(new ByteArrayInputStream(Arrays.copyOf(sealed,sealed.length-1)),key,"app-owner"),"Truncated envelope accepted");
  byte[] extra=Arrays.copyOf(sealed,sealed.length+1);reject(()->IdentityEnvelope.open(new ByteArrayInputStream(extra),key,"app-owner"),"Trailing envelope data accepted");reject(()->IdentityEnvelope.seal(key,"app-owner",new byte[16001]),"Unbounded identity accepted");
  if(args.length>0){JSONObject fixture=new JSONObject().put("script",script).put("capability",cap).put("nonce",nonce);Files.writeString(Path.of(args[0]),fixture.toString());}
  System.out.println(new JSONObject().put("passed",true).put("checks",checks).put("scope","Production bootstrap validation/MAC and real AES-GCM envelope; Android Keystore provider and broadcast permission enforcement require device verification"));
 }
}
