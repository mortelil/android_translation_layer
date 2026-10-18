/*
 * Copyright (C) 2007 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.view.inputmethod;

import android.os.Bundle;
import android.view.KeyEvent;

/**
 * <p>Wrapper class for proxying calls to another InputConnection.  Subclass and have fun!
 *
 * Behavioral reference: frameworks/base core/java/android/view/inputmethod/
 * InputConnectionWrapper.java at android-17.0.0_r1 (commit 94b4c163). This class
 * MUST declare {@code implements InputConnection}: applications (e.g. Fenix's
 * ExtractedTextInputConnection) subclass it and rely on the inherited interface,
 * so a bare ctor-only stub made invoke-interface commitText() fail with
 * IncompatibleClassChangeError and aborted the app's main process.
 */
public class InputConnectionWrapper implements InputConnection {
	private InputConnection mTarget;
	final boolean mMutable;

	public InputConnectionWrapper(InputConnection target, boolean mutable) {
		mMutable = mutable;
		mTarget = target;
	}

	/**
	 * Change the target of the input connection.
	 *
	 * @throws SecurityException when this wrapper has non-null target and is immutable.
	 */
	public void setTarget(InputConnection target) {
		if (mTarget != null && !mMutable) {
			throw new SecurityException("not mutable");
		}
		mTarget = target;
	}

	@Override
	public CharSequence getTextBeforeCursor(int n, int flags) {
		return mTarget.getTextBeforeCursor(n, flags);
	}

	@Override
	public CharSequence getTextAfterCursor(int n, int flags) {
		return mTarget.getTextAfterCursor(n, flags);
	}

	@Override
	public CharSequence getSelectedText(int flags) {
		return mTarget.getSelectedText(flags);
	}

	@Override
	public int getCursorCapsMode(int reqModes) {
		return mTarget.getCursorCapsMode(reqModes);
	}

	@Override
	public ExtractedText getExtractedText(ExtractedTextRequest request, int flags) {
		return mTarget.getExtractedText(request, flags);
	}

	@Override
	public boolean deleteSurroundingText(int beforeLength, int afterLength) {
		return mTarget.deleteSurroundingText(beforeLength, afterLength);
	}

	@Override
	public boolean setComposingText(CharSequence text, int newCursorPosition) {
		return mTarget.setComposingText(text, newCursorPosition);
	}

	@Override
	public boolean setComposingRegion(int start, int end) {
		return mTarget.setComposingRegion(start, end);
	}

	@Override
	public boolean finishComposingText() {
		return mTarget.finishComposingText();
	}

	@Override
	public boolean commitText(CharSequence text, int newCursorPosition) {
		return mTarget.commitText(text, newCursorPosition);
	}

	@Override
	public boolean commitCompletion(CompletionInfo text) {
		return mTarget.commitCompletion(text);
	}

	@Override
	public boolean commitCorrection(CorrectionInfo correctionInfo) {
		return mTarget.commitCorrection(correctionInfo);
	}

	@Override
	public boolean setSelection(int start, int end) {
		return mTarget.setSelection(start, end);
	}

	@Override
	public boolean performEditorAction(int editorAction) {
		return mTarget.performEditorAction(editorAction);
	}

	@Override
	public boolean performContextMenuAction(int id) {
		return mTarget.performContextMenuAction(id);
	}

	@Override
	public boolean beginBatchEdit() {
		return mTarget.beginBatchEdit();
	}

	@Override
	public boolean endBatchEdit() {
		return mTarget.endBatchEdit();
	}

	@Override
	public boolean sendKeyEvent(KeyEvent event) {
		return mTarget.sendKeyEvent(event);
	}

	@Override
	public boolean clearMetaKeyStates(int states) {
		return mTarget.clearMetaKeyStates(states);
	}

	@Override
	public boolean reportFullscreenMode(boolean enabled) {
		return mTarget.reportFullscreenMode(enabled);
	}

	@Override
	public boolean performPrivateCommand(String action, Bundle data) {
		return mTarget.performPrivateCommand(action, data);
	}

	@Override
	public void closeConnection() {
		mTarget.closeConnection();
	}
}
