package android.app;

import android.atl.ATLLoadedApp;
import android.content.Context;
import android.content.pm.ConfigurationInfo;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.Process;
import android.util.Slog;
import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class ActivityManager {

	/** AOSP {@code ActivityManager.RunningAppProcessInfo}: the fields a caller in this
	 *  process needs to find itself, above all the packages the process runs (microG
	 *  resolves the calling package with {@code pkgList}). */
	public static class RunningAppProcessInfo {
		public int importance;
		public int pid;
		public int uid;
		public String processName;
		public String[] pkgList;

		public RunningAppProcessInfo(String processName, int pid, String[] pkgList) {
			this.processName = processName;
			this.pid = pid;
			this.pkgList = pkgList;
		}

		private RunningAppProcessInfo(int pid, String processName, String[] pkgList) {
			this.pid = pid;
			this.processName = processName;
			this.pkgList = pkgList;
		}
	}

	public static class TaskDescription {
		public TaskDescription(String name) {}
		public TaskDescription(String name, Bitmap icon, int color) {}
	}

	/** Running processes in this ATL launcher session. */
	public List<RunningAppProcessInfo> getRunningAppProcesses() {
		return android.atl.ATLRemoteServices.runningProcesses();
	}

	public boolean isLowRamDevice() { return false; }

	public boolean isBackgroundRestricted() {
		// ATL does not implement Android's per-app background restriction policy.
		return false;
	}

	public static class MemoryInfo implements Parcelable {
		/* AOSP leaves these at 0 and getMemoryInfo() fills them in; ATL used to
		 * pretend there were always 10000 bytes of memory. */
		public long availMem;

		public long totalMem;

		public long threshold;

		public boolean lowMemory = false;

		public int describeContents() {
			return 0;
		}

		public void writeToParcel(Parcel dest, int flags) {
			dest.writeLong(availMem);
			dest.writeLong(totalMem);
			dest.writeLong(threshold);
			dest.writeInt(lowMemory ? 1 : 0);
		}

		public void readFromParcel(Parcel source) {
			availMem = source.readLong();
			totalMem = source.readLong();
			threshold = source.readLong();
			lowMemory = source.readInt() != 0;
		}

		public static final Parcelable.Creator<MemoryInfo> CREATOR = new Parcelable.Creator<MemoryInfo>() {
			public MemoryInfo createFromParcel(Parcel in) {
				MemoryInfo info = new MemoryInfo();
				info.readFromParcel(in);
				return info;
			}

			public MemoryInfo[] newArray(int size) {
				return new MemoryInfo[size];
			}
		};
	}

	public void getMemoryInfo(MemoryInfo outInfo) {
		/* AOSP fills the caller's object from the kernel: availMem/totalMem are what
		 * /proc/meminfo reports, threshold is the level of availMem at which the
		 * system considers itself low on memory, and lowMemory tells whether it is
		 * below it right now. ATL has no lowmemorykiller, so the threshold is the
		 * host kernel's own low-memory watermark (vm.min_free_kbytes); a device
		 * reports the framework's threshold. */
		long memTotal = -1, memAvailable = -1, memFree = 0, cached = 0, buffers = 0;

		try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream("/proc/meminfo")))) {
			String line;
			while ((line = reader.readLine()) != null) {
				int colon = line.indexOf(':');
				if (colon < 0)
					continue;

				String field = line.substring(0, colon);
				String[] value = line.substring(colon + 1).trim().split("\\s+");
				if (value.length == 0)
					continue;

				long bytes = Long.parseLong(value[0]) * 1024; // /proc/meminfo counts kB
				if (field.equals("MemTotal"))
					memTotal = bytes;
				else if (field.equals("MemAvailable"))
					memAvailable = bytes;
				else if (field.equals("MemFree"))
					memFree = bytes;
				else if (field.equals("Cached"))
					cached = bytes;
				else if (field.equals("Buffers"))
					buffers = bytes;
			}
		} catch (Exception e) {
			Slog.w("ATL", "cannot read /proc/meminfo: " + e);
		}

		if (memAvailable < 0) // kernels before 3.14 do not report MemAvailable
			memAvailable = memFree + cached + buffers;

		outInfo.totalMem = Math.max(memTotal, 0);
		outInfo.availMem = Math.max(memAvailable, 0);
		outInfo.threshold = readMinFreeBytes();
		outInfo.lowMemory = outInfo.availMem < outInfo.threshold;
	}

	/** The host kernel's low-memory watermark in bytes, or 0 if it cannot be read. */
	private static long readMinFreeBytes() {
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream("/proc/sys/vm/min_free_kbytes")))) {
			String line = reader.readLine();
			if (line != null)
				return Long.parseLong(line.trim()) * 1024;
		} catch (Exception e) {
			Slog.w("ATL", "cannot read /proc/sys/vm/min_free_kbytes: " + e);
		}
		return 0;
	}

	public ConfigurationInfo getDeviceConfigurationInfo() {
		return new ConfigurationInfo();
	}

	// AOSP answers these from the device heap properties (getMemoryClass() =
	// dalvik.vm.heapgrowthlimit, getLargeMemoryClass() = dalvik.vm.heapsize, the
	// maximum an application with ApplicationInfo.FLAG_LARGE_HEAP may grow to).
	// Host ART does not read dalvik.vm.*, so the launcher configures ART with the
	// emulated profile and passes the same numbers as -D properties: these report
	// the heap the application is really running with.
	public int getMemoryClass() {
		return Integer.parseInt(System.getProperty("atl.heap.growthlimit.mb", "256"));
	}
	public int getLargeMemoryClass() {
		return Integer.parseInt(System.getProperty("atl.heap.heapsize.mb", "512"));
	}

	public static void getMyMemoryState(RunningAppProcessInfo outInfo) {}

	public boolean clearApplicationUserData() { return false; }

	public static class ProcessErrorStateInfo implements Parcelable {
		public int condition;
		public String processName;
		public int pid;
		public int uid;
		public String tag;
		public String shortMsg;
		public String longMsg;
		public byte[] crashData;
		public int uid_mm;
		public int etype;
		public int euid;
		public int epid;
		public String eproc;
		public String edata;
		public Map<String, Object> extras;

		public static final int CRASHED = 1;
		public static final int NOT_RESPONDING = 2;
		public static final int NO_ERROR = 0;

		public int describeContents() {
			return 0;
		}

		public void writeToParcel(Parcel dest, int flags) {
			dest.writeInt(condition);
			dest.writeString(processName);
		}

		public void readFromParcel(Parcel source) {
			condition = source.readInt();
			processName = source.readString();
		}

		public static final Parcelable.Creator<ProcessErrorStateInfo> CREATOR = new Parcelable.Creator<ProcessErrorStateInfo>() {
			public ProcessErrorStateInfo createFromParcel(Parcel in) {
				ProcessErrorStateInfo info = new ProcessErrorStateInfo();
				info.readFromParcel(in);
				return info;
			}

			public ProcessErrorStateInfo[] newArray(int size) {
				return new ProcessErrorStateInfo[size];
			}
		};
	}

	public static class AppTask {}
	public List<ActivityManager.AppTask> getAppTasks() {
		return new ArrayList<>();
	}

	public static class RecentTaskInfo implements Parcelable {
		public int id;
		public int persistentId;
		public android.content.Intent baseIntent;
		public android.content.ComponentName origActivity;
		public CharSequence description;
		public int userId;

		public int describeContents() {
			return 0;
		}

		public void writeToParcel(Parcel dest, int flags) {
			dest.writeInt(id);
			dest.writeLong(persistentId);
		}

		public void readFromParcel(Parcel source) {
			id = source.readInt();
			persistentId = (int)source.readLong();
		}

		public static final Parcelable.Creator<RecentTaskInfo> CREATOR = new Parcelable.Creator<RecentTaskInfo>() {
			public RecentTaskInfo createFromParcel(Parcel in) {
				RecentTaskInfo info = new RecentTaskInfo();
				info.readFromParcel(in);
				return info;
			}

			public RecentTaskInfo[] newArray(int size) {
				return new RecentTaskInfo[size];
			}
		};
	}

	public static class RunningTaskInfo implements Parcelable {
		public int id;
		public android.content.ComponentName baseActivity;
		public android.content.ComponentName topActivity;
		public int numActivities;
		public int numRunning;
		public android.graphics.Bitmap thumbnail;
		public CharSequence description;

		public int describeContents() {
			return 0;
		}

		public void writeToParcel(Parcel dest, int flags) {
			dest.writeInt(id);
			dest.writeInt(numActivities);
			dest.writeInt(numRunning);
		}

		public void readFromParcel(Parcel source) {
			id = source.readInt();
			numActivities = source.readInt();
			numRunning = source.readInt();
		}

		public static final Parcelable.Creator<RunningTaskInfo> CREATOR = new Parcelable.Creator<RunningTaskInfo>() {
			public RunningTaskInfo createFromParcel(Parcel in) {
				RunningTaskInfo info = new RunningTaskInfo();
				info.readFromParcel(in);
				return info;
			}

			public RunningTaskInfo[] newArray(int size) {
				return new RunningTaskInfo[size];
			}
		};
	}

	public List<RecentTaskInfo> getRecentTasks(int maxNum, int flags) {
		return new ArrayList<>();
	}

	public List<RunningTaskInfo> getRunningTasks(int maxNum) {
		return new ArrayList<>();
	}

	public List<ActivityManager.ProcessErrorStateInfo> getProcessesInErrorState() {
		return new ArrayList<>();
	}

	public static class RunningServiceInfo implements Parcelable {
		public android.content.ComponentName service;
		public int pid;
		public int uid;
		public String process;
		public boolean foreground;
		public long activeSince;
		public boolean started;
		public int clientCount;
		public int clientPackageCount;
		public int crashCount;
		public long restarting;
		public int flags;
		public String clientPackage;

		public RunningServiceInfo() {
		}

		public int describeContents() {
			return 0;
		}

		public void writeToParcel(Parcel dest, int flags) {
			return;
		}

		public void readFromParcel(Parcel source) {
			return;
		}
	}

	public List<RunningServiceInfo> getRunningServices(int maxNum)
	    throws SecurityException {
		return new ArrayList<>();
	}

	public List<ApplicationExitInfo> getHistoricalProcessExitReasons(String pkgname, int pid, int maxNum) {
		return Collections.emptyList();
	}

	public static boolean isUserAMonkey() { return false; }

	public void moveTaskToFront(int taskId, int flags, Bundle options) {
	}
}
