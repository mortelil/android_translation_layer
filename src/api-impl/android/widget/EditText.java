package android.widget;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.text.Editable;
import android.text.SpannableStringBuilder;
import android.text.TextWatcher;
import android.text.Spanned;
import android.text.Selection;
import android.text.InputFilter;
import android.text.NoCopySpan;
import java.util.ArrayList;
import android.util.AttributeSet;

public class EditText extends TextView {
	{ setRawInputType(1); } // TYPE_CLASS_TEXT: a plain editable starts as text input.
	public EditText(Context context) {
		super(context);
	}

	public EditText(Context context, AttributeSet attrs) {
		super(context, attrs);
	}

	public EditText(Context context, AttributeSet attrs, int defStyle) {
		super(context, attrs, defStyle);
	}

	public EditText(Context context, AttributeSet attrs, int defStyle, int defStyleRes) {
		super(context, attrs, defStyle, defStyleRes);
	}

	@Override
	protected native long native_constructor(Context context, AttributeSet attrs);
	protected native String native_getText(long widget);
	private native int native_getSelection(long widget, boolean end);
	private native void native_setSelection(long widget, int start, int end);
	protected native void native_setOnEditorActionListener(long widget, OnEditorActionListener l);
	protected native void native_setText(long widget, String text);
	protected native void native_setHint(long widget, CharSequence s);
	protected native CharSequence native_getHint(long widget); // gtk_entry_set_placeholder_text

	private SpannableStringBuilder editable;
	private ArrayList<TextWatcher> watchers;
	private boolean syncingNative;

	private void ensureEditable() {
		if (editable != null) return;
		watchers = new ArrayList<>();
		editable = new SpannableStringBuilder();
		editable.setSpan(new ChangeWatcher(), 0, 0, Spanned.SPAN_INCLUSIVE_INCLUSIVE);
	}

	private class ChangeWatcher implements TextWatcher, NoCopySpan {
			public void beforeTextChanged(CharSequence s, int start, int count, int after) {
				for (TextWatcher watcher : new ArrayList<>(watchers))
					watcher.beforeTextChanged(s, start, count, after);
			}
			public void onTextChanged(CharSequence s, int start, int before, int count) {
				for (TextWatcher watcher : new ArrayList<>(watchers))
					watcher.onTextChanged(s, start, before, count);
			}
			public void afterTextChanged(Editable s) {
				syncNativeText();
				for (TextWatcher watcher : new ArrayList<>(watchers))
					watcher.afterTextChanged(s);
			}
	}

	private void syncNativeText() {
		if (syncingNative) return;
		syncingNative = true;
		try {
			String value = editable.toString();
			if (!value.equals(native_getText(widget))) native_setText(widget, value);
		} finally {
			syncingNative = false;
		}
	}

	// GTK has already edited its buffer. Apply the corresponding UTF-16 range
	// to the persistent Android Editable, which dispatches before/on/after.
	private void onNativeTextChanged(String value) {
		if (syncingNative) return;
		ensureEditable();
		String old = editable.toString();
		if (old.equals(value)) return;
		int start = 0, oldEnd = old.length(), newEnd = value.length();
		while (start < oldEnd && start < newEnd && old.charAt(start) == value.charAt(start)) start++;
		while (oldEnd > start && newEnd > start && old.charAt(oldEnd - 1) == value.charAt(newEnd - 1)) {
			oldEnd--; newEnd--;
		}
		editable.replace(start, oldEnd, value, start, newEnd);
		syncNativeText(); // A rejecting InputFilter may produce no change callbacks.
	}

	@Override
	public Editable getText() { ensureEditable(); return editable; }

	@Override
	public Editable getEditableText() { return getText(); }

	@Override
	public void setText(CharSequence text) { setText(text, BufferType.EDITABLE); }

	@Override
	public void setText(CharSequence text, BufferType type) {
		ensureEditable();
		CharSequence value = text instanceof Spanned ? new SpannableStringBuilder(text) : text;
		editable.replace(0, editable.length(), value == null ? "" : value);
	}

	@Override
	public void setTextSize(float size) {}

	@Override
	public void removeTextChangedListener(TextWatcher watcher) {
		ensureEditable(); watchers.remove(watcher);
	}

	@Override
	public void addTextChangedListener(TextWatcher watcher) {
		if (watcher == null) throw new NullPointerException("watcher");
		ensureEditable(); watchers.add(watcher);
	}

	@Override
	public void setOnEditorActionListener(OnEditorActionListener l) {
		native_setOnEditorActionListener(widget, l);
	}

	@Override
	public void setCompoundDrawables(Drawable left, Drawable top, Drawable right, Drawable bottom) {}

	@Override
	public void setHint(CharSequence s) {
		native_setHint(widget, s == null ? "" : s.toString());
	}

	@Override
	public CharSequence getHint() {
		return native_getHint(widget);
	}

	@Override
	public void setSelection(int index) { setSelection(index, index); }

	@Override
	public void setSelection(int start, int end) {
		Editable text = getText();
		if (start < 0 || end < 0 || start > text.length() || end > text.length())
			throw new IndexOutOfBoundsException("selection");
		Selection.setSelection(text, start, end);
		String value = text.toString();
		native_setSelection(widget, value.codePointCount(0, start), value.codePointCount(0, end));
	}

	@Override
	public int getSelectionStart() { return native_getSelection(widget, false); }

	@Override
	public int getSelectionEnd() { return native_getSelection(widget, true); }

	@Override
	public void setFilters(InputFilter[] filters) { getText().setFilters(filters); }

	public void selectAll() { setSelection(0, getText().length()); }
}
