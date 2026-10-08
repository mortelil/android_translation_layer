// SPDX-License-Identifier: GPL-3.0-only
package android.atl;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.webkit.MimeTypeMap;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Read-only document access to files and trees selected in the native chooser.
 * This is provider-level access control, not a sandbox for native app code. */
public final class ATLDocumentsProvider extends ContentProvider {
	public static final String AUTHORITY = "org.androidtranslationlayer.documents";
	private static final HashMap<String, String> offered = new HashMap<>();
	private static SharedPreferences preferences() {
		return ATLLoadedApp.getPrimaryApplication().getApplication().getSharedPreferences("atl-document-grants", 0);
	}
	private static File canonical(File file) {
		try {
			return file.getCanonicalFile();
		} catch (IOException e) {
			throw new IllegalArgumentException("Cannot resolve document", e);
		}
	}
	public static synchronized Uri select(Uri fileUri, boolean tree) {
		if (!"file".equals(fileUri.getScheme()))
			throw new IllegalArgumentException("Only local documents are supported");
		File file = canonical(new File(fileUri.getPath()));
		if (!file.canRead() || (tree ? !file.isDirectory() : !file.isFile()))
			throw new IllegalArgumentException("Selected document is not readable");
		String id = UUID.randomUUID().toString();
		offered.put(id, (tree ? "tree:" : "file:") + file.getPath());
		return new Uri.Builder().scheme("content").authority(AUTHORITY).appendPath(tree ? "tree" : "document").appendPath(id).build();
	}
	private static String rootId(Uri uri) {
		if (uri == null)
			throw new NullPointerException("uri");
		if (!"content".equals(uri.getScheme()) || !AUTHORITY.equals(uri.getAuthority()))
			throw new SecurityException("No document grant for " + uri);
		List<String> parts = uri.getPathSegments();
		if (parts.size() < 2 || !("document".equals(parts.get(0)) || "tree".equals(parts.get(0))))
			throw new IllegalArgumentException("Invalid document URI");
		return parts.get(1).split(":", 2)[0];
	}
	public static synchronized void takePermission(Uri uri, int flags) {
		if ((flags & ~3) != 0)
			throw new IllegalArgumentException("Invalid mode flags");
		String id = rootId(uri);
		String grant = offered.get(id);
		if (grant == null)
			grant = preferences().getString(id, null);
		if (grant == null || (flags & 2) != 0)
			throw new SecurityException("No persistable read grant for " + uri);
		if ((flags & 1) != 0 && !preferences().edit().putString(id, grant).commit())
			throw new IllegalStateException("Cannot persist document grant");
	}
	public static synchronized void releasePermission(Uri uri, int flags) {
		if ((flags & ~3) != 0)
			throw new IllegalArgumentException("Invalid mode flags");
		String id = rootId(uri);
		if (preferences().getString(id, null) == null)
			throw new SecurityException("No persisted document grant");
		if ((flags & 1) != 0 && !preferences().edit().remove(id).commit())
			throw new IllegalStateException("Cannot release document grant");
	}
	private static synchronized File resolve(Uri uri) {
		String id = rootId(uri);
		String grant = offered.get(id);
		if (grant == null)
			grant = preferences().getString(id, null);
		if (grant == null)
			throw new SecurityException("Document was not selected");
		boolean tree = grant.startsWith("tree:");
		File root = new File(grant.substring(5));
		List<String> parts = uri.getPathSegments();
		String document = parts.get(1);
		if ("tree".equals(parts.get(0))) {
			if (!tree)
				throw new SecurityException("Not a tree grant");
			if ((parts.size() == 4 || (parts.size() == 5 && "children".equals(parts.get(4)))) && "document".equals(parts.get(2)))
				document = parts.get(3);
			else if (parts.size() != 2)
				throw new IllegalArgumentException("Invalid tree URI");
		} else if (parts.size() != 2)
			throw new IllegalArgumentException("Invalid document URI");
		File file;
		if (document.equals(id))
			file = canonical(root);
		else if (tree && document.startsWith(id + ":"))
			file = canonical(new File(root, document.substring(id.length() + 1)));
		else
			throw new SecurityException("Document outside granted tree");
		// Recheck the canonical target each time, including after symlink changes.
		String prefix = root.getPath().endsWith(File.separator) ? root.getPath() : root.getPath() + File.separator;
		if (!file.equals(root) && !(tree && file.getPath().startsWith(prefix)))
			throw new SecurityException("Document outside granted root");
		return file;
	}
	@Override
	public String getType(Uri uri) {
		File file = resolve(uri);
		if (file.isDirectory())
			return DocumentsContract.Document.MIME_TYPE_DIR;
		String name = file.getName();
		int dot = name.lastIndexOf('.');
		String mime = dot < 0 ? null : MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substring(dot + 1).toLowerCase(Locale.ROOT));
		return mime == null ? "application/octet-stream" : mime;
	}
	private void addRow(MatrixCursor cursor, String[] columns, Uri uri, File file, String id) {
		Object[] row = new Object[columns.length];
		for (int i = 0; i < columns.length; i++) {
			switch (columns[i]) {
				case "document_id":
					row[i] = id;
					break;
				case "_display_name":
					row[i] = file.getName();
					break;
				case "_size":
					row[i] = file.length();
					break;
				case "last_modified":
					row[i] = file.lastModified();
					break;
				case "mime_type":
					row[i] = getType(uri);
					break;
				case "flags":
					row[i] = 0;
					break;
			}
		}
		cursor.addRow(row);
	}
	@Override
	public Cursor query(Uri uri, String[] columns, String selection, String[] args, String order) {
		if (selection != null || order != null)
			throw new UnsupportedOperationException("Document filtering and sorting");
		if (columns == null)
			columns = new String[] {"document_id", "_display_name", "mime_type", "_size", "last_modified", "flags"};
		MatrixCursor cursor = new MatrixCursor(columns);
		File file = resolve(uri);
		List<String> parts = uri.getPathSegments();
		if (parts.size() == 5 && "children".equals(parts.get(4))) {
			File[] children = file.listFiles();
			if (children == null)
				throw new IllegalArgumentException("Cannot list selected directory");
			String parent = DocumentsContract.getDocumentId(uri);
			for (File child : children) {
				String id = parent + (parent.contains(":") ? "/" : ":") + child.getName();
				Uri childUri = DocumentsContract.buildDocumentUriUsingTree(uri, id);
				try {
					addRow(cursor, columns, childUri, resolve(childUri), id);
				} catch (SecurityException e) { /* Do not expose symlinks escaping the selected tree. */
				}
			}
		} else if (file.exists()) {
			String id = parts.size() == 2 ? parts.get(1) : DocumentsContract.getDocumentId(uri);
			addRow(cursor, columns, uri, file, id);
		}
		return cursor;
	}
	@Override
	public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
		if (!"r".equals(mode))
			throw new SecurityException("Document grant is read-only");
		File file = resolve(uri);
		if (!file.isFile())
			throw new FileNotFoundException("Not a regular document");
		return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
	}
	@Override
	public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException("Read-only documents"); }
	@Override
	public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException("Read-only documents"); }
	@Override
	public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException("Read-only documents"); }
}
