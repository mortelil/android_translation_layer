// SPDX-License-Identifier: GPL-3.0-only
package android.content;

import android.net.Uri;
import java.util.ArrayList;
import java.util.Objects;

/** In-process clip contents; native clipboard transport is separate. */
public class ClipData {
	public static class Item {
		private final CharSequence text;
		private final String htmlText;
		private final Intent intent;
		private final Uri uri;
		public Item(Uri uri) { this(null, null, null, uri); }
		public Item(Intent intent) { this(null, null, intent, null); }
		public Item(CharSequence text) { this(text, null, null, null); }
		public Item(CharSequence text, String htmlText, Intent intent, Uri uri) {
			if (htmlText != null && text == null) throw new IllegalArgumentException("HTML requires plain text");
			this.text = text;
			this.htmlText = htmlText;
			this.intent = intent;
			this.uri = uri;
		}
		public CharSequence getText() { return text; }
		public String getHtmlText() { return htmlText; }
		public Intent getIntent() { return intent; }
		public Uri getUri() { return uri; }
	}
	// Retained for the existing text-only native ClipboardManager adapter.
	String text;
	private final ClipDescription description;
	private final ArrayList<Item> items = new ArrayList<>();
	public ClipData(ClipDescription description, Item item) {
		this.description = Objects.requireNonNull(description);
		addItem(item);
		if (item.getText() != null) text = item.getText().toString();
		else if (item.getUri() != null) text = item.getUri().toString();
	}
	public ClipData(CharSequence label, String[] mimeTypes, Item item) {
		this(new ClipDescription(label, mimeTypes), item);
	}
	public ClipDescription getDescription() { return description; }
	public static ClipData newIntent(CharSequence label, Intent intent) {
		return new ClipData(label, new String[]{ClipDescription.MIMETYPE_TEXT_INTENT}, new Item(intent));
	}
	public static ClipData newPlainText(CharSequence label, CharSequence text) {
		return new ClipData(label, new String[]{ClipDescription.MIMETYPE_TEXT_PLAIN}, new Item(text));
	}
	public static ClipData newRawUri(CharSequence label, Uri uri) {
		return new ClipData(label, new String[]{ClipDescription.MIMETYPE_TEXT_URILIST}, new Item(uri));
	}
	public static ClipData newUri(ContentResolver resolver, CharSequence label, Uri uri) {
		return newRawUri(label, uri);
	}
	public void addItem(Item item) { items.add(Objects.requireNonNull(item)); }
	public void addItem(ContentResolver resolver, Item item) { addItem(item); }
	public Item getItemAt(int index) { return items.get(index); }
	public int getItemCount() { return items.size(); }
}
