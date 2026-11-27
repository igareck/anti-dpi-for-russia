/* Copyright (C) 2026 Igor Krauch. SPDX-License-Identifier: GPL-3.0-or-later */
package com.igareck.antidpi;
import java.io.*;
import java.util.*;

/** Shared command constructor. Desktop Init and Args are appended byte-for-byte. */
public final class PacketCommand {
    public static List<String> build(Catalogue catalogue,String executable,File work,String endpoint,String id,String instance,int port,boolean verbose)throws IOException {
        return build(catalogue,executable,work,endpoint,id,instance,port,verbose,false,"Q1");
    }
    public static List<String> build(Catalogue catalogue,String executable,File work,String endpoint,String id,String instance,int port,boolean verbose,boolean udp,String quic)throws IOException {
        return build(catalogue,executable,work,endpoint,id,instance,port,verbose,udp,quic,"D0");
    }
    public static List<String> build(Catalogue catalogue,String executable,File work,String endpoint,String id,String instance,int port,boolean verbose,boolean udp,String quic,String discord)throws IOException {
        QuicProfiles.hash(quic);DiscordProfiles.hash(discord);
        Catalogue.Recipe recipe=catalogue.get(id);ArrayList<String> command=new ArrayList<>();command.add(executable);
        Collections.addAll(command,"--igareck-socket="+endpoint,"--igareck-profile="+id,"--igareck-instance="+instance,
            "--igareck-port="+port,"--qnum=0","--debug="+(verbose?1:0),
            "--lua-init=@"+new File(work,"zapret-lib.lua"),"--lua-init=@"+new File(work,"zapret-antidpi.lua"));
        if(recipe.needsGuard)command.add("--lua-init=@"+new File(work,"igareck-actual-tls.lua"));
        if(recipe.init!=null)command.add("--lua-init="+recipe.init);
        // SOCKS owns the external connection; no WinDivert/interface arguments are used.
        command.add("--filter-tcp=1-65535");
        command.addAll(recipe.args);
        if(udp){
            Collections.addAll(command,"--igareck-udp=1","--igareck-quic="+quic,"--igareck-discord="+discord);
            if(!"OFF".equals(quic)){
                Collections.addAll(command,"--new","--filter-udp=1-65535","--filter-l7=quic");
                command.addAll(QuicProfiles.args(quic));
            }
            if(!"D0".equals(discord)){
                Collections.addAll(command,"--new","--filter-udp=1-65535","--filter-l7=stun,discord","--out-range=-d10","--payload=stun,discord_ip_discovery");
                command.addAll(DiscordProfiles.args(discord));
            }
        }
        return Collections.unmodifiableList(command);
    }
    public static List<String> youtube(Catalogue catalogue,String executable,File work,String endpoint,String instance,int[] numbers)throws IOException {
        if(numbers.length<1||numbers.length>5)throw new IOException("Число параллелей: 1–5");
        ArrayList<String> command=new ArrayList<>();command.add(executable);
        Collections.addAll(command,"--igareck-socket="+endpoint,"--igareck-profile=YOUTUBE","--igareck-instance="+instance,
            "--igareck-port=0","--qnum=0","--debug=0","--lua-init=@"+new File(work,"zapret-lib.lua"),
            "--lua-init=@"+new File(work,"zapret-antidpi.lua"),"--lua-init=@"+new File(work,"igareck-actual-tls.lua"));
        StringBuilder ids=new StringBuilder();Set<Integer> seen=new HashSet<>();
        for(int n:numbers){
            if(!seen.add(n))throw new IOException("Повтор стратегии");
            Catalogue.Recipe recipe=catalogue.get("T"+n);
            if(ids.length()>0)ids.append(',');ids.append(n);
            // Each profile's generated TLS pattern has its own Lua name.
            if(recipe.init!=null)command.add("--lua-init="+recipe.init.replace("public_tls_pattern","public_tls_pattern_T"+n));
        }
        command.add("--igareck-probes="+ids);
        for(int i=0;i<numbers.length;i++){
            int n=numbers[i];Catalogue.Recipe recipe=catalogue.get("T"+n);
            if(i>0)command.add("--new");
            Collections.addAll(command,"--filter-tcp=1-65535","--filter-mark="+n);
            for(String arg:recipe.args)command.add(arg.replace("public_tls_pattern","public_tls_pattern_T"+n));
        }
        return Collections.unmodifiableList(command);
    }
    private PacketCommand(){}
}
