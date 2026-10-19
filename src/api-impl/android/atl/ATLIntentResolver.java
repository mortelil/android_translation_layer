// SPDX-License-Identifier: GPL-3.0-only
package android.atl;

import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.pm.*;
import java.io.File;
import java.util.*;

/**
 * Shared activity/service matching for package queries and local dispatch.
 * Semantics reference AOSP frameworks/base
 * 94b4c163b7dfe5ce3607f7bb8456f9573f7de57d:
 * ApplicationPackageManager, ResolveIntentHelper and ComponentResolver.
 * This is ATL's single-user resolver, not Android's permission/domain policy.
 */
public final class ATLIntentResolver {
    private ATLIntentResolver() {}

    private static String type(Intent intent) {
        String type = intent.getType();
        if (intent.getComponent() == null && type == null && "content".equals(intent.getScheme()))
            type = new ContentResolver().getType(intent.getData());
        return type;
    }

    public static Intent effective(Intent intent) {
        Objects.requireNonNull(intent, "intent");
        return intent.getComponent() == null && intent.getSelector() != null ? intent.getSelector() : intent;
    }

    public static List<ResolveInfo> queryPackage(PackageParser.Package pkg, Intent intent, int flags, boolean services) {
        String type = type(Objects.requireNonNull(intent, "intent"));
        List<ResolveInfo> result = new ArrayList<>();
        collect(pkg, effective(intent), type, flags, services, result);
        sort(result);
        return result;
    }

    public static List<ResolveInfo> queryVisible(Intent intent, int flags, boolean services) {
        String type = type(Objects.requireNonNull(intent, "intent"));
        intent = effective(intent);
        Map<String, PackageParser.Package> packages = new LinkedHashMap<>();
        ATLLoadedApp primary = ATLLoadedApp.getPrimaryApplication();
        if (primary == null) return new ArrayList<>();
        String target = intent.getComponent() != null ? intent.getComponent().getPackageName() : intent.getPackage();
        if (target != null) {
            PackageParser.Package pkg = ATLLoadedAppManager.getPackageFromPackageName(target);
            if (pkg != null) packages.put(pkg.packageName, pkg);
        } else {
            packages.put(primary.pkg.packageName, primary.pkg);
            File[] installed = ATLPaths.installed_apks_dir.listFiles();
            if (installed != null) for (File file : installed) {
                String name = file.getName();
                if (!file.isFile() || !name.endsWith(".apk")) continue;
                PackageParser.Package pkg = ATLLoadedAppManager.getPackageFromPackageName(name.substring(0, name.length()-4));
                if (pkg != null) packages.put(pkg.packageName, pkg);
            }
        }
        List<ResolveInfo> result = new ArrayList<>();
        for (PackageParser.Package pkg : packages.values()) collect(pkg, intent, type, flags, services, result);
        sort(result);
        return result;
    }

    private static void collect(PackageParser.Package pkg, Intent intent, String type, int flags,
                                boolean services, List<ResolveInfo> result) {
        if (pkg == null) return;
        ComponentName explicit = intent.getComponent();
        String target = explicit != null ? explicit.getPackageName() : intent.getPackage();
        // AOSP indexes unrestricted queries by action, MIME or scheme. A
        // package-restricted query instead scans that package's filter list.
        if (target == null && intent.getAction() == null && type == null && intent.getScheme() == null) return;
        if (target != null && !target.equals(pkg.packageName)) return;
        List<? extends PackageParser.Component<?>> components = services ? pkg.services : pkg.activities;
        if (components == null) return;
        for (PackageParser.Component<?> component : components) {
            if (component == null) continue;
            ComponentInfo info = services ? ((PackageParser.Service)component).info : ((PackageParser.Activity)component).info;
            if ((flags & PackageManager.MATCH_DISABLED_COMPONENTS) == 0 &&
                    (!info.enabled || !pkg.applicationInfo.enabled)) continue;
            PackageParser.IntentInfo matched = null;
            int score = 0;
            if (explicit != null) {
                if (!explicit.equals(component.getComponentName())) continue;
            } else {
                if (component.intents == null) continue;
                for (PackageParser.IntentInfo filter : component.intents) {
                    if ((flags & PackageManager.MATCH_DEFAULT_ONLY) != 0 &&
                            !filter.hasCategory(Intent.CATEGORY_DEFAULT)) continue;
                    score = filter.match(intent.getAction(), type, intent.getScheme(), intent.getData(),
                                         intent.getCategories(), "ATLIntentResolver");
                    if (score >= 0) { matched = filter; break; }
                }
                if (matched == null) continue;
            }
            ResolveInfo ri = new ResolveInfo();
            if (services) ri.serviceInfo = (ServiceInfo)info;
            else ri.activityInfo = (ActivityInfo)info;
            ri.priority = matched == null ? 0 : matched.priority;
            ri.match = score;
            ri.isDefault = matched != null && matched.hasCategory(Intent.CATEGORY_DEFAULT);
            ri.filter = (flags & PackageManager.GET_RESOLVED_FILTER) != 0 ? matched : null;
            result.add(ri);
        }
    }

    private static void sort(List<ResolveInfo> result) {
        Collections.sort(result, new Comparator<ResolveInfo>() {
            public int compare(ResolveInfo a, ResolveInfo b) {
                int order = Integer.compare(b.priority, a.priority);
                if (order != 0) return order;
                if (a.isDefault != b.isDefault) return a.isDefault ? -1 : 1;
                return Integer.compare(b.match, a.match);
            }
        });
    }
}
