// SPDX-License-Identifier: GPL-3.0-only
package android.text.method;

import android.text.Editable;
import android.view.KeyEvent;
import android.view.View;

/** Hardware keyboard editing. GTK's input method supplies composed text. */
public class TextKeyListener extends MetaKeyKeyListener implements KeyListener {
    private static final TextKeyListener INSTANCE = new TextKeyListener();
    public static TextKeyListener getInstance() { return INSTANCE; }

    public static void clear(Editable content) {
        content.clear();
        resetMetaState(content);
    }

    @Override public int getInputType() { return 1; /* TYPE_CLASS_TEXT */ }

    @Override public boolean onKeyDown(View view, Editable content, int code, KeyEvent event) {
        if (super.onKeyDown(view, content, code, event)) return true;
        boolean handled = QwertyKeyListener.getInstanceForFullKeyboard().onKeyDown(view, content, code, event);
        if (handled) adjustMetaAfterKeypress(content);
        return handled;
    }

    @Override public boolean onKeyOther(View view, Editable content, KeyEvent event) {
        return QwertyKeyListener.getInstanceForFullKeyboard().onKeyOther(view, content, event);
    }

    public enum Capitalize { CHARACTERS, WORDS, SENTENCES, NONE }
}
