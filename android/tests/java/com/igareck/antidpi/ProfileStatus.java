package com.igareck.antidpi;
/** Persistence boundary only; actual ProfileStatus is tested in CatalogueHarness. */
public final class ProfileStatus {public static int records;public static String hash="";public static void recordVpnSuccess(String p,String h,String n,long bytes){records++;hash=h;}}
