// SPDX-License-Identifier: GPL-3.0-only
package org.atl.cookieflush;
import android.app.Activity;
import android.os.Bundle;
import android.webkit.CookieManager;
import java.io.File;
import java.io.FileOutputStream;
public class Probe extends Activity {
    static final String URL="https://synthetic.invalid/";
    void require(boolean condition,String why){if(!condition)throw new AssertionError(why);}
    public void onCreate(Bundle state){super.onCreate(state);
        final CookieManager cm=CookieManager.getInstance();
        final File marker=new File(getFilesDir(),"restart");
        try {
            if(marker.exists()) {
                String cookies=cm.getCookie(URL);
                require(cookies!=null && cookies.contains("main=kept") && cookies.contains("worker=kept"),"persistent cookies survive forced process termination");
                require(!cookies.contains("session="),"session cookie not made persistent");
                cm.removeAllCookies(null);cm.flush();require(!cm.hasCookies(),"deletion followed by flush");
                if ("1".equals(System.getenv("ATL_TEST_COOKIE_IO_FAILURE"))) {
                    File db=new File(getFilesDir().getParentFile(),"atl-webview/cookies.sqlite");
                    File saved=new File(db.getParentFile(),"cookies.saved");
                    require(db.renameTo(saved),"prepare synthetic failure");
                    try {
                        require(db.mkdir(),"install invalid storage target");
                        boolean rejected=false;
                        try{cm.flush();}catch(IllegalStateException expected){rejected=true;}
                        require(rejected,"flush must report storage failure");
                    } finally {require(db.delete() && saved.renameTo(db),"restore synthetic storage");}
                    System.out.println("COOKIE_FLUSH_IO_FAILURE_PASS");
                }
                System.out.println("COOKIE_FLUSH_RESTART_PASS");finish();System.exit(0);return;
            }
            cm.removeAllCookies(null);cm.flush();
            cm.setCookie(URL,"main=kept; Max-Age=3600; Secure; HttpOnly");cm.flush();
            cm.setCookie(URL,"session=temporary; Secure");
            new Thread(new Runnable(){public void run(){try{
                cm.setCookie(URL,"worker=kept; Max-Age=3600; Secure");cm.flush();
                FileOutputStream out=new FileOutputStream(marker);out.write(1);out.close();
                System.out.println("COOKIE_FLUSH_READY_FOR_KILL");
            }catch(Throwable t){t.printStackTrace();System.out.println("COOKIE_FLUSH_FAIL");System.exit(1);}}}).start();
        }catch(Throwable t){t.printStackTrace();System.out.println("COOKIE_FLUSH_FAIL");finish();System.exit(1);}
    }
}
