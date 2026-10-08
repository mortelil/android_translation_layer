// SPDX-License-Identifier: GPL-3.0-only
package android.view.accessibility;

import android.view.View;
import java.util.ArrayList;
import java.util.List;

public class AccessibilityRecord {
	private final List<CharSequence> text = new ArrayList<>();
	private CharSequence className, contentDescription, beforeText;
	private boolean checked, enabled, password, fullScreen, scrollable;
	private int itemCount = -1, currentItemIndex = -1, fromIndex = -1, toIndex = -1;
	private int scrollX, scrollY, maxScrollX, maxScrollY, addedCount, removedCount;
	private View source;
	private int virtualId = -1;
	public static AccessibilityRecord obtain() { return new AccessibilityRecord(); }
	public void recycle() { /* No object pool; normal garbage collection owns records. */ }
	public List<CharSequence> getText() { return text; }
	public void setSource(View view) { setSource(view, -1); }
	public void setSource(View view, int id) {
		source = view;
		virtualId = id;
	}
	public void setClassName(CharSequence v) { className = v; }
	public CharSequence getClassName() { return className; }
	public void setContentDescription(CharSequence v) { contentDescription = v; }
	public CharSequence getContentDescription() { return contentDescription; }
	public void setBeforeText(CharSequence v) { beforeText = v; }
	public CharSequence getBeforeText() { return beforeText; }
	public void setChecked(boolean v) { checked = v; }
	public boolean isChecked() { return checked; }
	public void setEnabled(boolean v) { enabled = v; }
	public boolean isEnabled() { return enabled; }
	public void setPassword(boolean v) { password = v; }
	public boolean isPassword() { return password; }
	public void setFullScreen(boolean v) { fullScreen = v; }
	public boolean isFullScreen() { return fullScreen; }
	public void setScrollable(boolean v) { scrollable = v; }
	public boolean isScrollable() { return scrollable; }
	public void setItemCount(int v) { itemCount = v; }
	public int getItemCount() { return itemCount; }
	public void setCurrentItemIndex(int v) { currentItemIndex = v; }
	public int getCurrentItemIndex() { return currentItemIndex; }
	public void setFromIndex(int v) { fromIndex = v; }
	public int getFromIndex() { return fromIndex; }
	public void setToIndex(int v) { toIndex = v; }
	public int getToIndex() { return toIndex; }
	public void setScrollX(int v) { scrollX = v; }
	public int getScrollX() { return scrollX; }
	public void setScrollY(int v) { scrollY = v; }
	public int getScrollY() { return scrollY; }
	public void setMaxScrollX(int v) { maxScrollX = v; }
	public int getMaxScrollX() { return maxScrollX; }
	public void setMaxScrollY(int v) { maxScrollY = v; }
	public int getMaxScrollY() { return maxScrollY; }
	public void setAddedCount(int v) { addedCount = v; }
	public int getAddedCount() { return addedCount; }
	public void setRemovedCount(int v) { removedCount = v; }
	public int getRemovedCount() { return removedCount; }
}
