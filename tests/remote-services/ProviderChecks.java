/* SPDX-License-Identifier: GPL-3.0-only */
package org.atl.remoteservices;
import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.*;

final class ProviderChecks {
    static void check(boolean value, String label) { if (!value) throw new IllegalStateException(label); }
    static Uri uri(String path) { return Uri.parse("content://org.atl.remoteservices.provider/"+path); }
    static int run(Context context) {
        ContentResolver r = context.getContentResolver();
        ContentProviderClient client = r.acquireContentProviderClient(uri("values"));
        check(client != null && client.getLocalContentProvider() == null, "remote client identity");
        client.close();
        Bundle args = new Bundle();
        args.putString("android:query-arg-sql-selection", "filter=?");
        args.putStringArray("android:query-arg-sql-selection-args", new String[]{"argument"});
        args.putString("android:query-arg-sql-sort-order", "sort");
        int pid;
        for (int pass=0;pass<2;pass++) {
            Cursor c = pass == 0 ? r.query(uri("values"), new String[]{"requested"}, "filter=?", new String[]{"argument"}, "sort")
                : r.query(uri("values"), new String[]{"requested"}, args, null);
            check(c != null && c.getPosition()==-1 && c.getCount()==1 && c.moveToFirst(), "cursor positioning");
            pid=c.getInt(0);
            check(pid!=android.os.Process.myPid() && c.getInt(1)==73 && c.getInt(2)==android.os.Process.myPid(), "owner process state/calling PID");
            check(c.isNull(3) && c.getLong(4)==1234567890123L && c.getDouble(5)==2.5 && "æ漢字".equals(c.getString(6)) && java.util.Arrays.equals(c.getBlob(7),new byte[]{0,1,-1}), "cursor types");
            check("extras".equals(c.getExtras().getString("test")), "cursor extras");
            check(!c.requery(), "snapshot must not claim live requery");
            c.close();
        }
        check("vnd.atl.test".equals(r.getType(uri("values"))), "MIME type");
        check("vnd.atl.test".equals(r.getType(Uri.parse("content://org.atl.remoteservices.alias/values"))), "authority alias");
        check(r.query(uri("null"),null,null,null,null)==null, "null cursor");
        Cursor empty = r.query(uri("empty"),null,null,null,null);
        check(empty!=null && empty.getCount()==0 && !empty.moveToFirst(), "empty cursor distinct from null"); empty.close();
        for (String path : new String[]{"error","oversize","rows"}) {
            boolean failed=false;
            try { Cursor c=r.query(uri(path),null,null,null,null); if(c!=null)c.close(); }
            catch (IllegalArgumentException e) { failed="error".equals(path) && "provider-test-error".equals(e.getMessage()); }
            catch (UnsupportedOperationException e) { failed=!"error".equals(path); }
            check(failed,"explicit query failure: "+path);
        }
        CancellationSignal canceled=new CancellationSignal(); canceled.cancel();
        boolean rejected=false;
        try { r.query(uri("error"),null,args,canceled); } catch(OperationCanceledException e) { rejected=true; }
        check(rejected,"pre-canceled query");
        rejected=false;
        try { r.update(uri("values"),new ContentValues(),null,null); } catch(UnsupportedOperationException e) { rejected=true; }
        check(rejected,"unsupported mutation must fail");
        check(r.query(Uri.parse("content://unregistered.test/x"),null,null,null,null)==null,"unknown authority");
        // A proxy must not be reusable as access to another authority.
        client=r.acquireContentProviderClient(uri("values")); rejected=false;
        try { client.getType(Uri.parse("content://unregistered.test/x")); } catch(SecurityException e) { rejected=true; } catch(RemoteException e) { throw new IllegalStateException(e); }
        client.close(); check(rejected,"authority confinement");
        return 73;
    }
}
