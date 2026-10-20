/* SPDX-License-Identifier: GPL-3.0-only */
package org.atl.webmessage;
import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.ValueCallback;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;

public class Probe extends Activity {
    public interface Factory { String[] getSupportedFeatures(); InvocationHandler createWebView(WebView view); }
    public interface Provider { void addWebMessageListener(String name, String[] origins, InvocationHandler listener); void removeWebMessageListener(String name); }
    public interface Message { String getData(); }
    public interface Reply { void postMessage(String data); Object getOrCreatePeer(java.util.concurrent.Callable<Object> creator); }
    WebView web; InvocationHandler provider, savedReply; boolean finished; int received, childReceived; int phase,check;
    void fail(String s) { System.err.println("WEBMESSAGE FAIL "+s); System.exit(1); }
    Object invoke(InvocationHandler target, Class<?> type, String name, Class<?>[] types, Object... args) {
        try {return target.invoke(null,type.getMethod(name,types),args);} catch(Throwable t){throw new RuntimeException(t);}
    }
    void reply(InvocationHandler target,String data) { invoke(target,Reply.class,"postMessage",new Class[]{String.class},data); }
    final String[][] checks={
        {"JSON.stringify(results)","\"[\\\"one ø 😀\\\",\\\"two\\\"]\""},
        {"bad","\"undefined\""},
        {"childResult","\"childreply\""},
        {"typeof window.webkit?.messageHandlers?.atlWebMessage","\"undefined\""}
    };
    void checkNext() {
        if(check==checks.length){
            invoke(provider,Provider.class,"removeWebMessageListener",new Class[]{String.class},"Channel");
            reply(savedReply,"stale");
            web.loadDataWithBaseURL("https://fixture.invalid/next","done","text/html","UTF-8",null);return;
        }
        final String[] row=checks[check++];
        web.evaluateJavascript(row[0],new ValueCallback<String>(){public void onReceiveValue(String v){if(!row[1].equals(v))fail(row[0]+" -> "+v);checkNext();}});
    }
    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        new Handler().postDelayed(new Runnable(){public void run(){if(!finished)fail("timeout received="+received+" phase="+phase);}},20000);
        web=new WebView(this);web.getSettings().setJavaScriptEnabled(true);setContentView(web);
        try {
            Class<?> glue=Class.forName("org.chromium.support_lib_glue.SupportLibReflectionUtil",true,WebView.class.getClassLoader());
            InvocationHandler factory=(InvocationHandler)glue.getMethod("createWebViewProviderFactory").invoke(null);
            provider=(InvocationHandler)invoke(factory,Factory.class,"createWebView",new Class[]{WebView.class},web);
            InvocationHandler listener=new InvocationHandler(){public Object invoke(Object proxy,Method method,Object[] args){
                if(Looper.myLooper()!=Looper.getMainLooper())fail("wrong callback thread");
                String data=(String)Probe.this.invoke((InvocationHandler)args[1],Message.class,"getData",new Class[0]);
                if(!"https://fixture.invalid".equals(args[2].toString()))fail("wrong origin "+args[2]);
                if(!Boolean.TRUE.equals(args[3])) {
                    if(!data.equals("child"))fail("unexpected subframe payload");
                    childReceived++;reply((InvocationHandler)args[4],"childreply");return null;
                }
                received++; savedReply=(InvocationHandler)args[4];
                if(!data.equals("hello ø 😀"))fail("payload corrupted");
                reply(savedReply,"one ø 😀");
                new Handler().postDelayed(new Runnable(){public void run(){reply(savedReply,"two");}},100);
                return null;
            }};
            invoke(provider,Provider.class,"addWebMessageListener",new Class[]{String.class,String[].class,InvocationHandler.class},"Channel",new String[]{"https://fixture.invalid"},listener);
        } catch(Exception e){fail(e.toString());}
        web.setWebViewClient(new WebViewClient(){@Override public void onPageFinished(WebView v,String url){
            if(phase++==0) new Handler().postDelayed(new Runnable(){public void run(){if(received!=1)fail("count "+received);checkNext();}},500);
            else web.evaluateJavascript("typeof Channel",new ValueCallback<String>(){public void onReceiveValue(String v){if(!"\"undefined\"".equals(v))fail("removed object");pass();}});
        }});
        web.loadDataWithBaseURL("https://fixture.invalid/", "<script>window.results=[];Channel.onmessage=e=>results.push(e.data);Channel.postMessage('hello ø 😀');window.bad='pending';window.childResult='pending';addEventListener('message',e=>{if(e.data==='childreply')childResult=e.data;else bad=e.data;});</script>"+
            "<iframe srcdoc=\"<script>Channel.onmessage=e=>parent.postMessage(e.data,'*');Channel.postMessage('child')</script>\"></iframe>"+
            "<iframe src=\"data:text/html,%3Cscript%3Eparent.postMessage(typeof%20Channel,'*')%3C/script%3E\"></iframe>","text/html","UTF-8",null);
    }
    void pass(){if(childReceived!=1)fail("child callback count "+childReceived);if(received!=1)fail("removed listener called");finished=true;System.out.println("WEBMESSAGE PASS");System.exit(0);}
}
