/* Copyright (C) 2026 Igor Krauch. SPDX-License-Identifier: GPL-3.0-or-later */
package com.igareck.antidpi;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.JSONArray;
/** Exact five historical QUIC recipes. No user-supplied Lua or arguments. */
public final class QuicProfiles {
    private static final String[][] ARGS={
        {"--payload=quic_initial","--lua-desync=send:ipfrag:ipfrag_pos_udp=8","--lua-desync=drop"},
        {"--payload=quic_initial","--lua-desync=fake:blob=fake_default_quic:repeats=1","--lua-desync=send:ipfrag:ipfrag_pos_udp=8","--lua-desync=drop"},
        {"--payload=quic_initial","--lua-desync=fake:blob=fake_default_quic:repeats=20"},
        {"--payload=quic_initial","--lua-desync=fake:blob=fake_default_quic:repeats=1"},
        {"--payload=quic_initial","--lua-desync=fake:blob=fake_default_quic:repeats=5"}
    };
    public static final String[] NAMES=new String[]{"QUIC · Q1 · IP-фрагментация, позиция 8","QUIC · Q2 · QUIC-фейк ×1 + IP-фрагментация 8","QUIC · Q3 · QUIC-фейк ×20","QUIC · Q4 · QUIC-фейк ×1","QUIC · Q5 · QUIC-фейк ×5"};
    private static final String[] HASHES=new String[]{"7015cb6adb7bb8d5103fdd517e56c56f3d6fdeb28db4379c21636617942ff566","6d6b68fe6a190bfa64a2351c13e00071e9cd3112344ae717534c768818fed24a","e1a0fdf6a95e4c92c9f900239c8f2b367aa648d06c0194b5a501e4fcd7ae4f62","4da854afa6c300ec458e807f81c4900e19d78867943eab25f8e12ac3672f6e6d","0c7447696349e47fd4d2b635e66f271a3aa541c46af86869d225aff2a4f06b08"};
    public static int index(String id)throws IOException {
        if(id==null||!id.matches("Q[1-5]"))throw new IOException("Неизвестный QUIC-профиль: "+id);
        return id.charAt(1)-'1';
    }
    public static List<String> args(String id)throws IOException {
        if("OFF".equals(id))return Collections.emptyList();
        return Collections.unmodifiableList(Arrays.asList(ARGS[index(id)].clone()));
    }
    public static String hash(String id)throws IOException {
        if("OFF".equals(id))return Catalogue.digest("[]".getBytes(StandardCharsets.UTF_8));
        int i=index(id);String actual=Catalogue.digest(new JSONArray(Arrays.asList(ARGS[i])).toString().getBytes(StandardCharsets.UTF_8));
        if(!HASHES[i].equals(actual))throw new IOException("Не совпал хеш QUIC-рецепта "+id);
        return actual;
    }
    public static String applied(boolean enabled,String id){return enabled?id:"выключен";}
    private QuicProfiles(){}
}
