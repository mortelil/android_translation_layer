// SPDX-License-Identifier: Apache-2.0
package org.atl.globallayout;
import android.os.*;
import android.view.*;
import android.widget.TextView;
public class Probe extends android.app.Activity {
    TextView view; Handler handler=new Handler(); int first,second,canceled,dialogCalls; boolean done;
    final ViewTreeObserver.OnGlobalLayoutListener cancel=new ViewTreeObserver.OnGlobalLayoutListener(){public void onGlobalLayout(){canceled++;}};
    final ViewTreeObserver.OnGlobalLayoutListener later=new ViewTreeObserver.OnGlobalLayoutListener(){public void onGlobalLayout(){
        second++;view.getViewTreeObserver().removeOnGlobalLayoutListener(this);
        handler.postDelayed(new Runnable(){public void run(){dialogTest();}},300);
    }};
    final ViewTreeObserver.OnGlobalLayoutListener initial=new ViewTreeObserver.OnGlobalLayoutListener(){public void onGlobalLayout(){
        first++;
        if(view.getWidth()<=0 || view.getHeight()<=0){fail("callback before layout");return;}
        view.getViewTreeObserver().removeOnGlobalLayoutListener(this);
        // Remove last registration during dispatch; reconnect it on the next turn.
        handler.post(new Runnable(){public void run(){
            ViewTreeObserver observer=view.getViewTreeObserver();
            observer.addOnGlobalLayoutListener(cancel);observer.removeOnGlobalLayoutListener(cancel);
            observer.addOnGlobalLayoutListener(later);view.requestLayout();
        }});
    }};
    void dialogTest(){
        final android.app.Dialog dialog=new android.app.Dialog(this);
        final TextView text=new TextView(this);text.setText("Before dialog mapping");dialog.setContentView(text);
        ViewTreeObserver observer=text.getViewTreeObserver();
        observer.addOnGlobalLayoutListener(cancel);observer.removeOnGlobalLayoutListener(cancel);
        observer.addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener(){public void onGlobalLayout(){
            dialogCalls++;text.getViewTreeObserver().removeOnGlobalLayoutListener(this);
            if(text.getWidth()<=0 || text.getHeight()<=0){fail("dialog before layout");return;}
            handler.post(new Runnable(){public void run(){dialog.dismiss();handler.postDelayed(new Runnable(){public void run(){complete();}},300);}});
        }});
        dialog.show();
    }
    void fail(String why){if(done)return;done=true;System.out.println("GLOBAL_LAYOUT FAIL "+why+" first="+first+" second="+second+" canceled="+canceled);}
    void complete(){if(done)return;if(first!=1 || second!=1 || canceled!=0 || dialogCalls!=1){fail("counts");return;}done=true;try{
        getFilesDir().mkdirs();try(java.io.FileOutputStream f=new java.io.FileOutputStream(new java.io.File(getFilesDir(),"results.json"))){f.write("{\"status\":\"PASS\",\"first\":1,\"second\":1,\"canceled\":0,\"dialog\":1}".getBytes("UTF-8"));}
        System.out.println("GLOBAL_LAYOUT PASS");finish();
    }catch(Exception e){throw new RuntimeException(e);}}
    public void onCreate(Bundle state){super.onCreate(state);
        view=new TextView(this);view.setText("Global layout lifecycle probe");
        ViewTreeObserver floating=view.getViewTreeObserver();
        floating.addOnGlobalLayoutListener(cancel);floating.removeOnGlobalLayoutListener(cancel);
        floating.addOnGlobalLayoutListener(initial);
        setContentView(view);
        // Window-backed observer before first frame: registration then cancellation.
        view.getViewTreeObserver().addOnGlobalLayoutListener(cancel);
        view.getViewTreeObserver().removeOnGlobalLayoutListener(cancel);
        handler.postDelayed(new Runnable(){public void run(){if(!done)fail("timeout");}},4000);
    }
}
