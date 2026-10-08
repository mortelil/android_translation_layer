// SPDX-License-Identifier: GPL-3.0-only
package android.atl;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Locale;

/** An opt-in, read-only MediaStore image index for one Linux directory tree. */
final class ATLMediaIndex {
	private final File root;
	private final SQLiteDatabase db;
	private long lastScan;

	ATLMediaIndex(Context context, String directory) {
		try {
			root = new File(directory).getCanonicalFile();
		} catch (IOException e) {
			throw new IllegalArgumentException("Invalid media directory", e);
		}
		if (!root.isDirectory())
			throw new IllegalArgumentException("Media root is not a directory");
		db = SQLiteDatabase.openOrCreateDatabase(new File(context.getCacheDir(), "atl-media-index-v1.db"), null);
		db.execSQL("CREATE TABLE IF NOT EXISTS buckets (_id INTEGER PRIMARY KEY AUTOINCREMENT, path TEXT UNIQUE)");
		db.execSQL("CREATE TABLE IF NOT EXISTS media (_id INTEGER PRIMARY KEY AUTOINCREMENT, _data TEXT UNIQUE, "
		           + "_display_name TEXT, title TEXT, mime_type TEXT, media_type INTEGER, _size INTEGER, "
		           + "date_added INTEGER, date_modified INTEGER, datetaken INTEGER, width INTEGER, height INTEGER, "
		           + "orientation INTEGER, duration INTEGER, bucket_id INTEGER, bucket_display_name TEXT, "
		           + "relative_path TEXT, volume_name TEXT, is_pending INTEGER DEFAULT 0, is_trashed INTEGER DEFAULT 0, scan INTEGER)");
		ensureLocationColumns();
	}

	// Older Android clients still request these nullable, deprecated columns.
	// Unknown location must remain NULL, never a fabricated coordinate.
	private void ensureLocationColumns() {
		java.util.Set<String> columns = new java.util.HashSet<>();
		try (Cursor c = db.rawQuery("PRAGMA table_info(media)", null)) {
			while (c.moveToNext())
				columns.add(c.getString(c.getColumnIndexOrThrow("name")));
		}
		if (!columns.contains("longitude"))
			db.execSQL("ALTER TABLE media ADD COLUMN longitude REAL");
		if (!columns.contains("latitude"))
			db.execSQL("ALTER TABLE media ADD COLUMN latitude REAL");
	}

	private static String mime(File file) {
		String n = file.getName().toLowerCase(Locale.ROOT);
		if (n.endsWith(".jpg") || n.endsWith(".jpeg"))
			return "image/jpeg";
		if (n.endsWith(".png"))
			return "image/png";
		if (n.endsWith(".webp"))
			return "image/webp";
		if (n.endsWith(".gif"))
			return "image/gif";
		return null;
	}

	private long bucket(File directory) {
		String path = directory.getAbsolutePath();
		try (Cursor c = db.rawQuery("SELECT _id FROM buckets WHERE path=?", new String[] {path})) {
			if (c.moveToFirst())
				return c.getLong(0);
		}
		ContentValues values = new ContentValues();
		values.put("path", path);
		return db.insertOrThrow("buckets", null, values);
	}

	private void scan(File directory, long generation) throws IOException {
		if (new File(directory, ".nomedia").exists())
			return;
		File[] entries = directory.listFiles();
		if (entries == null)
			throw new IOException("Unable to enumerate media directory");
		for (File file : entries) {
			if (file.getName().startsWith(".") || Files.isSymbolicLink(file.toPath()))
				continue;
			if (file.isDirectory()) {
				scan(file, generation);
				continue;
			}
			String type = mime(file);
			if (!file.isFile() || type == null)
				continue;
			String path = file.getCanonicalPath();
			if (!path.startsWith(root.getPath() + File.separator))
				continue;
			long size = file.length(), modified = file.lastModified() / 1000;
			boolean exists = false, unchanged = false;
			try (Cursor c = db.rawQuery("SELECT _size,date_modified FROM media WHERE _data=?", new String[] {path})) {
				exists = c.moveToFirst();
				unchanged = exists && c.getLong(0) == size && c.getLong(1) == modified;
			}
			ContentValues values = new ContentValues();
			values.put("scan", generation);
			if (!unchanged) {
				BitmapFactory.Options options = new BitmapFactory.Options();
				options.inJustDecodeBounds = true;
				BitmapFactory.decodeFile(path, options);
				if (options.outWidth <= 0 || options.outHeight <= 0)
					continue;
				values.put("_data", path);
				values.put("_display_name", file.getName());
				values.put("title", file.getName());
				values.put("mime_type", type);
				values.put("media_type", 1);
				values.put("_size", size);
				values.put("date_modified", modified);
				// There is no portable filesystem creation time. Preserve first indexing time.
				if (!exists)
					values.put("date_added", System.currentTimeMillis() / 1000);
				values.put("datetaken", file.lastModified());
				values.put("width", options.outWidth);
				values.put("height", options.outHeight);
				values.put("orientation", 0);
				values.put("duration", 0);
				values.put("bucket_id", bucket(directory));
				values.put("bucket_display_name", directory.getName());
				values.put("relative_path", root.toPath().relativize(directory.toPath()).toString() + "/");
				values.put("volume_name", "external_primary");
			}
			if (exists)
				db.update("media", values, "_data=?", new String[] {path});
			else
				db.insertOrThrow("media", null, values);
		}
	}

	private void refresh() {
		long now = android.os.SystemClock.elapsedRealtime();
		if (lastScan != 0 && now - lastScan < 2000)
			return;
		long generation = System.currentTimeMillis();
		db.beginTransaction();
		try {
			scan(root, generation);
			db.delete("media", "scan!=?", new String[] {Long.toString(generation)});
			db.setTransactionSuccessful();
			lastScan = now;
		} catch (IOException e) {
			throw new IllegalStateException("Media scan failed", e);
		} finally {
			db.endTransaction();
		}
	}

	private String filter(Uri uri) {
		List<String> parts = uri.getPathSegments();
		if (parts.contains("internal"))
			return "0";
		if (parts.contains("video") || parts.contains("audio"))
			return "0";
		if (!parts.contains("external") && !parts.contains("external_primary"))
			throw new IllegalArgumentException("Unsupported media volume");
		String last = uri.getLastPathSegment();
		if (last != null && last.matches("[0-9]+"))
			return "_id=" + Long.parseLong(last);
		return "media_type=1";
	}

	synchronized Cursor query(Uri uri, String[] projection, String selection, String[] args, String order, Bundle bundle) {
		refresh();
		String where = filter(uri);
		if (selection != null && !selection.isEmpty())
			where += " AND (" + selection + ")";
		String group = null, limit = uri.getQueryParameter("limit");
		if (bundle != null) {
			if (bundle.containsKey("android:query-arg-sql-limit"))
				limit = bundle.getString("android:query-arg-sql-limit");
			group = bundle.getString("android:query-arg-sql-group-by");
			if (group == null) {
				String[] columns = bundle.getStringArray("android:query-arg-group-columns");
				if (columns != null)
					group = String.join(",", columns);
			}
			if (bundle.containsKey("android:query-arg-limit")) {
				int count = bundle.getInt("android:query-arg-limit");
				int offset = bundle.getInt("android:query-arg-offset", 0);
				if (count < 0 || offset < 0)
					throw new IllegalArgumentException("Negative pagination");
				limit = offset + "," + count;
			}
			if (order == null) {
				String[] columns = bundle.getStringArray("android:query-arg-sort-columns");
				if (columns != null)
					order = String.join(",", columns) + (bundle.getInt("android:query-arg-sort-direction", 0) == 1 ? " DESC" : " ASC");
			}
		}
		Cursor result = db.query("1".equals(uri.getQueryParameter("distinct")), "media", projection,
		                         where, args, group, null, order, limit);
		if ("1".equals(System.getenv("ATL_DEBUG_MEDIA")))
			System.err.println("ATL MediaStore: returned " + result.getCount() + " rows");
		return result;
	}

	private File resolve(Uri uri) throws FileNotFoundException {
		String id = uri.getLastPathSegment();
		if (id == null || !id.matches("[0-9]+"))
			throw new FileNotFoundException("Media ID required");
		try (Cursor c = query(uri, new String[] {"_data"}, null, null, null, null)) {
			if (!c.moveToFirst())
				throw new FileNotFoundException("Unknown media ID");
			File f = new File(c.getString(0));
			try {
				if (!f.getCanonicalPath().startsWith(root.getPath() + File.separator) || !f.isFile())
					throw new FileNotFoundException("Media file is no longer available");
			} catch (IOException e) {
				throw new FileNotFoundException(e.getMessage());
			}
			return f;
		}
	}

	synchronized ParcelFileDescriptor open(Uri uri, String mode) throws FileNotFoundException {
		if (!"r".equals(mode))
			throw new FileNotFoundException("ATL media index is read-only");
		return ParcelFileDescriptor.open(resolve(uri), ParcelFileDescriptor.MODE_READ_ONLY);
	}

	synchronized String getType(Uri uri) {
		try {
			return mime(resolve(uri));
		} catch (FileNotFoundException e) {
			return null;
		}
	}
}
