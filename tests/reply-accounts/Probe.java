// SPDX-License-Identifier: GPL-3.0-only
package org.atl.replyaccounts;
import android.app.Activity;
import android.os.*;
import android.accounts.*;
public class Probe extends Activity {
    int checks;
    void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); checks++; }
    void replies() {
        Parcel empty=Parcel.obtain();empty.readException();
        check(empty.dataPosition()==0,"empty exception header does not advance");empty.recycle();
        Binder target=new Binder(){protected boolean onTransact(int code,Parcel data,Parcel reply,int flags){return code==1;}};
        for(int code=1;code<=2;code++) {
            Parcel request=Parcel.obtain(), reply=Parcel.obtain();
            check(target.transact(code,request,reply,0)==(code==1),"handled result preserved");
            reply.readException();check(reply.dataSize()==0,"no fabricated reply bytes");
            request.recycle();reply.recycle();
        }
        Parcel p=Parcel.obtain(); p.writeNoException();
        check(p.dataSize()==4,"success header length");p.setDataPosition(0);
        check(p.readInt()==0,"success header code");p.recycle();
        p=Parcel.obtain();p.writeInt(0);p.writeInt(321);p.setDataPosition(0);
        p.readException();check(p.readInt()==321,"raw success preserves payload");p.recycle();
        RuntimeException[] errors={new SecurityException("s"),new BadParcelableException("b"),new IllegalArgumentException("a"),new NullPointerException("n"),new IllegalStateException("i"),new NetworkOnMainThreadException(),new UnsupportedOperationException("u"),new ServiceSpecificException(42,"svc")};
        for (RuntimeException e:errors) {
            p=Parcel.obtain();p.writeException(e);p.writeInt(456);p.setDataPosition(0);
            try {p.readException();throw new AssertionError("exception swallowed");}
            catch(RuntimeException actual) {
                check(actual.getClass()==e.getClass(),"exception class");
                check(java.util.Objects.equals(actual.getMessage(),e.getMessage()),"exception message");
                if(actual instanceof ServiceSpecificException)check(((ServiceSpecificException)actual).errorCode==42,"service code");
            }
            check(p.readInt()==456,"exception preserves payload");p.recycle();
        }
        p=Parcel.obtain();p.writeInt(-3);p.writeString("raw");p.writeInt(0);p.setDataPosition(0);
        try{p.readException();throw new AssertionError("raw error swallowed");}
        catch(IllegalArgumentException expected){check("raw".equals(expected.getMessage()),"raw error message");}
        p.recycle();
    }
    void fail(Throwable t){t.printStackTrace();android.util.Log.e("ReplyAccounts","FAIL "+t);finish();System.exit(1);}
    public void onCreate(Bundle b) {
        super.onCreate(b);
        try {
            replies();
            AccountManager am=AccountManager.get(this);
            Handler handler=new Handler(Looper.getMainLooper());
            final int[] calls={0};
            OnAccountsUpdateListener listener=new OnAccountsUpdateListener() { public void onAccountsUpdated(Account[] accounts) {
                try {check(Looper.myLooper()==handler.getLooper(),"callback thread");check(accounts!=null,"accounts array");calls[0]++;}
                catch(Throwable t){fail(t);}
            }};
            am.addOnAccountsUpdatedListener(listener,handler,true);
            check(calls[0]==0,"callback must be posted");
            try{am.addOnAccountsUpdatedListener(listener,handler,false);throw new AssertionError("duplicate accepted");}
            catch(IllegalStateException expected){checks++;}
            handler.postDelayed(new Runnable() { public void run() {
                try {
                    check(calls[0]==1,"one initial callback");
                    am.removeOnAccountsUpdatedListener(listener);
                    am.addOnAccountsUpdatedListener(listener,handler,false,new String[]{"synthetic.test"});
                    handler.postDelayed(new Runnable() { public void run() {
                        try{check(calls[0]==1,"no unsolicited callback");am.removeOnAccountsUpdatedListener(listener);
                            android.util.Log.i("ReplyAccounts","REPLY_ACCOUNTS_PASS checks="+checks);
                            System.out.println("REPLY_ACCOUNTS_PASS checks="+checks);finish();System.exit(0);
                        }catch(Throwable t){fail(t);}
                    }},150);
                } catch(Throwable t){fail(t);}
            }},150);
        }catch(Throwable t){fail(t);}
    }
}
