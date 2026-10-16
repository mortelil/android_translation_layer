/*
 * Copyright (C) 2010 The Android Open Source Project
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

package android.app;

import android.content.DialogInterface;
import android.os.Bundle;

/** Dialog lifecycle adapted from AOSP android-17.0.0_r1, frameworks/base
 * 94b4c163b7dfe5ce3607f7bb8456f9573f7de57d.
 * ATL supports dialog-producing fragments without a fragment back stack.
 * Embedded fragment view inflation and process-death restoration are not provided.
 */
public class DialogFragment extends Fragment implements DialogInterface.OnCancelListener, DialogInterface.OnDismissListener {
    public static final int STYLE_NORMAL = 0, STYLE_NO_TITLE = 1, STYLE_NO_FRAME = 2, STYLE_NO_INPUT = 3;
    private int style, theme;
    private boolean cancelable = true, showsDialog = true, dismissed, destroying;
    private Dialog dialog;
    private FragmentManager manager;

    public void setStyle(int style, int theme) {
        this.style = style;
        if (style == STYLE_NO_FRAME || style == STYLE_NO_INPUT)
            this.theme = com.android.internal.R.style.Theme_DeviceDefault_Dialog_NoFrame;
        if (theme != 0) this.theme = theme;
    }
    public int getTheme() { return theme; }
    public Dialog getDialog() { return dialog; }
    public void setCancelable(boolean value) { cancelable = value; if (dialog != null) dialog.setCancelable(value); }
    public boolean isCancelable() { return cancelable; }
    public void setShowsDialog(boolean value) { showsDialog = value; }
    public boolean getShowsDialog() { return showsDialog; }

    public void show(FragmentManager manager, String tag) {
        this.manager = manager;
        dismissed = false;
        destroying = false;
        manager.beginTransaction().add(this, tag).commit();
    }
    public int show(FragmentTransaction transaction, String tag) {
        dismissed = false;
        destroying = false;
        return transaction.add(this, tag).commit();
    }
    public void dismiss() { dismissInternal(); }
    public void dismissAllowingStateLoss() { dismissInternal(); }
    private void dismissInternal() {
        if (dismissed) return;
        dismissed = true;
        if (dialog != null) dialog.dismiss();
        FragmentManager host = manager != null ? manager : getFragmentManager();
        if (host != null) host.beginTransaction().remove(this).commit();
    }
    @Override public void onAttach(android.content.Context context) {
        super.onAttach(context);
        manager = getFragmentManager();
        // A transaction-based show may be dismissed before the add executes.
        if (dismissed && manager != null) manager.beginTransaction().remove(this).commit();
    }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (state != null) {
            style = state.getInt("android:style", style);
            theme = state.getInt("android:theme", theme);
            cancelable = state.getBoolean("android:cancelable", cancelable);
            showsDialog = state.getBoolean("android:showsDialog", showsDialog);
        }
    }
    public Dialog onCreateDialog(Bundle state) { return new Dialog(getActivity(), getTheme()); }
    @Override public void onActivityCreated(Bundle state) {
        super.onActivityCreated(state);
        if (!showsDialog || dismissed) return;
        dialog = onCreateDialog(state);
        if (dialog == null) return;
        if (style == STYLE_NO_INPUT) dialog.getWindow().addFlags(
            android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
            android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
        if (style != STYLE_NORMAL) dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        dialog.setOwnerActivity(getActivity());
        dialog.setCancelable(cancelable);
        dialog.setOnCancelListener(this);
        dialog.setOnDismissListener(this);
    }
    @Override public void onStart() { super.onStart(); if (dialog != null && !dismissed) dialog.show(); }
    @Override public void onStop() { if (dialog != null) dialog.hide(); super.onStop(); }
    @Override public void onDestroy() {
        destroying = true;
        if (dialog != null) {
            dialog.setOnDismissListener(null);
            if (!dismissed) onDismiss(dialog);
            dialog.dismiss();
            dialog = null;
        }
        manager = null;
        super.onDestroy();
    }
    @Override public void onCancel(DialogInterface value) {}
    @Override public void onDismiss(DialogInterface value) { if (!destroying) dismissInternal(); }
}
