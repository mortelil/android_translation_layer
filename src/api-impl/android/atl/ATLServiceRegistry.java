/* SPDX-License-Identifier: GPL-3.0-only */
package android.atl;

import android.app.Service;
import android.content.ComponentName;
import java.util.HashMap;

/**
 * Registry of same-process Android services, keyed by {@link ComponentName}.
 *
 * <p>Only the state needed by the started-service lifecycle is modelled: one
 * {@link ServiceRecord} per component holding the live {@link Service} instance
 * (null until {@code onCreate()} has run) and the last start ID handed out by
 * {@code startService}. Bound-service state is deliberately not modelled yet;
 * later cases extend this record instead of adding ad-hoc fields.
 *
 * <p>Locking: {@link #lock} is only held for map and counter mutations. It is
 * never held while application code ({@code onCreate}/{@code onStartCommand}/
 * {@code onDestroy}) is invoked, so a re-entrant {@code startService}/
 * {@code stopService} from inside a service callback cannot deadlock.
 */
public final class ATLServiceRegistry {

	/** Per-component state. */
	public static final class ServiceRecord {
		public final ComponentName component;
		public final String className;
		/** Live instance; set before onCreate() runs, null until then. */
		public volatile Service instance;
		/** Last start ID delivered by startService; 0 before the first start. */
		public int lastStartId;
		/** Set once the record was detached by stopService. */
		public volatile boolean destroyed;

		ServiceRecord(ComponentName component) {
			this.component = component;
			this.className = component.getClassName();
		}
	}

	private final Object lock = new Object();
	private final HashMap<ComponentName, ServiceRecord> records = new HashMap<>();

	/**
	 * Registers a start request and allocates its start ID. The first start of a
	 * component is ID 1 and each following start increments it, matching
	 * AOSP's per-{@code ServiceRecord} monotonic counter.
	 */
	public ServiceRecord allocateStart(ComponentName component) {
		synchronized (lock) {
			ServiceRecord record = records.get(component);
			if (record == null) {
				record = new ServiceRecord(component);
				records.put(component, record);
			}
			record.lastStartId++;
			return record;
		}
	}

	/** Registers a bind request; does not allocate a start ID. */
	public ServiceRecord allocateBind(ComponentName component) {
		synchronized (lock) {
			ServiceRecord record = records.get(component);
			if (record == null) {
				record = new ServiceRecord(component);
				records.put(component, record);
			}
			return record;
		}
	}

	/** True while {@code record} is still the registered, not-yet-stopped state. */
	public boolean isLive(ServiceRecord record) {
		synchronized (lock) {
			return !record.destroyed && records.get(record.component) == record;
		}
	}

	/**
	 * Returns the registered record owning {@code instance}, or null when the
	 * instance is not registered (never brought up, or already stopped). Matches
	 * by object identity so two instances of the same component are never
	 * confused.
	 */
	public ServiceRecord findByInstance(Service instance) {
		synchronized (lock) {
			for (ServiceRecord record : records.values()) {
				if (record.instance == instance) {
					return record;
				}
			}
			return null;
		}
	}

	/** The last start ID allocated for {@code record}; 0 before the first start. */
	public int lastStartIdOf(ServiceRecord record) {
		synchronized (lock) {
			return record.lastStartId;
		}
	}

	/**
	 * Removes {@code record} (the self-stop path) when it is still the registered
	 * record for its component. Returns false when it was already detached (e.g.
	 * a racing explicit stopService), so the caller does not destroy twice.
	 */
	public boolean detach(ServiceRecord record) {
		synchronized (lock) {
			if (records.get(record.component) != record) {
				return false;
			}
			records.remove(record.component);
			record.destroyed = true;
			return true;
		}
	}

	/**
	 * Removes the record for {@code component} (the stop path). Returns null when
	 * nothing was running, otherwise the detached record whose {@code instance}
	 * (possibly null if the pending start never landed) the caller must destroy on
	 * the main thread outside this lock.
	 */
	public ServiceRecord detach(ComponentName component) {
		synchronized (lock) {
			ServiceRecord record = records.get(component);
			if (record == null) {
				return null;
			}
			records.remove(component);
			record.destroyed = true;
			return record;
		}
	}
}
