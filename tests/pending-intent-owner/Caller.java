package org.atl.pendingprobe;
import android.app.*;
import android.content.*;
import android.os.*;
import java.util.*;
public class Caller extends Activity {
    static boolean isAtl() { try { Class.forName("android.atl.ATLLoadedApp"); return true; } catch (ClassNotFoundException android) { return false; } }
    Handler h = new Handler();
    int checks;
    IBinder service;
    PendingIntent remote;
    final List<String> deliveries = new ArrayList<>();
    void check(boolean ok, String why) {
        checks++;
        if (!ok)
            throw new AssertionError(why);
    }
    void fail(Throwable t) {
        t.printStackTrace();
        System.out.println("PENDING_FAIL " + t);
        android.util.Log.e("PendingProbe", "PENDING_FAIL " + t);
        finish();
        System.exit(1);
    }
    Parcel call(int code) throws Exception {
        Parcel d = Parcel.obtain(), r = Parcel.obtain();
        try {
            check(service.transact(code, d, r, 0), "transaction " + code);
            return r;
        } finally {
            d.recycle();
        }
    }
    PendingIntent copy(PendingIntent p) {
        Parcel parcel = Parcel.obtain();
        try {
            p.writeToParcel(parcel, 0);
            parcel.setDataPosition(0);
            return PendingIntent.CREATOR.createFromParcel(parcel);
        } finally {
            parcel.recycle();
        }
    }
    void canceled(PendingIntent p) throws Exception {
        try {
            p.send();
            throw new AssertionError("not canceled");
        } catch (PendingIntent.CanceledException expected) {
            checks++;
        }
    }
    public static class Payload implements Parcelable {
        int value;
        Payload(int v){value=v;}
        public int describeContents(){return 0;}
        public void writeToParcel(Parcel p,int flags){p.writeInt(value);}
        public static final Parcelable.Creator<Payload> CREATOR=new Parcelable.Creator<Payload>(){
            public Payload createFromParcel(Parcel p){return new Payload(p.readInt());}
            public Payload[] newArray(int n){return new Payload[n];}
        };
    }
    void local() throws Exception {
        registerReceiver(new BroadcastReceiver() {
            public void onReceive(Context c, Intent i) {
                if (i.hasExtra("payload")) check(((Payload)i.getParcelableExtra("payload")).value == 1, "creator classloader and snapshot");
                deliveries.add(i.getStringExtra("value") + ":" + i.getStringExtra("new"));
            }
        }, new IntentFilter("org.atl.pendingprobe.LOCAL"));
        if (isAtl()) { // ATL wire codec rejects unknown capability handles.
        Parcel forged=Parcel.obtain();
        try { forged.writeInt(12345);forged.setDataPosition(0);
            try { PendingIntent.CREATOR.createFromParcel(forged);throw new AssertionError("forged handle accepted"); }
            catch(RuntimeException expected){checks++;}
        } finally {forged.recycle();}
        }
        Intent base = new Intent("org.atl.pendingprobe.LOCAL")
                          .setPackage(getPackageName())
                          .putExtra("value", "original");
        Payload payload=new Payload(1);base.putExtra("payload",payload);
        PendingIntent p = PendingIntent.getBroadcast(this, 1, base, PendingIntent.FLAG_IMMUTABLE);
        check(p.equals(copy(p)), "parcel identity");
        check(p.isImmutable(), "immutable metadata");
        payload.value=2;
        base.removeExtra("payload");
        base.putExtra("value", "mutated");
        p.send(this, 0, new Intent().putExtra("value", "attacker"));
        PendingIntent updated = PendingIntent.getBroadcast(
            this, 1, base, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        check(p.equals(updated), "update retains token");
        updated.send();
        check(PendingIntent
                  .getBroadcast(this, 1, base, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_NO_CREATE)
                  .equals(p),
              "no-create existing");
        check(PendingIntent.getBroadcast(this, 999, base,
                                         PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_NO_CREATE) == null,
              "no-create absent");
        PendingIntent newer = PendingIntent.getBroadcast(
            this, 1, base, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_CANCEL_CURRENT);
        check(!newer.equals(p), "cancel-current new identity");
        canceled(p);
        PendingIntent once = PendingIntent.getBroadcast(
            this, 2, base, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_ONE_SHOT);
        once.send();
        canceled(copy(once));
        PendingIntent mutable = PendingIntent.getBroadcast(
            this, 3,
            new Intent("org.atl.pendingprobe.LOCAL").setPackage(getPackageName()).putExtra("value", "fixed"),
            PendingIntent.FLAG_MUTABLE);
        mutable.send(this, 0, new Intent("ATTACK").putExtra("value", "bad").putExtra("new", "allowed"));
        mutable.getIntentSender().sendIntent(this, 0, null, null, null);
        check(
            !PendingIntent
                 .getBroadcast(this, 3, new Intent(base).addCategory("distinct"), PendingIntent.FLAG_MUTABLE)
                 .equals(mutable),
            "categories in identity");
        check(!PendingIntent
                   .getBroadcast(this, 3, new Intent(base).setPackage("other"), PendingIntent.FLAG_MUTABLE)
                   .equals(mutable),
              "package in identity");
        try {
            PendingIntent.getBroadcast(this, 99, base,
                                       PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_MUTABLE);
            throw new AssertionError("conflicting flags accepted");
        } catch (IllegalArgumentException expected) {
            checks++;
        }
    }
    public void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            local();
            h.postDelayed(new Runnable() {
                public void run() { fail(new AssertionError("timeout")); }
            }, 15000);
            check(bindService(new Intent().setComponent(
                                  new ComponentName("org.atl.pendingowner", "org.atl.pendingowner.Owner")),
                              new ServiceConnection() {
                                  public void onServiceConnected(ComponentName name, IBinder binder) {
                                      service = binder;
                                      try {
                                          Parcel r = call(1);
                                          remote = PendingIntent.CREATOR.createFromParcel(r);
                                          r.recycle();
                                          check(remote.getCreatorPackage().equals("org.atl.pendingowner"),
                                                "creator survives transfer");
                                          check(copy(remote).equals(remote), "remote token identity");
                                          remote.send(Caller.this, 0,
                                                      new Intent("BAD").putExtra("value", "bad"));
                                          poll();
                                      } catch (Throwable t) {
                                          fail(t);
                                      }
                                  }
                                  public void onServiceDisconnected(ComponentName name) {}
                              },
                              BIND_AUTO_CREATE),
                  "bind accepted");
        } catch (Throwable t) {
            fail(t);
        }
    }
    void poll() {
        h.postDelayed(new Runnable() {
            public void run() {
                try {
                    Parcel r = call(2);
                    String result = r.readString();
                    int pid = r.readInt();
                    r.recycle();
                    if (result.isEmpty()) {
                        poll();
                        return;
                    }
                    check(pid != android.os.Process.myPid(), "real separate process");
                    check(result.equals("org.atl.pendingowner:creator"), "delivery under creator, immutable");
                    check(deliveries.equals(Arrays.asList("original:null", "mutated:null", "mutated:null",
                                                          "fixed:allowed", "fixed:null")),
                          "local delivery semantics " + deliveries);
                    call(3).recycle();
                    canceled(remote);
                    // Obtain a new live token, then terminate its owning process.
                    r = call(1);
                    remote = PendingIntent.CREATOR.createFromParcel(r);
                    r.recycle();
                    call(4).recycle();
                    h.postDelayed(new Runnable() {
                        public void run() {
                            try {
                                if (isAtl()) canceled(remote);
                                else { remote.send(); checks++; }
                                System.out.println("PENDING_PASS checks=" + checks);
                                android.util.Log.i("PendingProbe", "PENDING_PASS checks=" + checks);
                                finish();
                                System.exit(0);
                            } catch (Throwable t) {
                                fail(t);
                            }
                        }
                    }, 700);
                } catch (Throwable t) {
                    fail(t);
                }
            }
        }, 100);
    }
}
