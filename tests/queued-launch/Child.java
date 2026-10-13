/* SPDX-License-Identifier: Apache-2.0 */
package org.atl.queuedlaunch;
public class Child extends android.app.Activity {
    public void onCreate(android.os.Bundle b){
        super.onCreate(b);
        Probe.check(Probe.createReturned&&Probe.postCreateReturned,"target entered before caller completed creation");
    }
    public void onPostCreate(android.os.Bundle b){
        super.onPostCreate(b);
        startActivity(new android.content.Intent(this,Last.class));
        Probe.childPostReturned=true;
    }
    public void onResume(){super.onResume();Probe.check(!Probe.lastCreated,"intermediate resumed over final activity");}
}
