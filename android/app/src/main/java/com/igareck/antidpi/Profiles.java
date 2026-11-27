/* Copyright (C) 2026 Igor Krauch. SPDX-License-Identifier: GPL-3.0-or-later */
package com.igareck.antidpi;
import android.content.Context;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
/** Only the 484 public packet recipes are exposed by the application. */
public final class Profiles {
    public static final class Profile {
        public final String id,title,description,engine,library,group,family,recipeHash,processing;
        public final int recipeNumber;
        Profile(JSONObject o)throws JSONException {
            id=o.getString("id");title=o.getString("title_ru");description=o.getString("description_ru");
            group=o.getString("group");family=o.getString("family");recipeHash=o.getString("recipe_sha256");
            recipeNumber=o.getInt("recipe_number");processing=o.getString("processing");
            engine=o.getString("engine");library="libnfqws2.so";
            if(!engine.equals("nfqws2")||!id.equals("T"+recipeNumber)||recipeNumber<1||recipeNumber>484)
                throw new IllegalArgumentException("Unknown public recipe");
        }
        public String toString(){return id+" · "+title;}
    }
    private static List<Profile> profiles;
    public static synchronized List<Profile> all(Context c)throws IOException,JSONException {
        if(profiles==null){
            ByteArrayOutputStream out=new ByteArrayOutputStream();
            try(InputStream in=c.getAssets().open("profiles.json")){AppLog.copy(in,out);}
            JSONArray rows=new JSONObject(new String(out.toByteArray(),StandardCharsets.UTF_8)).getJSONArray("profiles");
            if(rows.length()!=484)throw new IOException("Нарушен состав 484 публичных стратегий");
            Catalogue catalogue=Catalogue.load(c);ArrayList<Profile> list=new ArrayList<>();
            for(int i=0;i<rows.length();i++){
                Profile p=new Profile(rows.getJSONObject(i));Catalogue.Recipe recipe=catalogue.get(p.id);
                if(p.recipeNumber!=i+1||!recipe.hash.equals(p.recipeHash)||!recipe.group().equals(p.group))
                    throw new IOException("Не совпал публичный рецепт "+p.id);
                list.add(p);
            }
            profiles=Collections.unmodifiableList(list);
        }
        return profiles;
    }
    public static Profile get(Context c,String id)throws Exception{
        id=TcpIds.canonical(id);
        for(Profile p:all(c))if(p.id.equals(id))return p;
        throw new IllegalArgumentException("Неизвестная публичная стратегия: "+id);
    }
    public static void validate(int port){if(port<1024||port>65535)throw new IllegalArgumentException("Порт должен быть от 1024 до 65535");}
    private Profiles(){}
}
