package android.content;

import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.io.OutputStream;

public class ContentProviderClient implements AutoCloseable {
	private final ContentProvider contentProvider;
	private final android.atl.ATLProviderBridge.Endpoint remote;
	private volatile boolean closed;

	ContentProviderClient(ContentProvider contentProvider) {
		this.contentProvider = contentProvider;
		this.remote = null;
	}

	ContentProviderClient(android.atl.ATLProviderBridge.Endpoint remote) {
		this.contentProvider = null;
		this.remote = remote;
	}

	private void checkOpen() {
		if (closed) throw new IllegalStateException("ContentProviderClient is closed");
	}
	private ContentProvider local() {
		checkOpen();
		if (contentProvider == null) throw new UnsupportedOperationException("Remote provider operation is not implemented");
		return contentProvider;
	}

	public synchronized android.os.Bundle call(String method, String arg, android.os.Bundle extras) throws RemoteException {
		checkOpen();
		if (method == null) throw new NullPointerException("method");
		if (remote != null) return remote.call(method, arg, extras);
		return contentProvider.call(method, arg, extras);
	}

	public ContentProvider getLocalContentProvider() {
		return contentProvider;
	}

	public synchronized Cursor query(Uri url, String[] projection, String selection, String[] selectionArgs, String sortOrder) throws RemoteException {
		checkOpen();
		if (remote != null) return remote.query(url, projection, selection, selectionArgs, sortOrder);
		return local().query(url, projection, selection, selectionArgs, sortOrder);
	}

	public String getType(Uri url) throws RemoteException {
		return local().getType(url);
	}

	public Uri insert(Uri url, ContentValues initialValues) throws RemoteException {
		return local().insert(url, initialValues);
	}

	public int delete(Uri url, String selection, String[] selectionArgs) throws RemoteException {
		return local().delete(url, selection, selectionArgs);
	}

	public int update(Uri url, ContentValues values, String selection, String[] selectionArgs) throws RemoteException {
		return local().update(url, values, selection, selectionArgs);
	}

	public ParcelFileDescriptor openFile(Uri url, String mode) throws RemoteException, FileNotFoundException {
		return local().openFile(url, mode);
	}

	public AssetFileDescriptor openAssetFile(Uri url, String mode) throws RemoteException, FileNotFoundException {
		return local().openAssetFile(url, mode);
	}

	public InputStream openInputStream(Uri url) throws FileNotFoundException, RemoteException {
		ParcelFileDescriptor fd = openFile(url, "r");
		return fd != null ? new ParcelFileDescriptor.AutoCloseInputStream(fd) : null;
	}

	public OutputStream openOutputStream(Uri url) throws FileNotFoundException, RemoteException {
		ParcelFileDescriptor fd = openFile(url, "w");
		return fd != null ? new ParcelFileDescriptor.AutoCloseOutputStream(fd) : null;
	}

	public synchronized boolean release() {
		if (closed) return false;
		closed = true;
		if (remote != null) remote.close();
		return true;
	}

	@Override
	public void close() {
		release();
	}
}
