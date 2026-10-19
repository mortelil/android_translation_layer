// SPDX-License-Identifier: GPL-3.0-only
package org.atl.messagesender;
import android.app.*;
import android.content.*;
import android.os.*;
public class Probe extends Activity {
    int checks;
    void check(boolean ok,String why){if(!ok)throw new AssertionError(why);checks++;}
    void fail(Throwable t){t.printStackTrace();System.out.println("MESSAGE_SENDER_FAIL "+t);finish();System.exit(1);}
    public static class Receiver extends Service {
        final Messenger endpoint=new Messenger(new Handler(Looper.getMainLooper()) {
            public void handleMessage(Message msg) {
                Message result=Message.obtain(); result.arg1=msg.sendingUid;
                result.arg2=android.os.Process.myPid(); result.sendingUid=123456;
                try{msg.replyTo.send(result);}catch(RemoteException e){throw new RuntimeException(e);}
            }
        });
        public IBinder onBind(Intent intent){return endpoint.getBinder();}
    }
    public void onCreate(Bundle saved){super.onCreate(saved);try{
        Message original=Message.obtain();check(original.sendingUid==-1,"default UID");original.sendingUid=234567;
        Message copy=Message.obtain(original);check(copy.sendingUid==234567,"obtain copy");copy.recycle();
        copy=Message.obtain();check(copy.sendingUid==-1,"pool resets UID");copy.copyFrom(original);check(copy.sendingUid==234567,"copyFrom");copy.recycle();
        Parcel p=Parcel.obtain();original.writeToParcel(p,0);p.setDataPosition(0);copy=Message.CREATOR.createFromParcel(p);
        check(copy.sendingUid==234567,"parcel UID");check(p.dataPosition()==p.dataSize(),"parcel fully read");copy.recycle();p.recycle();original.recycle();
        final Handler timeout=new Handler();timeout.postDelayed(new Runnable(){public void run(){fail(new AssertionError("timeout"));}},10000);
        final Messenger local=new Messenger(new Handler(){public void handleMessage(Message m){
            try{check(m.sendingUid==android.os.Process.myUid(),"local forged UID overwritten");}catch(Throwable t){fail(t);}
        }});
        Message m=Message.obtain();m.sendingUid=123456;local.send(m);
        final ServiceConnection connection=new ServiceConnection(){
            public void onServiceDisconnected(ComponentName name){fail(new AssertionError("service died"));}
            public void onServiceConnected(ComponentName name,IBinder binder){try{
                final ServiceConnection self=this;
                Message request=Message.obtain();request.sendingUid=123456;
                request.replyTo=new Messenger(new Handler(){public void handleMessage(Message response){try{
                    check(response.arg1==android.os.Process.myUid(),"remote receipt overwrites forged UID");
                    check(response.arg2!=android.os.Process.myPid(),"service really separate process");
                    check(response.sendingUid==android.os.Process.myUid(),"callback receipt overwrites forged UID");
                    timeout.removeCallbacksAndMessages(null);unbindService(self);
                    System.out.println("MESSAGE_SENDER_PASS checks="+checks);android.util.Log.i("MessageSender","MESSAGE_SENDER_PASS checks="+checks);finish();System.exit(0);
                }catch(Throwable t){fail(t);}}});
                new Messenger(binder).send(request);
            }catch(Throwable t){fail(t);}}
        };
        check(bindService(new Intent(this,Receiver.class),connection,Context.BIND_AUTO_CREATE),"bind accepted");
    }catch(Throwable t){fail(t);}}
}
