package android.media;

import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;

public class MediaDescription implements Parcelable {

	public Uri iconUri;
	public CharSequence title;
	public CharSequence subtitle;

	private String mMediaId;
	private CharSequence mDescription;
	private Bitmap mIconBitmap;
	private Bundle mExtras;
	private Uri mMediaUri;

	public String getMediaId() {
		return mMediaId;
	}

	public CharSequence getTitle() {
		return title;
	}

	public CharSequence getSubtitle() {
		return subtitle;
	}

	public CharSequence getDescription() {
		return mDescription;
	}

	public Uri getIconUri() {
		return iconUri;
	}

	public Bitmap getIconBitmap() {
		return mIconBitmap;
	}

	public Bundle getExtras() {
		return mExtras;
	}

	public Uri getMediaUri() {
		return mMediaUri;
	}

	@Override
	public int describeContents() {
		return 0;
	}

	@Override
	public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(mMediaId);
        writeText(dest, title);
        writeText(dest, subtitle);
        writeText(dest, mDescription);
        dest.writeParcelable(mIconBitmap, flags);
        dest.writeParcelable(iconUri, flags);
        dest.writeBundle(mExtras);
        dest.writeParcelable(mMediaUri, flags);
	}

	private MediaDescription(Parcel in) {
		mMediaId = in.readString();
        title = in.readString();
        subtitle = in.readString();
        mDescription = in.readString();
        ClassLoader loader = MediaDescription.class.getClassLoader();
        mIconBitmap = (Bitmap) in.readParcelable(loader);
        iconUri = (Uri) in.readParcelable(loader);
        try { mExtras = in.readBundle(loader); }
        catch (ReflectiveOperationException e) { throw new android.os.BadParcelableException(e); }
        mMediaUri = (Uri) in.readParcelable(loader);
	}

    private static void writeText(Parcel dest, CharSequence text) {
        if (text instanceof android.text.Spanned)
            throw new UnsupportedOperationException("Styled media descriptions need span parcel support");
        dest.writeString(text == null ? null : text.toString());
    }

	public MediaDescription() {
	}

	public static final Parcelable.Creator<MediaDescription> CREATOR =
			new Parcelable.Creator<MediaDescription>() {
		@Override
		public MediaDescription createFromParcel(Parcel in) {
			return new MediaDescription(in);
		}

		@Override
		public MediaDescription[] newArray(int size) {
			return new MediaDescription[size];
		}
	};

	public static class Builder {

		MediaDescription description = new MediaDescription();

		public Builder setMediaId(String mediaId) {
			description.mMediaId = mediaId;
			return this;
		}

		public Builder setTitle(CharSequence title) {
			description.title = title;
			return this;
		}

		public Builder setSubtitle(CharSequence subtitle) {
			description.subtitle = subtitle;
			return this;
		}

		public Builder setDescription(CharSequence description) {
			this.description.mDescription = description;
			return this;
		}

		public Builder setIconBitmap(Bitmap iconBitmap) {
			description.mIconBitmap = iconBitmap;
			return this;
		}

		public Builder setIconUri(Uri iconUri) {
			description.iconUri = iconUri;
			return this;
		}

		public Builder setExtras(Bundle extras) {
			description.mExtras = extras;
			return this;
		}

		public Builder setMediaUri(Uri mediaUri) {
			description.mMediaUri = mediaUri;
			return this;
		}

		public MediaDescription build() {
			return new MediaDescription(description);
		}
	}

	private MediaDescription(MediaDescription description) {
		this.iconUri = description.iconUri;
		this.title = description.title;
		this.subtitle = description.subtitle;
		this.mMediaId = description.mMediaId;
		this.mDescription = description.mDescription;
		this.mIconBitmap = description.mIconBitmap;
		this.mExtras = description.mExtras;
		this.mMediaUri = description.mMediaUri;
	}
}
