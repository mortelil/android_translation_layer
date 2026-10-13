/* SPDX-License-Identifier: Apache-2.0 */
package org.atl.queuedlaunch;
import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
public class Probe extends Activity {
    static boolean createReturned, postCreateReturned, childPostReturned, lastCreated;
    static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    public void onCreate(Bundle b){
        super.onCreate(b);
        createReturned=false;postCreateReturned=false;childPostReturned=false;lastCreated=false;
        startActivityForResult(new Intent(this,Child.class),23);
        createReturned=true;
    }
    public void onPostCreate(Bundle b){super.onPostCreate(b);postCreateReturned=true;}
    public void onResume(){super.onResume();check(!lastCreated,"caller resumed over final activity");}
}
