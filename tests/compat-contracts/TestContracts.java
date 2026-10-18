// SPDX-License-Identifier: GPL-3.0-only
import android.content.Intent;
import android.os.Bundle;
import android.net.wifi.WifiInfo;
import android.view.inputmethod.*;
import java.lang.reflect.*;

public class TestContracts {
    static int checks;
    static void check(boolean ok, String why) {
        checks++;
        if (!ok) throw new AssertionError(why);
    }
    static void extras() {
        Bundle source = new Bundle(); source.putString("shared", "new");
        Intent target = new Intent().putExtra("keep", "original").putExtra("shared", "old");
        check(target.putExtras(source) == target, "fluent bundle merge");
        check("original".equals(target.getStringExtra("keep")), "merge preserves existing keys");
        check("new".equals(target.getStringExtra("shared")), "incoming key wins");
        source.putString("shared", "changed");
        check("new".equals(target.getStringExtra("shared")), "no source bundle alias");
        Intent fresh = new Intent().putExtras(source);
        source.putString("shared", "again");
        check("changed".equals(fresh.getStringExtra("shared")), "first merge copies bundle");
        Intent other = new Intent().putExtra("added", "value");
        check(target.putExtras(other) == target, "fluent intent merge");
        other.putExtra("added", "changed");
        check("value".equals(target.getStringExtra("added")), "no source intent alias");
        check("original".equals(target.getStringExtra("keep")), "intent merge preserves keys");
        target.putExtras(new Intent());
        check("value".equals(target.getStringExtra("added")), "empty intent is no-op");
        Intent copied = new Intent().putExtras(other);
        other.putExtra("added", "later");
        check("changed".equals(copied.getStringExtra("added")), "first intent merge copies");
    }
    static void input() throws Exception {
        final Method[] called = new Method[1]; final Object[][] received = new Object[1][];
        InvocationHandler handler = new InvocationHandler() {
          public Object invoke(Object proxy, Method method, Object[] args) {
            called[0] = method; received[0] = args;
            if (method.getReturnType() == boolean.class) return true;
            if (method.getReturnType() == int.class) return 37;
            if (method.getReturnType() == CharSequence.class) return "returned";
            return null;
          }
        };
        InputConnection target = (InputConnection) Proxy.newProxyInstance(
            TestContracts.class.getClassLoader(), new Class<?>[]{InputConnection.class}, handler);
        InputConnection connection = new InputConnectionWrapper(target, true);
        for (Method method : InputConnection.class.getDeclaredMethods()) {
            Object[] args = new Object[method.getParameterTypes().length];
            for (int i=0; i<args.length; i++) {
                Class<?> type = method.getParameterTypes()[i];
                args[i] = type == int.class ? Integer.valueOf(i+11) :
                    type == boolean.class ? Boolean.TRUE : type == CharSequence.class ? "typed" : null;
            }
            called[0] = null;
            Object result = method.invoke(connection, args);
            check(method.equals(called[0]), "delegation: " + method.getName());
            for (int i=0; i<args.length; i++)
                check(java.util.Objects.equals(args[i], received[0][i]), "argument forwarding");
            if (method.getReturnType() == boolean.class) check(Boolean.TRUE.equals(result), "boolean result");
            if (method.getReturnType() == int.class) check(Integer.valueOf(37).equals(result), "int result");
            if (method.getReturnType() == CharSequence.class) check("returned".equals(result), "text result");
        }
        InputConnectionWrapper fixed = new InputConnectionWrapper(target, false);
        try { fixed.setTarget(null); throw new AssertionError("immutable wrapper accepted replacement"); }
        catch (SecurityException expected) { checks++; }
        InputConnectionWrapper initiallyEmpty = new InputConnectionWrapper(null, false);
        initiallyEmpty.setTarget(target);
        check(initiallyEmpty.commitText("hello", 1), "first target allowed");
        InputConnection replacement = (InputConnection) Proxy.newProxyInstance(
            TestContracts.class.getClassLoader(), new Class<?>[]{InputConnection.class},
            new InvocationHandler() { public Object invoke(Object p, Method m, Object[] a) { return m.getReturnType() == boolean.class ? false : null; } });
        ((InputConnectionWrapper)connection).setTarget(replacement);
        check(!connection.commitText("hello", 1), "mutable replacement used");
    }
    public static void main(String[] args) throws Exception {
        if (args[0].equals("extras")) extras();
        else if (args[0].equals("input")) input();
        else {
            WifiInfo info = new WifiInfo();
            check(info.getLinkSpeed() == -1, "unknown speed");
            check(info.getRssi() == -127, "unknown signal");
        }
        System.out.println("PASS " + args[0] + " checks=" + checks);
        // Standalone ART has a known teardown failure; halt only after all assertions.
        System.out.flush();
        Runtime.getRuntime().halt(0);
    }
}
