// SPDX-License-Identifier: GPL-3.0-only
package org.atl.providercaller;
import android.app.Activity;
import android.content.*;
import android.os.*;
/**
 * The caller side of tests/provider-call. With no ATL_PROVIDER_ROW it runs the
 * original cross-package provider-call suite and prints PROVIDER_CALL_PASS.
 * ATL_PROVIDER_ROW selects one provider-publication row (see README.md); each
 * row prints "PROVIDER_CALL_PASS <row>".
 */
public class Probe extends Activity {
    private static void check(boolean b,String m) { if (!b) throw new AssertionError(m); }
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        String configured = System.getenv("ATL_PROVIDER_ROW");
        final String row = configured == null || configured.isEmpty() ? "legacy" : configured;
        new Thread(new Runnable() { public void run() {
            try {
                if ("legacy".equals(row)) { test(); System.out.println("PROVIDER_CALL_PASS"); }
                else { rows(row); System.out.println("PROVIDER_CALL_PASS " + row); }
                System.exit(0);
            } catch (Throwable t) {
                t.printStackTrace();
                System.out.println("PROVIDER_CALL_FAIL " + row + ": " + t);
                System.exit(1);
            }
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
        try { a.call("badparcel",null,null); throw new AssertionError("partial Bundle accepted"); }
        catch (IllegalArgumentException expected) { check(expected.toString().contains("intentional parcel failure"),"partial reply replaced by exception"); }
        android.database.Cursor cursor = a.query(android.net.Uri.parse("content://org.atl.owner/rows"),null,null,null,null);
        check(cursor.getCount()==1 && cursor.moveToFirst() && getPackageName().equals(cursor.getString(0))
            && cursor.getLong(1)==12345678901L && cursor.getDouble(2)==1.25 && cursor.isNull(3)
            && cursor.getBlob(4)[2]==3, "typed query snapshot");
        cursor.close();
        try { a.query(android.net.Uri.parse("content://org.atl.owner/large"),null,null,null,null); throw new AssertionError("oversized query accepted"); }
        catch (UnsupportedOperationException expected) {}
        for (String path : new String[]{"utfbytes", "widecolumns"}) {
            try { a.query(android.net.Uri.parse("content://org.atl.owner/"+path),null,null,null,null); throw new AssertionError("serialized byte bound not enforced: "+path); }
            catch (UnsupportedOperationException expected) {}
        }
        cursor = a.query(android.net.Uri.parse("content://org.atl.owner/rows"),null,null,null,null);
        check(cursor.getCount()==1,"failed cursor must not poison the next RPC"); cursor.close();
        check(a.release() && !a.release(),"release once");
        try { a.call("echo",null,null); throw new AssertionError("use after release"); }
        catch (IllegalStateException expected) {}
        check(b.call("echo",null,null).getInt("calls") == 3,"other client survives release");
        try { b.call("die",null,null); throw new AssertionError("death reported success"); }
        catch (Exception expected) {}
        b.release();
        ContentProviderClient c = resolver.acquireContentProviderClient("org.atl.owner");
        check(c != null && c.call("echo",null,null).getInt("calls") == 1,"reacquire after death");
        check(resolver.call(android.net.Uri.parse("content://org.atl.owner"),"echo","uri",null).getInt("calls")==2,"resolver URI call");
        check(resolver.call("org.atl.owner","echo","authority",null).getInt("calls")==3,"resolver authority call");
        try { resolver.call("org.atl.missing","echo",null,null); throw new AssertionError("unknown call accepted"); }
        catch (IllegalArgumentException expected) {}
        check(c.call("echo",null,null).getInt("calls")==4,"resolver releases only its own reference");
        c.close();
    }

    // ------------------------------------------------------------------
    // Provider-publication rows (ATL_PROVIDER_ROW).
    // ------------------------------------------------------------------

    private void rows(String row) throws Exception {
        if ("init-order".equals(row)) {
            ContentProviderClient c=acquire("org.atl.order.low");
            check(c!=null && c.call("order",null,null).getBoolean("ordered"),"initOrder descending and disabled exclusion");
            check(acquire("org.atl.order.disabled")==null,"disabled provider must not resolve");
            c.close(); return;
        }
        if ("ctor-throw".equals(row)) { unavailableTwice("org.atl.latch.ctorthrow"); return; }
        if ("attach-throw".equals(row)) { unavailableTwice("org.atl.latch.attachthrow"); return; }
        if ("oncreate-throw".equals(row)) { unavailableTwice("org.atl.latch.oncreatethrow"); return; }
        if ("reentrant".equals(row)) { reentrant(); return; }
        if ("oncreate-false".equals(row)) { onCreateFalse(); return; }
        if ("retry-after-failure".equals(row)) { retryAfterFailure(); return; }
        if ("single-init".equals(row)) { concurrentAcquire(); return; }
        if ("timeout".equals(row)) { timeoutAndLateCompletion(); return; }
        if ("interrupted-waiter".equals(row)) { interruptedWaiter(); return; }
        throw new IllegalArgumentException("unknown row " + row);
    }

    private ContentProviderClient acquire(String authority) {
        return getContentResolver().acquireContentProviderClient(authority);
    }

    /** A provider that cannot be installed must never become visible, and the retry must not succeed either. */
    private void unavailableTwice(String authority) {
        for (int access = 1; access <= 2; access++) {
            ContentProviderClient client = acquire(authority);
            check(client == null, authority + " must stay unpublished, access " + access);
        }
    }

    private void onCreateFalse() throws Exception {
        ContentProviderClient client = acquire("org.atl.latch");
        check(client != null, "a provider whose onCreate returns false must still be published");
        Bundle b = client.call("latch", null, null);
        check(b.getBoolean("onDone") && !b.getBoolean("onReturned"),
              "onCreate must have completed and returned false");
        check(b.getInt("ctors") == 1 && b.getInt("ons") == 1 && b.getInt("attaches") == 1,
              "attachInfo/onCreate run once per instance: ctors=" + b.getInt("ctors")
              + " ons=" + b.getInt("ons") + " attaches=" + b.getInt("attaches"));
        client.release();
    }

    private void reentrant() throws Exception {
        ContentProviderClient client = acquire("org.atl.latch.reentrant");
        check(client != null, "the re-entrant provider must publish");
        Bundle b = client.call("latch", null, null);
        check(b.getBoolean("reentrantCtorNull") && b.getBoolean("reentrantOnCreateNull"),
              "a provider resolving its own authority while installing must get null, not deadlock");
        check(b.getInt("token") == 1 && b.getInt("ctors") == 1 && b.getInt("ons") == 1 && b.getBoolean("onDone"),
              "one install");
        client.release();
    }

    /**
     * FailTwiceOwner fails its first two onCreate calls. The boot attempt and later accesses
     * must consume these failures without publishing either instance. Whichever thread wins the race, the published token must be 3:
     * the two failed instances were never published and no access installed twice.
     */
    private void retryAfterFailure() throws Exception {
        String authority = "org.atl.latch.failtwice";
        Thread.sleep(1000);
        int published = -1;
        for (int access = 1; access <= 3 && published < 0; access++) {
            ContentProviderClient client = acquire(authority);
            if (client == null) continue;
            Bundle b = client.call("latch", null, null);
            check(b.getInt("token") == 3 && b.getInt("ctors") == 3 && b.getInt("ons") == 3 && b.getBoolean("onDone"),
                  "the failed installs must not publish and the retry must be the third one: token="
                  + b.getInt("token") + " ctors=" + b.getInt("ctors") + " ons=" + b.getInt("ons"));
            published = b.getInt("token");
            client.release();
        }
        check(published == 3, "no access published the provider after the two failures");
    }

    /**
     * Four accesses started together must all end at the same instance, installed
     * once (ATL_LATCH_SLEEP_MS makes the install slow enough to overlap them).
     */
    private void concurrentAcquire() throws Exception {
        final String authority = "org.atl.latch";
        final int count = 4;
        final ContentProviderClient[] clients = new ContentProviderClient[count];
        final Throwable[] failures = new Throwable[count];
        Thread[] threads = gatedAcquire(count, authority, clients, failures);
        for (int i = 0; i < count; i++) threads[i].join(120000);
        for (int i = 0; i < count; i++) {
            check(failures[i] == null, "concurrent acquire " + i + " threw " + failures[i]);
            check(clients[i] != null, "concurrent acquire " + i + " must wait for the install instead of failing");
            Bundle b = clients[i].call("latch", null, null);
            check(b.getInt("token") == 1 && b.getInt("ctors") == 1 && b.getInt("ons") == 1 && b.getInt("attaches") == 1,
                  "all waiters must see the one install: access " + i + " token=" + b.getInt("token")
                  + " ctors=" + b.getInt("ctors") + " ons=" + b.getInt("ons") + " attaches=" + b.getInt("attaches"));
            check(b.getBoolean("onDone"), "the published provider must have finished onCreate");
            check(b.getInt("pid") != android.os.Process.myPid(), "the owner must be another process");
            clients[i].release();
        }
    }

    /**
     * ATL_LATCH_SLEEP_MS is 3 s here and the loader's bound
     * (ATL_PROVIDER_READY_TIMEOUT_MS) is 250 ms, so accesses started together
     * cannot all return the provider. Whichever way the race goes, the install
     * that is still running must stay the one publication record: a timed-out
     * access must not leave a second instance behind, and a later access must
     * adopt the record and see the same single install.
     */
    private void timeoutAndLateCompletion() throws Exception {
        final String authority = "org.atl.latch";
        final int count = 2;
        final ContentProviderClient[] clients = new ContentProviderClient[count];
        final Throwable[] failures = new Throwable[count];
        final long gatedAt = System.nanoTime();
        Thread[] threads = gatedAcquire(count, authority, clients, failures);
        for (int i = 0; i < count; i++) threads[i].join(120000);
        System.out.println("timeout row: gated->join " + ((System.nanoTime() - gatedAt) / 1000000L) + " ms"
                           + " clients=" + (clients[0] != null) + "," + (clients[1] != null)
                           + " failures=" + failures[0] + "," + failures[1]);
        int unavailable = 0;
        for (int i = 0; i < count; i++) {
            check(failures[i] == null, "concurrent acquire " + i + " threw " + failures[i]);
            if (clients[i] == null) { unavailable++; continue; }
            Bundle b = clients[i].call("latch", null, null);
            check(b.getInt("token") == 1 && b.getInt("ctors") == 1,
                  "an access that outlived the bound must not install again: token=" + b.getInt("token")
                  + " ctors=" + b.getInt("ctors"));
            clients[i].release();
        }
        check(unavailable >= 1, "an install longer than the 250 ms bound must time out at least one concurrent access");
        /* The install the timed-out callers left behind is still running. Poll with the
         * same 250 ms bound: once it publishes, the next access must adopt THAT install. */
        ContentProviderClient later = null;
        for (int attempt = 1; attempt <= 80 && later == null; attempt++) {
            later = acquire(authority);
            if (later == null) Thread.sleep(200);
        }
        check(later != null, "a later access must adopt the install the timed-out access gave up on");
        Bundle b = later.call("latch", null, null);
        check(b.getInt("token") == 1 && b.getInt("ctors") == 1 && b.getInt("ons") == 1 && b.getBoolean("onDone"),
              "the timed-out access must not have started a second install: token=" + b.getInt("token")
              + " ctors=" + b.getInt("ctors") + " ons=" + b.getInt("ons") + " onDone=" + b.getBoolean("onDone"));
        later.release();
    }

    /**
     * LatchOwner interrupts a second thread that is waiting on its own authority
     * from inside onCreate: the waiter must be released with null and keep its
     * interrupt status, and the install must still publish the same instance.
     */
    private void interruptedWaiter() throws Exception {
        ContentProviderClient client = acquire("org.atl.latch");
        check(client != null, "an interrupted waiter must not stop the install");
        Bundle b = client.call("latch", null, null);
        check(b.getBoolean("helperRan"), "the in-install waiter must have run");
        check(b.getBoolean("helperNull"), "the interrupted waiter must be released with null");
        check(b.getBoolean("helperInterrupted"), "the released waiter must keep its interrupt status");
        check(b.getInt("token") == 1 && b.getInt("ctors") == 1 && b.getInt("ons") == 1 && b.getBoolean("onDone"),
              "the install must survive the interrupted waiter: token=" + b.getInt("token")
              + " ctors=" + b.getInt("ctors") + " ons=" + b.getInt("ons"));
        client.release();
    }

    /** Starts count accesses of one authority as simultaneously as the scheduler allows. */
    private Thread[] gatedAcquire(final int count, final String authority,
                                  final ContentProviderClient[] clients, final Throwable[] failures) throws Exception {
        final Object gate = new Object();
        final boolean[] go = new boolean[1];
        Thread[] threads = new Thread[count];
        for (int i = 0; i < count; i++) {
            final int index = i;
            threads[i] = new Thread(new Runnable() { public void run() {
                synchronized (gate) {
                    while (!go[0]) {
                        try { gate.wait(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
                    }
                }
                try { clients[index] = acquire(authority); }
                catch (Throwable t) { failures[index] = t; }
            }}, "acquire-" + i);
            threads[i].start();
        }
        synchronized (gate) { go[0] = true; gate.notifyAll(); }
        return threads;
    }
}
