/*
 * Copyright (C) 2008 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package android.view.inputmethod;

import android.content.Context;
import android.content.res.TypedArray;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.Editable;
import android.text.NoCopySpan;
import android.text.Selection;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;

/**
 * Base class for implementors of the InputConnection interface, taking care
 * of most of the common behavior for providing a connection to an Editable.
 * Implementors of this class will want to be sure to implement
 * {@link #getEditable} to provide access to their own editable object.
 */
public class BaseInputConnection implements InputConnection {
	private static final Object COMPOSING = new NoCopySpan.Concrete();
	private Editable editable;

	BaseInputConnection(InputMethodManager mgr, boolean fullEditor) {
	}

	public BaseInputConnection(View targetView, boolean fullEditor) {
	}

	public static final void removeComposingSpans(Spannable text) {
		for (Object span : text.getSpans(0, text.length(), Object.class))
			if ((text.getSpanFlags(span) & Spanned.SPAN_COMPOSING) != 0)
				text.removeSpan(span);
		text.removeSpan(COMPOSING);
	}
	public static void setComposingSpans(Spannable text) {
		setComposingSpans(text, 0, text.length());
	}
	/**
	 * @hide
	 */
	public static void setComposingSpans(Spannable text, int start, int end) {
		text.setSpan(COMPOSING, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE | Spanned.SPAN_COMPOSING);
	}

	public static int getComposingSpanStart(Spannable text) {
		return text.getSpanStart(COMPOSING);
	}

	public static int getComposingSpanEnd(Spannable text) {
		return text.getSpanEnd(COMPOSING);
	}

	/**
	 * Return the target of edit operations.  The default implementation
	 * returns its own fake editable that is just used for composing text;
	 * subclasses that are real text editors should override this and
	 * supply their own.
	 */
	public Editable getEditable() {
		if (editable == null) {
			editable = new SpannableStringBuilder();
			Selection.setSelection(editable, 0);
		}
		return editable;
	}

	/**
	 * Default implementation does nothing.
	 */
	public boolean beginBatchEdit() {
		return false;
	}
	/**
	 * Default implementation does nothing.
	 */
	public boolean endBatchEdit() {
		return false;
	}
	/**
	 * Called when this InputConnection is no longer used by the InputMethodManager.
	 *
	 * @hide
	 */
	protected void reportFinish() {
		// Intentionaly empty
	}
	/**
	 * Default implementation uses
	 * {@link MetaKeyKeyListener#clearMetaKeyState(long, int)
	 * MetaKeyKeyListener.clearMetaKeyState(long, int)} to clear the state.
	 */
	public boolean clearMetaKeyStates(int states) {
		return true;
	}
	/**
	 * Default implementation does nothing and returns false.
	 */
	public boolean commitCompletion(CompletionInfo text) {
		return false;
	}
	/**
	 * Default implementation does nothing and returns false.
	 */
	public boolean commitCorrection(CorrectionInfo correctionInfo) {
		return false;
	}
	/**
	 * Default implementation replaces any existing composing text with
	 * the given text.  In addition, only if dummy mode, a key event is
	 * sent for the new text and the current editable buffer cleared.
	 */
	public boolean commitText(CharSequence text, int newCursorPosition) {
		return replaceText(text, newCursorPosition, false);
	}

	private boolean replaceText(CharSequence text, int cursor, boolean composing) {
		Editable content = getEditable();
		if (content == null)
			return false;
		beginBatchEdit();
		try {
			int start = getComposingSpanStart(content), end = getComposingSpanEnd(content);
			if (start < 0 || end < 0) {
				start = Math.max(0, Selection.getSelectionStart(content));
				end = Math.max(0, Selection.getSelectionEnd(content));
			}
			if (start > end) {
				int tmp = start;
				start = end;
				end = tmp;
			}
			removeComposingSpans(content);
			content.replace(start, end, text);
			if (composing && text.length() > 0)
				setComposingSpans(content, start, start + text.length());
			int position = cursor > 0 ? start + text.length() + cursor - 1 : start + cursor;
			Selection.setSelection(content, Math.max(0, Math.min(content.length(), position)));
			return true;
		} finally {
			endBatchEdit();
		}
	}
	/**
	 * The default implementation performs the deletion around the current
	 * selection position of the editable text.
	 * @param beforeLength
	 * @param afterLength
	 */
	public boolean deleteSurroundingText(int beforeLength, int afterLength) {
		if (beforeLength < 0 || afterLength < 0)
			return false;
		Editable content = getEditable();
		if (content == null)
			return false;
		int start = Selection.getSelectionStart(content), end = Selection.getSelectionEnd(content);
		if (start < 0 || end < 0)
			return false;
		if (start > end) {
			int tmp = start;
			start = end;
			end = tmp;
		}
		int composingStart = getComposingSpanStart(content), composingEnd = getComposingSpanEnd(content);
		if (composingStart >= 0 && composingEnd >= 0) {
			start = Math.min(start, Math.min(composingStart, composingEnd));
			end = Math.max(end, Math.max(composingStart, composingEnd));
		}
		beginBatchEdit();
		try {
			content.delete(end, end + Math.min(afterLength, content.length() - end));
			content.delete(Math.max(0, start - beforeLength), start);
			return true;
		} finally {
			endBatchEdit();
		}
	}
	/**
	 * The default implementation removes the composing state from the
	 * current editable text.  In addition, only if dummy mode, a key event is
	 * sent for the new text and the current editable buffer cleared.
	 */
	public boolean finishComposingText() {
		Editable content = getEditable();
		if (content == null)
			return false;
		beginBatchEdit();
		try {
			removeComposingSpans(content);
		} finally {
			endBatchEdit();
		}
		return true;
	}
	/**
	 * The default implementation uses TextUtils.getCapsMode to get the
	 * cursor caps mode for the current selection position in the editable
	 * text, unless in dummy mode in which case 0 is always returned.
	 */
	public int getCursorCapsMode(int reqModes) {
		return 0;
	}
	/**
	 * The default implementation always returns null.
	 */
	public ExtractedText getExtractedText(ExtractedTextRequest request, int flags) {
		return null;
	}
	/**
	 * The default implementation returns the given amount of text from the
	 * current cursor position in the buffer.
	 */
	public CharSequence getTextBeforeCursor(int length, int flags) {
		if (length < 0)
			throw new IllegalArgumentException("length");
		Editable content = getEditable();
		if (content == null)
			return null;
		int end = Math.max(0, Math.min(Selection.getSelectionStart(content), Selection.getSelectionEnd(content)));
		return content.subSequence(Math.max(0, end - length), end);
	}
	/**
	 * The default implementation returns the text currently selected, or null if none is
	 * selected.
	 */
	public CharSequence getSelectedText(int flags) {
		Editable content = getEditable();
		if (content == null)
			return null;
		int start = Selection.getSelectionStart(content), end = Selection.getSelectionEnd(content);
		return start < 0 || end < 0 || start == end ? null : content.subSequence(Math.min(start, end), Math.max(start, end));
	}
	/**
	 * The default implementation returns the given amount of text from the
	 * current cursor position in the buffer.
	 */
	public CharSequence getTextAfterCursor(int length, int flags) {
		if (length < 0)
			throw new IllegalArgumentException("length");
		Editable content = getEditable();
		if (content == null)
			return null;
		int start = Math.max(0, Math.max(Selection.getSelectionStart(content), Selection.getSelectionEnd(content)));
		return content.subSequence(start, start + Math.min(length, content.length() - start));
	}
	/**
	 * The default implementation turns this into the enter key.
	 */
	public boolean performEditorAction(int actionCode) {
		return true;
	}
	/**
	 * The default implementation does nothing.
	 */
	public boolean performContextMenuAction(int id) {
		return false;
	}
	/**
	 * The default implementation does nothing.
	 */
	public boolean performPrivateCommand(String action, Bundle data) {
		return false;
	}
	/**
	 * The default implementation places the given text into the editable,
	 * replacing any existing composing text.  The new text is marked as
	 * in a composing state with the composing style.
	 */
	public boolean setComposingText(CharSequence text, int newCursorPosition) {
		return replaceText(text, newCursorPosition, true);
	}
	public boolean setComposingRegion(int start, int end) {
		Editable content = getEditable();
		if (content == null)
			return false;
		beginBatchEdit();
		try {
			removeComposingSpans(content);
			int lo = Math.max(0, Math.min(content.length(), Math.min(start, end)));
			int hi = Math.max(0, Math.min(content.length(), Math.max(start, end)));
			if (lo != hi)
				setComposingSpans(content, lo, hi);
		} finally {
			endBatchEdit();
		}
		return true;
	}
	/**
	 * The default implementation changes the selection position in the
	 * current editable text.
	 */
	public boolean setSelection(int start, int end) {
		Editable content = getEditable();
		if (content == null)
			return false;
		if (start < 0 || end < 0 || start > content.length() || end > content.length())
			return true;
		Selection.setSelection(content, start, end);
		return true;
	}
	/**
	 * Provides standard implementation for sending a key event to the window
	 * attached to the input connection's view.
	 */
	public boolean sendKeyEvent(KeyEvent event) {
		return false;
	}

	/**
	 * Updates InputMethodManager with the current fullscreen mode.
	 */
	public boolean reportFullscreenMode(boolean enabled) {
		return true;
	}

	/**
	 * Default implementation calls {@link #finishComposingText()} and {@code
	 * setImeConsumesInput(false)}.
	 */
	@Override
	public void closeConnection() {
		finishComposingText();
		// setImeConsumesInput(false);
	}
}
