package com.igareck.antidpi;
import java.io.*;import java.nio.file.*;import java.security.*;import java.security.cert.*;import javax.net.ssl.*;
/** Verified fixture certificate only; production always uses system TLS trust. */
public final class WebsiteNativeClient {
 public static void main(String[] args)throws Exception{
  CertificateFactory cf=CertificateFactory.getInstance("X.509");java.security.cert.Certificate cert;try(InputStream in=Files.newInputStream(Paths.get(args[2]))){cert=cf.generateCertificate(in);}
  KeyStore store=KeyStore.getInstance(KeyStore.getDefaultType());store.load(null,null);store.setCertificateEntry("fixture",cert);TrustManagerFactory tm=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());tm.init(store);SSLContext ctx=SSLContext.getInstance("TLS");ctx.init(null,tm.getTrustManagers(),new SecureRandom());
  WebTarget target=WebTarget.valueOf(args[1]);
  String url=args.length==5?java.net.URI.create(target.url).resolve(args[4]).toString():target.probeUrl();
  YoutubeProbe.Result result=YoutubeProbe.probe(Integer.parseInt(args[0]),new YoutubeProbe.Cancellation(),5000,url,ctx.getSocketFactory(),target,
      (host,cancel,deadline)->java.net.InetAddress.getByName(args[3]).getAddress());
  System.out.println(result.json());if(args.length==4&&!result.pass)System.exit(1);
 }
}
