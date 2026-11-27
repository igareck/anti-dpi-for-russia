/* Copyright (C) 2026 Igor Krauch. SPDX-License-Identifier: GPL-3.0-or-later */
package com.igareck.antidpi;
import java.util.*;
/** Pure selection logic, shared by the UI and host regression checks. */
public final class ProfileFilter {
    public static List<Profiles.Profile> select(List<Profiles.Profile> all,String group,String query,Set<String> favorites){
        return select(all,group,query,favorites,profile->"");
    }
    static List<Profiles.Profile> select(List<Profiles.Profile> all,String group,String query,
            Set<String> favorites,java.util.function.Function<Profiles.Profile,String> displayText){
        String q=query.trim().toLowerCase(Locale.ROOT);String number=q.replace("№","").replace(" ","");
        if(number.startsWith("tcp"))number="t"+number.substring(3);
        String exact=number.matches("[0-9]{1,3}")?"t"+Integer.parseInt(number):number.matches("[ntт][0-9]{1,3}")?"t"+number.substring(1):null;
        ArrayList<Profiles.Profile> result=new ArrayList<>();
        for(Profiles.Profile p:all){
            if(group.equals("GOLDEN")&&!p.group.equals("Golden"))continue;
            if(group.equals("SILVER")&&!p.group.equals("Silver"))continue;
            if(group.equals("FAVORITES")&&!favorites.contains(p.id))continue;
            if(exact!=null){if(!p.id.equalsIgnoreCase(exact))continue;}
            else if(!q.isEmpty()&&!(p.id+" "+p.title+" "+p.family+" "+p.processing+" "+displayText.apply(p)).toLowerCase(Locale.ROOT).contains(q))continue;
            result.add(p);
        }
        return result;
    }
    private ProfileFilter(){}
}
