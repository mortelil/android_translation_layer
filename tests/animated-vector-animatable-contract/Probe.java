package org.atl.animatedvectorcontract;

import android.app.Activity;
import android.graphics.drawable.Animatable;
import android.graphics.drawable.AnimatedVectorDrawable;
import android.os.Bundle;

public class Probe extends Activity {
    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            AnimatedVectorDrawable drawable = new AnimatedVectorDrawable();
            if (!(drawable instanceof Animatable))
                throw new AssertionError("AnimatedVectorDrawable must implement Animatable");
            if (((Animatable) drawable).isRunning())
                throw new AssertionError("unconfigured ATL drawable must not report running");
            android.util.Log.i("AVDContractProbe", "AVD_ANIMATABLE_CONTRACT_PASS");
            System.out.println("AVD_ANIMATABLE_CONTRACT_PASS");
            finish();
            System.exit(0);
        } catch (Throwable t) {
            t.printStackTrace();
            android.util.Log.e("AVDContractProbe", "AVD_ANIMATABLE_CONTRACT_FAIL", t);
            finish();
            System.exit(1);
        }
    }
}
