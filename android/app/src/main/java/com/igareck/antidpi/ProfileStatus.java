/* Copyright (C) 2026 Igor Krauch. SPDX-License-Identifier: GPL-3.0-or-later */
package com.igareck.antidpi;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import org.json.*;
import java.util.*;

/** Local measurement evidence, bound to this version, firmware and recipe hash. */
public final class ProfileStatus {
    private static volatile SharedPreferences storage;
    public static void init(Context c){storage=c.getSharedPreferences("packet_profile_evidence",Context.MODE_PRIVATE);}
    private static String key(String id,String hash){return AppLog.VERSION+"|"+Build.FINGERPRINT+"|"+id+"|"+hash;}
    public static String unavailable(Profiles.Profile p){
        if(p.engine.equals("nfqws2")&&Build.VERSION.SDK_INT<30)return "Для nfqws2/TAP нужен Android 11 или новее";
        return "";
    }
    public static void recordVpnSuccess(String id,String hash,String node,long bytes){
        SharedPreferences s=storage;if(s==null||hash.isEmpty())return;
        try{s.edit().putString(key(id,hash),new JSONObject().put("profile",id).put("recipe_sha256",hash).put("version",AppLog.VERSION)
            .put("model",Build.MODEL).put("android_sdk",Build.VERSION.SDK_INT).put("node_label",node).put("bytes",bytes).put("time_epoch_ms",System.currentTimeMillis()).toString()).apply();}
        catch(JSONException e){AppLog.error("Save profile evidence",e);}
    }
    public static String label(Profiles.Profile p){
        String blocked=unavailable(p);if(!blocked.isEmpty())return "Недоступен: "+blocked;
        SharedPreferences s=storage;
        if(s!=null){String value=s.getString(key(p.id,p.recipeHash),"");if(!value.isEmpty())try{
            JSONObject e=new JSONObject(value);String node=e.optString("node_label");
            String time=new java.text.SimpleDateFormat("dd.MM HH:mm",Locale.US).format(new Date(e.getLong("time_epoch_ms")));
            return "Успешный VPN-замер на этом устройстве · "+time+(node.isEmpty()?"":" · "+node);
        }catch(JSONException ignored){}}
        return "";
    }
    public static String summary(){
        JSONArray list=new JSONArray();SharedPreferences s=storage;if(s==null)return list.toString();
        String prefix=AppLog.VERSION+"|"+Build.FINGERPRINT+"|";
        for(Map.Entry<String,?> e:s.getAll().entrySet())if(e.getKey().startsWith(prefix)&&e.getValue() instanceof String)try{list.put(new JSONObject((String)e.getValue()));}catch(JSONException ignored){}
        return list.toString();
    }
    private ProfileStatus(){}
}
