// SPDX-License-Identifier: GPL-3.0-only
import android.graphics.*;
import android.os.*;
import android.media.MediaRouter;
public class TestUsabilityRepairs {
    static void check(boolean v, String m) { if (!v) throw new AssertionError(m); }
    public static void run(android.content.Context context) {
        try {
            android.content.Context restricted = context.createPackageContext(context.getPackageName(), android.content.Context.CONTEXT_RESTRICTED);
            check(restricted.isRestricted() && restricted.getClassLoader() != null, "restricted resource context");
            try { context.createPackageContext("org.atl.does.not.exist", 0); throw new AssertionError("missing package"); }
            catch (android.content.pm.PackageManager.NameNotFoundException expected) {}
        } catch (android.content.pm.PackageManager.NameNotFoundException unexpected) { throw new AssertionError(unexpected); }
        Bitmap b = Bitmap.createBitmap(3, 2, Bitmap.Config.ARGB_8888);
        int[] colors = {0xffff0000, 0xff00ff00, 0xff0000ff, 0xff112233, 0xff445566, 0xff778899};
        b.setPixels(colors, 0, 3, 0, 0, 3, 2);
        int[] out = new int[8];
        b.getPixels(out, 5, -4, 1, 0, 2, 2);
        check(out[5]==colors[1] && out[6]==colors[2] && out[1]==colors[4] && out[2]==colors[5], "negative getPixels stride");
        b.setPixels(colors, 3, -3, 0, 0, 3, 2);
        check(b.getPixel(0,0)==colors[3] && b.getPixel(2,1)==colors[2], "negative setPixels stride");
        b.setPixel(1,0,0x80402010);
        check(b.getPixel(1,0)==0x80402010 && b.getPixel(0,0)==colors[3], "alpha replacement, not over compositing");
        try { b.getPixels(out, Integer.MAX_VALUE, 3, 0,0,2,2); throw new AssertionError("overflow"); }
        catch (ArrayIndexOutOfBoundsException expected) {}
        try { b.getPixels(out,0,3,Integer.MAX_VALUE,0,2,1); throw new AssertionError("rectangle overflow"); }
        catch (IllegalArgumentException expected) {}
        java.nio.ByteBuffer copied = java.nio.ByteBuffer.allocate(24);
        b.copyPixelsToBuffer(copied); // Keep the existing JNI export available.
        check(copied.get(0) != 0 || copied.get(1) != 0 || copied.get(2) != 0, "copyPixelsToBuffer data");
        Bitmap layer = Bitmap.createBitmap(8,8,Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(layer); Paint paint = new Paint(); paint.setColor(0xffff0000);
        int saved = canvas.saveLayerAlpha(0,0,8,8,128);
        canvas.drawRect(0,0,8,8,paint); canvas.restoreToCount(saved);
        int pixel = layer.getPixel(4,4);
        check((pixel >>> 24) >= 126 && (pixel >>> 24) <= 129 && ((pixel >>> 16)&255)>250, "layer group opacity");
        Bitmap gradient = Bitmap.createBitmap(16,4,Bitmap.Config.ARGB_8888);
        Paint shader = new Paint(); shader.setShader(new LinearGradient(0,0,16,0,0xffff0000,0xff0000ff,Shader.TileMode.CLAMP));
        new Canvas(gradient).drawRect(0,0,16,4,shader);
        check(((gradient.getPixel(1,1)>>16)&255)>200 && (gradient.getPixel(14,1)&255)>200, "linear gradient channel direction");
        // targetSdkVersion 26: legacy flags must remain accepted.
        int oldFlags = canvas.saveLayerAlpha(0, 0, 8, 8, 128, 0);
        canvas.restoreToCount(oldFlags);
        final android.media.MediaRouter router = new android.media.MediaRouter();
        final int[] added = {0};
        android.media.MediaRouter.Callback callback = new android.media.MediaRouter.Callback() {
            public void onRouteAdded(android.media.MediaRouter r, android.media.MediaRouter.RouteInfo info) {
                added[0]++; r.removeCallback(this);
            }
            public void onRouteSelected(MediaRouter r, int t, MediaRouter.RouteInfo i) {}
            public void onRouteUnselected(MediaRouter r, int t, MediaRouter.RouteInfo i) {}
            public void onRouteRemoved(MediaRouter r, MediaRouter.RouteInfo i) {}
            public void onRouteChanged(MediaRouter r, MediaRouter.RouteInfo i) {}
            public void onRouteGrouped(MediaRouter r, MediaRouter.RouteInfo i, MediaRouter.RouteGroup g, int n) {}
            public void onRouteUngrouped(MediaRouter r, MediaRouter.RouteInfo i, MediaRouter.RouteGroup g) {}
            public void onRouteVolumeChanged(MediaRouter r, MediaRouter.RouteInfo i) {}
        };
        router.addCallback(android.media.MediaRouter.ROUTE_TYPE_USER, callback);
        android.media.MediaRouter.UserRouteInfo route = router.createUserRoute(router.createRouteCategory("Test", false));
        router.addUserRoute(route); router.addUserRoute(route);
        check(added[0] == 1 && router.getRouteCount() == 2, "route callback mutation and duplicate add");
        router.selectRoute(android.media.MediaRouter.ROUTE_TYPE_USER, route);
        router.removeUserRoute(route);
        check(router.getSelectedRoute() == router.getDefaultRoute(), "removed selected route falls back");
        Binder server = new Binder() {
            protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
                if (code==1) { reply.writeInt(data.readInt()+1); return true; }
                if (code==2) return true; // nullable reply
                if (code==3) throw new IllegalStateException("server error");
                return super.onTransact(code,data,reply,flags);
            }
        };
        Parcel data=Parcel.obtain(), reply=Parcel.obtain(); data.writeInt(41);
        check(server.transact(1,data,reply,0) && reply.readInt()==42, "Binder dispatch and parcel rewind");
        check(server.transact(2,data,null,1), "oneway null reply");
        check(!server.transact(99,data,reply,0), "unknown transaction");
        try { server.transact(3,data,reply,0); throw new AssertionError("swallowed exception"); }
        catch (IllegalStateException expected) {}
        data.recycle(); reply.recycle();
        android.app.ActivityManager.MemoryInfo memory = new android.app.ActivityManager.MemoryInfo();
        android.app.ActivityManager manager = new android.app.ActivityManager();
        manager.getMemoryInfo(memory);
        check(memory.totalMem>0 && memory.availMem>=0 && memory.availMem<=memory.totalMem, "real host memory");
        check(manager.getMemoryClass()<=manager.getLargeMemoryClass(), "heap classes ordered");
        check(context.getPackageManager().queryIntentServices(new android.content.Intent("test.action").setPackage("org.atl.does.not.exist"),0).isEmpty(), "unknown package cannot resolve caller services");
        System.out.println("PASS: pixel subrectangles, Binder contracts, memory and package isolation");
    }
}
