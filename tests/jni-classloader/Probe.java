package org.atl.loaderprobe;
import android.app.Activity;
import android.os.Bundle;

public class Probe extends Activity {
    static { System.loadLibrary("loaderprobe"); }
    public static class Marker {}
    static native String check(Class<?> expected, ClassLoader loader);
    public void onCreate(Bundle b) {super.onCreate(b);try {
        String error=check(Marker.class,Marker.class.getClassLoader());
        if(error!=null)throw new AssertionError(error);
        ClassLoader system=ClassLoader.getSystemClassLoader();
        try {system.loadClass(Marker.class.getName());throw new AssertionError("App class leaked into system loader");}
        catch(ClassNotFoundException expected){}
        ClassLoader other=(ClassLoader)Class.forName("dalvik.system.DexClassLoader").getConstructor(String.class,String.class,String.class,ClassLoader.class).newInstance(getApplicationInfo().sourceDir,getCacheDir().getPath(),null,system);
        Class<?> separate=other.loadClass(Marker.class.getName());
        if(separate==Marker.class || separate.getClassLoader()!=other)throw new AssertionError("App class identity collapsed");
        android.util.Log.i("LoaderProbe","JNI_CLASSLOADER_PASS");System.out.println("JNI_CLASSLOADER_PASS");finish();System.exit(0);
    }catch(Throwable t){t.printStackTrace();android.util.Log.e("LoaderProbe","JNI_CLASSLOADER_FAIL "+t);System.exit(1);}}
}
