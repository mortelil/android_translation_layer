/* SPDX-License-Identifier: GPL-3.0-only */
package org.atl.packagequeries;
import android.app.Activity;
import android.os.Bundle;
import android.atl.ATLLoadedApp;
import android.content.pm.PackageManager;
import android.content.pm.PackageParser;

public class TestActivity extends Activity {
    private void visible(String name) throws Exception {
        if (!name.equals(getPackageManager().getPackageInfo(name,0).packageName)) throw new AssertionError("wrong package");
    }
    private void hidden(String name) throws Exception {
        try { getPackageManager().getPackageInfo(name,0); throw new AssertionError("unexpected visibility: "+name); }
        catch (PackageManager.NameNotFoundException expected) {}
    }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            PackageParser.Package source=ATLLoadedApp.getPrimaryApplication().pkg;
            if (!source.queriesPackages.contains("org.atl.queryfixture")) throw new AssertionError("manifest query not parsed");
            visible("org.atl.queryfixture");
            hidden("org.atl.hiddenfixture");
            hidden("org.atl.notinstalled"); // a declaration does not fabricate an installation
            source.applicationInfo.targetSdkVersion=29;
            visible("org.atl.hiddenfixture");
            source.applicationInfo.targetSdkVersion=30;
            hidden("org.atl.hiddenfixture"); // cached metadata must not bypass the policy
            source.requestedPermissions.add("android.permission.QUERY_ALL_PACKAGES");
            visible("org.atl.hiddenfixture");
            source.requestedPermissions.remove("android.permission.QUERY_ALL_PACKAGES");
            hidden("org.atl.hiddenfixture");
            System.out.println("ATL PACKAGE QUERY CONTRACTS PASSED");
            System.exit(0);
        } catch (Throwable failure) { failure.printStackTrace();System.exit(1); }
    }
}
