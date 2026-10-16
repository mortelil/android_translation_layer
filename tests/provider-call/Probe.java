// SPDX-License-Identifier: GPL-3.0-only
package org.atl.providercaller;
import android.app.Activity;
import android.content.*;
import android.os.*;
public class Probe extends Activity {
    private static void check(boolean b,String m) { if (!b) throw new AssertionError(m); }
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        new Thread(new Runnable() { public void run() {
            try { test(); System.out.println("PROVIDER_CALL_PASS"); System.exit(0); }
            catch (Throwable t) { t.printStackTrace(); System.exit(1); }
        }}, "provider-test").start();
    }
    private void test() throws Exception {
        ContentResolver resolver = getContentResolver();
        check(resolver.acquireContentProviderClient("org.atl.missing") == null,"unknown authority");
        for (String authority : new String[]{"org.atl.private", "org.atl.protected"}) {
            try { resolver.acquireContentProviderClient(authority); throw new AssertionError("access allowed: "+authority); }
            catch (SecurityException expected) {}
        }
        ContentProviderClient a = resolver.acquireContentProviderClient("org.atl.owner");
        ContentProviderClient b = resolver.acquireContentProviderClient("org.atl.owner.alias");
        check(a != null && b != null,"acquire");
        check(a.getLocalContentProvider() == null,"remote must not appear local");
        Bundle extras = new Bundle(); extras.putInt("value", 42);
        Intent intent = new Intent("probe.ACTION").setPackage("org.atl.providerowner")
            .setComponent(new ComponentName("org.atl.providerowner", "Example"))
            .setDataAndType(android.net.Uri.parse("content://org.atl.owner/item"), "text/plain")
            .setFlags(123).addCategory("probe.CATEGORY").putExtra("nested", "yes");
        extras.putParcelable("intent", intent);
        Bundle result = a.call("echo", "argument", extras);
        check("org.atl.providerowner".equals(result.getString("owner")),"owner context");
        check(getPackageName().equals(result.getString("caller")),"caller identity");
        check(getPackageName().equals(result.getString("callerProcessPackage")),"caller process lookup");
        Intent roundTrip = result.getParcelable("intent");
        check(roundTrip != intent && "probe.ACTION".equals(roundTrip.getAction())
            && "org.atl.providerowner".equals(roundTrip.getPackage()) && roundTrip.getFlags() == 123
            && "Example".equals(roundTrip.getComponent().getClassName())
            && "text/plain".equals(roundTrip.getType()) && roundTrip.hasCategory("probe.CATEGORY")
            && "yes".equals(roundTrip.getStringExtra("nested")), "Intent round trip");
        check(result.getParcelableArray("accounts").length == 0, "empty parcelable account array");
        check(result.getInt("pid") != android.os.Process.myPid(),"separate process");
        check(result.getInt("callerPid") == android.os.Process.myPid(),"authenticated caller pid");
        check(result.getInt("value") == 42 && "argument".equals(result.getString("arg")),"arguments");
        check(b.call("echo",null,null).getInt("calls") == 2,"shared provider aliases");
        try { a.call("throw",null,null); throw new AssertionError("exception swallowed"); }
        catch (RuntimeException expected) { check(expected.toString().contains("probe rejection"),"exception content"); }
        android.database.Cursor cursor = a.query(android.net.Uri.parse("content://org.atl.owner/rows"),null,null,null,null);
        check(cursor.getCount()==1 && cursor.moveToFirst() && getPackageName().equals(cursor.getString(0))
            && cursor.getLong(1)==12345678901L && cursor.getDouble(2)==1.25 && cursor.isNull(3)
            && cursor.getBlob(4)[2]==3, "typed query snapshot");
        cursor.close();
        try { a.query(android.net.Uri.parse("content://org.atl.owner/large"),null,null,null,null); throw new AssertionError("oversized query accepted"); }
        catch (UnsupportedOperationException expected) {}
        check(a.release() && !a.release(),"release once");
        try { a.call("echo",null,null); throw new AssertionError("use after release"); }
        catch (IllegalStateException expected) {}
        check(b.call("echo",null,null).getInt("calls") == 3,"other client survives release");
        try { b.call("die",null,null); throw new AssertionError("death reported success"); }
        catch (Exception expected) {}
        b.release();
        ContentProviderClient c = resolver.acquireContentProviderClient("org.atl.owner");
        check(c != null && c.call("echo",null,null).getInt("calls") == 1,"reacquire after death");
        c.close();
    }
}
