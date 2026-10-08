// SPDX-License-Identifier: GPL-3.0-only
import android.atl.ATLDocumentsProvider;
import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

public class TestDocuments {
	public static void run(android.content.Context context) throws Exception {
		File root = new File(context.getCacheDir(), "document-test");
		root.mkdirs();
		File file = new File(root, "a test.txt");
		try (FileOutputStream output = new FileOutputStream(file)) {
			output.write(new byte[] {65, 66, 67});
		}
		ContentResolver resolver = context.getContentResolver();
		Uri uri = ATLDocumentsProvider.select(Uri.fromFile(file), false);
		if (!DocumentsContract.isDocumentUri(context, uri))
			throw new AssertionError("Document URI");
		resolver.takePersistableUriPermission(uri, 1);
		try (Cursor cursor = resolver.query(uri, new String[] {"_display_name", "_size", "mime_type", "last_modified"}, null, null, null)) {
			if (!cursor.moveToFirst() || !"a test.txt".equals(cursor.getString(0)) || cursor.getLong(1) != 3 || !"text/plain".equals(cursor.getString(2)) || cursor.getLong(3) <= 0)
				throw new AssertionError("Document metadata");
		}
		// Simulate losing process-local grants. Access must survive via persisted storage.
		java.lang.reflect.Field offered = ATLDocumentsProvider.class.getDeclaredField("offered");
		offered.setAccessible(true);
		((java.util.Map)offered.get(null)).clear();
		try (InputStream input = resolver.openInputStream(uri)) {
			if (input.read() != 65 || input.read() != 66 || input.read() != 67 || input.read() != -1)
				throw new AssertionError("Document bytes");
		}
		try {
			resolver.openFileDescriptor(uri, "w");
			throw new AssertionError("Write grant fabricated");
		} catch (SecurityException expected) {
		}
		try {
			resolver.takePersistableUriPermission(uri, 2);
			throw new AssertionError("Write permission fabricated");
		} catch (SecurityException expected) {
		}
		Uri tree = ATLDocumentsProvider.select(Uri.fromFile(root), true);
		String id = DocumentsContract.getTreeDocumentId(tree);
		try (Cursor children = resolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, id), new String[] {"document_id", "_display_name"}, null, null, null)) {
			if (!children.moveToFirst() || !"a test.txt".equals(children.getString(1)))
				throw new AssertionError("Tree listing");
			Uri child = DocumentsContract.buildDocumentUriUsingTree(tree, children.getString(0));
			try (InputStream input = resolver.openInputStream(child)) {
				if (input.read() != 65)
					throw new AssertionError("Tree child bytes");
			}
		}
		Uri escape = DocumentsContract.buildDocumentUriUsingTree(tree, id + ":../outside.txt");
		try {
			resolver.query(escape, null, null, null, null);
			throw new AssertionError("Traversal accepted");
		} catch (SecurityException expected) {
		}
		java.nio.file.Path link = new File(root, "escape-link").toPath();
		java.nio.file.Files.deleteIfExists(link);
		java.nio.file.Files.createSymbolicLink(link, root.getParentFile().toPath());
		try {
			resolver.query(DocumentsContract.buildDocumentUriUsingTree(tree, id + ":escape-link"), null, null, null, null);
			throw new AssertionError("Escaping symlink accepted");
		} catch (SecurityException expected) {
		} finally {
			java.nio.file.Files.delete(link);
		}
		resolver.releasePersistableUriPermission(uri, 1);
		try {
			resolver.openInputStream(uri);
			throw new AssertionError("Released grant survived");
		} catch (SecurityException expected) {
		}
		System.out.println("PASS: document selection, metadata, bytes, persistence, release, read-only grants, tree listing and traversal rejection");
	}
}
