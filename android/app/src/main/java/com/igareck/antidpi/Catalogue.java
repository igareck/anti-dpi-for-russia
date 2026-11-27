/* Copyright (C) 2026 Igor Krauch. SPDX-License-Identifier: GPL-3.0-or-later */
package com.igareck.antidpi;

import android.content.Context;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.json.*;

/** The desktop catalogue is an immutable, APK-owned allowlist, not user Lua input. */
public final class Catalogue {
    public static final String SHA256="6790030c69ab74255003e4c7e5a331edb3bb6d5dfac4efaf3bfb004dc358d029";
    public static final String GUARD_SHA256="314f436bdd9a0e93a857452019f0d2ddd8a40a43deec42a9357f6a9f35c96e9e";
    public static final String VERSION="20261023_public_v1";
    public static final String ASSET="nfqws2/catalogue.json";
    private static volatile Catalogue cached;
    private final List<Recipe> recipes;
    public static final class Recipe {
        public final int number,family;
        public final String name,chain,processing,hash,init,familyName;
        public final boolean needsTimestamps,needsGuard;
        public final List<String> args;
        private Recipe(JSONObject row,String familyName)throws JSONException {
            number=row.getInt("Number");family=row.getInt("Family");name=row.getString("Name");
            chain=row.getString("Chain");processing=row.getString("Processing");hash=row.getString("RecipeSHA256");
            init=row.isNull("Init")?null:row.getString("Init");this.familyName=familyName;
            needsTimestamps=row.getBoolean("NeedsTimestamps");
            JSONArray input=row.getJSONArray("Args");ArrayList<String> values=new ArrayList<>();boolean guard=false;
            for(int i=0;i<input.length();i++){
                String s=input.getString(i);values.add(s);
                if(s.startsWith("--lua-desync=fake:")&&s.contains(":blob=reasm_data")&&s.contains(":tls_mod="))guard=true;
            }
            args=Collections.unmodifiableList(values);needsGuard=guard;
        }
        public String id(){return "T"+number;}
        public String group(){return number<=45?"Golden":"Silver";}
    }
    private Catalogue(JSONObject input)throws JSONException,IOException {
        JSONObject info=input.getJSONObject("CatalogueInfo");
        JSONArray rows=input.getJSONArray("ProfileRows"),families=input.getJSONArray("FamilyRows");
        if(!VERSION.equals(info.getString("Version"))||rows.length()!=484||families.length()!=23||info.getInt("ProfileCount")!=484)
            throw new IOException("Некорректный состав TLS-каталога");
        Map<Integer,String> names=new HashMap<>();for(int i=0;i<families.length();i++){
            JSONObject f=families.getJSONObject(i);names.put(f.getInt("Number"),f.getString("Name"));
        }
        ArrayList<Recipe> list=new ArrayList<>();
        for(int i=0;i<rows.length();i++){
            JSONObject row=rows.getJSONObject(i);String name=names.get(row.getInt("Family"));
            if(row.getInt("Number")!=i+1||name==null||!row.getString("RecipeSHA256").matches("[0-9a-f]{64}"))
                throw new IOException("Нарушен номер или хеш рецепта "+(i+1));
            list.add(new Recipe(row,name));
        }
        recipes=Collections.unmodifiableList(list);
    }
    public static byte[] bytes(InputStream in)throws IOException {
        ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[16384];int n;
        while((n=in.read(buffer))!=-1){if(out.size()+n>2*1024*1024)throw new IOException("Слишком большой APK-каталог");out.write(buffer,0,n);}
        return out.toByteArray();
    }
    public static String digest(byte[] bytes){
        try{StringBuilder s=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(bytes))s.append(String.format(Locale.US,"%02x",b&255));return s.toString();}
        catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    public static Catalogue read(InputStream in)throws IOException,JSONException {
        byte[] bytes=bytes(in);if(!SHA256.equals(digest(bytes)))throw new IOException("Не совпал SHA-256 встроенного TLS-каталога");
        return new Catalogue(new JSONObject(new String(bytes,StandardCharsets.UTF_8)));
    }
    public static synchronized Catalogue load(Context c)throws IOException,JSONException {
        if(cached==null)try(InputStream in=c.getAssets().open(ASSET)){cached=read(in);}return cached;
    }
    public static synchronized Catalogue fromApk(String path)throws IOException,JSONException {
        if(cached==null)try(ZipFile apk=new ZipFile(path)){
            ZipEntry e=apk.getEntry("assets/"+ASSET);if(e==null)throw new IOException("В APK нет TLS-каталога");
            try(InputStream in=apk.getInputStream(e)){cached=read(in);}
        }
        return cached;
    }
    public List<Recipe> all(){return recipes;}
    public Recipe get(String id)throws IOException {
        id=TcpIds.canonical(id);
        if(id==null||!id.matches("T[1-9][0-9]{0,2}"))throw new IOException("Неизвестный пакетный профиль: "+id);
        int n=Integer.parseInt(id.substring(1));if(n<1||n>recipes.size())throw new IOException("Нет рецепта "+id);
        return recipes.get(n-1);
    }
    public static String knownHash(String id){
        Catalogue c=cached;if(c==null)return "";try{Recipe r=c.get(id);return r==null?"":r.hash;}catch(IOException e){return "";}
    }
    private Catalogue(){throw new AssertionError();}
}
