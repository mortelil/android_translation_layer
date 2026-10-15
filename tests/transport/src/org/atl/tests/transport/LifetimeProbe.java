/* SPDX-License-Identifier: GPL-3.0-only */
package org.atl.tests.transport;

import android.os.*;
import java.io.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** Ownership regressions against the production transport, not a model of it. */
final class LifetimeProbe {
    static void require(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
    static IBinder value() {
        return new Binder() {
            protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
                reply.writeInt(42); return true;
            }
        };
    }
    static void ping(IBinder binder) {
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try { require(binder.transact(1, data, reply, 0) && reply.readInt() == 42, "retained proxy stopped working"); }
        finally { data.recycle(); reply.recycle(); }
    }
    static IBinder get(IBinder root, int code) {
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try { require(root.transact(code, data, reply, 0), "export refused"); return reply.readStrongBinder(); }
        finally { data.recycle(); reply.recycle(); }
    }
    static void collected(ATLBinderConnection owner, int expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (LinkTools.exportTableSize(owner) != expected && System.nanoTime() < deadline) {
            System.gc(); System.runFinalization(); Thread.sleep(20);
        }
        require(LinkTools.exportTableSize(owner) == expected,
            "automatic reclamation: expected " + expected + ", got " + LinkTools.exportTableSize(owner));
    }
    static void churn(IBinder root) throws Exception {
        // No reflective drop, no explicit connection cleanup between transfers.
        for (int i = 0; i < 64; i++) ping(get(root, 1));
    }
    static void repeated(IBinder root, IBinder retained) {
        for (int i = 0; i < 64; i++) require(get(root, 2) == retained, "live proxy identity changed");
    }
    static void ownership() throws Exception {
        final IBinder shared = value();
        int[] pair = ATLBinderConnection.socketPair();
        ATLBinderConnection owner = new ATLBinderConnection(pair[0], -1, new Binder() {
            protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
                reply.writeStrongBinder(code == 2 ? shared : value()); return true;
            }
        });
        ATLBinderConnection client = new ATLBinderConnection(pair[1], -1, new Binder());
        owner.start(); client.start();
        try {
            IBinder root = client.remote(1);
            retainAndChurn(root, owner);
            collected(owner, 1);
            System.out.println("LIFETIME PASS normal-api-churn=4224 repeated-transfer parcel-owner gc-release");
        } finally { client.close(); owner.close(); }
    }
    static void retainAndChurn(IBinder root, ATLBinderConnection owner) throws Exception {
            IBinder retained = get(root, 2);
            Parcel holder = Parcel.obtain(); holder.writeStrongBinder(retained);
            repeated(root, retained);
            for (int batch = 0; batch < 66; batch++) {
                churn(root); collected(owner, 2); ping(retained);
            }
            // Parcel is the only remaining owner of this proxy.
            retained = null;
            System.gc(); holder.setDataPosition(0); ping(holder.readStrongBinder());
            holder.recycle();
            // Recycled-but-still-reachable Parcels must no longer retain references.
            require(holder != null, "keep recycled holder reachable");
    }
    static void awaitDeferredRelease(ATLBinderConnection owner) throws Exception {
        java.lang.reflect.Field table = ATLBinderConnection.class.getDeclaredField("objects");
        table.setAccessible(true);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            synchronized (owner) {
                for (Object state : ((java.util.Map<?, ?>)table.get(owner)).values()) {
                    java.lang.reflect.Field acks = state.getClass().getDeclaredField("releaseAcks");
                    acks.setAccessible(true);
                    if (!((java.util.List<?>)acks.get(state)).isEmpty()) return;
                }
            }
            Thread.sleep(5);
        }
        throw new AssertionError("owner never deferred the in-flight release ACK");
    }
    static void inFlight() throws Exception {
        final CountDownLatch entered = new CountDownLatch(1), unblock = new CountDownLatch(1);
        final Binder blocking = new Binder() {
            protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
                entered.countDown();
                try { require(unblock.await(5, TimeUnit.SECONDS), "test never unblocked call"); }
                catch (InterruptedException e) { throw new RuntimeException(e); }
                reply.writeInt(42); return true;
            }
        };
        int[] pair = ATLBinderConnection.socketPair();
        ATLBinderConnection owner = new ATLBinderConnection(pair[0], -1, new Binder() {
            protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
                reply.writeStrongBinder(blocking); return true;
            }
        });
        final ATLBinderConnection client = new ATLBinderConnection(pair[1], -1, new Binder());
        owner.start(); client.start();
        final AtomicReference<Throwable> error = new AtomicReference<>();
        final CountDownLatch acknowledged = new CountDownLatch(1);
        try {
            final IBinder proxy = get(client.remote(1), 1);
            Thread caller = new Thread(new Runnable() { public void run() {
                try { ping(proxy); } catch (Throwable t) { error.set(t); }
            }});
            caller.start(); require(entered.await(3, TimeUnit.SECONDS), "call did not enter service");
            Thread release = new Thread(new Runnable() { public void run() {
                try { LinkTools.drop(client, proxy, true); acknowledged.countDown(); }
                catch (Throwable t) { error.set(t); }
            }});
            release.start();
            awaitDeferredRelease(owner);
            require(acknowledged.getCount() == 1, "release ACK preceded in-flight completion");
            unblock.countDown();
            caller.join(5000); release.join(5000);
            require(!caller.isAlive() && !release.isAlive() && error.get() == null, "pinned release failed: " + error.get());
            require(acknowledged.getCount() == 0, "deferred ACK lost");
            require(LinkTools.exportTableSize(owner) == 1, "pinned export leaked");
            System.out.println("LIFETIME PASS deferred-release-ack");
        } finally { unblock.countDown(); client.close(); owner.close(); }
    }
    static void publication() throws Exception {
        int[] pair = ATLBinderConnection.socketPair();
        final AtomicReference<ATLBinderConnection> connection = new AtomicReference<>();
        final AtomicReference<Throwable> error = new AtomicReference<>();
        final CountDownLatch released = new CountDownLatch(1);
        ATLBinderConnection client = new ATLBinderConnection(pair[0], -1, new Binder() {
            protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
                try { LinkTools.drop(connection.get(), data.readStrongBinder(), false); }
                catch (Throwable t) { error.set(t); }
                return true;
            }
        });
        connection.set(client); client.start();
        Thread receiver = new Thread(new Runnable() { public void run() {
            try {
                byte[] frame = LinkTools.rawReceive(pair[1], new int[65]);
                DataInputStream in = new DataInputStream(new ByteArrayInputStream(frame));
                require(in.readInt() == 0x41544c32, "wrong version"); in.readInt();
                require(in.readInt() == 3 && in.readInt() == 7000 && in.readInt() == 1,
                    "single transfer did not release its credit");
                released.countDown();
            } catch (Throwable t) { error.set(t); }
        }});
        receiver.setDaemon(true); receiver.start();
        try {
            // Single positive reference, no subsequent acquisition frame. The handler
            // releases immediately, deterministically exercising the former race.
            Parcel payload = Parcel.obtain(); payload.writeInt(0);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            for (int v : new int[]{0x41544c32, 1, 0, 1, 1, IBinder.FLAG_ONEWAY}) out.writeInt(v);
            out.writeLong(0); out.writeInt(0);
            byte[] data = payload.marshall(); out.writeInt(data.length); out.write(data);
            out.writeInt(1); out.writeInt(7000); out.writeInt(0);
            payload.recycle();
            LinkTools.rawSend(pair[1], bytes.toByteArray(), new int[0]);
            require(released.await(3, TimeUnit.SECONDS), "release lost before acquire: " + error.get());
            System.out.println("LIFETIME PASS publish-before-dispatch high-handle");
        } finally { client.close(); LinkTools.closeFd(pair[1]); receiver.join(1000); }
    }
    static void run() throws Exception { publication(); inFlight(); ownership(); }
}
