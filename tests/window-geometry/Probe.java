/* SPDX-License-Identifier: Apache-2.0 */
package org.atl.windowgeometry;
import android.app.*;
import android.content.*;
import android.content.res.*;
import android.util.*;
import android.view.*;
import java.util.Locale;
public class Probe extends Activity {
    static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    String value(Context context,String name){Resources r=context.getResources();return r.getString(r.getIdentifier(name,"string",getPackageName()));}
    void geometry(int width,int height)throws Exception{
        Display.class.getMethod("setWindowSize",int.class,int.class).invoke(null,width,height);
        Configuration c=getResources().getConfiguration();DisplayMetrics dm=getResources().getDisplayMetrics();
        check(dm.widthPixels==width&&dm.heightPixels==height,"live metrics dimensions");
        check(c.screenWidthDp==Math.round(width/dm.density)&&c.screenHeightDp==Math.round(height/dm.density),"dp geometry");
        check(c.smallestScreenWidthDp==Math.min(c.screenWidthDp,c.screenHeightDp),"smallest width");
        check(value(this,"orientation_value").equals(width>height?"landscape":"portrait"),"native resource selection");
    }
    public void onCreate(android.os.Bundle b){
        super.onCreate(b);
        try{
            DisplayMetrics out=new DisplayMetrics();out.density=99;out.densityDpi=999;
            getWindowManager().getDefaultDisplay().getMetrics(out);
            check(out.density>0&&out.density!=99&&out.densityDpi!=999,"Display fills density as well as dimensions");
            Configuration delta=new Configuration();delta.uiMode=Configuration.UI_MODE_NIGHT_YES;delta.fontScale=1.5f;delta.locale=Locale.FRANCE;delta.screenWidthDp=777;delta.densityDpi=320;
            final Context overridden=createConfigurationContext(delta);
            Configuration childDelta=new Configuration();childDelta.fontScale=0;childDelta.screenHeightDp=888;
            final Context nested=overridden.createConfigurationContext(childDelta);
            DisplayMetrics fixed=new DisplayMetrics();fixed.widthPixels=fixed.noncompatWidthPixels=111;fixed.heightPixels=fixed.noncompatHeightPixels=222;
            Resources standalone=new Resources(getAssets(),fixed,new Configuration(getResources().getConfiguration()));
            boolean atl;
            try{Display.class.getMethod("setWindowSize",int.class,int.class);atl=true;}catch(NoSuchMethodException e){atl=false;}
            if(atl){
                int oldW=out.widthPixels,oldH=out.heightPixels;
                geometry(900,1600);geometry(1600,900);
                check(standalone.getDisplayMetrics().widthPixels==111,"standalone resource was resized");
                Configuration c=overridden.getResources().getConfiguration();
                check(c.screenWidthDp==777&&c.densityDpi==320&&c.fontScale==1.5f&&Locale.FRANCE.equals(c.locale),"override fields lost");
                check(nested.getResources().getConfiguration().screenWidthDp==777&&nested.getResources().getConfiguration().screenHeightDp==888,"nested override lost");
                check(value(overridden,"night_value").equals("night"),"override qualifier lost");
                geometry(oldW,oldH);
            }
            android.widget.TextView text=new android.widget.TextView(this);text.setText("Window geometry probe");setContentView(text);
            new android.os.Handler().postDelayed(new Runnable(){public void run(){dialogTest();}},500);
        }catch(Throwable t){t.printStackTrace();System.out.println("WINDOW_GEOMETRY FAIL "+t);}
    }
    void dialogTest(){
        final Display display=getWindowManager().getDefaultDisplay();
        final int width=display.getWidth(),height=display.getHeight();
        final AlertDialog dialog=new AlertDialog.Builder(this).setMessage("Small dialog").create();dialog.show();
        new android.os.Handler().postDelayed(new Runnable(){public void run(){
            try{
                check(display.getWidth()==width&&display.getHeight()==height,"dialog changed app display dimensions");
                dialog.dismiss();getFilesDir().mkdirs();
                java.io.OutputStream out=new java.io.FileOutputStream(new java.io.File(getFilesDir(),"results.json"));out.write("{\"status\":\"PASS\"}".getBytes("UTF-8"));out.close();
                System.out.println("WINDOW_GEOMETRY PASS");
            }catch(Throwable t){t.printStackTrace();System.out.println("WINDOW_GEOMETRY FAIL "+t);}
        }},500);
    }
}
