/* Copyright (C) 2026 Igor Krauch. SPDX-License-Identifier: GPL-3.0-or-later */
package com.igareck.antidpi;

import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Process;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructPollfd;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.function.Consumer;
import org.json.*;

/** A local, UID-authenticated Stop-only path; independent of ADB. */
final class PacketControl {
    interface Stop { String run(String instance)throws Exception; }
    static String canonical(String value)throws IOException {
        try{if(value!=null&&UUID.fromString(value).toString().equals(value))return value;}
        catch(IllegalArgumentException ignored){}
        throw new IOException("Invalid control identity");
    }
    static String name(String instance)throws IOException{return "igareck-stop-"+canonical(instance);}
    private static String line(InputStream in,int limit)throws IOException {
        ByteArrayOutputStream out=new ByteArrayOutputStream();int b;
        while((b=in.read())!=-1){if(b=='\n')return new String(out.toByteArray(),StandardCharsets.UTF_8);
            if(out.size()>=limit)throw new IOException("Control message exceeds limit");out.write(b);}
        throw new EOFException("Control channel closed before acknowledgement");
    }
    private static void send(LocalSocket socket,JSONObject value)throws IOException {
        byte[] bytes=(value.toString()+"\n").getBytes(StandardCharsets.UTF_8);
        socket.getOutputStream().write(bytes);socket.getOutputStream().flush();
    }
    static JSONObject stop(String instance,String epoch)throws Exception {
        canonical(instance);canonical(epoch);String nonce=UUID.randomUUID().toString();
        try(LocalSocket socket=new LocalSocket()){
            socket.connect(new LocalSocketAddress(name(instance),LocalSocketAddress.Namespace.ABSTRACT));
            socket.setSoTimeout(10000);
            if(socket.getPeerCredentials().getUid()!=2000)throw new SecurityException("Unexpected Stop helper UID");
            send(socket,new JSONObject().put("op","stop").put("instance",instance).put("service_epoch",epoch).put("nonce",nonce));
            JSONObject reply=new JSONObject(line(socket.getInputStream(),65536));
            if(!nonce.equals(reply.optString("control_nonce")))throw new IOException("Stop acknowledgement nonce mismatch");
            return reply;
        }
    }
    static final class Server implements AutoCloseable {
        private final LocalServerSocket server;
        private final int appUid;
        private final String instance,epoch;
        private final Stop stop;
        private final Consumer<String> logger;
        private volatile boolean closed;
        Server(int appUid,String instance,String epoch,Stop stop)throws Exception {
            this(appUid,instance,epoch,stop,message->AppLog.line("PACKET_CONTROL",message));
        }
        Server(int appUid,String instance,String epoch,Stop stop,Consumer<String> logger)throws Exception {
            this.appUid=appUid;this.instance=canonical(instance);this.epoch=canonical(epoch);this.stop=stop;
            this.logger=logger;
            if(Process.myUid()!=2000)throw new SecurityException("Stop helper must use shell UID 2000");
            server=new LocalServerSocket(name(instance));
            Thread thread=new Thread(this::serve,"packet-stop-control");thread.setDaemon(true);thread.start();
        }
        private void serve(){
            while(!closed){try{
                StructPollfd fd=new StructPollfd();fd.fd=server.getFileDescriptor();fd.events=(short)OsConstants.POLLIN;
                if(Os.poll(new StructPollfd[]{fd},250)<=0)continue;
                if(closed)return;
                try(LocalSocket peer=server.accept()){
                    if(peer.getPeerCredentials().getUid()!=appUid)continue;
                    peer.setSoTimeout(3000);JSONObject request=new JSONObject(line(peer.getInputStream(),1024));
                    if(!"stop".equals(request.optString("op"))||!instance.equals(request.optString("instance"))||!epoch.equals(request.optString("service_epoch")))continue;
                    String nonce=canonical(request.getString("nonce"));
                    JSONObject reply=new JSONObject(stop.run(instance));reply.put("control_nonce",nonce);send(peer,reply);
                }
            }catch(Exception e){if(!closed)logger.accept("Local Stop request unavailable: "+e.getClass().getSimpleName());}}
        }
        public void close(){closed=true;try{server.close();}catch(IOException ignored){}}
    }
    private PacketControl(){}
}
