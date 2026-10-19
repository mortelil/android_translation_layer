package android.content.pm;

import android.content.IntentFilter;
import android.graphics.drawable.Drawable;

public class ResolveInfo {
	public ActivityInfo activityInfo = new ActivityInfo();
	public ServiceInfo serviceInfo = new ServiceInfo();
	/** Set only when the resolution came from a provider query, as in AOSP
	 * (core/java/android/content/pm/ResolveInfo.java:69). */
	public ProviderInfo providerInfo;
	public IntentFilter filter = new IntentFilter();
	public int priority = -500;
	public int match;
	public boolean isDefault;

	public Drawable loadIcon(PackageManager pm) {
		Drawable icon = activityInfo.loadIcon(pm);
		if (icon == null) {
			icon = new Drawable();
		}
		return icon;
	}

	public CharSequence loadLabel(PackageManager pm) {
		CharSequence label = activityInfo.loadLabel(pm);
		if (label == null) {
			label = "fixme ResolveInfo.loadLabel";
		}
		return label;
	}

	public static class DisplayNameComparator {

		public DisplayNameComparator(PackageManager pm) {}
	}
}
