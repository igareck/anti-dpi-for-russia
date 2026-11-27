/* Copyright (C) 2026 Igor Krauch. SPDX-License-Identifier: GPL-3.0-or-later */
package com.igareck.antidpi;
import java.net.URI;
import java.util.*;
import java.util.regex.Pattern;
/** Fixed public HTTPS targets; no arbitrary URL or executable input. */
public enum WebTarget {
 YOUTUBE("youtube","YouTube","https://www.youtube.com/","youtube.com","m.youtube.com"),
 DISCORD("discord","Discord","https://discord.com/","www.discord.com"),
 LINKEDIN("linkedin","LinkedIn","https://www.linkedin.com/","linkedin.com"),
 FACEBOOK("facebook","Facebook","https://www.facebook.com/","facebook.com","m.facebook.com","web.facebook.com"),
 X("x","X","https://x.com/","www.x.com","twitter.com","www.twitter.com","mobile.twitter.com"),
 THREADS("threads","Threads","https://www.threads.com/","threads.com","www.threads.net","threads.net");
 private static final Pattern LINKEDIN_LOCALE=Pattern.compile("[a-z]{2}\\.linkedin\\.com");
 public final String key,label,url,domain;
 private final Set<String> hosts;
 WebTarget(String key,String label,String url,String... aliases){
     this.key=key;this.label=label;this.url=url;
     String canonical=URI.create(url).getHost();
     domain=canonical.startsWith("www.")?canonical.substring(4):canonical;
     hosts=new HashSet<>(Arrays.asList(aliases));
     hosts.add(canonical);
 }
 static WebTarget fromKey(String key){
     for(WebTarget target:values())if(target.key.equals(key))return target;
     return null;
 }
 public boolean allows(URI u){
     if(!"https".equalsIgnoreCase(u.getScheme())||u.getUserInfo()!=null||u.getHost()==null)return false;
     String host=u.getHost().toLowerCase(Locale.ROOT);
     return hosts.contains(host)||(this==LINKEDIN&&LINKEDIN_LOCALE.matcher(host).matches());
 }
 boolean testsOrigin(){return this==FACEBOOK;}
 String probeUrl(){return testsOrigin()?url+"robots.txt":url;}
 boolean acceptsCompleteShortPage(){return this==X;}
 public String strategy(int n){return "TCP"+n;}
 public String resultsLabel(String text){
     if(testsOrigin())text=text.replace(" (HTTPS ×2)"," (pass)");
     return text.replaceAll("\\bT([1-9][0-9]{0,2})(?= \\((?:pass|unstable)\\))","TCP$1");
 }
}
