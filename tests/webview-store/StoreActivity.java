package org.atl.tests.store;
import android.app.Activity;
import android.os.Bundle;
import android.content.*;
import android.webkit.WebViewDatabase;
import java.io.File;
import java.util.Arrays;
public class StoreActivity extends Activity {
 int checks;
 void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
 Context scoped(final String name){return new ContextWrapper(this){public File getSharedPrefsFile(String file){return new File(getFilesDir(),name+"/"+file+".xml");}};}
 public void onCreate(Bundle b){super.onCreate(b);try{
  Context a=scoped("a"),other=scoped("b");
  WebViewDatabase db=WebViewDatabase.getInstance(a),db2=WebViewDatabase.getInstance(other);
  check(db==WebViewDatabase.getInstance(a),"same scope singleton");check(db!=db2,"separate package stores");
  boolean reload=new File(getFilesDir(),"reload").exists();
  if(reload){check(Arrays.equals(db.getHttpAuthUsernamePassword("h\u0000x","r"),new String[]{"user","password"}),"disk reload");check(db2.getHttpAuthUsernamePassword("h\u0000x","r")==null,"disk isolation");}
  db.clearHttpAuthUsernamePassword();db2.clearHttpAuthUsernamePassword();
  check(!db.hasHttpAuthUsernamePassword(),"initially empty");
  db.setHttpAuthUsernamePassword("h\u0000x","r","user","password");
  check(Arrays.equals(db.getHttpAuthUsernamePassword("h\u0000x","r"),new String[]{"user","password"}),"roundtrip");
  check(db.getHttpAuthUsernamePassword("h","x\u0000r")==null,"key boundary");
  check(db2.getHttpAuthUsernamePassword("h\u0000x","r")==null,"no cross-app leakage");
  db.clearUsernamePassword();db.clearFormData();check(db.hasHttpAuthUsernamePassword(),"legacy clear preserves auth");
  db.setHttpAuthUsernamePassword("empty","",null,null);check(Arrays.equals(db.getHttpAuthUsernamePassword("empty",""),new String[]{null,null}),"nullable pair");
  db.setHttpAuthUsernamePassword(null,"r","x","y");check(db.getHttpAuthUsernamePassword(null,"r")==null,"null host");
  db.setHttpAuthUsernamePassword("h\u0000x","r","new","newpass");check("new".equals(db.getHttpAuthUsernamePassword("h\u0000x","r")[0]),"replacement");
  db.clearHttpAuthUsernamePassword();check(!db.hasHttpAuthUsernamePassword()&&db.getHttpAuthUsernamePassword("empty","")==null,"clear all pairs");
  db.setHttpAuthUsernamePassword("h\u0000x","r","user","password");
  new File(getFilesDir(),"reload").createNewFile();
  System.out.println("WEBSTORE_PASS reload="+reload+" checks="+checks);finish();System.exit(0);
 }catch(Throwable t){t.printStackTrace();System.out.println("WEBSTORE_FAIL "+t);finish();System.exit(1);}}
}
