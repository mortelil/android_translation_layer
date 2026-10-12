/* SPDX-License-Identifier: GPL-3.0-only */
package android.content;

import android.app.Application;
import android.atl.ATLLoadedApp;
import android.atl.ATLRemoteServices;
import android.content.pm.PackageParser;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.*;

/** Bounded, read-only provider RPC within one application's launcher session. */
public final class ATLRemoteProvider extends ContentProvider {
	private static final String DESCRIPTOR = "android.atl.parent.provider";
	private static final int QUERY = 1, TYPE = 2, MAX_BYTES = 64 * 1024, MAX_ROWS = 512, MAX_COLUMNS = 128;
	private static final boolean DIAGNOSTICS = "1".equals(System.getenv("ATL_PROVIDER_DIAGNOSTICS"));
	private final String authority;
	private final IBinder remote;

	private ATLRemoteProvider(String authority, IBinder remote) {
		this.authority = authority;
		this.remote = remote;
	}

	private static PackageParser.Provider declared(String authority, String process) {
		ATLLoadedApp app = ATLLoadedApp.getPrimaryApplication();
		for (PackageParser.Provider provider : app.pkg.providers) {
			String owner = provider.info.processName;
			if (owner == null) owner = app.pkg.applicationInfo.processName;
			if (owner == null) owner = app.pkg.packageName;
			if (!process.equals(owner) || provider.info.authority == null) continue;
			for (String alias : provider.info.authority.split(";"))
				if (alias.equals(authority)) return provider;
		}
		return null;
	}

	static ContentProvider lookup(String authority) {
		String parent = System.getenv("ATL_SERVICE_PARENT_PROCESS");
		if (!"1".equals(System.getenv("ATL_ENABLE_REMOTE_SERVICES")) || parent == null) return null;
		String ownPackage = ATLLoadedApp.getPrimaryApplication().pkg.packageName;
		// The channel is not a general service manager or a cross-app permission grant.
		if (!ownPackage.equals(System.getenv("ATL_SERVICE_PARENT_PACKAGE"))) return null;
		if (declared(authority, parent) == null) return null;
		return new ATLRemoteProvider(authority, ATLRemoteServices.parentProviderBinder());
	}

	/** Root endpoint bound to this child's package, rather than a claimed wire identity. */
	public static IBinder endpoint(final String childPackage) {
		return new Binder() {
			@Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
				if (code != QUERY && code != TYPE) return super.onTransact(code, data, reply, flags);
				data.enforceInterface(DESCRIPTOR);
				if (!childPackage.equals(ATLLoadedApp.getPrimaryApplication().pkg.packageName))
					throw new SecurityException("Cross-package provider RPC is not supported");
				Uri uri = Uri.parse(data.readString());
				if (!"content".equals(uri.getScheme()) || declared(uri.getAuthority(), Application.getProcessName()) == null)
					throw new SecurityException("Provider does not belong to the parent application process");
				ContentProvider provider = ContentProvider.atl_get_local_content_provider(uri.getAuthority());
				if (provider == null) throw new IllegalStateException("Parent provider is unavailable");
				reply.writeNoException();
				if (code == TYPE) {
					reply.writeString(provider.getType(uri));
				} else {
					String[] projection = data.createStringArray();
					Bundle args;
					try { args = data.readBundle(ATLRemoteProvider.class.getClassLoader()); }
					catch (ReflectiveOperationException e) { throw new IllegalArgumentException("Invalid query arguments", e); }
					// Like AOSP ContentProvider transport, execute on the Binder caller thread.
					// Do not post to the parent UI thread: it may be waiting on this child.
					Cursor cursor = provider.query(uri, projection, args, null);
					if (cursor == null) {
						if (DIAGNOSTICS) android.util.Log.i("ATLProviderIPC", "query returned null");
						reply.writeInt(0);
					}
					else try {
						reply.writeInt(1);
						String[] columns = cursor.getColumnNames();
						int count = cursor.getCount();
						if (columns.length > MAX_COLUMNS || count < 0 || count > MAX_ROWS)
							throw new UnsupportedOperationException("Remote provider snapshot exceeds row/column limit");
						reply.writeStringArray(columns);
						reply.writeInt(count);
						for (int row = 0; row < count; row++) {
							if (!cursor.moveToPosition(row)) throw new IllegalStateException("Provider cursor changed while reading");
							for (int col = 0; col < columns.length; col++) {
								int type = cursor.getType(col);
								reply.writeInt(type);
								switch (type) {
									case Cursor.FIELD_TYPE_NULL: break;
									case Cursor.FIELD_TYPE_INTEGER: reply.writeLong(cursor.getLong(col)); break;
									case Cursor.FIELD_TYPE_FLOAT: reply.writeDouble(cursor.getDouble(col)); break;
									case Cursor.FIELD_TYPE_STRING: reply.writeString(cursor.getString(col)); break;
									case Cursor.FIELD_TYPE_BLOB: reply.writeByteArray(cursor.getBlob(col)); break;
									default: throw new IllegalArgumentException("Unknown cursor field type");
								}
								bounded(reply);
							}
						}
						reply.writeBundle(cursor.getExtras());
						if (DIAGNOSTICS) android.util.Log.i("ATLProviderIPC", "query snapshot completed, rows=" + count);
					} finally { cursor.close(); }
				}
				bounded(reply);
				return true;
			}
		};
	}

	private static void bounded(Parcel parcel) {
		if (parcel.dataSize() > MAX_BYTES)
			throw new UnsupportedOperationException("Remote provider snapshot exceeds 64 KiB");
	}
	private Parcel request(Uri uri) {
		if (!"content".equals(uri.getScheme()) || !authority.equals(uri.getAuthority()))
			throw new SecurityException("URI does not match acquired provider");
		Parcel data = Parcel.obtain();
		data.writeInterfaceToken(DESCRIPTOR);
		data.writeString(uri.toString());
		return data;
	}
	@Override public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
		Bundle args = new Bundle();
		args.putString("android:query-arg-sql-selection", selection);
		args.putStringArray("android:query-arg-sql-selection-args", selectionArgs);
		args.putString("android:query-arg-sql-sort-order", sortOrder);
		return query(uri, projection, args, null);
	}
	@Override public Cursor query(Uri uri, String[] projection, Bundle args, CancellationSignal signal) {
		if (signal != null) signal.throwIfCanceled();
		Parcel data = request(uri), reply = Parcel.obtain();
		try {
			data.writeStringArray(projection);
			data.writeBundle(args);
			bounded(data);
			if (!remote.transact(QUERY, data, reply, 0)) throw new IllegalStateException("Provider query transaction unsupported");
			reply.readException();
			if (signal != null) signal.throwIfCanceled();
			if (reply.readInt() == 0) return null;
			String[] columns = reply.createStringArray();
			int count = reply.readInt();
			if (columns == null || columns.length > MAX_COLUMNS || count < 0 || count > MAX_ROWS)
				throw new IllegalStateException("Invalid provider snapshot");
			MatrixCursor cursor = new MatrixCursor(columns, count) {
				@Override public boolean requery() { return false; }
				@Override public Bundle respond(Bundle extras) { throw new UnsupportedOperationException("Remote cursor respond is not implemented"); }
			};
			for (int row = 0; row < count; row++) {
				Object[] values = new Object[columns.length];
				for (int col = 0; col < columns.length; col++) switch (reply.readInt()) {
					case Cursor.FIELD_TYPE_NULL: break;
					case Cursor.FIELD_TYPE_INTEGER: values[col] = reply.readLong(); break;
					case Cursor.FIELD_TYPE_FLOAT: values[col] = reply.readDouble(); break;
					case Cursor.FIELD_TYPE_STRING: values[col] = reply.readString(); break;
					case Cursor.FIELD_TYPE_BLOB: values[col] = reply.createByteArray(); break;
					default: throw new IllegalStateException("Invalid cursor field type");
				}
				cursor.addRow(values);
			}
			cursor.setExtras(reply.readBundle(ATLRemoteProvider.class.getClassLoader()));
			return cursor;
		} catch (ReflectiveOperationException e) { throw new IllegalStateException("Invalid cursor extras", e); }
		finally { data.recycle(); reply.recycle(); }
	}
	@Override public String getType(Uri uri) {
		Parcel data = request(uri), reply = Parcel.obtain();
		try {
			bounded(data);
			if (!remote.transact(TYPE, data, reply, 0)) throw new IllegalStateException("Provider type transaction unsupported");
			reply.readException();
			return reply.readString();
		} finally { data.recycle(); reply.recycle(); }
	}
	private UnsupportedOperationException unsupported() { return new UnsupportedOperationException("Remote providers currently support query/getType only"); }
	@Override public Uri insert(Uri uri, ContentValues values) { throw unsupported(); }
	@Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw unsupported(); }
	@Override public int delete(Uri uri, String selection, String[] args) { throw unsupported(); }
	@Override public ParcelFileDescriptor openFile(Uri uri, String mode) { throw unsupported(); }
}
