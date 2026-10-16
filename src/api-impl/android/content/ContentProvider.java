package android.content;

import android.atl.ATLLoadedApp;
import android.atl.ATLMediaContentProvider;
import android.atl.ATLProvider;
import android.content.pm.PackageParser;
import android.content.pm.ProviderInfo;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import java.io.FileNotFoundException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Predicate;

public abstract class ContentProvider {

	static final HashMap<String, ATLProvider> atl_providers = new HashMap<>();

	/**
	 * Creates the content providers declared for this process. Called on
	 * application start by the launcher (main.c open()) and by a service child
	 * in its boot sequence, before Application.onCreate.
	 */
	public static void createContentProviders() {
		atl_providers.put(android.atl.ATLDocumentsProvider.AUTHORITY, new ATLProvider(new android.atl.ATLDocumentsProvider()));
		atl_providers.put("media", new ATLProvider(new ATLMediaContentProvider()));
		ATLLoadedApp primary = ATLLoadedApp.getPrimaryApplication();
		for (PackageParser.Provider provider_parsed : primary.pkg.providers) {
			String process_name = provider_parsed.info.processName;
			if (process_name == null)
				process_name = primary.pkg.applicationInfo.processName != null ? primary.pkg.applicationInfo.processName : primary.pkg.packageName;
			/* Each process hosts only the providers whose android:process resolves
			 * to its own name: the main process (loader) creates its own, a
			 * child process (ATL_SERVICE_PROCESS, see android.atl.ChildHost)
			 * creates only the providers declared for it. */
			if (!process_name.equals(own_process_name(primary))) {
				System.out.println("not creating provider " + provider_parsed.className + ", it belongs to process " + process_name);
				continue;
			}
			ATLProvider hosted = new ATLProvider(primary, provider_parsed);
			for (String authority : provider_parsed.info.authority.split(";"))
				atl_providers.put(authority, hosted);
		}
		// getContentProvider() initializes the content providers,
		// we also opportunistically remove the ones that failed to load
		atl_providers.values().removeIf(new Predicate<ATLProvider>() {
			@Override
			public boolean test(ATLProvider atlProvider) {
				return atlProvider.getContentProvider() == null;
			}
		});
	}

	/** The android:process name of the running process: ATL_SERVICE_PROCESS in a child, else the application's. */
	private static String own_process_name(ATLLoadedApp primary) {
		String child = System.getenv("ATL_SERVICE_PROCESS");
		if (child != null)
			return child;
		return primary.pkg.applicationInfo.processName != null ? primary.pkg.applicationInfo.processName : primary.pkg.packageName;
	}

	public static ContentProvider atl_get_content_provider(String authority) {
		ATLProvider atlProvider = atl_providers.get(authority);
		return atlProvider == null ? null : atlProvider.getContentProvider();
	}

	/** App-defined provider operation; the default has no operation to perform.
	 * Matches AOSP ContentProvider.call(String, String, Bundle).
	 */
	public android.os.Bundle call(String method, String arg, android.os.Bundle extras) {
		return null;
	}

	public boolean onCreate() { return false; }

	private Context context;
	private final ThreadLocal<String> callerPackage = new ThreadLocal<>();
	public Context getContext() {
		return context != null ? context : ATLLoadedApp.getPrimaryApplication().getApplication();
	}

	public abstract Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder);

	public Cursor query(Uri uri, String[] projection, android.os.Bundle args, android.os.CancellationSignal signal) {
		if (signal != null)
			signal.throwIfCanceled();
		return query(uri, projection,
		             args == null ? null : args.getString("android:query-arg-sql-selection"),
		             args == null ? null : args.getStringArray("android:query-arg-sql-selection-args"),
		             args == null ? null : args.getString("android:query-arg-sql-sort-order"));
	}

	public abstract Uri insert(Uri uri, ContentValues values);

	public abstract int update(Uri uri, ContentValues values, String selection, String[] selectionArgs);

	public abstract int delete(Uri uri, String selection, String[] selectionArgs);

	public abstract String getType(Uri uri);

	public abstract ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException;

	public ParcelFileDescriptor openFile(Uri uri, String mode, android.os.CancellationSignal signal) throws FileNotFoundException {
		return openFile(uri, mode);
	}

	public AssetFileDescriptor openAssetFile(Uri uri, String mode) throws FileNotFoundException {
		ParcelFileDescriptor fd = openFile(uri, mode);
		return fd != null ? new AssetFileDescriptor(fd, 0, -1) : null;
	}

	public void attachInfo(Context context, ProviderInfo provider) {
		if (this.context == null) this.context = context;
	}

	public final android.os.Bundle atl_callFromPackage(String caller, String method, String arg, android.os.Bundle extras) {
		String previous = callerPackage.get();
		callerPackage.set(caller);
		try { return call(method, arg, extras); }
		finally { if (previous == null) callerPackage.remove(); else callerPackage.set(previous); }
	}

	public final Cursor atl_queryFromPackage(String caller, Uri uri, String[] projection,
	                                        String selection, String[] args, String order) {
		String previous = callerPackage.get();
		callerPackage.set(caller);
		try { return query(uri, projection, selection, args, order); }
		finally { if (previous == null) callerPackage.remove(); else callerPackage.set(previous); }
	}

	public String getCallingPackage() {
		String caller = callerPackage.get();
		return caller != null ? caller : ATLLoadedApp.getPrimaryApplication().pkg.packageName;
	}
}
