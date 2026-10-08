// SPDX-License-Identifier: GPL-3.0-only
package android.view.accessibility;

import java.util.ArrayList;

public final class AccessibilityEvent extends AccessibilityRecord {
	private int eventType, contentChangeTypes, action, movementGranularity;
	private long eventTime;
	private CharSequence packageName;
	private final ArrayList<AccessibilityRecord> records = new ArrayList<>();
	public static AccessibilityEvent obtain() { return new AccessibilityEvent(); }
	public static AccessibilityEvent obtain(int type) {
		AccessibilityEvent event = obtain();
		event.eventType = type;
		return event;
	}
	public void setEventType(int v) { eventType = v; }
	public int getEventType() { return eventType; }
	public void setContentChangeTypes(int v) { contentChangeTypes = v; }
	public int getContentChangeTypes() { return contentChangeTypes; }
	public void setAction(int v) { action = v; }
	public int getAction() { return action; }
	public void setMovementGranularity(int v) { movementGranularity = v; }
	public int getMovementGranularity() { return movementGranularity; }
	public void setEventTime(long v) { eventTime = v; }
	public long getEventTime() { return eventTime; }
	public void setPackageName(CharSequence v) { packageName = v; }
	public CharSequence getPackageName() { return packageName; }
	public void appendRecord(AccessibilityRecord record) { records.add(record); }
	public int getRecordCount() { return records.size(); }
	public AccessibilityRecord getRecord(int index) { return records.get(index); }
}
