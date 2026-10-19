// SPDX-License-Identifier: GPL-3.0-only
package org.atl.clipdataintent;
import android.app.Activity;import android.os.Bundle;import android.content.*;import android.net.Uri;
public class Probe extends Activity {
 int checks;void check(boolean b,String s){if(!b)throw new AssertionError(s);checks++;}
 public void onCreate(Bundle b){super.onCreate(b);int result=0;try{
 Intent intent=new Intent(Intent.ACTION_VIEW,Uri.parse("https://example.org/"));
 ClipData clip=ClipData.newIntent("video",intent);
 check(clip.getItemCount()==1,"one item");check(clip.getItemAt(0).getIntent()==intent,"intent retained");
 check(clip.getItemAt(0).getText()==null,"not text");check(clip.getItemAt(0).getUri()==null,"not URI");
 check("video".contentEquals(clip.getDescription().getLabel()),"label");
 check(clip.getDescription().getMimeTypeCount()==1,"one MIME");
 check(ClipDescription.MIMETYPE_TEXT_INTENT.equals(clip.getDescription().getMimeType(0)),"intent MIME");
 clip.addItem(new ClipData.Item("second"));check(clip.getItemCount()==2,"append");check("second".contentEquals(clip.getItemAt(1).getText()),"order");
 ClipData plain=ClipData.newPlainText(null,"hello");check("hello".contentEquals(plain.getItemAt(0).getText()),"plain text");
 check(ClipDescription.MIMETYPE_TEXT_PLAIN.equals(plain.getDescription().getMimeType(0)),"text MIME");
 Uri uri=Uri.parse("content://example/item");check(ClipData.newRawUri(null,uri).getItemAt(0).getUri()==uri,"URI retained");
 check(ClipData.newIntent(null,null).getItemAt(0).getIntent()==null,"null intent allowed");
 boolean failed=false;try{clip.addItem((ClipData.Item)null);}catch(NullPointerException e){failed=true;}check(failed,"null item rejected");
 failed=false;try{clip.getItemAt(2);}catch(IndexOutOfBoundsException e){failed=true;}check(failed,"invalid index");
 System.out.println("CLIPDATA_INTENT_PASS checks="+checks);android.util.Log.i("ClipDataIntent","CLIPDATA_INTENT_PASS checks="+checks);
 }catch(Throwable t){result=1;t.printStackTrace();android.util.Log.e("ClipDataIntent","FAIL "+t);}finish();System.exit(result);}
}
