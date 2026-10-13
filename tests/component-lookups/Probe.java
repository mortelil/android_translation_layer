// SPDX-License-Identifier: Apache-2.0
package org.atl.componentlookups;
import android.content.*;
import android.content.pm.*;
import android.os.Bundle;
public class Probe extends android.app.Activity {
    int checks;
    void check(boolean value) { if (!value) throw new AssertionError("check "+checks); checks++; }
    ComponentInfo lookup(PackageManager pm,int kind,ComponentName name,int flags) throws Exception {
        switch(kind) {
        case 0:return pm.getActivityInfo(name,flags);
        case 1:return pm.getReceiverInfo(name,flags);
        case 2:return pm.getServiceInfo(name,flags);
        default:return pm.getProviderInfo(name,flags);
        }
    }
    public void onCreate(Bundle state) { super.onCreate(state); try {
        PackageManager pm=getPackageManager();
        int expectedConfig=pm.getPackageInfo(getPackageName(),PackageManager.GET_ACTIVITIES).activities[0].configChanges;
        check((expectedConfig & (ActivityInfo.CONFIG_ORIENTATION|ActivityInfo.CONFIG_SCREEN_SIZE))==(ActivityInfo.CONFIG_ORIENTATION|ActivityInfo.CONFIG_SCREEN_SIZE));
        check((expectedConfig & ActivityInfo.CONFIG_UI_MODE)==0);
        String[] names={"Probe","Receiver","Service","Provider"};
        String[] values={"activity","receiver","service","provider"};
        for(int k=0;k<4;k++) {
            ComponentName name=new ComponentName(getPackageName(),getPackageName()+"."+names[k]);
            int flags=PackageManager.GET_DISABLED_COMPONENTS;
            ComponentInfo bare=lookup(pm,k,name,flags);
            check(bare!=null && bare.name.equals(name.getClassName()));
            check(bare.metaData==null);
            ComponentInfo full=lookup(pm,k,name,flags|PackageManager.GET_META_DATA);
            check(full.metaData!=null && values[k].equals(full.metaData.getString("probe")));
            if(k==0) {
                System.out.println("COMPONENT_CONFIG expected="+Integer.toHexString(expectedConfig));
                check(((ActivityInfo)full).configChanges==expectedConfig);
                ((ActivityInfo)full).configChanges=0;
            }
            full.name="changed";full.metaData.putString("probe","changed");
            full.applicationInfo.packageName="changed";
            ComponentInfo again=lookup(pm,k,name,flags|PackageManager.GET_META_DATA);
            check(again.name.equals(name.getClassName()));
            if(k==0) check(((ActivityInfo)again).configChanges==expectedConfig);
            if(k==3) {
                boolean threw=false;
                try { lookup(pm,k,name,0); } catch(PackageManager.NameNotFoundException expected) { threw=true; }
                check(threw);
            }
            check(values[k].equals(again.metaData.getString("probe")));
            check(getPackageName().equals(again.applicationInfo.packageName));
            check(lookup(pm,k,name,flags).metaData==null);
            for(ComponentName missing:new ComponentName[]{
                new ComponentName(getPackageName(),getPackageName()+".Missing"),
                new ComponentName("org.atl.nonexistent",name.getClassName())}) {
                boolean threw=false;
                try { lookup(pm,k,missing,flags); } catch(PackageManager.NameNotFoundException expected) { threw=true; }
                check(threw);
            }
        }
        getFilesDir().mkdirs();
        try(java.io.FileOutputStream f=new java.io.FileOutputStream(new java.io.File(getFilesDir(),"results.json"))) {
            f.write(("{\"status\":\"PASS\",\"checks\":"+checks+"}").getBytes("UTF-8"));
        }
        System.out.println("COMPONENT_LOOKUPS PASS checks="+checks);
    } catch(Throwable t) { t.printStackTrace();System.out.println("COMPONENT_LOOKUPS FAIL "+t); } }
}
