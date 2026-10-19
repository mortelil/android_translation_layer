/* SPDX-License-Identifier: GPL-3.0-only */
package org.atl.webcookies;
import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.webkit.*;

public class TestActivity extends Activity {
    static final String URL="http://127.0.0.1:18761/";
    CookieManager cm;
    WebView web;
    boolean finished;
    void check(boolean result, String label) { if(!result) { System.err.println("COOKIE CONTRACT FAILED: "+label); System.exit(1); } }
    boolean has(String url,String cookie) { String s=cm.getCookie(url); return s!=null && java.util.Arrays.asList(s.split("; ")).contains(cookie); }
    final class Bridge {
        @JavascriptInterface public boolean cookieFromWorker() {
            return Looper.myLooper()!=Looper.getMainLooper() && has(URL,"server=private");
        }
    }
    public void onCreate(Bundle state) {
        super.onCreate(state);
        new Handler().postDelayed(new Runnable(){public void run(){if(!finished)check(false,"timeout");}},30000);
        cm=CookieManager.getInstance();
        cm.removeAllCookies(null);
        check(!cm.hasCookies(),"empty");
        cm.setAcceptCookie(false);
        cm.setCookie(URL,"disabled=no");
        check(!has(URL,"disabled=no"),"policy");
        cm.setAcceptCookie(true);
        cm.setCookie(URL,"java=before; Path=/");
        cm.setCookie(URL,"bad=no; Domain=other.test");
        check(cm.getCookie("http://other.test/")==null,"domain isolation");
        cm.setCookie("https://secure.test/", "secure=yes; Secure; Path=/");
        check(has("https://secure.test/","secure=yes") && !has("http://secure.test/","secure=yes"),"Secure isolation");
        cm.setCookie(URL,"persistent=kept; Max-Age=3600; Path=/");
        web=new WebView(this);
        check(web.getTitle()==null,"no title before navigation");
        web.getSettings().setJavaScriptEnabled(true);
        web.addJavascriptInterface(new Bridge(),"Native");
        web.setWebViewClient(new WebViewClient(){public void onPageFinished(WebView w,String u){
            web.setWebViewClient(null);
            check(has(URL,"server=private"),"HTTP Set-Cookie visible to Java including HttpOnly");
            check(has(URL,"script=after"),"JavaScript cookie visible to Java");
            web.evaluateJavascript("window.sent && document.cookie.includes('java=before') && !document.cookie.includes('server=private') && Native.cookieFromWorker()",new ValueCallback<String>(){
                public void onReceiveValue(String value){
                    check("Cookie title 🎵".equals(web.getTitle()),"WebView title including supplementary Unicode");
                    check("true".equals(value),"Java cookie sent in HTTP and visible to JS; HttpOnly protected; worker read");
                    cm.removeSessionCookies(new ValueCallback<Boolean>(){public void onReceiveValue(Boolean removed){
                        check(removed && Looper.myLooper()==Looper.getMainLooper(),"session deletion callback");
                        check(!has(URL,"server=private") && has(URL,"persistent=kept"),"session vs persistent");
                        web.evaluateJavascript("document.cookie === 'persistent=kept'", new ValueCallback<String>(){public void onReceiveValue(String value){
                            check("true".equals(value),"Java deletion visible to JavaScript");
                            cm.removeAllCookies(new ValueCallback<Boolean>(){public void onReceiveValue(Boolean removed){
                                check(removed && !cm.hasCookies(),"remove all");
                                finished=true;
                                System.out.println("ATL WEB COOKIE CONTRACTS PASSED");
                                System.exit(0);
                            }});
                        }});
                    }});
                }
            });
        }});
        setContentView(web);
        web.loadUrl(URL);
    }
}
