/* SPDX-License-Identifier: GPL-3.0-only */
package android.webkit;

import android.net.Uri;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.json.JSONArray;
import org.json.JSONObject;

/** String WebMessage listener transport. AndroidX boundary protocol, implemented
 * independently of Chromium. Origin and frame metadata come from an isolated
 * WebKit script world, never from arguments supplied by page JavaScript. */
final class WebMessageBridge {
    private final WebView view;
    private final Map<String, Registration> listeners = new HashMap<>();
    private final Map<String, Reply> replies = new HashMap<>();
    private static final class Registration {
        final String token = UUID.randomUUID().toString();
        final InvocationHandler listener;
        final Method callback;
        Registration(InvocationHandler listener) throws ReflectiveOperationException {
            this.listener = listener;
            Class<?> boundary = Class.forName("org.chromium.support_lib_boundary.WebMessageListenerBoundaryInterface", false,
                    listener.getClass().getClassLoader());
            callback = boundary.getMethod("onPostMessage", WebView.class, InvocationHandler.class, Uri.class,
                    boolean.class, InvocationHandler.class);
        }
    }
    WebMessageBridge(WebView view) { this.view = view; }

    InvocationHandler provider() {
        return new InvocationHandler() {
          public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            view.checkJavascriptThread();
            switch (method.getName()) {
                case "addWebMessageListener": add((String) args[0], (String[]) args[1], (InvocationHandler) args[2]); return null;
                case "removeWebMessageListener": remove((String) args[0]); return null;
                default: throw new UnsupportedOperationException("WebView provider: " + method.getName());
            }
          }
        };
    }

    private void add(String name, String[] rules, InvocationHandler listener) throws ReflectiveOperationException {
        if (name == null || name.isEmpty() || name.indexOf('\0') >= 0 || listener == null || rules == null) throw new IllegalArgumentException();
        if (listeners.containsKey(name)) throw new IllegalArgumentException("Listener already registered");
        JSONArray origins = new JSONArray();
        for (String rule : rules) {
            if (rule == null) throw new IllegalArgumentException("Null origin rule");
            if (rule.equals("*")) { origins.put(rule); continue; }
            // Normalize with the same URL parser as the injected world. Reject
            // paths/credentials rather than widening a malformed origin rule.
            if (!rule.matches("[a-zA-Z][a-zA-Z0-9+.-]*://(?:\\*\\.)?(?:[a-zA-Z0-9.-]+|\\[[0-9a-fA-F:]+\\])(?::[0-9]+)?"))
                throw new IllegalArgumentException("Invalid origin rule: " + rule);
            try {
                java.net.URI origin = new java.net.URI(rule.replace("://*.", "://"));
                if (origin.getHost() == null || origin.getPort() > 65535)
                    throw new IllegalArgumentException("Invalid origin rule: " + rule);
            } catch (java.net.URISyntaxException e) { throw new IllegalArgumentException("Invalid origin rule", e); }
            origins.put(rule);
        }
        Registration r = new Registration(listener);
        String event = "atl-wm-" + r.token;
        String prelude = "(()=>{'use strict';const rules=" + origins + ";const actualOrigin=globalThis.origin;let source;try{source=new URL(actualOrigin);}catch(e){}const allowed=rules.some(r=>{if(r==='*')return true;if(!source)return false;"
            + "const wild=r.includes('://*.');const u=new URL(r.replace('://*.','://'));"
            + "return source.protocol===u.protocol&&source.port===u.port&&(wild?source.hostname.endsWith('.'+u.hostname):source.hostname===u.hostname);});if(!allowed)return;";
        String isolated = prelude
            + "const event=" + JSONObject.quote(event) + ",name=" + JSONObject.quote(name) + ",registration=" + JSONObject.quote(r.token) + ";"
            + "const origin=actualOrigin,main=window===window.top,doc=registration+':'+Array.from(crypto.getRandomValues(new Uint32Array(4))).join('-');"
            + "const send=window.webkit.messageHandlers.atlWebMessage.postMessage.bind(window.webkit.messageHandlers.atlWebMessage);"
            + "let alive=true;const base={name,registration,origin,main,doc};"
            + "document.addEventListener(event,e=>{if(typeof e.detail==='string')send({...base,op:'post',data:JSON.stringify(e.detail)}).catch(()=>{});});"
            + "async function receive(){while(alive){try{const data=await send({...base,op:'wait'});if(data===null)break;"
            + "document.dispatchEvent(new CustomEvent(event+'-reply',{detail:JSON.parse(data)}));}catch(e){break;}}}receive();"
            + "window.addEventListener('pagehide',()=>{alive=false;send({...base,op:'gone'}).catch(()=>{});});window.addEventListener('pageshow',e=>{if(e.persisted&&!alive){alive=true;receive();}});})();";
        String page = prelude
            + "const event=" + JSONObject.quote(event) + ";let handlers=[];const obj={onmessage:null,"
            + "postMessage(data){if(typeof data!=='string')throw new TypeError('Only string messages are supported');"
            + "document.dispatchEvent(new CustomEvent(event,{detail:data}));},"
            + "addEventListener(type,fn){if(type==='message'&&typeof fn==='function'&&!handlers.includes(fn))handlers.push(fn);},"
            + "removeEventListener(type,fn){if(type==='message')handlers=handlers.filter(x=>x!==fn);}};"
            + "document.addEventListener(event+'-reply',e=>{const msg=new MessageEvent('message',{data:e.detail});"
            + "if(typeof obj.onmessage==='function')obj.onmessage(msg);for(const fn of handlers.slice())fn.call(obj,msg);});"
            + "Object.defineProperty(window," + JSONObject.quote(name) + ",{value:obj,configurable:true});})();";
        view.installMessageListener(name, this, isolated, page);
        listeners.put(name, r);
    }

    private void remove(String name) {
        if (listeners.remove(name) != null) {
            for (String key : new java.util.ArrayList<>(replies.keySet()))
                if (replies.get(key).name.equals(name)) replies.remove(key);
            view.removeMessageListener(name);
        }
    }
    void navigated() { replies.clear(); }
    void destroy() { for (String name : new java.util.ArrayList<>(listeners.keySet())) remove(name); }

    // JNI entry. Runs on GTK/Android's UI thread. No contents or origins logged.
    void onMessage(String name, String token, String document, String origin, boolean main, String encoded) throws Throwable {
        final String data = new JSONArray("[" + encoded + "]").getString(0);
        Registration r = listeners.get(name);
        if (r == null || !r.token.equals(token)) return;
        Reply reply = replies.get(document);
        if (reply == null) { reply = new Reply(name, document, r); replies.put(document, reply); }
        InvocationHandler message = new InvocationHandler() {
          public Object invoke(Object proxy, Method method, Object[] args) {
            switch (method.getName()) {
                case "getData": return data;
                case "getPorts": return new InvocationHandler[0];
                case "getSupportedFeatures": return new String[0];
                default: throw new UnsupportedOperationException(method.getName());
            }
          }
        };
        r.listener.invoke(null, r.callback, new Object[]{view, message, Uri.parse(origin), main, reply});
    }
    private final class Reply implements InvocationHandler {
        final String name, document;
        final Registration registration;
        Object peer;
        Reply(String name, String document, Registration registration) { this.name=name; this.document=document; this.registration=registration; }
        @Override public Object invoke(Object proxy, Method method, Object[] args) throws Exception {
            view.checkJavascriptThread();
            switch (method.getName()) {
                case "getOrCreatePeer":
                    if (peer == null) peer = ((Callable<?>)args[0]).call();
                    return peer;
                case "postMessage":
                    if (args[0] == null) throw new IllegalArgumentException("Null message");
                    if (listeners.get(name) == registration && replies.get(document) == this)
                        view.replyMessage(document, JSONObject.quote((String)args[0]));
                    return null;
                default: throw new UnsupportedOperationException(method.getName());
            }
        }
    }
}
