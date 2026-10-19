/* SPDX-License-Identifier: GPL-3.0-only */
package android.content;
import android.app.PendingIntent;
import android.os.*;
/** A view of the same creator-owned operation, not a copied Intent. */
public class IntentSender implements Parcelable {
    private final PendingIntent pending;
    public IntentSender(PendingIntent pending) {
        if (pending == null)
            throw new NullPointerException();
        this.pending = pending;
    }
    public interface OnFinished {
        void onSendFinished(IntentSender sender, Intent intent, int resultCode, String resultData,
                            Bundle resultExtras);
    }
    public void sendIntent(Context context, int code, Intent intent, OnFinished finished, Handler handler)
        throws SendIntentException {
        if (finished != null)
            throw new UnsupportedOperationException("IntentSender completion callbacks");
        try {
            pending.send(context, code, intent);
        } catch (PendingIntent.CanceledException e) {
            throw new SendIntentException(e);
        }
    }
    public String getCreatorPackage() { return pending.getCreatorPackage(); }
    public int getCreatorUid() { return pending.getCreatorUid(); }
    public String getTargetPackage() { return getCreatorPackage(); }
    public boolean equals(Object o) {
        return o instanceof IntentSender && pending.equals(((IntentSender)o).pending);
    }
    public int hashCode() { return pending.hashCode(); }
    public int describeContents() { return 0; }
    public void writeToParcel(Parcel out, int flags) { pending.writeToParcel(out, flags); }
    public static final Parcelable.Creator<IntentSender> CREATOR = new Parcelable.Creator<IntentSender>() {
        public IntentSender createFromParcel(Parcel in) {
            return new IntentSender(PendingIntent.CREATOR.createFromParcel(in));
        }
        public IntentSender[] newArray(int n) { return new IntentSender[n]; }
    };
    public static class SendIntentException extends Exception {
        public SendIntentException() {}
        public SendIntentException(String s) { super(s); }
        public SendIntentException(Exception e) { super(e); }
    }
}
