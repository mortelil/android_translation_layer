// SPDX-License-Identifier: GPL-3.0-only
package org.atl.searchhistory;
import android.app.Activity;
import android.os.*;
import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.provider.SearchRecentSuggestions;
import java.io.*;
public class Probe extends Activity {
 static final String AUTH="org.atl.searchhistory.provider";
 static final Uri URI=Uri.parse("content://"+AUTH+"/suggestions");
 public static class Provider extends SearchRecentSuggestionsProvider {public Provider(){setupSuggestions(AUTH,3);}}
 static class History extends SearchRecentSuggestions {History(Context c){super(c,AUTH,3);} void keep(Context c,int n){truncateHistory(c.getContentResolver(),n);}}
 int checks,attempts;
 void check(boolean ok,String label){if(!ok)throw new AssertionError(label);checks++;}
 int count(){Cursor c=getContentResolver().query(URI,null,null,null,null);try{return c.getCount();}finally{c.close();}}
 void done(String phase){System.out.println("SEARCH_HISTORY_PASS "+phase+" checks="+checks);android.util.Log.i("SearchProbe","SEARCH_HISTORY_PASS "+phase+" checks="+checks);finish();System.exit(0);}
 void fail(Throwable t){t.printStackTrace();android.util.Log.e("SearchProbe","FAIL "+t);finish();System.exit(1);}
 public void onCreate(Bundle state){super.onCreate(state);try{
  final File marker=new File(getFilesDir(),"saved");final History history=new History(this);
  if(marker.exists()){check(count()==1,"history survives restart");history.clearHistory();check(count()==0,"clear persisted history");marker.delete();done("restart");return;}
  history.clearHistory();check(count()==0,"initial clear");
  boolean rejected=false;try{new SearchRecentSuggestions(this,AUTH,0);}catch(IllegalArgumentException e){rejected=true;}check(rejected,"invalid mode rejected");
  rejected=false;try{new SearchRecentSuggestions(this,AUTH,1).saveRecentQuery("one","two");}catch(IllegalArgumentException e){rejected=true;}check(rejected,"two lines rejected in single-line mode");
  for(int i=0;i<3;i++){ContentValues v=new ContentValues();v.put("display1","item"+i);v.put("display2","details"+i);v.put("query","item"+i);v.put("date",100L+i);getContentResolver().insert(URI,v);}
  check(count()==3,"inserts");history.keep(this,1);check(count()==1,"truncate");history.clearHistory();
  history.saveRecentQuery("Bunny's film","animation");
  final Handler h=new Handler();h.postDelayed(new Runnable(){public void run(){try{
   if(count()!=1){if(++attempts>100)throw new AssertionError("async save timeout");h.postDelayed(this,50);return;}
   check(true,"async save");
   Cursor c=getContentResolver().query(Uri.parse("content://"+AUTH+"/search_suggest_query"),null,null,new String[]{"Bunny'"},null);
   try{check(c.moveToFirst(),"quoted query match");check("Bunny's film".equals(c.getString(c.getColumnIndex("suggest_text_1"))),"first line");check("animation".equals(c.getString(c.getColumnIndex("suggest_text_2"))),"second line");}finally{c.close();}
   FileOutputStream out=new FileOutputStream(marker);out.write(1);out.close();done("write");
  }catch(Throwable t){fail(t);}}},50);
 }catch(Throwable t){fail(t);}}
}
