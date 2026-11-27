/* Copyright (C) 2026 Igor Krauch. SPDX-License-Identifier: GPL-3.0-or-later */
package com.igareck.antidpi;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.Html;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class InstructionsActivity extends Activity {
    private static final int SAVE_YAML=50;
    private static final Pattern GUIDE_BLOCK = Pattern.compile(
        "<(p|h3)( class=\"note\")?>(.*?)</\\1>", Pattern.DOTALL);
    private static final Pattern KARING_OPTION = Pattern.compile(
        "<section>\\s*<h4>([^<]+)</h4>(.*?)</section>", Pattern.DOTALL);
    private static final String TAG="Igareck_AntiDPI_Local";
    private enum Client {
        KARING("Karing","karing","https://github.com/KaringX/karing/releases"),
        EXCLAVE("Exclave","exclave","https://github.com/ExclaveNetwork/Exclave/releases"),
        V2RAY_NG("v2rayNG","v2rayng","https://github.com/2dust/v2rayNG/releases"),
        FLCLASH("FlClash","flclash","https://github.com/chen08209/FlClash/releases"),
        ORBOT("Orbot","orbot","https://github.com/guardianproject/orbot-android/releases"),
        INVIZIBLE("InviZible Pro","invizible","https://github.com/Gedsh/InviZible/releases");
        final String label,asset,releases;
        Client(String label,String asset,String releases){
            this.label=label;
            this.asset=asset;
            this.releases=releases;
        }
    }
    private final ScreenUi ui=new ScreenUi(this);
    private final Button[] buttons=new Button[Client.values().length];
    private LinearLayout guide;
    private Client selected=Client.KARING;
    private int port;
    private String pendingYaml;

    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);
        port=ProxyService.running?ProxyService.activePort:savedPort();
        if(saved!=null){
            int index=Math.max(0,Math.min(buttons.length-1,saved.getInt("client")));
            selected=Client.values()[index];
            pendingYaml=saved.getString("yaml");
        }
        LinearLayout page=ui.page("Инструкции");
        guideText(page,"setup");
        for(int i=0;i<buttons.length;i+=2){
            LinearLayout clients=ui.row();
            for(int column=0;column<2;column++){
                Client client=Client.values()[i+column];
                Button button=ui.button(client.label,v->show(client));
                buttons[i+column]=button;
                ui.weight(clients,button,1,column==0?8:0);
            }
            ui.add(page,clients,8);
        }
        guide=ui.card(page);
        show(selected);
        ui.back(page);
    }

    private int savedPort(){
        try{return AdbBootstrap.port(ScreenUi.preferences(this).getString("port","1080"),false);}
        catch(IOException e){return 1080;}
    }

    private void show(Client client){
        selected=client;
        for(int i=0;i<buttons.length;i++){
            boolean current=i==selected.ordinal();
            ui.styleButton(buttons[i],false,current);
            buttons[i].setSelected(current);
            buttons[i].setStateDescription(current?ui.en("Открыта инструкция"):null);
        }
        guide.removeAllViews();
        ui.add(guide,ui.heading(client.label,22),10);

        switch(client){
            case KARING:
                heading("Локальный профиль SOCKS5");
                code("Karing · SOCKS5",karingConfig());
                break;
            case EXCLAVE:
            case V2RAY_NG:
                heading("Локальный профиль SOCKS5");
                socksFields();
                break;
            case FLCLASH:
                heading("Готовый YAML для прямого доступа");
                String yaml=flClashYaml();
                code("FlClash · SOCKS5",yaml);
                ui.add(guide,ui.button("Сохранить YAML для FlClash",v->saveYaml(yaml)),8);
                break;
            default:
                break;
        }

        guideText(guide,client.asset);
        if(client==Client.FLCLASH){
            String script=asset("guides/flclash.js");
            if(script!=null)code("FlClash · Igareck AntiDPI",script.replace("port: 1080","port: "+port));
            else ui.note(guide,"Не удалось открыть инструкцию.");
            guideText(guide,"flclash-after");
        }
        ui.link(guide,ui.t("Скачать ","Download ")+client.label,client.releases);
    }

    private String asset(String path){
        try(InputStream in=getAssets().open(path)){
            return new String(Catalogue.bytes(in),StandardCharsets.UTF_8);
        }catch(IOException e){
            AppLog.error("Read instruction asset "+path,e);
            return null;
        }
    }
    private void guideText(LinearLayout card,String name){
        String html=asset("guides/"+ui.t("ru","en")+"/"+name+".html");
        if(html==null){
            ui.note(card,"Не удалось открыть инструкцию.");
            return;
        }
        html = html.replace("@PORT@", Integer.toString(port));
        if (Client.KARING.asset.equals(name)) {
            karingGuide(card, html);
        } else {
            guideBlocks(card, html);
        }
    }

    private void karingGuide(LinearLayout card, String html) {
        Matcher options = KARING_OPTION.matcher(html);
        int previousEnd = 0;
        int labelTopPadding = 24;
        while (options.find()) {
            guideBlocks(card, html.substring(previousEnd, options.start()));

            TextView label = ui.heading(options.group(1), 14);
            label.setPaintFlags(label.getPaintFlags() | Paint.UNDERLINE_TEXT_FLAG);
            label.setIncludeFontPadding(false);
            label.setPadding(ui.dp(8), ui.dp(labelTopPadding), 0, 0);
            ui.add(card, label, 10);

            LinearLayout panel = ui.column();
            panel.setBackground(ui.shape(ScreenUi.GUIDE_PANEL, 12, ScreenUi.BORDER));
            panel.setPadding(ui.dp(16), ui.dp(8), ui.dp(16), ui.dp(24));
            guideBlocks(panel, options.group(2));
            ui.add(card, panel, 38);

            previousEnd = options.end();
            labelTopPadding = 10;
        }
        guideBlocks(card, html.substring(previousEnd));
    }

    private void guideBlocks(LinearLayout card, String html) {
        Matcher blocks = GUIDE_BLOCK.matcher(html);
        while (blocks.find()) {
            boolean title = blocks.group(1).equals("h3");
            String content = blocks.group(3).replaceAll("<br>\\s*", "<br>");
            if (title) {
                TextView heading = ui.heading(content, 17);
                heading.setPadding(0, ui.dp(13), 0, 0);
                ui.add(card, heading, 7);
            } else {
                CharSequence text = Html.fromHtml(content, Html.FROM_HTML_MODE_COMPACT, this::guideIcon, null);
                boolean note = blocks.group(2) != null;
                TextView paragraph = ui.text(text, note ? 13 : 14, note ? ScreenUi.MUTED : ScreenUi.INK);
                paragraph.setIncludeFontPadding(false);
                ui.add(card, paragraph, 8);
            }
        }
    }

    private Drawable guideIcon(String name) {
        int icon;
        if ("pencil".equals(name)) icon = R.drawable.ic_guide_pencil;
        else if ("file-plus".equals(name)) icon = R.drawable.ic_guide_file_plus;
        else return null;
        Drawable drawable = getDrawable(icon);
        int size = ui.dp(16);
        drawable.setBounds(0, 0, size, size);
        return drawable;
    }

    private void heading(String text){ui.add(guide,ui.heading(text,17),6);}
    private void code(String label,String value){code(label,value,null);}
    private void code(String label,String value,String note){
        TextView config=ui.text(value,13,ScreenUi.INK);
        config.setTypeface(Typeface.MONOSPACE);
        config.setTextIsSelectable(true);
        config.setHorizontallyScrolling(true);
        config.setContentDescription(label);
        int padding=ui.dp(11);
        config.setPadding(padding,padding,padding,padding);
        HorizontalScrollView scroll=new HorizontalScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackground(ui.shape(ScreenUi.GUIDE_CODE,11,ScreenUi.BORDER));
        scroll.addView(config,new android.widget.FrameLayout.LayoutParams(-2,-2));
        ui.add(guide,scroll,6);
        if(note!=null)ui.note(guide,note);
        ui.add(guide,ui.button("Копировать в буфер обмена",v->{
            ui.copy(label,value);
            if(android.os.Build.VERSION.SDK_INT<33)ui.toast("Скопировано.");
        }),12);
    }

    private String karingConfig(){
        return "{\n  \"outbounds\": [\n    {\n      \"type\": \"socks\",\n      \"tag\": \""+TAG+"\",\n"
            +"      \"server\": \"127.0.0.1\",\n      \"server_port\": "+port+",\n      \"version\": \"5\",\n      \"udp_over_tcp\": false\n    }\n  ]\n}";
    }
    private void socksFields(){
        String fields=ui.t("Имя       ","Name      ")+TAG+"\n"
            +ui.t("Тип       ","Type      ")+"SOCKS5\n"
            +ui.t("Адрес     ","Address   ")+"127.0.0.1\n"
            +ui.t("Порт      ","Port      ")+port;
        if (selected == Client.EXCLAVE)
            code("SOCKS5", fields, ui.t("Имя пользователя и пароль в Exclave оставьте пустыми.",
                "Leave the username and password blank in Exclave."));
        else {
            code("SOCKS5", fields);
            ui.note(guide,"Имя пользователя и пароль оставьте пустыми.");
        }
    }
    private String flClashYaml(){
        return "mode: rule\nallow-lan: false\nipv6: false\nproxies:\n"
            +"  - name: "+TAG+"\n    type: socks5\n    server: 127.0.0.1\n    port: "+port+"\n    udp: true\n"
            +"rules:\n  - MATCH,"+TAG+"\n";
    }
    private void saveYaml(String yaml){
        pendingYaml=yaml;
        try{startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType("text/plain").putExtra(Intent.EXTRA_TITLE,"Igareck_AntiDPI_Local.yaml"),SAVE_YAML);}
        catch(Exception e){pendingYaml=null;ui.toast("Не удалось открыть выбор файла.");}
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(request!=SAVE_YAML)return;
        String yaml=pendingYaml;pendingYaml=null;
        if(result!=RESULT_OK||data==null||data.getData()==null||yaml==null)return;
        try(OutputStream out=getContentResolver().openOutputStream(data.getData(),"w")){
            if(out==null)throw new IOException("Файл недоступен");
            out.write(yaml.getBytes(StandardCharsets.UTF_8));
            ui.toast("Профиль FlClash сохранён.");
        }catch(IOException e){ui.toast("Не удалось сохранить профиль FlClash.");}
    }
    @Override protected void onSaveInstanceState(Bundle out){
        super.onSaveInstanceState(out);
        out.putInt("client",selected.ordinal());
        out.putString("yaml",pendingYaml);
    }
}
