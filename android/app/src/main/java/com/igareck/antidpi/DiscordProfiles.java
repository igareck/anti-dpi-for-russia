/* Copyright (C) 2026 Igor Krauch. SPDX-License-Identifier: GPL-3.0-or-later */
package com.igareck.antidpi;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.JSONArray;
/** Public D1–D8 recipes; D0 is only the internal disabled-state token. */
public final class DiscordProfiles {
    private static final String[][] ARGS={
        {},
        {"--lua-desync=fake:blob=0x00000000000000000000000000000000:repeats=2"},
        {"--lua-desync=fake:blob=0x00000000000000000000000000000000:repeats=6"},
        {"--lua-desync=fake:blob=0x00000000000000000000000000000000:repeats=12"},
        {"--lua-desync=fake:blob=0x00000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000:repeats=2"},
        {"--lua-desync=fake:blob=0x00000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000:repeats=6"},
        {"--lua-desync=fake:blob=fake_default_quic:repeats=2"},
        {"--lua-desync=fake:blob=fake_default_quic:repeats=6"},
        {"--lua-desync=fake:blob=0x00000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000:ip_ttl=2:repeats=6"}
    };
    public static final String[] NAMES=new String[]{"D1 · нули 16 Б ×2","D2 · нули 16 Б ×6","D3 · нули 16 Б ×12","D4 · нули 64 Б ×2","D5 · нули 64 Б ×6","D6 · QUIC-фейк ×2","D7 · QUIC-фейк ×6","D8 · нули 64 Б · TTL 2 · ×6"};
    private static final String[] HASHES=new String[]{"4f53cda18c2baa0c0354bb5f9a3ecbe5ed12ab4d8e11ba873c2f11161202b945","72a18656b560eb04bf7addde412030faa3128246da99144200bf2e8a4386b655","f4c3ba0d574e22b34a05bc26abdd4470458a099848dbb571d770d8e6168f965d","754404b1f2fa3d3b3406e1bee445de457028c4c0e4e4bb706f59d9ef4571e528","dd5f875c6fbedc9e6f6072a2750e6a786250d7217bb4ccb1bd37be8cf104ed48","33d572c7380961d2a588a157308e58bb5dee7f57dd97c132cf0425f564e69bbf","f4715984a81271ff2dab7c2578cab32e718f8e38c78054b1da2f456a251cac57","695a1f4c5c52e7b3969e4f42f864bd4dbbf130d43487e3b4dcbe883d7f0b1143","2bbf314d4b5019282fb561b51a044747218b4259bbcf6b29fb39163b5e0561fe","f3a02e4d6a7be2222e0466b68272a567ce8604efbd1e10a784dbfeb63d1fdc1f","8ff0590e5c3922442f51afdda80fbf73e3da008f9c8336f27f76540be6fb2c4f"};
    public static int index(String id)throws IOException {
        if(id==null||!id.matches("D[0-8]"))throw new IOException("Неизвестный Discord/STUN-профиль: "+id);
        return Integer.parseInt(id.substring(1));
    }
    public static List<String> args(String id)throws IOException {
        return Collections.unmodifiableList(Arrays.asList(ARGS[index(id)].clone()));
    }
    public static String hash(String id)throws IOException {
        int i=index(id);String actual=Catalogue.digest(new JSONArray(Arrays.asList(ARGS[i])).toString().getBytes(StandardCharsets.UTF_8));
        if(!HASHES[i].equals(actual))throw new IOException("Не совпал хеш Discord/STUN-рецепта "+id);
        return actual;
    }
    private DiscordProfiles(){}
}
