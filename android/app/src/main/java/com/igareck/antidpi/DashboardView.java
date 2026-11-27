/* Copyright (C) 2026 Igor Krauch. SPDX-License-Identifier: GPL-3.0-or-later */
package com.igareck.antidpi;

import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.WindowInsets;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebResourceError;
import android.webkit.WebMessage;
import android.webkit.WebMessagePort;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

final class DashboardView extends FrameLayout {
    private static final String HOST = "appassets.androidplatform.net";
    private static final Uri ORIGIN = Uri.parse("https://" + HOST);
    private static final String PAGE = ORIGIN + "/dashboard/index.html";
    private final Consumer<String> messages;
    private WebView view;
    private WebMessagePort channel;
    private boolean recovered;
    private boolean dropdownOpen;
    private Runnable removeDropdownBack;

    DashboardView(MainActivity activity, Consumer<String> messages) {
        super(activity);
        this.messages = messages;
        setDark(ScreenUi.prefersDark(activity));
        setOnApplyWindowInsetsListener((self, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            int bottom = Math.max(bars.bottom, insets.getInsets(WindowInsets.Type.ime()).bottom);
            setPadding(bars.left, bars.top, bars.right, bottom);
            return insets;
        });
        open();
    }

    void setDark(boolean dark) {
        setBackground(dark ? ScreenUi.darkBackground() : new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            new int[]{0xfffef2f4, 0xffeeebfd, 0xffe9edff}));
        if (view != null) view.setBackgroundColor(dark ? ScreenUi.DARK_WINDOW : 0xfffef2f4);
    }

    void publish(String state) {
        if (channel == null) return;
        try {
            channel.postMessage(new WebMessage(state));
        } catch (IllegalStateException e) {
            AppLog.error("Dashboard channel", e);
            closeChannel();
        }
    }

    void pressFeedback() {
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
    }

    void dropdownChanged(boolean open) {
        if (open == dropdownOpen) return;
        dropdownOpen = open;
        if (open && Build.VERSION.SDK_INT >= 33) {
            removeDropdownBack = DropdownBack.register((MainActivity)getContext(), this::dismissDropdown);
        } else if (removeDropdownBack != null) {
            removeDropdownBack.run();
            removeDropdownBack = null;
        }
    }

    boolean dismissDropdown() {
        if (!dropdownOpen) return false;
        dropdownChanged(false);
        publish("{\"action\":\"dismiss-dropdown\"}");
        return true;
    }

    @android.annotation.TargetApi(33)
    private static final class DropdownBack {
        static Runnable register(MainActivity activity, Runnable dismiss) {
            android.window.OnBackInvokedDispatcher dispatcher = activity.getOnBackInvokedDispatcher();
            android.window.OnBackInvokedCallback callback = dismiss::run;
            dispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_OVERLAY, callback);
            return () -> dispatcher.unregisterOnBackInvokedCallback(callback);
        }
    }

    private void open() {
        final WebView web = new WebView(getContext());
        view = web;
        web.setForceDarkAllowed(false);
        WebSettings settings = web.getSettings();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            settings.setAlgorithmicDarkeningAllowed(false);
        } else {
            settings.setForceDark(WebSettings.FORCE_DARK_OFF);
        }
        settings.setJavaScriptEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setUseWideViewPort(true);
        web.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        web.setBackgroundColor(ScreenUi.prefersDark(getContext()) ? ScreenUi.DARK_WINDOW : 0xfffef2f4);
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView sender, WebResourceRequest request) {
                return true;
            }

            @Override public WebResourceResponse shouldInterceptRequest(WebView sender, WebResourceRequest request) {
                return localAsset(request);
            }

            @Override public void onReceivedError(WebView sender, WebResourceRequest request, WebResourceError error) {
                if (view == sender && request.isForMainFrame()) {
                    AppLog.line("DASHBOARD", "Could not load main screen; WebView error=" + error.getErrorCode());
                    ((MainActivity)getContext()).dashboardUnavailable();
                }
            }

            @Override public void onPageFinished(WebView sender, String url) {
                if (view != sender || channel != null || !PAGE.equals(url)) return;
                WebMessagePort[] ports = sender.createWebMessageChannel();
                channel = ports[0];
                channel.setWebMessageCallback(new WebMessagePort.WebMessageCallback() {
                    @Override public void onMessage(WebMessagePort port, WebMessage message) {
                        if (channel == port) messages.accept(message.getData());
                    }
                });
                sender.postWebMessage(new WebMessage("dashboard", new WebMessagePort[]{ports[1]}), ORIGIN);
            }

            @Override public boolean onRenderProcessGone(WebView sender, RenderProcessGoneDetail detail) {
                if (view != sender) return true;
                AppLog.line("DASHBOARD", "WebView renderer exited; crashed=" + detail.didCrash());
                destroy();
                if (!recovered) {
                    recovered = true;
                    post(DashboardView.this::open);
                } else {
                    ((MainActivity)getContext()).dashboardUnavailable();
                }
                return true;
            }
        });
        addView(web, new FrameLayout.LayoutParams(-1, -1));
        web.loadUrl(PAGE);
    }

    private WebResourceResponse localAsset(WebResourceRequest request) {
        Uri uri = request.getUrl();
        String path = uri.getEncodedPath();
        if ("GET".equals(request.getMethod()) && "https".equals(uri.getScheme()) && HOST.equals(uri.getHost())
                && uri.getPort() == -1 && uri.getUserInfo() == null && uri.getQuery() == null && path != null
                && path.matches("/dashboard/[a-z-]+\\.(html|css|js|png)")) {
            String mime = path.endsWith(".html") ? "text/html" : path.endsWith(".css") ? "text/css"
                : path.endsWith(".js") ? "text/javascript" : "image/png";
            try {
                InputStream file = getContext().getAssets().open(path.substring(1));
                if ("/dashboard/index.html".equals(path) && ScreenUi.prefersDark(getContext())) {
                    try (InputStream page = file) {
                        String markup = new String(Catalogue.bytes(page), StandardCharsets.UTF_8);
                        file = new ByteArrayInputStream(markup.replace("<html lang=\"ru\" data-theme=\"light\">",
                            "<html lang=\"ru\" data-theme=\"dark\">").getBytes(StandardCharsets.UTF_8));
                    }
                }
                Map<String,String> headers = new HashMap<>();
                headers.put("X-Content-Type-Options", "nosniff");
                headers.put("Cache-Control", "no-store");
                return new WebResourceResponse(mime, mime.equals("image/png") ? null : "UTF-8", 200, "OK",
                    headers, file);
            } catch (IOException ignored) {
                // Requests outside the shipped resources receive a local response.
            }
        }
        return new WebResourceResponse("text/plain", "UTF-8", 404, "Not Found",
            Collections.emptyMap(), new ByteArrayInputStream(new byte[0]));
    }

    private void closeChannel() {
        dropdownChanged(false);
        WebMessagePort old = channel;
        channel = null;
        if (old != null) try { old.close(); } catch (IllegalStateException ignored) {}
    }

    void destroy() {
        closeChannel();
        if (view == null) return;
        removeView(view);
        view.destroy();
        view = null;
    }
}
