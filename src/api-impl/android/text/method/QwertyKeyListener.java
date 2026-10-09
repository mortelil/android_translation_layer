// SPDX-License-Identifier: GPL-3.0-only
package android.text.method;

import android.text.Editable;
import android.text.Selection;
import android.view.KeyEvent;
import android.view.View;

/** Full hardware keyboard editing; GTK/IME handles composition and key mapping. */
public class QwertyKeyListener implements KeyListener {
	private static final QwertyKeyListener FULL_KEYBOARD = new QwertyKeyListener();

	private QwertyKeyListener() {}

	public static QwertyKeyListener getInstanceForFullKeyboard() { return FULL_KEYBOARD; }

	@Override
	public int getInputType() { return 1; /* TYPE_CLASS_TEXT */ }

	private static int start(Editable text) {
		int a = Selection.getSelectionStart(text), b = Selection.getSelectionEnd(text);
		return a < 0 || b < 0 ? text.length() : Math.min(a, b);
	}

	private static int end(Editable text) {
		int a = Selection.getSelectionStart(text), b = Selection.getSelectionEnd(text);
		return a < 0 || b < 0 ? text.length() : Math.max(a, b);
	}

	private static void replace(Editable text, int start, int end, CharSequence value) {
		int oldLength = text.length();
		text.replace(start, end, value);
		// InputFilters may alter the inserted length.
		Selection.setSelection(text, Math.min(text.length(), start + text.length() - oldLength + end - start));
	}

	@Override
	public boolean onKeyDown(View view, Editable text, int keyCode, KeyEvent event) {
		if (event.getAction() != KeyEvent.ACTION_DOWN || event.isCtrlPressed() || event.isAltPressed() || event.isMetaPressed())
			return false;
		int start = start(text), end = end(text);
		if (keyCode == KeyEvent.KEYCODE_DEL || keyCode == KeyEvent.KEYCODE_FORWARD_DEL) {
			if (start == end) {
				if (keyCode == KeyEvent.KEYCODE_DEL && start > 0)
					start = Character.offsetByCodePoints(text, start, -1);
				else if (keyCode == KeyEvent.KEYCODE_FORWARD_DEL && end < text.length())
					end = Character.offsetByCodePoints(text, end, 1);
				else
					return false;
			}
			replace(text, start, end, "");
			return true;
		}
		int codePoint = event.getUnicodeChar();
		if (keyCode == KeyEvent.KEYCODE_ENTER) codePoint = '\n';
		if (keyCode == KeyEvent.KEYCODE_TAB) codePoint = '\t';
		// Dead-key values are handled by the host IME, never insert their flag bits.
		if (codePoint <= 0 || !Character.isValidCodePoint(codePoint))
			return false;
		replace(text, start, end, new String(Character.toChars(codePoint)));
		return true;
	}

	@Override
	public boolean onKeyOther(View view, Editable text, KeyEvent event) {
		if (event.getAction() != KeyEvent.ACTION_MULTIPLE || event.getKeyCode() != KeyEvent.KEYCODE_UNKNOWN
		    || event.getCharacters() == null)
			return false;
		replace(text, start(text), end(text), event.getCharacters());
		return true;
	}
	// Modifier state is carried by each GTK KeyEvent, not latched in Editable spans.
}
