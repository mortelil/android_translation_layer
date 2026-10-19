// SPDX-License-Identifier: GPL-3.0-only
package org.atl.providertest;
public class LowOwner extends Owner {
 private boolean correct;
 public boolean onCreate(){correct=HighOwner.ready&&!HighOwner.disabled;return true;}
 public android.os.Bundle call(String method,String arg,android.os.Bundle extras){
  android.os.Bundle b=new android.os.Bundle();b.putBoolean("ordered",correct);return b;
 }
}
