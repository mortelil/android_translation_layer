/*
 * Copyright (C) 2006 The Android Open Source Project
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

package android.content;

/*
 * Adapted from AOSP platform/frameworks/base
 *   tag     android-17.0.0_r1
 *   commit  94b4c163b7dfe5ce3607f7bb8456f9573f7de57d
 *   file    core/java/android/content/ComponentCallbacks2.java
 * Apache License 2.0; see this repository's LICENSE.txt.
 * Values verbatim (ComponentCallbacks2.java:93-176). The @IntDef/@TrimMemoryLevel
 * annotations are not carried over (ATL has no android.annotation support).
 */
public interface ComponentCallbacks2 extends ComponentCallbacks {
	int TRIM_MEMORY_COMPLETE = 80;
	int TRIM_MEMORY_MODERATE = 60;
	int TRIM_MEMORY_BACKGROUND = 40;
	int TRIM_MEMORY_UI_HIDDEN = 20;
	int TRIM_MEMORY_RUNNING_CRITICAL = 15;
	int TRIM_MEMORY_RUNNING_LOW = 10;
	int TRIM_MEMORY_RUNNING_MODERATE = 5;

	void onTrimMemory(int level);
}
