/* Copyright (C) 2026 Igor Krauch. SPDX-License-Identifier: GPL-3.0-or-later */
package com.igareck.antidpi;
import android.content.Context;
import android.os.Build;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.json.*;

public final class CatalogueHarness {
 static int checks;
 static void check(boolean b,String s){checks++;if(!b)throw new AssertionError(s);}
 public static void main(String[] args)throws Exception{
  Path assets=Paths.get(args[0]),output=Paths.get(args[1]);System.setProperty("igareck.test.assets",assets.toString());
  byte[] raw=Files.readAllBytes(assets.resolve(Catalogue.ASSET));Catalogue catalogue=Catalogue.read(new ByteArrayInputStream(raw));
  check(catalogue.all().size()==484,"catalogue size");
  byte[] bad=raw.clone();bad[bad.length-2]^=1;boolean rejected=false;try{Catalogue.read(new ByteArrayInputStream(bad));}catch(IOException e){rejected=true;}check(rejected,"tampered catalogue accepted");
  for(String invalid:Arrays.asList("T0","T00","T01","T485","T999","N-1","A7","T20;ls","T0/../20")){
   rejected=false;try{catalogue.get(invalid);}catch(IOException e){rejected=true;}check(rejected,"invalid ID accepted: "+invalid);
  }
  check(catalogue.get("N20").hash.equals(catalogue.get("T20").hash),"Legacy N ID migration");
  JSONArray commands=new JSONArray();int golden=0,silver=0,guards=0;
  for(int n=1;n<=484;n++){
   String id="T"+n;Catalogue.Recipe r=catalogue.get(id);List<String> command=PacketCommand.build(catalogue,"__BINARY__",assets.resolve("nfqws2").toFile(),"test-endpoint",id,"catalogue-host-check",1080,false);
   check(command.contains("--filter-tcp=1-65535"),"port scope");check(command.stream().noneMatch(s->s.startsWith("--wf-")),"desktop-only argument");
   if(r!=null){
    check(r.number==n&&command.subList(command.size()-r.args.size(),command.size()).equals(r.args),"recipe arguments changed: "+id);
    check(r.init==null||command.contains("--lua-init="+r.init),"missing Init: "+id);
    if(r.needsGuard){guards++;check(command.stream().anyMatch(s->s.endsWith("igareck-actual-tls.lua")),"guard missing");}
    if(r.number<=45)golden++;else silver++;
   }else check(command.contains("--reasm-disable")&&command.stream().noneMatch(s->s.startsWith("--lua-desync=")),"T0 affected packets");
   commands.put(new JSONObject().put("id",id).put("number",n).put("family",r==null?0:r.family).put("processing",r==null?"CONTROL":r.processing).put("recipe_sha256",r==null?"":r.hash).put("command",new JSONArray(command)));
  }
  JSONArray quicCommands=new JSONArray();
  JSONArray quicRows=new JSONObject(new String(Files.readAllBytes(assets.resolve("nfqws2/quic_catalogue.json")),StandardCharsets.UTF_8)).getJSONArray("profiles");
  check(quicRows.length()==5,"QUIC catalogue size");
  for(int q=1;q<=5;q++){
   String qid="Q"+q;JSONObject historical=quicRows.getJSONObject(q-1);
   check(historical.getString("candidate_id").equals(qid),"QUIC catalogue ordering");
   check(QuicProfiles.hash(qid).equals(historical.getString("recipe_sha256")),"QUIC hash differs from historical result");
   check(new JSONArray(QuicProfiles.args(qid)).toString().equals(historical.getJSONArray("args").toString()),"QUIC arguments differ from historical result");
   for(int n:new int[]{20,40,484}){
    String id="T"+n;Catalogue.Recipe recipe=catalogue.get(id);
    List<String> command=PacketCommand.build(catalogue,"__BINARY__",assets.resolve("nfqws2").toFile(),"test-endpoint",id,"quic-host-check",1080,false,true,qid);
    check(command.contains("--igareck-udp=1")&&command.contains("--igareck-quic="+qid),"UDP switch or independent Q missing");
    int split=command.indexOf("--new");
    // Internal Igareck switches are stripped before upstream parses profiles.
    check(command.subList(command.indexOf("--igareck-udp=1")-recipe.args.size(),command.indexOf("--igareck-udp=1")).equals(recipe.args),"TCP recipe changed when enabling UDP");
    check(command.contains("--filter-udp=1-65535")&&command.contains("--filter-l7=quic"),"QUIC filter scope");
    check(command.subList(command.size()-QuicProfiles.args(qid).size(),command.size()).equals(QuicProfiles.args(qid)),"Q recipe order changed");
    List<String> off=PacketCommand.build(catalogue,"__BINARY__",assets.resolve("nfqws2").toFile(),"test-endpoint",id,"quic-host-check",1080,false,false,qid);
    check(off.stream().noneMatch(x->x.startsWith("--igareck-udp=")||x.startsWith("--filter-udp=")),"disabled UDP created a QUIC branch");
    quicCommands.put(new JSONObject().put("id",id).put("q",qid).put("command",new JSONArray(command)));
   }
  }
  JSONArray discordCommands=new JSONArray();
  JSONArray discordRows=new JSONObject(new String(Files.readAllBytes(assets.resolve("nfqws2/discord_catalogue.json")),StandardCharsets.UTF_8)).getJSONArray("profiles");
  check(discordRows.length()==8,"Eight public Discord recipes");
  for(int d=0;d<=8;d++){
   String did="D"+d;
   if(d>0){JSONObject row=discordRows.getJSONObject(d-1);
    check(DiscordProfiles.hash(did).equals(row.getString("recipe_sha256")),"Discord catalogue hash");
    check(new JSONArray(DiscordProfiles.args(did)).toString().equals(row.getJSONArray("args").toString()),"Discord catalogue arguments");
   }else check(DiscordProfiles.args(did).isEmpty(),"Internal disabled token has an effect");
   for(int q=1;q<=5;q++){
    String qid="Q"+q;List<String> command=PacketCommand.build(catalogue,"__BINARY__",assets.resolve("nfqws2").toFile(),"test-endpoint","T20","discord-host-check",1080,false,true,qid,did);
    check(command.contains("--igareck-discord="+did),"Discord selection missing");
    int first=command.indexOf("--new"),last=command.lastIndexOf("--new");
    check(command.subList(first+3,first+3+QuicProfiles.args(qid).size()).equals(QuicProfiles.args(qid)),"Discord altered Q recipe");
    if(d==0)check(first==last,"D0 added a desync branch");
    else {check(command.contains("--filter-l7=stun,discord")&&command.contains("--payload=stun,discord_ip_discovery"),"Discord selector missing");
     check(command.subList(command.size()-DiscordProfiles.args(did).size(),command.size()).equals(DiscordProfiles.args(did)),"Discord recipe changed");}
    discordCommands.put(new JSONObject().put("id","T20").put("q",qid).put("d",did).put("command",new JSONArray(command)));
   }
  }
  for(int d=1;d<=8;d++){
   String did="D"+d;List<String> command=PacketCommand.build(catalogue,"__BINARY__",assets.resolve("nfqws2").toFile(),"test-endpoint","T20","discord-only-check",1080,false,true,"OFF",did);
   check(!command.contains("--filter-l7=quic")&&command.contains("--filter-l7=stun,discord"),"Discord-only mode added a Q profile");
   discordCommands.put(new JSONObject().put("id","T20").put("q","OFF").put("d",did).put("command",new JSONArray(command)));
  }
  for(String invalid:Arrays.asList("D9","D10","D11","D01","D-1","d1","D1;ls")){
   boolean invalidRejected=false;try{DiscordProfiles.args(invalid);}catch(IOException e){invalidRejected=true;}check(invalidRejected,"invalid Discord ID accepted");
  }
  check(golden==45&&silver==439,"group sizes");
  List<Profiles.Profile> profiles=Profiles.all(new Context());Set<String> favorites=new HashSet<>(Arrays.asList("T20","T1"));
  check(profiles.size()==484,"profile count");
  check(ProfileFilter.select(profiles,"GOLDEN","",favorites).size()==45,"Golden filter");
  check(ProfileFilter.select(profiles,"SILVER","",favorites).size()==439,"Silver filter");
  check(ProfileFilter.select(profiles,"FAVORITES","",favorites).size()==2,"favorites filter");
  check(ProfileFilter.select(profiles,"FAVORITES","",Collections.emptySet()).isEmpty(),"empty favorites");
  for(String query:Arrays.asList("20","T20","TCP20","№ 20"))check(ProfileFilter.select(profiles,"ALL",query,favorites).get(0).id.equals("T20"),"number search: "+query);
  check(ProfileFilter.select(profiles,"SILVER","484",favorites).get(0).id.equals("T484"),"last recipe search");
  check(ProfileFilter.select(profiles,"ALL","hostfakesplit",favorites).size()>0,"family search");
  check(ProfileFilter.select(profiles,"ALL","no-match-xyz",favorites).isEmpty(),"empty search");
  Profiles.Profile md5=Profiles.get(new Context(),"T23");
  check(md5.processing.equals("MD5")&&ProfileStatus.unavailable(md5).isEmpty(),"raw MD5 wrongly blocked by socket capability");
  ProfileStatus.init(new Context());Profiles.Profile n20=Profiles.get(new Context(),"T20"),n1=Profiles.get(new Context(),"T1");
  check(ProfileStatus.label(n20).isEmpty()&&ProfileStatus.label(n1).isEmpty(),"Stale experimental UI label");
  ProfileStatus.recordVpnSuccess(n20.id,n20.recipeHash,"fixture-node",1048576);check(ProfileStatus.label(n20).startsWith("Успешный VPN-замер"),"successful measurement not shown");
  String saved=Build.FINGERPRINT;Build.FINGERPRINT="different/firmware";check(!ProfileStatus.label(n20).startsWith("Успешный VPN-замер"),"firmware change reused proof");Build.FINGERPRINT=saved;
  Build.VERSION.SDK_INT=29;check(!ProfileStatus.unavailable(n20).isEmpty(),"Android <11 enabled packet mode");Build.VERSION.SDK_INT=35;
  check(new JSONArray(ProfileStatus.summary()).length()==1,"evidence export");
  JSONArray youtubeCommands=new JSONArray();
  for(int[] batch:new int[][]{ {1,20,40,6,12},{4,7,49,68,83} }){
   List<String> cmd=PacketCommand.youtube(catalogue,"__BINARY__",assets.resolve("nfqws2").toFile(),"test-endpoint","youtube-check",batch);
   for(int n:batch)check(cmd.contains("--filter-mark="+n),"YouTube isolation mark");
   youtubeCommands.put(new JSONObject().put("numbers",new JSONArray(batch)).put("command",new JSONArray(cmd)));
  }
  Files.write(output,new JSONObject().put("checks",checks).put("passed",true).put("profiles",484).put("catalogue_recipes",484).put("golden",golden).put("silver",silver).put("actual_tls_guard_recipes",guards).put("youtube_commands",youtubeCommands).put("commands",commands).put("quic_commands",quicCommands).put("discord_commands",discordCommands).toString(2).getBytes(StandardCharsets.UTF_8));
  System.out.println("Catalogue checks passed: "+checks+"; commands="+commands.length()+"; profiles="+profiles.size());
 }
}
