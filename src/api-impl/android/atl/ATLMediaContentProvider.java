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
import java.nio.file.Files;

public class ATLMediaContentProvider extends ContentProvider {
	private ATLMediaIndex index;

	private synchronized ATLMediaIndex index() {
		String root = System.getenv("ATL_MEDIA_ROOT");
		if (root == null || root.isEmpty())
			return null;
		if (index == null)
			index = new ATLMediaIndex(getContext(), root);
		return index;
	}

	@Override
	public Cursor query(Uri uri, String[] projection, android.os.Bundle args, android.os.CancellationSignal signal) {
		if (signal != null)
			signal.throwIfCanceled();
		ATLMediaIndex media = index();
		if (media == null)
			return super.query(uri, projection, args, signal);
		return media.query(uri, projection,
		                   args == null ? null : args.getString("android:query-arg-sql-selection"),
		                   args == null ? null : args.getStringArray("android:query-arg-sql-selection-args"),
		                   args == null ? null : args.getString("android:query-arg-sql-sort-order"), args);
	}

	boolean waitingForFileChooser = false;
	File selectedFile = null;
	long timestamp = 0;

	// called from native
	void setSelectedFile(String selectedFile) {
		this.selectedFile = selectedFile == null ? null : new File(selectedFile);
		this.waitingForFileChooser = false;
		this.timestamp = System.currentTimeMillis();
		synchronized (this) {
			notifyAll();
		}
	}

	private void openFileChooser() {
		if (!waitingForFileChooser) {
			waitingForFileChooser = true;
			new Handler(Looper.getMainLooper()).post(new Runnable() {
				@Override
				public void run() {
					native_open_media_folder();
				}
			});
		}
		synchronized (this) {
			try {
				while (waitingForFileChooser) {
					wait();
				}
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
		}
	}

	@Override
	public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
		ATLMediaIndex media = index();
		if (media != null)
			return media.query(uri, projection, selection, selectionArgs, sortOrder, null);
		if (selectionArgs != null && selectionArgs.length > 0 && selectedFile != null) {
			// A follow-up metadata query for an already selected file must not reopen the chooser.
			timestamp = System.currentTimeMillis();
		}
		// if we haven't selected a file, open the file chooser
		if (!"0".equals(uri.getLastPathSegment()) && timestamp + 1000 < System.currentTimeMillis()) {
			openFileChooser();
		}
		MatrixCursor cursor = new MatrixCursor(projection);
		Object[] row = new Object[projection.length];
		if (uri.getQueryParameter("distinct") != null) {
			for (int i = 0; i < projection.length; i++) {
				switch (projection[i]) {
					case "bucket_display_name":
						row[i] = "files";
						break;
					case "bucket_id":
						row[i] = 0;
						break;
				}
			}
		} else {
			for (int i = 0; i < projection.length; i++) {
				switch (projection[i]) {
					case "_id":
						row[i] = 0;
						break;
					case "_data":
						row[i] = selectedFile == null ? null : selectedFile.getAbsolutePath();
						break;
					case "title":
						row[i] = selectedFile == null ? null : selectedFile.getName();
						break;
					case "mime_type":
						row[i] = getType(uri);
						break;
					case "media_type":
						if (getType(uri).startsWith("image/"))
							row[i] = 1;
						else if (getType(uri).startsWith("audio/"))
							row[i] = 2;
						else if (getType(uri).startsWith("video/"))
							row[i] = 3;
						else
							row[i] = 0;
						break;
					case "date_modified":
					case "datetaken":
						row[i] = selectedFile.lastModified();
						break;
					case "orientation":
						row[i] = 0;
						break;
					case "_size":
						row[i] = selectedFile.length();
						break;
				}
			}
		}
		cursor.addRow(row);
		return cursor;
	}

	@Override
	public String getType(Uri uri) {
		ATLMediaIndex media = index();
		if (media != null)
			return media.getType(uri);
		try {
			return Files.probeContentType(selectedFile.toPath());
		} catch (IOException e) {
			e.printStackTrace();
			return "application/octet-stream";
		}
	}

	@Override
	public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
		ATLMediaIndex media = index();
		if (media != null)
			return media.open(uri, mode);
		return ParcelFileDescriptor.open(selectedFile, ParcelFileDescriptor.parseMode(mode));
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
