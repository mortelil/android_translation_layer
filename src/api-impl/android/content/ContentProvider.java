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

public abstract class ContentProvider {

	/* Read by every Binder worker and written by the provider-install thread at
	 * boot; a plain HashMap would tear. AOSP keeps its provider map under the
	 * ActivityManager lock (ContentProviderHelper.mProviderMap). */
	static final java.util.concurrent.ConcurrentHashMap<String, ATLProvider> atl_providers = new java.util.concurrent.ConcurrentHashMap<>();

	/**
	 * Creates the content providers declared for this process. Called on
	 * application start by the launcher (main.c open()) and by a service child
	 * in its boot sequence, before Application.onCreate.
	 */
	public static void createContentProviders() {
		atl_register_providers();
        ATLLoadedApp primary = ATLLoadedApp.getPrimaryApplication();
        java.util.ArrayList<PackageParser.Provider> ordered = new java.util.ArrayList<>(primary.pkg.providers);
        java.util.Collections.sort(ordered, new java.util.Comparator<PackageParser.Provider>() {
            public int compare(PackageParser.Provider a, PackageParser.Provider b) {
                return Integer.compare(b.info.initOrder, a.info.initOrder);
            }
        });
        java.util.HashSet<ATLProvider> installed = new java.util.HashSet<>();
        for (PackageParser.Provider declaration : ordered) {
            if (declaration.info.authority == null) continue;
            ATLProvider handle = atl_providers.get(declaration.info.authority.split(";")[0]);
            if (handle != null && installed.add(handle)) handle.getContentProvider();
        }
	}

	/**
	 * Registers this process' declared provider records without running any
	 * application code, so a client can always find a record to wait on.
	 *
	 * <p>AOSP creates the {@code ContentProviderRecord}s from the manifest in the
	 * system server before the provider's process is even spawned, and a client
	 * blocks on that record until {@code publishContentProviders} fills it in
	 * ({@code ContentProviderHelper.java:630-711, 806-808}, pinned 94b4c163b7df).
	 * ATL's records live in the provider process, so a child registers them before
	 * it reports READY (see {@code android.atl.ChildHost.init}); the install -
	 * constructor, {@code attachInfo}, {@code onCreate} - still happens after
	 * attach, in the AOSP order.
	 *
	 * <p>Idempotent: an existing record (possibly already published) is never
	 * replaced, so calling this before and inside
	 * {@link #createContentProviders()} cannot drop a live provider's state.
	 */
	public static synchronized void atl_register_providers() {
		atl_providers.putIfAbsent(android.atl.ATLDocumentsProvider.AUTHORITY, new ATLProvider(new android.atl.ATLDocumentsProvider()));
		atl_providers.putIfAbsent("media", new ATLProvider(new ATLMediaContentProvider()));
		ATLLoadedApp primary = ATLLoadedApp.getPrimaryApplication();
		for (PackageParser.Provider provider_parsed : primary.pkg.providers) {
			if (!primary.pkg.applicationInfo.enabled || !provider_parsed.info.enabled
                || provider_parsed.info.authority == null) continue;
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
			ATLProvider hosted = new ATLProvider(primary, provider_parsed.info);
			for (String authority : provider_parsed.info.authority.split(";"))
				atl_providers.putIfAbsent(authority, hosted);
		}
	}

	/** The android:process name of the running process: ATL_SERVICE_PROCESS in a child, else the application's. */
	private static String own_process_name(ATLLoadedApp primary) {
		String child = System.getenv("ATL_SERVICE_PROCESS");
		if (child != null)
			return child;
		return primary.pkg.applicationInfo.processName != null ? primary.pkg.applicationInfo.processName : primary.pkg.packageName;
	}

    public static boolean atl_has_provider(String authority) {
        return atl_providers.containsKey(authority);
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
