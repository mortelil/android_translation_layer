/* SPDX-License-Identifier: GPL-3.0-only */
package android.webkit;

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/** Private transport for explicitly annotated methods, never arbitrary reflection. */
final class JavascriptBridge {
    private final Map<String, Object> objects = new LinkedHashMap<>();
    private final Map<String, Map<String, Object>> documents = new HashMap<>();
    private final Map<String, Integer> generations = new HashMap<>();
    private int generation;
    private boolean destroyed;

    synchronized String update(String name, Object object) {
        if (destroyed) throw new IllegalStateException("WebView destroyed");
        if (object == null) objects.remove(name); else objects.put(name, object);
        String token = java.util.UUID.randomUUID().toString();
        documents.put(token, new HashMap<>(objects));
        generations.put(token, ++generation);
        StringBuilder js = new StringBuilder("(function(){'use strict';var p=window.prompt.bind(window),g=")
            .append(JSONObject.quote(token)).append(";function call(n,m,a){var s=p('__ATL_JAVA_BRIDGE__',JSON.stringify([g,n,m,a]));")
            .append("if(s===null)throw new Error('Java bridge unavailable');var r=JSON.parse(s);")
            .append("if(r.error)throw new Error(r.error);return r.isVoid?undefined:r.value;}")
            .append("if(window===window.top)call(null,null,[]);");
        for (Map.Entry<String, Object> entry : objects.entrySet()) {
            String nameJson = JSONObject.quote(entry.getKey());
            js.append("var o=Object.create(null);");
            for (Method method : entry.getValue().getClass().getMethods()) {
                if (!method.isAnnotationPresent(JavascriptInterface.class)) continue;
                String methodJson = JSONObject.quote(method.getName());
                js.append("o[").append(methodJson).append("]=function(){return call(")
                    .append(nameJson).append(',').append(methodJson)
                    .append(",Array.prototype.slice.call(arguments));};");
            }
            js.append("Object.defineProperty(window,").append(nameJson)
                .append(",{value:o,writable:true,configurable:true,enumerable:true});");
        }
        return js.append("})();").toString();
    }

    synchronized void destroy() {
        destroyed = true;
        objects.clear();
        documents.clear();
        generations.clear();
    }

    // Called only by the native bridge's serial background worker. Never log
    // requests, returned values or exception messages: they may contain tokens.
    String invoke(String request) {
        try {
            JSONArray call = new JSONArray(request);
            String id = call.getString(0);
            Object object;
            synchronized (this) {
                if (destroyed) throw new IllegalStateException();
                Map<String, Object> snapshot = documents.get(id);
                if (snapshot == null) throw new IllegalArgumentException();
                if (call.isNull(1)) {
                    // A new main document releases objects retained by the old
                    // document. Add/remove become visible only on navigation.
                    int current = generations.get(id);
                    for (String key : new java.util.ArrayList<>(documents.keySet())) {
                        if (generations.get(key) < current) { documents.remove(key); generations.remove(key); }
                    }
                    return "{\"isVoid\":true}";
                }
                object = snapshot.get(call.getString(1));
            }
            if (object == null) throw new IllegalArgumentException();
            String name = call.getString(2);
            JSONArray arguments = call.getJSONArray(3);
            Method selected = null;
            Object[] converted = null;
            for (Method method : object.getClass().getMethods()) {
                if (!method.getName().equals(name) || !method.isAnnotationPresent(JavascriptInterface.class)) continue;
                Class<?>[] types = method.getParameterTypes();
                if (types.length != arguments.length()) continue;
                Object[] values = new Object[types.length];
                try {
                    for (int i = 0; i < types.length; i++) values[i] = convert(arguments.get(i), types[i]);
                } catch (IllegalArgumentException unsupported) { continue; }
                if (selected != null) throw new IllegalArgumentException("ambiguous overload");
                selected = method;
                converted = values;
            }
            if (selected == null) throw new IllegalArgumentException();
            // Public annotated methods on non-public app classes are supported.
            selected.setAccessible(true);
            Object result = selected.invoke(object, converted);
            if (selected.getReturnType() == Void.TYPE) return "{\"isVoid\":true}";
            if (result instanceof Character) result = (int)((Character)result).charValue();
            if (result != null && !(result instanceof String) && !(result instanceof Number) && !(result instanceof Boolean))
                throw new IllegalArgumentException("object returns unsupported");
            return new JSONObject().put("value", result == null ? JSONObject.NULL : result).toString();
        } catch (Exception failure) {
            if (System.getenv("ATL_WEBVIEW_DIAGNOSTICS") != null)
                System.err.println("ATL WebView: JavaScript bridge invocation failed (details omitted)");
            return "{\"error\":\"Java bridge invocation failed or unsupported signature\"}";
        }
    }

    private static Object convert(Object value, Class<?> type) {
        if (value == JSONObject.NULL) {
            if (type.isPrimitive()) throw new IllegalArgumentException();
            return null;
        }
        if (type == String.class && value instanceof String) return value;
        if (type == boolean.class && value instanceof Boolean) return value;
        if (value instanceof Number) {
            Number n = (Number)value;
            if (type == double.class) return n.doubleValue();
            if (type == float.class) return n.floatValue();
            if (type == long.class) return n.longValue();
            if (type == int.class) return n.intValue();
            if (type == short.class) return n.shortValue();
            if (type == byte.class) return n.byteValue();
            if (type == char.class) return (char)n.intValue();
        }
        if (type.isArray() && type.getComponentType().isPrimitive() && value instanceof JSONArray) {
            JSONArray values = (JSONArray)value;
            Object array = Array.newInstance(type.getComponentType(), values.length());
            try {
                for (int i = 0; i < values.length(); i++) Array.set(array, i, convert(values.get(i), type.getComponentType()));
            } catch (org.json.JSONException e) { throw new IllegalArgumentException(); }
            return array;
        }
        throw new IllegalArgumentException();
    }
}
