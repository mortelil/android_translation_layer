package android.app.job;

import android.net.Uri;
import android.os.Parcelable;
import android.os.PersistableBundle;

public class JobParameters implements Parcelable {

	public static final Creator<JobParameters> CREATOR = null;

	JobInfo jobInfo;

	JobParameters(JobInfo jobInfo) {
		this.jobInfo = jobInfo;
	}

	public PersistableBundle getExtras() {
		return jobInfo.getExtras();
	}

	public int getJobId() {
		return jobInfo.getId();
	}

	public android.net.Network getNetwork() {
		// ATL's scheduler does not assign a network to a job yet. In particular,
		// returning the default network would falsely promise constraint matching.
		return null;
	}

	public Uri[] getTriggeredContentUris() {
		return new Uri[0];
	}

	public String[] getTriggeredContentAuthorities() {
		return new String[0];
	}
}
