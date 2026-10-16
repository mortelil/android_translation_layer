// SPDX-License-Identifier: GPL-3.0-only
import android.content.Context;
import android.graphics.SurfaceTexture;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.security.keystore.KeyGenParameterSpec;
import android.view.TextureView;
import java.lang.reflect.Method;
import java.security.AlgorithmParameters;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public class TestCompatRepairs {
    static void check(boolean b, String s) { if (!b) throw new AssertionError(s); }
    static class Generator extends android.security.keystore.KeyGenerator.AES {
        int size(int bits, String mode) throws Exception {
            KeyGenParameterSpec.Builder b = new KeyGenParameterSpec.Builder("unused-probe", 3)
                .setBlockModes(new String[]{mode}).setEncryptionPaddings(new String[]{"NoPadding"});
            if (bits != -1) b.setKeySize(bits);
            engineInit(b.build(), new SecureRandom());
            // Test generated size without persisting any key to the desktop keyring.
            return keyGenerator.generateKey().getEncoded().length * 8;
        }
    }
    static class Texture extends TextureView {
        Texture(Context c) { super(c); }
        void attach() { onAttachedToWindow(); }
        void detach() { onDetachedFromWindow(); }
    }
    static TextureView.SurfaceTextureListener listener(final boolean release, final boolean fail) {
        return new TextureView.SurfaceTextureListener() {
            public void onSurfaceTextureAvailable(SurfaceTexture s,int w,int h) {}
            public void onSurfaceTextureSizeChanged(SurfaceTexture s,int w,int h) {}
            public void onSurfaceTextureUpdated(SurfaceTexture s) {}
            public boolean onSurfaceTextureDestroyed(SurfaceTexture s) {
                if (fail) throw new IllegalStateException("test detach");
                return release;
            }
        };
    }
    public static void run(Context context) throws Exception {
        Generator g = new Generator();
        check(g.size(-1,"GCM")==128, "default AES size");
        for(int bits:new int[]{128,192,256}) check(g.size(bits,"CBC")==bits,"AES CBC size");
        try { g.size(129,"GCM"); throw new AssertionError("invalid AES size accepted"); }
        catch(java.security.InvalidAlgorithmParameterException expected) {}
        SecretKeySpec key = new SecretKeySpec(new byte[16],"AES");
        for(int overload=0;overload<3;overload++) {
            Cipher c=Cipher.getInstance("AES/GCM/NoPadding");
            if(overload==0) c.init(Cipher.ENCRYPT_MODE,key);
            if(overload==1) c.init(Cipher.ENCRYPT_MODE,key,(java.security.spec.AlgorithmParameterSpec)null);
            if(overload==2) c.init(Cipher.ENCRYPT_MODE,key,(AlgorithmParameters)null);
            check(c.getIV().length==12,"default GCM IV overload "+overload);
            c.updateAAD(new byte[]{3}); byte[] out=c.doFinal(new byte[]{1,2});
            Cipher d=Cipher.getInstance("AES/GCM/NoPadding");
            d.init(Cipher.DECRYPT_MODE,key,new GCMParameterSpec(128,c.getIV()));
            d.updateAAD(new byte[]{3});check(Arrays.equals(d.doFinal(out),new byte[]{1,2}),"GCM roundtrip");
            out[0]^=1;d.init(Cipher.DECRYPT_MODE,key,new GCMParameterSpec(128,c.getIV()));
            d.updateAAD(new byte[]{3});
            try { d.doFinal(out); throw new AssertionError("bad tag accepted"); }
            catch(javax.crypto.AEADBadTagException expected) {}
        }
        System.out.println("PASS: AES defaults, sizes, CBC and all GCM init overloads with bad-tag rejection");
        for(int mode=0;mode<4;mode++) {
            Texture t=new Texture(context);
            if(mode>0)t.setSurfaceTextureListener(listener(mode!=2,mode==3));
            t.attach();SurfaceTexture old=t.getSurfaceTexture();
            try {t.detach();check(mode!=3,"detach exception lost");}
            catch(IllegalStateException e){check(mode==3,"unexpected detach exception");}
            check(t.getSurfaceTexture()==null && !t.isAvailable(),"detached surface retained");
            check(old.isReleased()==(mode!=2),"surface ownership wrong");
            t.setSurfaceTextureListener(null);t.attach();
            check(t.getSurfaceTexture()!=old,"transferred surface reused");
            SurfaceTexture replacement=new SurfaceTexture(false), previous=t.getSurfaceTexture();
            t.setSurfaceTexture(replacement);check(previous.isReleased(),"replacement leaks old surface");
            try {t.setSurfaceTexture(replacement);throw new AssertionError("same surface accepted");}
            catch(IllegalArgumentException expected) {}
            try {t.setSurfaceTexture(null);throw new AssertionError("null surface accepted");}
            catch(NullPointerException expected) {}
            SurfaceTexture dead=new SurfaceTexture(false);dead.release();
            try {t.setSurfaceTexture(dead);throw new AssertionError("released surface accepted");}
            catch(IllegalArgumentException expected) {}
            t.detach();old.release();
        }
        System.out.println("PASS: TextureView detach ownership, exceptions, reattach and replacement");
        Method parse=NsdManager.class.getDeclaredMethod("parseTxt",byte[].class,NsdServiceInfo.class);
        parse.setAccessible(true);NsdServiceInfo info=new NsdServiceInfo();
        parse.invoke(null,new byte[]{5,'x','=',0,(byte)255,1,2,'e','=',1,'n'},info);
        check(Arrays.equals(info.getAttributes().get("x"),new byte[]{0,(byte)255,1}),"binary TXT lost");
        check(info.getAttributes().get("e").length==0 && info.getAttributes().containsKey("n") && info.getAttributes().get("n")==null,"empty TXT semantics");
        try {parse.invoke(null,new byte[]{5,1},info);throw new AssertionError("truncated TXT accepted");}
        catch(java.lang.reflect.InvocationTargetException e){check(e.getCause() instanceof IllegalArgumentException,"TXT exception");}
        System.out.println("PASS: DNS-SD binary, empty and malformed TXT records");
        java.lang.reflect.Field lockField=NsdManager.class.getDeclaredField("sLock");
        lockField.setAccessible(true);Object lock=lockField.get(null);
        final Method incoming=NsdManager.class.getDeclaredMethod("onNativeServiceFound",long.class,String.class,String.class,String.class,int.class,int.class);
        incoming.setAccessible(true);
        final java.util.concurrent.CountDownLatch done=new java.util.concurrent.CountDownLatch(1);
        final Throwable[] failure=new Throwable[1];
        synchronized(lock) {
            new Thread(new Runnable(){public void run(){
                try {incoming.invoke(null,-123L,"probe","_probe._tcp","local",-1,-1);}
                catch(Throwable t){failure[0]=t;} finally {done.countDown();}
            }},"simulated-avahi-callback").start();
            check(done.await(2,java.util.concurrent.TimeUnit.SECONDS),"Avahi callback blocked on Java operation lock");
        }
        check(failure[0]==null,"NSD callback enqueue failed");
        System.out.println("PASS: NSD callback never waits on Java operation lock");

    }
}
