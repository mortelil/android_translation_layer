// SPDX-License-Identifier: GPL-3.0-only
import android.os.Parcel;
import android.os.ParcelUuid;
import android.media.PlaybackParams;

public class TestCompatibilityValues {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    public static void run(android.content.Context context) {
        android.net.ConnectivityManager connectivity = (android.net.ConnectivityManager)
            context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE);
        check(connectivity.getRestrictBackgroundStatus() == android.net.ConnectivityManager.RESTRICT_BACKGROUND_STATUS_DISABLED,
              "host networking has no Android Data Saver policy");
        ParcelUuid id = ParcelUuid.fromString("12345678-1234-5678-9abc-123456789abc");
        Parcel parcel = Parcel.obtain();
        id.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);
        check(id.equals(ParcelUuid.CREATOR.createFromParcel(parcel)), "UUID parcel round trip");
        parcel.recycle();
        PlaybackParams params = new PlaybackParams();
        try { params.getSpeed(); throw new AssertionError("unset speed"); }
        catch (IllegalStateException expected) {}
        params.allowDefaults().setSpeed(1.5f).setPitch(0.75f);
        parcel = Parcel.obtain();
        params.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);
        PlaybackParams copy = PlaybackParams.CREATOR.createFromParcel(parcel);
        check(copy.getSpeed() == 1.5f && copy.getPitch() == 0.75f, "playback parcel round trip");
        parcel.recycle();
        android.app.NotificationChannel channel = new android.app.NotificationChannel("test", "Test", 2);
        channel.setImportance(4);
        check(channel.getId().equals("test") && channel.getName().equals("Test") && channel.getImportance()==4,
              "notification channel state");
        check(context.getSystemService(android.content.Context.ACCOUNT_SERVICE) instanceof android.accounts.AccountManager,
              "account service type");
        java.util.concurrent.Executor executor = new java.util.concurrent.Executor() { public void execute(Runnable command) { command.run(); } };
        android.os.StrictMode.OnThreadViolationListener listener = new android.os.StrictMode.OnThreadViolationListener() { public void onThreadViolation(android.os.strictmode.Violation violation) {} };
        android.os.StrictMode.ThreadPolicy policy = new android.os.StrictMode.ThreadPolicy.Builder()
            .penaltyListener(executor, listener).build();
        check(policy.getOnThreadViolationExecutor()==executor && policy.getOnThreadViolationListener()==listener,
              "policy retains callback configuration");
        android.view.ViewGroup.LayoutParams original = new android.view.ViewGroup.LayoutParams(123, 456);
        android.view.ViewGroup.LayoutParams copied = new android.view.ViewGroup.LayoutParams(original);
        check(copied.width == 123 && copied.height == 456, "layout params copy");
        check(new android.view.accessibility.CaptioningManager().getUserStyle().foregroundColor == android.graphics.Color.WHITE,
              "default caption style");
        android.view.accessibility.CaptioningManager captions = new android.view.accessibility.CaptioningManager();
        android.view.accessibility.CaptioningManager.CaptioningChangeListener captionListener =
            new android.view.accessibility.CaptioningManager.CaptioningChangeListener() {
                @Override public void onEnabledChanged(boolean enabled) {
                    throw new AssertionError("registration must not fabricate a caption change");
                }
            };
        captions.addCaptioningChangeListener(captionListener);
        captions.removeCaptioningChangeListener(captionListener);
        captions.removeCaptioningChangeListener(captionListener); // absent removal is harmless
        check(!captions.isEnabled() && captions.getFontScale() == 1f, "caption preferences unchanged");
        check(android.webkit.WebView.getWebViewClassLoader() == android.webkit.WebView.class.getClassLoader(), "actual WebView implementation loader");
        android.view.View contextView = new android.view.View(context);
        check(!contextView.isContextClickable(), "context-click default");
        contextView.setContextClickable(true);
        check(contextView.isContextClickable() && !contextView.isClickable(), "independent context-click flag");
        contextView.setContextClickable(false);
        check(!contextView.isContextClickable(), "context-click cleared");
        check("<unknown ssid>".equals(new android.net.wifi.WifiInfo().getSSID()), "unavailable Wi-Fi identity");
        check(android.accounts.AccountManager.get(context).getAccountsByTypeForPackage("probe", context.getPackageName()).length == 0,
            "empty account store stays empty");
        android.accounts.AccountManager populated = new android.accounts.AccountManager() {
            @Override public android.accounts.Account[] getAccountsByType(String type) {
                return new android.accounts.Account[]{new android.accounts.Account("synthetic", type)};
            }
        };
        try { populated.getAccountsByTypeForPackage("probe", context.getPackageName()); throw new AssertionError("visibility bypass"); }
        catch (UnsupportedOperationException expected) {}
        System.out.println("PASS: compatibility value APIs and account service");
    }
}
