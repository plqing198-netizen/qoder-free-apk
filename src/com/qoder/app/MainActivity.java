package com.qoder.app;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

public class MainActivity extends Activity {
    private WebView web;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private CheckinTask checkinTask;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        web.setWebViewClient(new WebViewClient());
        web.addJavascriptInterface(new Bridge(this), "AndroidBridge");
        setContentView(web);
        web.loadUrl("file:///android_asset/index.html");

        startService(new Intent(this, GatewayService.class));
        checkinTask = new CheckinTask(this);
        ui.postDelayed(checkinTask, 60 * 1000L);
    }

    @Override
    protected void onDestroy() {
        if (checkinTask != null) ui.removeCallbacks(checkinTask);
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    static class CheckinTask implements Runnable {
        private final MainActivity a;
        CheckinTask(MainActivity a) { this.a = a; }
        @Override
        public void run() {
            String t = Store.token(a);
            if (!t.isEmpty()) {
                try { QoderApi.checkinClaim(t); } catch (Exception ignored) {}
            }
            a.ui.postDelayed(this, 3600 * 1000L);
        }
    }
}
