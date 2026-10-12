/* SPDX-License-Identifier: GPL-3.0-only */
package org.atl.webjavascript;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.ValueCallback;

public class TestActivity extends Activity {
    private WebView web;
    private int phase, check;
    private boolean finished;
    private Thread ui;
    private boolean returned;
    private final String[][] checks = {
        {"1+2", "3"},
        {"'hello \\u00f8 \\ud83d\\ude00'", "\"hello ø 😀\""},
        {"({a:1,b:[true,null]})", "{\"a\":1,\"b\":[true,null]}"},
        {"undefined", "null"},
        {"throw new Error('DO_NOT_LOG_THIS_SECRET')", "null"},
        {"window.initial", "5"},
        {"Native.echo('ø 😀')", "\"ø 😀\""},
        {"Native.echo(null)", "null"},
        {"Native.flip(true)", "false"},
        {"Native.sum([2,3,4])", "9"},
        {"Native.worker()", "true"},
        {"Native.incrementThreadLocal()+Native.incrementThreadLocal()", "3"},
        {"typeof Native.hidden + ',' + typeof Native.getClass", "\"undefined,undefined\""},
        {"typeof Native.nothing()", "\"undefined\""},
        {"(function(){try{Native.fail();return false;}catch(e){return true;}})()", "true"},
        {"document.getElementById('child').contentWindow.fromBridge", "\"frame\""},
        {"window.crossResult", "\"cross-origin\""},
        {"JSON.parse(prompt('__ATL_JAVA_BRIDGE__','[\"forged\",\"Native\",\"worker\",[]]')).error.length>0", "true"}
    };

    private final class Bridge {
        private final ThreadLocal<Integer> calls = new ThreadLocal<>();
        @JavascriptInterface public int incrementThreadLocal() {
            Integer n=calls.get(); int next=n==null?1:n+1; calls.set(next); return next;
        }
        @JavascriptInterface public int add(int a, int b) { return a+b; }
        @JavascriptInterface public String echo(String value) { return value; }
        @JavascriptInterface public boolean flip(boolean value) { return !value; }
        @JavascriptInterface public int sum(int[] values) { int n=0; for(int v:values)n+=v;return n; }
        @JavascriptInterface public boolean worker() { return Thread.currentThread()!=ui; }
        @JavascriptInterface public void nothing() {}
        @JavascriptInterface public void fail() { throw new IllegalStateException("DO_NOT_LOG_THIS_SECRET"); }
        public String hidden() { throw new AssertionError("unannotated method reached"); }
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        ui=Thread.currentThread();
        new Handler().postDelayed(new Runnable() { public void run() { if(!finished) fail("timeout"); } }, 25000);
        web=new WebView(this);
        web.getSettings().setJavaScriptEnabled(true);
        web.addJavascriptInterface(new Bridge(), "Native");
        web.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) {
                if (phase==0) { phase=1; next(); }
                else if (phase==2) verifyRemoved();
            }
        });
        setContentView(web);
        web.loadDataWithBaseURL("https://fixture.invalid/", "<script>window.initial=Native.add(2,3);"+
            "addEventListener('message',function(e){window.crossResult=e.data})</script>"+
            "<iframe id='child' srcdoc=\"<script>window.fromBridge=Native.echo('frame')</script>\"></iframe>"+
            "<iframe src=\"data:text/html,%3Cscript%3Eparent.postMessage(Native.echo('cross-origin'),'*')%3C/script%3E\"></iframe>",
            "text/html", "UTF-8", null);
    }

    private void fail(String message) {
        System.err.println("JAVASCRIPT CONTRACT FAILED: "+message);
        System.exit(1);
    }
    private void expect(String script, String expected, Runnable done) {
        returned=false;
        web.evaluateJavascript(script, new ValueCallback<String>() { public void onReceiveValue(String value) {
            if(!returned || Looper.myLooper()!=Looper.getMainLooper()) fail("callback thread/order");
            if(!expected.equals(value)) fail("case "+check+" expected "+expected+" got "+value);
            done.run();
        }});
        returned=true;
    }
    private void next() {
        if (check<checks.length) {
            String[] test=checks[check++];
            expect(test[0], test[1], new Runnable() { public void run() { next(); } });
        } else {
            web.removeJavascriptInterface("Native");
            expect("Native.add(3,4)", "7", new Runnable() { public void run() {
                phase=2;
                web.loadData("<p>removed on next document</p>","text/html","UTF-8");
            }});
        }
    }
    private void verifyRemoved() {
        phase=3;
        expect("typeof Native", "\"undefined\"", new Runnable() { public void run() {
            web.loadUrl("javascript:window.fromUrl=42");
            expect("window.fromUrl", "42", new Runnable() { public void run() {
                verifyIsolation();
            }});
        }});
    }
    private void verifyIsolation() {
        web.addJavascriptInterface(new Bridge(), "Native");
        final WebView other = new WebView(this);
        other.getSettings().setJavaScriptEnabled(true);
        other.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) {
                other.setWebViewClient(null);
                other.evaluateJavascript("typeof Native", new ValueCallback<String>() {
                    public void onReceiveValue(String value) {
                        if (!"\"undefined\"".equals(value)) fail("interface leaked to another WebView");
                        other.destroy();
                        web.destroy();
                        try { web.evaluateJavascript("1", null); fail("destroy did not reject"); }
                        catch(IllegalStateException expected) {}
                        finished=true;
                        System.out.println("ATL JAVASCRIPT CONTRACTS PASSED");
                        System.exit(0);
                    }
                });
            }
        });
        other.loadData("<p>separate view</p>", "text/html", "UTF-8");
    }
}
