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

package android.os;

import java.util.concurrent.Executor;

import android.os.strictmode.Violation;

public final class StrictMode {
	public static void setThreadPolicy(final ThreadPolicy policy) {}
	public static void setVmPolicy(final VmPolicy policy) {}
	public static VmPolicy getVmPolicy() { return new VmPolicy.Builder().build(); }
	public static ThreadPolicy allowThreadDiskWrites() {
		return new ThreadPolicy();
	}
	public static ThreadPolicy allowThreadDiskReads() {
		return new ThreadPolicy();
	}
	public static ThreadPolicy getThreadPolicy() {
		return new ThreadPolicy();
	}

	public static void noteSlowCall(String tag) {}

	/**
	 * When {@link ThreadPolicy.Builder#penaltyListener} is enabled, the listener is called on
	 * the provided executor when a Thread violation occurs.
	 */
	public interface OnThreadViolationListener {
		/** Called on a thread policy violation. */
		void onThreadViolation(Violation v);
	}

	public static final class ThreadPolicy {
		public static final ThreadPolicy LAX;
		final int mask;
		final OnThreadViolationListener listener;
		final Executor callbackExecutor;

		private ThreadPolicy(int mask, OnThreadViolationListener listener, Executor executor) {
			this.mask = mask;
			this.listener = listener;
			this.callbackExecutor = executor;
		}

		private ThreadPolicy() {
			this.mask = 0;
			this.listener = new OnThreadViolationListener() {
				@Override
				public void onThreadViolation(Violation v) {}
			};
			this.callbackExecutor = new Executor() {
				@Override
				public void execute(Runnable command) {}
			};
		}

		public OnThreadViolationListener getOnThreadViolationListener() {
			return listener;
		}

		public Executor getOnThreadViolationExecutor() {
			return callbackExecutor;
		}

		public static final class Builder {
			private int mask = 0;
			private OnThreadViolationListener listener;
			private Executor executor;

			public Builder() {
				mask = 0;
			}

			public Builder(ThreadPolicy policy) {
				if (policy != null) {
					mask = policy.mask;
					listener = policy.listener;
					executor = policy.callbackExecutor;
				}
			}

			public Builder detectAll() {
				return this;
			}
			public Builder detectNetwork() {
				return this;
			}
			public Builder permitAll() {
				return this;
			}
			public Builder permitDiskReads() {
				return this;
			}
			public Builder permitDiskWrites() {
				return this;
			}
			public Builder detectResourceMismatches() {
				return this;
			}
			public Builder penaltyLog() {
				return this;
			}
			public Builder penaltyDeath() {
				return this;
			}
			/**
			 * Calls {@link OnThreadViolationListener#onThreadViolation} on the specified
			 * executor for every violation.
			 */
			public Builder penaltyListener(Executor executor, OnThreadViolationListener listener) {
				if (executor == null) {
					throw new NullPointerException("executor must not be null");
				}
				this.listener = listener;
				this.executor = executor;
				return this;
			}
			public ThreadPolicy build() {
				return new ThreadPolicy(mask, listener, executor);
			}
			public Builder detectUnbufferedIo() {
				return this;
			}
			public Builder permitUnbufferedIo() {
				return this;
			}
		}
		static {
			LAX = (new Builder()).build();
		}
	}
	public static final class VmPolicy {
		public static final VmPolicy LAX;
		public static final class Builder {
			public Builder detectActivityLeaks() {
				return this;
			}
			public Builder detectAll() {
				return this;
			}
			public Builder detectLeakedSqlLiteObjects() {
				return this;
			}
			public Builder detectLeakedClosableObjects() {
				return this;
			}
			public Builder detectLeakedRegistrationObjects() {
				return this;
			}
			public Builder detectFileUriExposure() {
				return this;
			}
			public Builder penaltyDeath() {
				return this;
			}
			public Builder penaltyLog() {
				return this;
			}
			public Builder penaltyDropBox() {
				return this;
			}
			public VmPolicy build() {
				return new VmPolicy();
			}
		}

		static {
			LAX = (new Builder()).build();
		}
	}
}
