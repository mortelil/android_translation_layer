package android.atl;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

public class ATLMediaContentProvider extends ContentProvider {
	private ATLMediaIndex index;

	private synchronized ATLMediaIndex index() {
		String root = System.getenv("ATL_MEDIA_ROOT");
		if (root == null || root.isEmpty())
			root = getContext().getSharedPreferences("atl-media", 0).getString("root", null);
		if (root == null || root.isEmpty()) return null;
		if (index == null)
			index = new ATLMediaIndex(getContext(), root);
		return index;
	}

	@Override
	public Cursor query(Uri uri, String[] projection, android.os.Bundle args, android.os.CancellationSignal signal) {
		if (signal != null)
			signal.throwIfCanceled();
		ATLMediaIndex media = queryIndex();
		if (signal != null) signal.throwIfCanceled();
		if (media == null) return emptyCursor(projection);
		return media.query(uri, projection,
		                   args == null ? null : args.getString("android:query-arg-sql-selection"),
		                   args == null ? null : args.getStringArray("android:query-arg-sql-selection-args"),
		                   args == null ? null : args.getString("android:query-arg-sql-sort-order"), args);
	}

	private boolean waitingForFileChooser;

	private boolean selectionAttempted;

	// GTK returns a directory, never a single media file. Persist only an
	// explicitly selected root, scoped to this application's preferences.
	void setSelectedDirectory(String path) {
		synchronized (this) {
			selectionAttempted = true;
			try {
				if (path != null) {
					File root = new File(path).getCanonicalFile();
					if (root.isDirectory()) {
						getContext().getSharedPreferences("atl-media", 0).edit()
						    .putString("root", root.getPath()).apply();
						index = null;
					}
				}
			} catch (IOException e) {
				android.util.Log.w("ATLMedia", "Cannot resolve selected media root", e);
			} finally {
				waitingForFileChooser = false;
				notifyAll();
			}
		}
	}

	private void openFileChooser() {
		synchronized (this) {
			if (!selectionAttempted) {
				selectionAttempted = true;
				waitingForFileChooser = true;
				new Handler(Looper.getMainLooper()).post(new Runnable() {
					@Override public void run() { native_open_media_folder(); }
				});
			}
			// Never block GTK's own thread while waiting for its chooser.
			if (Looper.myLooper() == Looper.getMainLooper()) return;
			try {
				while (waitingForFileChooser) wait();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
	}

	@Override
	public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
		ATLMediaIndex media = queryIndex();
		return media == null ? emptyCursor(projection) : media.query(uri, projection, selection, selectionArgs, sortOrder, null);
	}

	private ATLMediaIndex queryIndex() {
		ATLMediaIndex media = index();
		if (media == null) { openFileChooser(); media = index(); }
		return media;
	}

	private Cursor emptyCursor(String[] projection) {
		return new MatrixCursor(projection == null ? new String[]{"_id"} : projection);
	}

	@Override
	public String getType(Uri uri) {
		ATLMediaIndex media = index();
		return media == null ? null : media.getType(uri);
	}

	@Override
	public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
		ATLMediaIndex media = index();
		if (media == null) throw new FileNotFoundException("No media directory selected");
		return media.open(uri, mode);
	}

	@Override
	public Uri insert(Uri uri, ContentValues values) {
		// TODO Auto-generated method stub
		throw new UnsupportedOperationException("Unimplemented method 'insert'");
	}

	@Override
	public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
		// TODO Auto-generated method stub
		throw new UnsupportedOperationException("Unimplemented method 'update'");
	}

	@Override
	public int delete(Uri uri, String selection, String[] selectionArgs) {
		// TODO Auto-generated method stub
		throw new UnsupportedOperationException("Unimplemented method 'delete'");
	}

	private native void native_open_media_folder();

	@Override
	public AssetFileDescriptor openAssetFile(Uri uri, String mode) throws FileNotFoundException {
		return super.openAssetFile(uri, mode);
	}
}
