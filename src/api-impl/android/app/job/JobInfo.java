package android.app.job;

import android.content.ComponentName;
import android.net.Uri;
import android.os.PersistableBundle;
import java.util.ArrayList;

public class JobInfo {

	private ComponentName service;
	long initialBackoffMillis;
	int backoffPolicy;
	private PersistableBundle extras;
	long periodicMillis;
	private int id;
	boolean running;
	long minLatencyMillis;
	private long triggerContentUpdateDelay = -1;
	private long triggerContentMaxDelay = -1;
	private final ArrayList<TriggerContentUri> triggerContentUris = new ArrayList<>();

	public long getTriggerContentUpdateDelay() { return triggerContentUpdateDelay; }
	public long getTriggerContentMaxDelay() { return triggerContentMaxDelay; }
	public TriggerContentUri[] getTriggerContentUris() {
		return triggerContentUris.isEmpty() ? null : triggerContentUris.toArray(new TriggerContentUri[0]);
	}

	public JobInfo() {}

	public ComponentName getService() {
		return service;
	}

	public PersistableBundle getExtras() {
		return extras;
	}

	public int getId() {
		return id;
	}

	public static long getMinPeriodMillis() {
		return 1000;
	}

	public String toString() {
		return "JobInfo{"
		     + "jobService=" + service
		     + ", initialBackoffMillis=" + initialBackoffMillis
		     + ", backoffPolicy=" + backoffPolicy
		     + ", extras=" + extras
		     + ", periodicMillis=" + periodicMillis
		     + ", id=" + id
		     + '}';
	}

	public static final class Builder {

		private JobInfo jobInfo;

		public Builder(int jobId, ComponentName jobService) {
			jobInfo = new JobInfo();
			jobInfo.id = jobId;
			jobInfo.service = jobService;
		}

		public Builder setBackoffCriteria(long initialBackoffMillis, int backoffPolicy) {
			jobInfo.initialBackoffMillis = initialBackoffMillis;
			jobInfo.backoffPolicy = backoffPolicy;
			return this;
		}

		public Builder setExtras(PersistableBundle extras) {
			jobInfo.extras = extras;
			return this;
		}

		public Builder setMinimumLatency(long minLatencyMillis) {
			jobInfo.minLatencyMillis = minLatencyMillis;
			return this;
		}

		public Builder setOverrideDeadline(long a) {
			return this;
		}

		public Builder setPeriodic(long dummy) {
			jobInfo.periodicMillis = dummy;
			return this;
		}

		public Builder setPersisted(boolean persisted) {
			return this;
		}

		public Builder setRequiredNetworkType(int networkType) {
			return this;
		}

		public Builder setRequiresCharging(boolean requires_charging) {
			return this;
		}

		public Builder setRequiresDeviceIdle(boolean requires_device_idle) {
			return this;
		}

		public Builder setRequiresBatteryNotLow(boolean requires_battery_not_low) {
			return this;
		}

		public Builder setRequiresStorageNotLow(boolean requires_storage_not_low) {
			return this;
		}

		public Builder setTriggerContentUpdateDelay(long durationMs) {
			jobInfo.triggerContentUpdateDelay = durationMs;
			return this;
		}

		public Builder setTriggerContentMaxDelay(long durationMs) {
			jobInfo.triggerContentMaxDelay = durationMs;
			return this;
		}

		public Builder addTriggerContentUri(TriggerContentUri triggerContentUri) {
			if (triggerContentUri == null) throw new NullPointerException("triggerContentUri");
			jobInfo.triggerContentUris.add(triggerContentUri);
			return this;
		}

		public JobInfo build() {
			return jobInfo;
		}
	}

	public static class TriggerContentUri {
		public static final int FLAG_NOTIFY_FOR_DESCENDANTS = 1;
		private final Uri uri;
		private final int flags;
		public TriggerContentUri(Uri uri, int flags) {
			if (uri == null) throw new NullPointerException("uri");
			this.uri = uri;
			this.flags = flags;
		}
		public Uri getUri() { return uri; }
		public int getFlags() { return flags; }
		@Override public boolean equals(Object other) {
			if (!(other instanceof TriggerContentUri)) return false;
			TriggerContentUri trigger = (TriggerContentUri)other;
			return flags == trigger.flags && uri.equals(trigger.uri);
		}
		@Override public int hashCode() { return uri.hashCode() ^ flags; }
	}
}
