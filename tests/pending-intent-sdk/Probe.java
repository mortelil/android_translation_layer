package org.atl.pendingsdk;
import android.app.*;
import android.os.*;
import android.content.*;
public class Probe extends Activity {
    int count;
    void attempt(int request,int flags,boolean explicit,boolean reject) {
        Intent intent = new Intent("org.atl.pendingsdk.TEST");
        if(explicit)intent.setClass(this,Probe.class);
        try {
            PendingIntent p=PendingIntent.getActivity(this,request,intent,flags);
            if(reject)throw new AssertionError("accepted flags="+flags);
            p.cancel();
        }catch(IllegalArgumentException e){if(!reject)throw new AssertionError("rejected flags="+flags,e);}
        count++;
    }
    public void onCreate(Bundle b){super.onCreate(b);try {
        int sdk=Build.VERSION.SDK_INT;
        if(getApplicationInfo().targetSdkVersion!=35)throw new AssertionError("fixture target");
        attempt(1,0,true,sdk>=31);
        attempt(2,PendingIntent.FLAG_IMMUTABLE,true,false);
        attempt(3,PendingIntent.FLAG_MUTABLE,true,false);
        attempt(4,PendingIntent.FLAG_MUTABLE,false,sdk>=34);
        attempt(5,PendingIntent.FLAG_MUTABLE|PendingIntent.FLAG_IMMUTABLE,true,sdk>=31);
        attempt(6,PendingIntent.FLAG_MUTABLE|PendingIntent.FLAG_ALLOW_UNSAFE_IMPLICIT_INTENT,false,false);
        android.util.Log.i("PendingSDK","PENDING_SDK_PASS sdk="+sdk+" checks="+count);
        System.out.println("PENDING_SDK_PASS sdk="+sdk+" checks="+count);finish();System.exit(0);
    }catch(Throwable t){t.printStackTrace();android.util.Log.e("PendingSDK","PENDING_SDK_FAIL "+t);System.exit(1);}}
}
