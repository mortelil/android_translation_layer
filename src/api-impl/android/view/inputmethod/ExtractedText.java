// SPDX-License-Identifier: GPL-3.0-only
package android.view.inputmethod;

/** Text and selection snapshot exchanged with the input method. */
public class ExtractedText {
	public static final int FLAG_SINGLE_LINE = 1;
	public static final int FLAG_SELECTING = 2;
	public CharSequence text;
	public CharSequence hint;
	public int startOffset;
	public int partialStartOffset;
	public int partialEndOffset;
	public int selectionStart;
	public int selectionEnd;
	public int flags;
}
