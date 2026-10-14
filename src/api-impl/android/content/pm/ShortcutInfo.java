package android.content.pm;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Parcel;
import android.os.BaseBundle;
import android.os.Bundle;
import java.io.ByteArrayInputStream;
import android.os.Parcelable;
import android.os.PersistableBundle;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Shortcut metadata published by an app through {@link ShortcutManager}.
 *
 * <p>Contract reference: AOSP frameworks/base,
 * core/java/android/content/pm/ShortcutInfo.java (tag {@code android-17.0.0_r1},
 * commit {@code 94b4c163b7dfe5ce3607f7bb8456f9573f7de57d}). This is a local
 * implementation of the publisher-side subset ATL needs (dynamic shortcut
 * publishing), not a port of the AOSP parcellation or resource handling.
 *
 * <p>Intent and publisher extras use an explicit typed codec (scalar values and
 * int/long/String arrays). Unsupported values are rejected, not silently lost.
 * Icons are not supported by ATL's Icon implementation. Store schema 4 and
 * parcel schema 2 add extras; old store schemas 2/3 and parcel schema 1 remain
 * readable. No Android binary Parcel compatibility is claimed.

 */
public class ShortcutInfo implements Parcelable {
	/** AOSP: {@code getRank()} value when no explicit rank was set. */
	public static final int RANK_NOT_SET = Integer.MAX_VALUE;

	/** AOSP flag: the shortcut is published as a dynamic shortcut. */
	public static final int FLAG_DYNAMIC = 1 << 0;
	/** AOSP flag: the shortcut is long-lived. */
	public static final int FLAG_LONG_LIVED = 1 << 13;

	/** Bumped when the parcel layout below changes incompatibly. */
	private static final int PARCEL_VERSION = 2;

	/** Guard against absurd strings while decoding (1 MiB). */
	private static final int MAX_STRING_BYTES = 1 << 20;
	/** Guard against absurd lists while decoding. */
	private static final int MAX_LIST_SIZE = 4096;

	private String id;
	private String packageName;
	private ComponentName activity;
	private CharSequence shortLabel;
	private CharSequence longLabel;
	private Intent[] intents;
	private int rank;
	private CharSequence disabledMessage;
	private Set<String> categories;
	private int flags;
	private boolean longLived;
	/**
	 * Stored in memory only; ATL's Icon is not Parcelable, so it is not written
	 * to the parcel and is lost on restart (documented in the class comment).
	 */
	private Icon icon;
	/**
	 * Publisher extras, encoded independently of PersistableBundle Parcelable support.
	 */
	private PersistableBundle extras;

	private ShortcutInfo() {
		this.rank = RANK_NOT_SET;
	}

	private ShortcutInfo(Builder b) {
		if (b.id == null || b.id.length() == 0) {
			throw new IllegalArgumentException("Shortcut ID must be provided");
		}
		this.id = b.id;
		if (b.context == null) {
			throw new IllegalArgumentException("Builder context must be provided");
		}
		this.packageName = b.context.getPackageName();
		this.activity = b.activity == null ? null : b.activity.clone();
		this.icon = b.icon;
		this.extras = copyExtras(b.extras, new PersistableBundle());
		this.shortLabel = b.shortLabel;
		this.longLabel = b.longLabel;
		this.disabledMessage = b.disabledMessage;
		this.categories = b.categories == null ? null : new LinkedHashSet<String>(b.categories);
		this.intents = cloneIntents(b.intents);
		this.rank = b.rank;
		this.longLived = b.longLived;
	}

	private ShortcutInfo(Parcel in) {
		int version = in.readInt();
		if (version != 1 && version != PARCEL_VERSION) {
			throw new IllegalArgumentException("Unsupported ShortcutInfo parcel version " + version);
		}
		this.id = in.readString();
		this.packageName = in.readString();
		this.activity = readComponent(in);
		this.shortLabel = in.readString();
		this.longLabel = in.readString();
		int intentCount = in.readInt();
		if (intentCount < 0) {
			this.intents = null;
		} else {
			this.intents = new Intent[intentCount];
			for (int i = 0; i < intentCount; i++) {
				this.intents[i] = readIntent(in);
			}
		}
		this.rank = in.readInt();
		this.disabledMessage = in.readString();
		ArrayList<String> restoredCategories = in.createStringArrayList();
		this.categories = restoredCategories == null
		    ? null : new LinkedHashSet<String>(restoredCategories);
		this.flags = in.readInt();
		this.longLived = in.readInt() != 0;
		if (version >= 2) {
			try { readExtras(new DataInputStream(new ByteArrayInputStream(in.createByteArray()))); }
			catch (IOException e) { throw new IllegalArgumentException("Invalid shortcut extras", e); }
		}
	}

	public String getId() {
		return id;
	}

	public String getPackage() {
		return packageName;
	}

	public ComponentName getActivity() {
		return activity == null ? null : activity.clone();
	}

	public CharSequence getShortLabel() {
		return shortLabel;
	}

	public CharSequence getLongLabel() {
		return longLabel;
	}

	public CharSequence getTitle() {
		return shortLabel;
	}

	public CharSequence getText() {
		return longLabel;
	}

	public CharSequence getDisabledMessage() {
		return disabledMessage;
	}

	/** Returns the last intent, as AOSP does when more than one was set. */
	public Intent getIntent() {
		if (intents == null || intents.length == 0) {
			return null;
		}
		Intent last = intents[intents.length - 1];
		return last == null ? null : cloneIntent(last);
	}

	/** Returns a detached copy of the intent array. */
	public Intent[] getIntents() {
		return cloneIntents(intents);
	}

	public int getRank() {
		return rank;
	}

	/** @hide system-assigned rank, as AOSP's ShortcutService sets it. */
	void setRank(int rank) {
		this.rank = rank;
	}

	public boolean isLongLived() {
		return longLived;
	}

	public Icon getIcon() {
		return icon;
	}

	/** AOSP: extras the app set through {@link Builder#setExtras}. */
	public PersistableBundle getExtras() {
		return extras;
	}

	public Set<String> getCategories() {
		return categories == null ? null : new LinkedHashSet<String>(categories);
	}

	public int getFlags() {
		return flags;
	}

	/** @hide used by {@link ShortcutManager} to tag published records. */
	public void setFlags(int flags) {
		this.flags = flags;
	}

	/**
	 * Throws {@link IllegalArgumentException} if a mandatory field is missing.
	 *
	 * @param forPinned {@code true} when the shortcut is only being pinned, in
	 *                  which case the activity is not required.
	 */
	public void enforceMandatoryFields(boolean forPinned) {
		if (id == null || id.length() == 0) {
			throw new IllegalArgumentException("Shortcut ID must be provided");
		}
		if (!forPinned && activity == null) {
			throw new IllegalArgumentException("Activity must be provided");
		}
		if (shortLabel == null) {
			throw new IllegalArgumentException("Short label must be provided");
		}
		if (intents == null || intents.length == 0) {
			throw new IllegalArgumentException("Shortcut Intent must be provided");
		}
	}

	/**
	 * Merges the non-empty fields of a partial update record into this one.
	 * Used by {@link ShortcutManager#updateShortcuts}.
	 *
	 * @hide
	 */
	public void mergeFrom(ShortcutInfo source) {
		if (source.activity != null) {
			activity = source.activity.clone();
		}
		if (source.shortLabel != null) {
			shortLabel = source.shortLabel;
		}
		if (source.longLabel != null) {
			longLabel = source.longLabel;
		}
		if (source.disabledMessage != null) {
			disabledMessage = source.disabledMessage;
		}
		if (source.intents != null && source.intents.length > 0) {
			intents = cloneIntents(source.intents);
		}
		if (source.categories != null) {
			categories = new LinkedHashSet<String>(source.categories);
		}
		if (source.icon != null) {
			icon = source.icon;
		}
		if (source.extras != null) {
			extras = copyExtras(source.extras, new PersistableBundle());
		}
		if (source.rank != RANK_NOT_SET) {
			rank = source.rank;
		}
		if (source.longLived) {
			longLived = true;
		}
	}

	/** Returns a deep-enough copy so callers cannot mutate stored state. */
	@Override
	public ShortcutInfo clone() {
		ShortcutInfo copy = new ShortcutInfo();
		copy.id = id;
		copy.packageName = packageName;
		copy.activity = activity == null ? null : activity.clone();
		copy.shortLabel = shortLabel;
		copy.longLabel = longLabel;
		copy.intents = cloneIntents(intents);
		copy.rank = rank;
		copy.disabledMessage = disabledMessage;
		copy.categories = categories == null ? null : new LinkedHashSet<String>(categories);
		copy.flags = flags;
		copy.longLived = longLived;
		copy.icon = icon;
		copy.extras = copyExtras(extras, new PersistableBundle());
		return copy;
	}

	@Override
	public int describeContents() {
		return 0;
	}

	@Override
	public void writeToParcel(Parcel dest, int flags) {
		dest.writeInt(PARCEL_VERSION);
		dest.writeString(id);
		dest.writeString(packageName);
		writeComponent(dest, activity);
		dest.writeString(shortLabel == null ? null : shortLabel.toString());
		dest.writeString(longLabel == null ? null : longLabel.toString());
		dest.writeInt(intents == null ? -1 : intents.length);
		if (intents != null) {
			for (Intent intent : intents) {
				writeIntent(dest, intent);
			}
		}
		dest.writeInt(rank);
		dest.writeString(disabledMessage == null ? null : disabledMessage.toString());
		dest.writeStringList(categories == null ? null : new ArrayList<String>(categories));
		dest.writeInt(this.flags);
		dest.writeInt(longLived ? 1 : 0);
		try {
			ByteArrayOutputStream bytes = new ByteArrayOutputStream();
			writeExtras(new DataOutputStream(bytes));
			dest.writeByteArray(bytes.toByteArray());
		} catch (IOException e) { throw new IllegalArgumentException("Invalid shortcut extras", e); }
	}

	public static final Creator<ShortcutInfo> CREATOR = new Creator<ShortcutInfo>() {
		@Override
		public ShortcutInfo createFromParcel(Parcel source) {
			return new ShortcutInfo(source);
		}

		@Override
		public ShortcutInfo[] newArray(int size) {
			return new ShortcutInfo[size];
		}
	};

	// --- typed store codec (on-disk schema version 4) -------------------------
	//
	// Layout of one record on the DataOutputStream, all integers big-endian:
	//
	//   utf8   id              (nullable)
	//   utf8   packageName     (nullable)
	//   flags8 activity        (0 = absent, 1 = present)
	//   utf8   activity.pkg    (only when activity present)
	//   utf8   activity.class  (only when activity present)
	//   utf8   shortLabel      (nullable, CharSequence toString())
	//   utf8   longLabel       (nullable)
	//   int32  intentCount     (-1 = null)
	//   intent intentCount times:
	//       flags8 present (0 = null entry)
	//       utf8   action            (nullable)
	//       flags8 hasData
	//       utf8   data              (only when hasData)
	//       utf8   type              (nullable)
	//       utf8   package           (nullable)
	//       flags8 hasComponent
	//       utf8   component.pkg / component.class (only when hasComponent)
	//       int32  flags
	//       strlst categories
	//   int32  rank
	//   utf8   disabledMessage (nullable)
	//   strlst categories
	//   int32  flags
	//   flags8 longLived
	//
	// utf8 = int32 byte length, -1 for null, followed by UTF-8 bytes.
	// strlst = int32 count, -1 for null, followed by that many utf8 strings.
	//
	// Version 4 adds a typed extras section after the version-3 fields. The
	// previous format contains no extras section.

	/** Writes the supported fields to the typed store stream. */
	void writeTypedTo(DataOutputStream out) throws IOException {
		writeString(out, id);
		writeString(out, packageName);
		writeComponent(out, activity);
		writeString(out, shortLabel == null ? null : shortLabel.toString());
		writeString(out, longLabel == null ? null : longLabel.toString());
		out.writeInt(intents == null ? -1 : intents.length);
		if (intents != null) {
			for (Intent intent : intents) {
				writeIntent(out, intent);
			}
		}
		out.writeInt(rank);
		writeString(out, disabledMessage == null ? null : disabledMessage.toString());
		writeStringList(out, categories);
		out.writeInt(this.flags);
		out.writeByte(longLived ? 1 : 0);
		writeExtras(out);
	}

	/** Reads one record written by {@link #writeTypedTo}. */
	static ShortcutInfo readTypedFrom(DataInputStream in, boolean withExtras) throws IOException {
		ShortcutInfo info = new ShortcutInfo();
		info.id = readString(in);
		info.packageName = readString(in);
		info.activity = readComponent(in);
		info.shortLabel = readString(in);
		info.longLabel = readString(in);
		int intentCount = in.readInt();
		if (intentCount < 0) {
			info.intents = null;
		} else if (intentCount > MAX_LIST_SIZE) {
			throw new IOException("intent count out of range: " + intentCount);
		} else {
			info.intents = new Intent[intentCount];
			for (int i = 0; i < intentCount; i++) {
				info.intents[i] = readIntent(in);
			}
		}
		info.rank = in.readInt();
		info.disabledMessage = readString(in);
		info.categories = readStringSet(in);
		info.flags = in.readInt();
		info.longLived = in.readByte() != 0;
		if (withExtras) info.readExtras(in);
		return info;
	}

	private static void writeString(DataOutputStream out, String value) throws IOException {
		if (value == null) {
			out.writeInt(-1);
			return;
		}
		byte[] bytes = value.getBytes("UTF-8");
		if (bytes.length > MAX_STRING_BYTES) throw new IOException("Shortcut string too long");
		out.writeInt(bytes.length);
		out.write(bytes);
	}

	private static String readString(DataInputStream in) throws IOException {
		int length = in.readInt();
		if (length < 0) {
			return null;
		}
		if (length > MAX_STRING_BYTES) {
			throw new IOException("string length out of range: " + length);
		}
		byte[] bytes = new byte[length];
		in.readFully(bytes);
		return new String(bytes, "UTF-8");
	}

	private static void writeStringList(DataOutputStream out, Set<String> values) throws IOException {
		if (values == null) {
			out.writeInt(-1);
			return;
		}
		out.writeInt(values.size());
		for (String value : values) {
			writeString(out, value);
		}
	}

	private static Set<String> readStringSet(DataInputStream in) throws IOException {
		int count = in.readInt();
		if (count < 0) {
			return null;
		}
		if (count > MAX_LIST_SIZE) {
			throw new IOException("list size out of range: " + count);
		}
		Set<String> values = new LinkedHashSet<String>();
		for (int i = 0; i < count; i++) {
			values.add(readString(in));
		}
		return values;
	}

	private static void writeComponent(DataOutputStream out, ComponentName component) throws IOException {
		out.writeByte(component == null ? 0 : 1);
		if (component != null) {
			writeString(out, component.getPackageName());
			writeString(out, component.getClassName());
		}
	}

	private static ComponentName readComponent(DataInputStream in) throws IOException {
		if (in.readByte() == 0) {
			return null;
		}
		return new ComponentName(readString(in), readString(in));
	}

	private static void writeIntent(DataOutputStream out, Intent intent) throws IOException {
		out.writeByte(intent == null ? 0 : 1);
		if (intent == null) {
			return;
		}
		writeString(out, intent.getAction());
		Uri data = intent.getData();
		out.writeByte(data == null ? 0 : 1);
		if (data != null) {
			writeString(out, data.toString());
		}
		writeString(out, intent.getType());
		writeString(out, intent.getPackage());
		writeComponent(out, intent.getComponent());
		out.writeInt(intent.getFlags());
		writeStringList(out, intent.getCategories());
	}

	private static Intent readIntent(DataInputStream in) throws IOException {
		if (in.readByte() == 0) {
			return null;
		}
		Intent intent = new Intent();
		intent.setAction(readString(in));
		Uri data = in.readByte() != 0 ? Uri.parse(readString(in)) : null;
		intent.setDataAndType(data, readString(in));
		intent.setPackage(readString(in));
		intent.setComponent(readComponent(in));
		intent.setFlags(in.readInt());
		Set<String> categories = readStringSet(in);
		if (categories != null) {
			for (String category : categories) {
				intent.addCategory(category);
			}
		}
		return intent;
	}

	/** Serializes this record with {@link #writeTypedTo} into a fresh buffer. */
	byte[] typedBytes() throws IOException {
		ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		DataOutputStream out = new DataOutputStream(buffer);
		writeTypedTo(out);
		out.flush();
		return buffer.toByteArray();
	}

	// Version 4 appends an extras section after each version-3 typed record.
	private void writeExtras(DataOutputStream out) throws IOException {
		writeBundle(out, extras);
		if (intents != null) for (Intent intent : intents)
			writeBundle(out, intent == null ? null : intent.getExtras());
	}

	private void readExtras(DataInputStream in) throws IOException {
		extras = readBundle(in, new PersistableBundle());
		if (intents != null) for (Intent intent : intents) {
			Bundle values = readBundle(in, new Bundle());
			if (intent != null && values != null) intent.putExtras(values);
		}
	}

	private static <T extends BaseBundle> T copyExtras(BaseBundle source, T target) {
		if (source == null) return null;
		try {
			ByteArrayOutputStream bytes = new ByteArrayOutputStream();
			writeBundle(new DataOutputStream(bytes), source);
			return readBundle(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())), target);
		} catch (IOException e) { throw new IllegalArgumentException("Invalid shortcut extras", e); }
	}

	private static void writeBundle(DataOutputStream out, BaseBundle bundle) throws IOException {
		if (bundle == null) { out.writeInt(-1); return; }
		if (bundle.keySet().size() > MAX_LIST_SIZE) throw new IOException("Too many extras");
		out.writeInt(bundle.keySet().size());
		for (String key : bundle.keySet()) {
			writeString(out, key);
			Object v = bundle.get(key);
			if (v == null) out.writeByte(0);
			else if (v instanceof String) { out.writeByte(1); writeString(out, (String)v); }
			else if (v instanceof Integer) { out.writeByte(2); out.writeInt((Integer)v); }
			else if (v instanceof Long) { out.writeByte(3); out.writeLong((Long)v); }
			else if (v instanceof Boolean) { out.writeByte(4); out.writeBoolean((Boolean)v); }
			else if (v instanceof Double) { out.writeByte(5); out.writeDouble((Double)v); }
			else if (v instanceof int[]) { out.writeByte(6); int[] a=(int[])v; writeCount(out,a.length); for(int x:a) out.writeInt(x); }
			else if (v instanceof long[]) { out.writeByte(7); long[] a=(long[])v; writeCount(out,a.length); for(long x:a) out.writeLong(x); }
			else if (v instanceof String[]) { out.writeByte(8); String[] a=(String[])v; writeCount(out,a.length); for(String x:a) writeString(out,x); }
			else throw new IOException("Unsupported shortcut extra type: " + v.getClass().getName());
		}
	}

	private static void writeCount(DataOutputStream out, int count) throws IOException {
		if (count > MAX_LIST_SIZE) throw new IOException("Too many extra elements");
		out.writeInt(count);
	}

	private static int readCount(DataInputStream in) throws IOException {
		int count=in.readInt();
		if (count < 0 || count > MAX_LIST_SIZE) throw new IOException("Invalid extra count");
		return count;
	}

	private static <T extends BaseBundle> T readBundle(DataInputStream in, T b) throws IOException {
		int count=in.readInt();
		if (count == -1) return null;
		if (count < 0 || count > MAX_LIST_SIZE) throw new IOException("Invalid extras count");
		for (int i=0;i<count;i++) {
			String key=readString(in);
			switch(in.readUnsignedByte()) {
			case 0: b.putString(key,null); break;
			case 1: b.putString(key,readString(in)); break;
			case 2: b.putInt(key,in.readInt()); break;
			case 3: b.putLong(key,in.readLong()); break;
			case 4: b.putBoolean(key,in.readBoolean()); break;
			case 5: b.putDouble(key,in.readDouble()); break;
			case 6: { int[] a=new int[readCount(in)]; for(int j=0;j<a.length;j++) a[j]=in.readInt(); b.putIntArray(key,a); break; }
			case 7: { long[] a=new long[readCount(in)]; for(int j=0;j<a.length;j++) a[j]=in.readLong(); b.putLongArray(key,a); break; }
			case 8: { String[] a=new String[readCount(in)]; for(int j=0;j<a.length;j++) a[j]=readString(in); b.putStringArray(key,a); break; }
			default: throw new IOException("Invalid shortcut extra tag");
			}
		}
		return b;
	}

	// --- Parcel helpers -------------------------------------------------------

	private static void writeComponent(Parcel dest, ComponentName component) {
		dest.writeInt(component == null ? 0 : 1);
		if (component != null) {
			dest.writeString(component.getPackageName());
			dest.writeString(component.getClassName());
		}
	}

	private static ComponentName readComponent(Parcel in) {
		if (in.readInt() == 0) {
			return null;
		}
		return new ComponentName(in.readString(), in.readString());
	}

	private static void writeIntent(Parcel dest, Intent intent) {
		dest.writeInt(intent == null ? 0 : 1);
		if (intent == null) {
			return;
		}
		dest.writeString(intent.getAction());
		Uri data = intent.getData();
		dest.writeInt(data == null ? 0 : 1);
		if (data != null) {
			dest.writeString(data.toString());
		}
		dest.writeString(intent.getType());
		dest.writeString(intent.getPackage());
		writeComponent(dest, intent.getComponent());
		dest.writeInt(intent.getFlags());
		Set<String> intentCategories = intent.getCategories();
		dest.writeStringList(intentCategories == null
		    ? null : new ArrayList<String>(intentCategories));
	}

	private static Intent readIntent(Parcel in) {
		if (in.readInt() == 0) {
			return null;
		}
		Intent intent = new Intent();
		intent.setAction(in.readString());
		Uri data = in.readInt() != 0 ? Uri.parse(in.readString()) : null;
		intent.setDataAndType(data, in.readString());
		intent.setPackage(in.readString());
		intent.setComponent(readComponent(in));
		intent.setFlags(in.readInt());
		ArrayList<String> intentCategories = in.createStringArrayList();
		if (intentCategories != null) {
			for (String category : intentCategories) {
				intent.addCategory(category);
			}
		}
		return intent;
	}

	/** Copies the essentials ATL's Intent exposes through its public getters. */
	private static Intent cloneIntent(Intent source) {
		Intent copy = new Intent();
		copy.setAction(source.getAction());
		Uri data = source.getData();
		if (data != null) {
			copy.setData(data);
		}
		copy.setDataAndType(data, source.getType());
		Bundle copiedExtras = copyExtras(source.getExtras(), new Bundle());
		if (copiedExtras != null) copy.putExtras(copiedExtras);
		if (source.getSelector() != null || source.getClipData() != null)
			throw new IllegalArgumentException("Shortcut intent selectors/ClipData are unsupported");
		copy.setPackage(source.getPackage());
		ComponentName component = source.getComponent();
		if (component != null) {
			copy.setComponent(component.clone());
		}
		copy.setFlags(source.getFlags());
		Set<String> sourceCategories = source.getCategories();
		if (sourceCategories != null) {
			for (String category : sourceCategories) {
				copy.addCategory(category);
			}
		}
		return copy;
	}

	private static Intent[] cloneIntents(Intent[] source) {
		if (source == null) {
			return null;
		}
		Intent[] copy = new Intent[source.length];
		for (int i = 0; i < source.length; i++) {
			copy[i] = source[i] == null ? null : cloneIntent(source[i]);
		}
		return copy;
	}

	public static class Builder {
		private final Context context;
		private String id;
		private ComponentName activity;
		private Icon icon;
		private CharSequence shortLabel;
		private CharSequence longLabel;
		private CharSequence disabledMessage;
		private Set<String> categories;
		private Intent[] intents;
		private int rank = RANK_NOT_SET;
		private boolean longLived;
		private PersistableBundle extras;

		public Builder(Context context) {
			this(context, null);
		}

		public Builder(Context context, String id) {
			this.context = context;
			this.id = id;
		}

		public Builder setId(String id) {
			this.id = id;
			return this;
		}

		public Builder setActivity(ComponentName activity) {
			this.activity = activity;
			return this;
		}

		public Builder setIcon(Icon icon) {
			this.icon = icon;
			return this;
		}

		public Builder setShortLabel(CharSequence shortLabel) {
			this.shortLabel = shortLabel;
			return this;
		}

		public Builder setLongLabel(CharSequence longLabel) {
			this.longLabel = longLabel;
			return this;
		}

		public Builder setTitle(CharSequence title) {
			this.shortLabel = title;
			return this;
		}

		public Builder setText(CharSequence text) {
			this.longLabel = text;
			return this;
		}

		public Builder setDisabledMessage(CharSequence disabledMessage) {
			this.disabledMessage = disabledMessage;
			return this;
		}

		public Builder setCategories(Set<String> categories) {
			this.categories = categories;
			return this;
		}

		public Builder setIntent(Intent intent) {
			this.intents = intent == null ? null : new Intent[] { intent };
			return this;
		}

		public Builder setIntents(Intent[] intents) {
			this.intents = intents;
			return this;
		}

		public Builder setRank(int rank) {
			this.rank = rank;
			return this;
		}

		public Builder setLongLived(boolean longLived) {
			this.longLived = longLived;
			return this;
		}

		/**
		 * AOSP {@code ShortcutInfo.Builder.setExtras(PersistableBundle)} (API 26).
		 */
		public Builder setExtras(PersistableBundle extras) {
			this.extras = extras;
			return this;
		}

		public ShortcutInfo build() {
			return new ShortcutInfo(this);
		}
	}
}