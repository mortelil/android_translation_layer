// SPDX-License-Identifier: GPL-3.0-only
package android.atl;
import android.content.pm.PackageParser;
import java.lang.reflect.*;

public class OwnerContracts {
    static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    static void forwardedResults(ATLLoadedApp app) throws Exception {
        final ATLForwardingProcessBackend backend=ATLForwardingProcessBackend.get(app);
        final java.util.ArrayList<String> events=new java.util.ArrayList<>();
        ATLProcessBackend.Callback<android.os.IBinder> callback=new ATLProcessBackend.Callback<android.os.IBinder>() {
            public void onResult(android.os.IBinder binder) { events.add("connected"); }
            public void onHostUnavailable() { events.add("unavailable"); }
            public void onHostDied() { events.add("disconnected"); }
            public void onBindingDied() { events.add("binding-died"); }
        };
        Method remember=ATLForwardingProcessBackend.class.getDeclaredMethod("remember",int.class,String.class,android.content.Intent.class,ATLProcessBackend.Callback.class);
        Method register=ATLForwardingProcessBackend.class.getDeclaredMethod("registerBinding",int.class,ATLProcessBackend.Callback.class);
        Method complete=ATLForwardingProcessBackend.class.getDeclaredMethod("complete",int.class,int.class,int.class,android.os.IBinder.class);
        remember.setAccessible(true); register.setAccessible(true); complete.setAccessible(true);
        remember.invoke(backend,100001,"Fixture",new android.content.Intent(),callback);
        register.invoke(backend,100001,callback);
        complete.invoke(backend,100001,ATLProcessControl.SVC_STATUS_OK,1,new android.os.Binder());
        complete.invoke(backend,100001,ATLProcessControl.SVC_STATUS_DIED,0,null);
        complete.invoke(backend,100001,ATLProcessControl.SVC_STATUS_OK,1,new android.os.Binder());
        complete.invoke(backend,100001,ATLProcessControl.SVC_STATUS_BINDING_DIED,0,null);
        remember.invoke(backend,100002,"Refused",new android.content.Intent(),callback);
        register.invoke(backend,100002,callback);
        complete.invoke(backend,100002,ATLProcessControl.SVC_STATUS_REFUSED,0,null);
        Field bindings=ATLForwardingProcessBackend.class.getDeclaredField("bindings"); bindings.setAccessible(true);
        check(((java.util.List<?>)bindings.get(backend)).size()==1,"refused bind retained");
        new android.os.Handler(android.os.Looper.getMainLooper()).post(new Runnable() {
            public void run() {
                check(events.toString().equals("[connected, disconnected, connected, binding-died, unavailable]"),events.toString());
                System.out.println("FORWARDED_RESULTS_PASS");
            }
        });
    }

    public static void run() throws Exception {
        PackageParser.Package pa = new PackageParser.Package("test.owner.a");
        PackageParser.Package pb = new PackageParser.Package("test.owner.b");
        pa.applicationInfo.sourceDir = "/fixtures/a.apk";
        pb.applicationInfo.sourceDir = "/fixtures/b.apk";
        Constructor<ATLLoadedApp> ctor = ATLLoadedApp.class.getDeclaredConstructor(android.content.res.Resources.class, ClassLoader.class, PackageParser.Package.class);
        ctor.setAccessible(true);
        // Synthetic apps construct controllers too. Preserve the legacy fake's
        // global kill seam so the service matrix still targets the real test app.
        Field fakeInstance = ATLFakeProcessBackend.class.getDeclaredField("instance");
        fakeInstance.setAccessible(true);
        Object previousFake = fakeInstance.get(null);
        ATLLoadedApp a, b;
        try {
            a = ctor.newInstance(null, OwnerContracts.class.getClassLoader(), pa);
            b = ctor.newInstance(null, OwnerContracts.class.getClassLoader(), pb);
        } finally {
            fakeInstance.set(null, previousFake);
        }
        ATLProcessHost ha = ATLProcessHost.get(a), hb = ATLProcessHost.get(b);
        check(ha != hb, "owners share callback/process registry");
        check(ha == ATLProcessHost.get(a), "unstable backend identity");
        Method path = ATLProcessHost.class.getDeclaredMethod("spawnApk", String.class); path.setAccessible(true);
        check("/fixtures/a.apk".equals(path.invoke(ha, "com.unrelated.custom")), "absolute process incorrectly treated as package");
        check("/fixtures/b.apk".equals(path.invoke(hb, "com.unrelated.custom")), "second package uses wrong APK");
        check("/fixtures/a.apk".equals(path.invoke(ha, "test.owner.b:worker")), "prefix can override owner");
        ATLLoadedApp primary = ATLLoadedApp.getPrimaryApplication();
        PackageParser.Service service = primary.pkg.services.get(0);
        Method process = ATLProcessControl.class.getDeclaredMethod("componentProcess", ATLLoadedApp.class, PackageParser.Service.class); process.setAccessible(true);
        String previous = service.info.processName;
        String defaultProcess = primary.pkg.applicationInfo.processName;
        try {
            service.info.processName = null;
            primary.pkg.applicationInfo.processName = "custom.application.process";
            check("custom.application.process".equals(process.invoke(null,primary,service)), "application process fallback lost");
            primary.pkg.applicationInfo.processName = null;
            check(primary.pkg.packageName.equals(process.invoke(null,primary,service)), "package fallback lost");
            service.info.processName = "explicit.worker";
            check("explicit.worker".equals(process.invoke(null,primary,service)), "component override lost");
        } finally {
            service.info.processName = previous;
            primary.pkg.applicationInfo.processName = defaultProcess;
        }
        forwardedResults(a);
        System.out.println("SERVICE_OWNER_PASS");
    }
}
