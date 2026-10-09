// SPDX-License-Identifier: GPL-3.0-only
import android.text.*;
import android.text.method.*;
import android.view.KeyEvent;

public class TestQwerty {
	public static void run() {
		KeyListener listener = QwertyKeyListener.getInstanceForFullKeyboard();
		SpannableStringBuilder text = new SpannableStringBuilder("ab");
		Selection.setSelection(text, 2, 0);
		KeyEvent insert = new KeyEvent(0, "x\ud83d\ude00", 0, 0);
		if (!listener.onKeyOther(null, text, insert) || !text.toString().equals("x\ud83d\ude00") || Selection.getSelectionEnd(text) != 3)
			throw new AssertionError("Replacement of reversed selection");
		listener.onKeyDown(null, text, KeyEvent.KEYCODE_DEL, new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL));
		if (!text.toString().equals("x") || Selection.getSelectionEnd(text) != 1)
			throw new AssertionError("Backspace must preserve Unicode surrogate pairs");
		Selection.setSelection(text, 0);
		listener.onKeyDown(null, text, KeyEvent.KEYCODE_FORWARD_DEL, new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_FORWARD_DEL));
		if (text.length() != 0 || Selection.getSelectionEnd(text) != 0)
			throw new AssertionError("Forward deletion");
		KeyEvent unicode = new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_UNKNOWN) {
			@Override public int getUnicodeChar() { return 0x1f600; }
		};
		if (!listener.onKeyDown(null, text, KeyEvent.KEYCODE_UNKNOWN, unicode) || text.length() != 2)
			throw new AssertionError("Unicode hardware key insertion");
		listener.onKeyDown(null, text, KeyEvent.KEYCODE_ENTER, new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER));
		if (!text.toString().endsWith("\n")) throw new AssertionError("Enter insertion");
		String before = text.toString();
		if (listener.onKeyDown(null, text, KeyEvent.KEYCODE_DEL, new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL)) || !before.equals(text.toString()))
			throw new AssertionError("Key-up must not edit text");
		System.out.println("PASS: Qwerty listener selection, Unicode input, backward/forward delete and key-up rejection");
	}
}
