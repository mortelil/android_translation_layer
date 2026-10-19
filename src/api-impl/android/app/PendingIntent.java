/*
 * Copyright (C) 2006 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.app;

import android.content.*;
import android.os.*;
import java.util.*;
import java.lang.ref.*;

/**
 * Creator-owned Binder capability. Unlike Android's system-server records,
 * ATL records currently live in the creator process and die with that process.
 * Reference: AOSP frameworks/base 94b4c163b7dfe5ce3607f7bb8456f9573f7de57d,
 * PendingIntent, IIntentSender and PendingIntentRecord. No system-server code copied.
 */
public final class PendingIntent implements Parcelable {
    public static final int FLAG_ONE_SHOT = 1 << 30, FLAG_NO_CREATE = 1 << 29, FLAG_CANCEL_CURRENT = 1 << 28,
                            FLAG_UPDATE_CURRENT = 1 << 27, FLAG_IMMUTABLE = 1 << 26, FLAG_MUTABLE = 1 << 25,
                            FLAG_MUTABLE_UNAUDITED = FLAG_MUTABLE,
                            FLAG_ALLOW_UNSAFE_IMPLICIT_INTENT = 1 << 24;
    private static final int SEND = 1, CANCEL = 2, INFO = 3;
    private static final String DESCRIPTOR = "android.atl.PendingIntent";
    private static final Map<Key, RecordRef> records = new HashMap<>();
    private static final ReferenceQueue<Record> collected = new ReferenceQueue<>();
    private static final class RecordRef extends WeakReference<Record> {
        final Key key;
        RecordRef(Record operation) {
            super(operation, collected);
            key = operation.key;
        }
    }
    private static Record lookup(Key key) {
        RecordRef ref = records.get(key);
        return ref == null ? null : ref.get();
    }
    private static void reap() {
        RecordRef ref;
        while ((ref = (RecordRef)collected.poll()) != null)
            if (records.get(ref.key) == ref)
                records.remove(ref.key);
    }
    private final IBinder token;
    private PendingIntent(IBinder token) {
        if (token == null)
            throw new IllegalArgumentException("Null PendingIntent token");
        this.token = token;
    }
    private static final class Key {
        final String owner;
        final int uid, request, type, flags;
        final Intent filter;
        Key(Context c, int request, Intent intent, int type, int flags) {
            owner = c.getPackageName();
            uid = c.getApplicationInfo().uid;
            this.request = request;
            this.type = type;
            this.flags = flags;
            filter = new Intent(intent);
            filter.replaceExtras((Bundle)null);
        }
        public int hashCode() {
            return Objects.hash(owner, uid, request, type, flags, filter.getAction(), filter.getData(),
                                filter.getType(), filter.getComponent(), filter.getPackage(),
                                filter.getIdentifier(), filter.getCategories());
        }
        public boolean equals(Object obj) {
            if (!(obj instanceof Key))
                return false;
            Key k = (Key)obj;
            return uid == k.uid && request == k.request && type == k.type && flags == k.flags &&
                owner.equals(k.owner) && Objects.equals(filter.getAction(), k.filter.getAction()) &&
                Objects.equals(filter.getData(), k.filter.getData()) &&
                Objects.equals(filter.getType(), k.filter.getType()) &&
                Objects.equals(filter.getComponent(), k.filter.getComponent()) &&
                Objects.equals(filter.getPackage(), k.filter.getPackage()) &&
                Objects.equals(filter.getIdentifier(), k.filter.getIdentifier()) &&
                Objects.equals(filter.getCategories(), k.filter.getCategories());
        }
    }
    private static Intent snapshot(Intent intent, ClassLoader loader) {
        Parcel p = Parcel.obtain();
        try {
            intent.writeToParcel(p, 0);
            p.setDataPosition(0);
            return ((Parcelable.ClassLoaderCreator<Intent>)Intent.CREATOR).createFromParcel(p, loader);
        } finally {
            p.recycle();
        }
    }
    private static PendingIntent obtain(Context context, int request, Intent intent, int flags, int type,
                                        Bundle options) {
        if (options != null && !options.isEmpty())
            throw new UnsupportedOperationException("PendingIntent launch options");
        if ((flags & FLAG_IMMUTABLE) != 0 && (flags & FLAG_MUTABLE) != 0)
            throw new IllegalArgumentException("Conflicting mutability flags");
        if (context.getApplicationInfo().targetSdkVersion >= 31 &&
            (flags & (FLAG_IMMUTABLE | FLAG_MUTABLE)) == 0)
            throw new IllegalArgumentException("PendingIntent mutability must be explicit");
        if ((flags & FLAG_MUTABLE) != 0 && context.getApplicationInfo().targetSdkVersion >= 34 &&
            intent.getComponent() == null && intent.getPackage() == null &&
            (flags & FLAG_ALLOW_UNSAFE_IMPLICIT_INTENT) == 0 && (flags & FLAG_NO_CREATE) == 0)
            throw new IllegalArgumentException("Mutable implicit PendingIntent");
        // ClipData is not yet implemented by ATL's Intent codec.
        if ((flags & 128) != 0)
            throw new UnsupportedOperationException("PendingIntent ClipData fill-in");
        Context owner = context.getApplicationContext();
        if (owner == null)
            owner = context;
        int identityFlags = flags & ~(FLAG_NO_CREATE | FLAG_CANCEL_CURRENT | FLAG_UPDATE_CURRENT);
        Key key = new Key(owner, request, intent, type, identityFlags);
        synchronized (records) {
            reap();
            Record operation = lookup(key);
            if (operation != null && (flags & FLAG_CANCEL_CURRENT) != 0) {
                operation.canceled = true;
                records.remove(key);
                operation = null;
            }
            if (operation == null) {
                if ((flags & FLAG_NO_CREATE) != 0)
                    return null;
                operation = new Record(key, owner, snapshot(intent, owner.getClassLoader()));
                records.put(key, new RecordRef(operation));
            } else if ((flags & FLAG_UPDATE_CURRENT) != 0)
                operation.intent.replaceExtras(snapshot(intent, owner.getClassLoader()).getExtras());
            return new PendingIntent(operation);
        }
    }
    public static PendingIntent getActivity(Context c, int r, Intent i, int f) {
        return obtain(c, r, i, f, 0, null);
    }
    public static PendingIntent getActivity(Context c, int r, Intent i, int f, Bundle o) {
        return obtain(c, r, i, f, 0, o);
    }
    public static PendingIntent getService(Context c, int r, Intent i, int f) {
        return obtain(c, r, i, f, 1, null);
    }
    public static PendingIntent getBroadcast(Context c, int r, Intent i, int f) {
        return obtain(c, r, i, f, 2, null);
    }
    public static PendingIntent getForegroundService(Context c, int r, Intent i, int f) {
        return obtain(c, r, i, f, 3, null);
    }
    public static PendingIntent getActivities(Context c, int r, Intent[] i, int f) {
        return getActivities(c, r, i, f, null);
    }
    public static PendingIntent getActivities(Context c, int r, Intent[] i, int f, Bundle o) {
        if (i == null || i.length == 0)
            throw new IllegalArgumentException("Empty activity stack");
        if (i.length != 1)
            throw new UnsupportedOperationException("PendingIntent activity stacks");
        return obtain(c, r, i[0], f, 0, o);
    }
    // Adapted from AOSP Intent.fillIn at the revision cited above, for fields ATL supports.
    private static void fill(Intent dst, Intent src, int flags) {
        if (src.getAction() != null && (dst.getAction() == null || (flags & 1) != 0))
            dst.setAction(src.getAction());
        if ((src.getData() != null || src.getType() != null) &&
            ((dst.getData() == null && dst.getType() == null) || (flags & 2) != 0))
            dst.setDataAndType(src.getData(), src.getType());
        if (src.getCategories() != null &&
            (dst.getCategories() == null || dst.getCategories().isEmpty() || (flags & 4) != 0)) {
            if (dst.getCategories() != null)
                for (String c : new HashSet<String>(dst.getCategories()))
                    dst.removeCategory(c);
            for (String c : src.getCategories())
                dst.addCategory(c);
        }
        if (src.getIdentifier() != null && (dst.getIdentifier() == null || (flags & 256) != 0))
            dst.setIdentifier(src.getIdentifier());
        if (src.getPackage() != null && (dst.getPackage() == null || (flags & 16) != 0) &&
            dst.getSelector() == null)
            dst.setPackage(src.getPackage());
        if (src.getSelector() != null && (flags & 64) != 0 && dst.getPackage() == null)
            dst.setSelector(new Intent(src.getSelector()));
        if (src.getComponent() != null && (flags & 8) != 0)
            dst.setComponent(src.getComponent());
        if (src.getSourceBounds() != null && (dst.getSourceBounds() == null || (flags & 32) != 0))
            dst.setSourceBounds(new android.graphics.Rect(src.getSourceBounds()));
        dst.addFlags(src.getFlags());
        Bundle extras = src.getExtras() == null ? new Bundle() : new Bundle(src.getExtras());
        if (dst.getExtras() != null)
            extras.putAll(dst.getExtras()); // creator's extras win
        dst.replaceExtras(extras);
    }
    private static final class Record extends Binder {
        final Key key;
        final Context owner;
        Intent intent;
        boolean canceled;
        Record(Key key, Context owner, Intent intent) {
            this.key = key;
            this.owner = owner;
            this.intent = intent;
            attachInterface(null, DESCRIPTOR);
        }
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
            if (code != SEND && code != CANCEL && code != INFO)
                return super.onTransact(code, data, reply, flags);
            data.enforceInterface(DESCRIPTOR);
            if (code == INFO) {
                reply.writeString(key.owner);
                reply.writeInt(key.uid);
                reply.writeInt(key.flags);
                return true;
            }
            synchronized (records) {
                if (code == CANCEL) {
                    canceled = true;
                    if (lookup(key) == this)
                        records.remove(key);
                    return true;
                }
                if (canceled) {
                    reply.writeInt(-1);
                    return true;
                }
                final Intent sent = snapshot(intent, owner.getClassLoader());
                Intent extra = data.readInt() != 0 ? ((Parcelable.ClassLoaderCreator<Intent>)Intent.CREATOR)
                                                         .createFromParcel(data, owner.getClassLoader())
                                                   : null;
                if (extra != null && (key.flags & FLAG_IMMUTABLE) == 0)
                    fill(sent, extra, key.flags);
                if ((key.flags & FLAG_ONE_SHOT) != 0) {
                    canceled = true;
                    if (lookup(key) == this)
                        records.remove(key);
                }
                // Like system-server delivery, enqueue on the owning app's main thread.
                // Never synchronously wait for it from a Binder worker (nested calls deadlock).
                boolean queued = new Handler(Looper.getMainLooper()).post(new Runnable() {
                    public void run() {
                        long identity = Binder.clearCallingIdentity();
                        try {
                            switch (key.type) {
                            case 0:
                                owner.startActivity(sent);
                                break;
                            case 1:
                                owner.startService(sent);
                                break;
                            case 2:
                                owner.sendBroadcast(sent);
                                break;
                            case 3:
                                owner.startForegroundService(sent);
                                break;
                            }
                        } finally {
                            Binder.restoreCallingIdentity(identity);
                        }
                    }
                });
                reply.writeInt(queued ? 0 : -1);
                return true;
            }
        }
    }
    public void send() throws CanceledException { send(null, 0, null); }
    public void send(int code) throws CanceledException { send(null, code, null); }
    public void send(Context ignored, int code, Intent fillIn) throws CanceledException {
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            data.writeInt(fillIn == null ? 0 : 1);
            if (fillIn != null)
                fillIn.writeToParcel(data, 0);
            if (!token.transact(SEND, data, reply, 0) || reply.readInt() != 0)
                throw new CanceledException();
        } catch (Exception e) {
            throw new CanceledException(e);
        } finally {
            data.recycle();
            reply.recycle();
        }
    }
    public void atlSendFromNotification() {
        try {
            send();
        } catch (CanceledException canceled) {
            android.util.Log.i("PendingIntent", "Ignoring canceled notification action");
        }
    }
    public void cancel() {
        Parcel d = Parcel.obtain(), r = Parcel.obtain();
        try {
            d.writeInterfaceToken(DESCRIPTOR);
            token.transact(CANCEL, d, r, 0);
        } catch (Exception e) {
            if (!(e instanceof RemoteException)) throw new IllegalStateException("Cannot cancel PendingIntent", e);
        } finally {
            d.recycle();
            r.recycle();
        }
    }
    private Parcel info() {
        Parcel d = Parcel.obtain(), r = Parcel.obtain();
        try {
            d.writeInterfaceToken(DESCRIPTOR);
            if (!token.transact(INFO, d, r, 0))
                throw new IllegalStateException("PendingIntent owner unavailable");
            return r;
        } catch (Exception e) {
            r.recycle();
            throw new IllegalStateException("PendingIntent owner unavailable", e);
        } finally {
            d.recycle();
        }
    }
    public String getCreatorPackage() {
        Parcel r = info();
        try {
            return r.readString();
        } finally {
            r.recycle();
        }
    }
    public String getTargetPackage() { return getCreatorPackage(); }
    public int getCreatorUid() {
        Parcel r = info();
        try {
            r.readString();
            return r.readInt();
        } finally {
            r.recycle();
        }
    }
    public boolean isImmutable() {
        Parcel r = info();
        try {
            r.readString();
            r.readInt();
            return (r.readInt() & FLAG_IMMUTABLE) != 0;
        } finally {
            r.recycle();
        }
    }
    public IntentSender getIntentSender() { return new IntentSender(this); }
    public int describeContents() { return 0; }
    public void writeToParcel(Parcel out, int flags) { out.writeStrongBinder(token); }
    public static final Parcelable.Creator<PendingIntent> CREATOR = new Parcelable.Creator<PendingIntent>() {
        public PendingIntent createFromParcel(Parcel in) { return new PendingIntent(in.readStrongBinder()); }
        public PendingIntent[] newArray(int size) { return new PendingIntent[size]; }
    };
    public static void writePendingIntentOrNullToParcel(PendingIntent sender, Parcel out) {
        out.writeStrongBinder(sender == null ? null : sender.token);
    }
    public static PendingIntent readPendingIntentOrNullFromParcel(Parcel in) {
        IBinder b = in.readStrongBinder();
        return b == null ? null : new PendingIntent(b);
    }
    public boolean equals(Object o) {
        return o instanceof PendingIntent && token.equals(((PendingIntent)o).token);
    }
    public int hashCode() { return token.hashCode(); }
    public static class CanceledException extends Exception {
        public CanceledException() {}
        public CanceledException(String message) { super(message); }
        public CanceledException(Exception cause) { super(cause); }
    }
}
