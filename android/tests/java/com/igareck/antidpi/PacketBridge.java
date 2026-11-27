package com.igareck.antidpi;
/** HTTP-only fixture. Explicit metrics scripts exercise evidence boundaries. */
public final class PacketBridge {
 public static String fixtureInstance="";private static int ticks;
 public static org.json.JSONObject freshMetrics(){try{return fixtureInstance.isEmpty()?new org.json.JSONObject():new org.json.JSONObject().put("instance",fixtureInstance).put("input_out",++ticks).put("input_in",ticks);}catch(Exception e){throw new IllegalStateException(e);}}
}
