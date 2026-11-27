/* Copyright (C) 2026 Igor Krauch. SPDX-License-Identifier: GPL-3.0-or-later */
package com.igareck.antidpi;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** Shared palette, spacing and native controls for the app screens. */
final class ScreenUi {
    static final int INK=0xff28213d, MUTED=0xff58506c, ACCENT=0xff49427f;
    static final int GREEN=0xff236d54, MINT=0xff4ec69c, AMBER=0xff7e4c1d, ERROR=0xffa63949;
    static final int HANDWRITING=0xff635a91;
    static final int DARK_WINDOW=0xff100d1a;
    static final int BORDER=0xffddd3ec, GUIDE_PANEL=0xffede9f8, GUIDE_CODE=0xfff1edf9, TERMINAL=0xffeeedf3;
    private static final int PRIMARY=0xff726d9e, SECONDARY=0xffeeebfb, SELECTED=0xffc9caeb;
    private static final int FIELD=0xfff4f0fc;
    private static final int SURFACE=0xfffdfaff;
    private static final int DARK_NAVIGATION=0xff1b1224;
    private static final String DARK_THEME="dark_theme";
    private final Activity activity;
    private boolean dark;

    ScreenUi(Activity activity){this.activity=activity;}
    int dp(int number){return Math.round(number*activity.getResources().getDisplayMetrics().density);}
    static SharedPreferences preferences(Activity activity){
        return UiLanguage.preferences(activity);
    }

    static boolean prefersDark(Context context){
        return UiLanguage.preferences(context).getBoolean(DARK_THEME,false);
    }
    boolean isDark(){return dark;}
    void toggleTheme(){
        preferences(activity).edit().putBoolean(DARK_THEME,!dark).apply();
    }
    boolean applyTheme(){
        boolean requested=prefersDark(activity);
        if(dark==requested)return false;
        dark=requested;
        activity.setTheme(dark?R.style.AppThemeDark:R.style.AppTheme);
        Window window=activity.getWindow();
        int background=dark?DARK_WINDOW:0xfffff2f5;
        window.setBackgroundDrawable(new ColorDrawable(background));
        window.setStatusBarColor(background);
        window.setNavigationBarColor(dark?DARK_NAVIGATION:0xffe8edff);
        // The theme supplies the initial bar appearance before the decor is created.
        WindowInsetsController controller=window.peekDecorView()==null?null:window.getInsetsController();
        if(controller!=null){
            int lightBars=WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
            controller.setSystemBarsAppearance(dark?0:lightBars,lightBars);
        }
        return true;
    }
    static GradientDrawable darkBackground(){
        return new GradientDrawable(GradientDrawable.Orientation.TL_BR,
            new int[]{DARK_WINDOW,0xff171022,DARK_NAVIGATION});
    }
    int color(int light){
        if(!dark)return light;
        switch(light){
            case INK: return 0xffdad0e0;
            case MUTED: return 0xffb4a6c0;
            case ACCENT: return 0xffc7b9d0;
            case GREEN: return 0xff81cca9;
            case MINT: return 0xff9ddbbd;
            case AMBER: return 0xffe3b182;
            case ERROR: return 0xffef9da9;
            case HANDWRITING: return 0xffb58ac3;
            case PRIMARY: return 0xff674575;
            case SELECTED: return 0xff3e2c4c;
            case SECONDARY:
            case GUIDE_PANEL: return 0xff1a1324;
            case FIELD:
            case GUIDE_CODE:
            case TERMINAL: return 0xff16101f;
            case SURFACE: return 0xff0b0911;
            case BORDER: return 0xff3d2c4e;
            default: return light;
        }
    }

    String en(String text){return UiLanguage.text(activity,text);}
    String t(String russian,String english){return UiLanguage.choose(activity,russian,english);}
    int contentWidthDp(){return Math.max(0,Math.min(760,activity.getResources().getConfiguration().screenWidthDp)-32);}
    boolean roomy(){return contentWidthDp()>=490&&activity.getResources().getConfiguration().fontScale<=1.3f;}
    boolean wide(){return contentWidthDp()>=595&&activity.getResources().getConfiguration().fontScale<=1.3f;}

    LinearLayout page(String title){
        applyTheme();
        ScrollView scroll=new ScrollView(activity);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setBackground(dark?darkBackground():new GradientDrawable(GradientDrawable.Orientation.TL_BR,
            new int[]{0xfffff2f5,0xffeeecfd,0xffe8edff}));
        scroll.setOnApplyWindowInsetsListener((view,insets)->{
            Insets safe=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());
            view.setPadding(safe.left,safe.top,safe.right,Math.max(safe.bottom,insets.getInsets(WindowInsets.Type.ime()).bottom));
            return insets;
        });
        FrameLayout frame=new FrameLayout(activity);
        LinearLayout body=new LinearLayout(activity){
            @Override protected void onMeasure(int widthSpec,int heightSpec){
                int width=Math.min(MeasureSpec.getSize(widthSpec),dp(760));
                super.onMeasure(MeasureSpec.makeMeasureSpec(width,MeasureSpec.EXACTLY),heightSpec);
            }
        };
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(16),dp(16),dp(16),dp(24));
        body.setFocusableInTouchMode(true);
        frame.addView(body,new FrameLayout.LayoutParams(-1,-2,Gravity.TOP|Gravity.CENTER_HORIZONTAL));
        scroll.addView(frame,new ScrollView.LayoutParams(-1,-2));
        activity.setContentView(scroll);
        scroll.requestApplyInsets();
        if(title!=null){
            activity.setTitle(en(title));
            add(body,center(title,25,ACCENT,true),14);
        }
        return body;
    }

    LinearLayout column(){
        LinearLayout view=new LinearLayout(activity);
        view.setOrientation(LinearLayout.VERTICAL);
        return view;
    }
    LinearLayout row(){
        LinearLayout view=new LinearLayout(activity);
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }
    LinearLayout card(LinearLayout page){
        LinearLayout view=column();
        view.setPadding(dp(14),dp(12),dp(14),dp(12));
        view.setBackground(shape(SURFACE,20,BORDER));
        add(page,view,12);
        return view;
    }
    void add(LinearLayout parent,View child,int bottom){
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2);
        params.bottomMargin=dp(bottom);
        parent.addView(child,params);
    }
    void weight(LinearLayout row,View child,float weight,int nextGap){
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(0,-2,weight);
        params.setMarginEnd(dp(nextGap));
        row.addView(child,params);
    }
    TextView text(CharSequence value,int size,int lightColor){
        TextView view=new TextView(activity);
        view.setText(value instanceof String?en((String)value):value);
        view.setTextSize(size);
        view.setTextColor(color(lightColor));
        view.setLineSpacing(dp(1),1.08f);
        return view;
    }
    TextView heading(String value,int size){
        TextView view=text(value,size,INK);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setAccessibilityHeading(true);
        return view;
    }
    TextView center(String value,int size,int lightColor,boolean bold){
        TextView view=bold?heading(value,size):text(value,size,lightColor);
        view.setTextColor(color(lightColor));
        view.setGravity(Gravity.CENTER);
        return view;
    }
    void paragraph(LinearLayout parent,CharSequence value){add(parent,text(value,14,INK),10);}
    void note(LinearLayout parent,CharSequence value){add(parent,text(value,13,MUTED),8);}
    Button button(String value,View.OnClickListener action){return button(value,false,action);}
    Button button(String value,boolean primary,View.OnClickListener action){
        Button view=new Button(activity);
        view.setText(en(value));
        view.setAllCaps(false);
        view.setTextSize(13);
        view.setLetterSpacing(0);
        view.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
        view.setMinHeight(dp(48));
        view.setMinimumHeight(dp(48));
        view.setMinimumWidth(0);
        view.setMinWidth(0);
        view.setPadding(dp(9),dp(8),dp(9),dp(8));
        view.setStateListAnimator(null);
        styleButton(view,primary,false);
        view.setOnClickListener(action);
        return view;
    }
    void styleButton(Button button,boolean primary,boolean selected){
        button.setTextColor(new ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled},new int[]{}},
            new int[]{dark?0xff92859e:0xff837891,primary?0xffffffff:color(ACCENT)}));
        StateListDrawable state=new StateListDrawable();
        state.addState(new int[]{-android.R.attr.state_enabled},shape(dark?SECONDARY:0xffeeebf3,13,BORDER));
        state.addState(new int[]{android.R.attr.state_focused},shape(primary?PRIMARY:selected?SELECTED:SECONDARY,13,ACCENT));
        state.addState(new int[]{},shape(primary?PRIMARY:selected?SELECTED:SECONDARY,13,primary?0:BORDER));
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(primary?0x36ffffff:dark?0x33c7b9d0:0x2649427f),state,null));
    }
    EditText field(String hint,int inputType){
        EditText field=new EditText(activity);
        field.setSingleLine(true);
        field.setInputType(inputType);
        field.setTextSize(15);
        field.setTextColor(color(INK));
        field.setHintTextColor(color(MUTED));
        field.setHint(en(hint));
        field.setMinHeight(dp(48));
        field.setPadding(dp(11),dp(8),dp(11),dp(8));
        field.setBackground(shape(FIELD,11,BORDER));
        return field;
    }
    CheckBox check(String label,boolean checked){
        CheckBox check=new CheckBox(activity);
        check.setText(en(label));
        check.setTextSize(14);
        check.setTextColor(new ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled},new int[]{}},new int[]{color(MUTED),color(INK)}));
        check.setButtonTintList(new ColorStateList(new int[][]{
            new int[]{-android.R.attr.state_enabled},new int[]{android.R.attr.state_checked},new int[]{}},
            new int[]{dark?0xff665a71:0xffaaa1b5,dark?0xffa897b3:PRIMARY,color(MUTED)}));
        check.setChecked(checked);
        check.setMinHeight(dp(48));
        return check;
    }
    GradientDrawable shape(int lightColor,int radius,int border){
        GradientDrawable shape=new GradientDrawable();
        shape.setColor(color(lightColor));
        shape.setCornerRadius(dp(radius));
        if(border!=0)shape.setStroke(dp(1),color(border));
        return shape;
    }
    void back(LinearLayout page){back(page,"Вернуться в главное меню");}
    void back(LinearLayout page,String label){
        Button button=button(label,view->activity.finish());
        styleButton(button,false,true);
        add(page,button,0);
    }
    void openUrl(String url){
        try{activity.startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(url)));}
        catch(android.content.ActivityNotFoundException e){toast("Откройте ссылку в браузере");}
    }
    void link(LinearLayout page,String label,String url){
        add(page,button(label,view->openUrl(url)),8);
    }
    void copy(String label,String text){
        ClipboardManager clipboard=activity.getSystemService(ClipboardManager.class);
        if(clipboard!=null)clipboard.setPrimaryClip(ClipData.newPlainText(label,text));
    }
    void toast(String message){
        Toast.makeText(activity,en(message==null?"Не удалось выполнить действие":message),Toast.LENGTH_LONG).show();
    }
}
