// SPDX-License-Identifier: GPL-3.0-only
package org.atl.packagenativemetadata;
import android.app.Activity;
import android.os.Bundle;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import java.io.File;
import java.io.FileInputStream;
public class Probe extends Activity {
 int checks;
 void check(boolean b,String m){if(!b)throw new AssertionError(m);checks++;}
 void inspect(ApplicationInfo i)throws Exception{
  check(i.nativeLibraryDir!=null,"metadata native path");
  File lib=new File(i.nativeLibraryDir,"libmetadata_fixture.so");
  check(lib.isFile(),"library in published directory");
  try(FileInputStream in=new FileInputStream(lib)){check(in.read()==42,"fixture bytes");}
  check(new File(i.sourceDir).isFile(),"APK source path");
 }
 public void onCreate(Bundle state){super.onCreate(state);int result=0;try{
  ApplicationInfo first=getPackageManager().getApplicationInfo("org.atl.nativefixture",0);inspect(first);
  Context metadata=createPackageContext("org.atl.nativefixture",0);inspect(metadata.getApplicationInfo());
  String generation=metadata.getApplicationInfo().nativeLibraryDir;
  Context code=createPackageContext("org.atl.nativefixture",Context.CONTEXT_INCLUDE_CODE|Context.CONTEXT_IGNORE_SECURITY);inspect(code.getApplicationInfo());
  check(generation.equals(code.getApplicationInfo().nativeLibraryDir),"same immutable generation");
  check(metadata.getApplicationInfo().nativeLibraryDir.equals(generation),"existing context retains generation");
  System.out.println("PACKAGE_NATIVE_METADATA_PASS checks="+checks);android.util.Log.i("NativeMetadataProbe","PACKAGE_NATIVE_METADATA_PASS checks="+checks);
 }catch(Throwable t){result=1;t.printStackTrace();android.util.Log.e("NativeMetadataProbe","FAIL "+t);}finish();System.exit(result);}
}
