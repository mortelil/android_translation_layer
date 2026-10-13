/*
 * Copyright (C) 2014 The Android Open Source Project
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

package android.media.session;

/**
 * Provides information about the remote user of a media session. ATL runs
 * services in-process, so the information is the calling process's own
 * package/pid/uid as reported by the binder.
 */
public final class MediaSessionManager {

    /**
     * Information of a remote user of a media session.
     */
    public static final class RemoteUserInfo {
        private final String mPackageName;
        private final int mPid;
        private final int mUid;

        public RemoteUserInfo(String packageName, int pid, int uid) {
            if (packageName == null) throw new NullPointerException("packageName");
            if (packageName.isEmpty()) throw new IllegalArgumentException("packageName is empty");
            mPackageName = packageName;
            mPid = pid;
            mUid = uid;
        }

        public String getPackageName() {
            return mPackageName;
        }

        public int getPid() {
            return mPid;
        }

        public int getUid() {
            return mUid;
        }

        @Override
        public boolean equals(Object obj) {
            if (!(obj instanceof RemoteUserInfo)) {
                return false;
            }
            RemoteUserInfo other = (RemoteUserInfo) obj;
            return mPid == other.mPid && mUid == other.mUid
                    && (mPackageName == null ? other.mPackageName == null
                            : mPackageName.equals(other.mPackageName));
        }

        @Override
        public int hashCode() {
            int result = 17;
            result = 31 * result + (mPackageName == null ? 0 : mPackageName.hashCode());
            result = 31 * result + mPid;
            result = 31 * result + mUid;
            return result;
        }

        @Override
        public String toString() {
            return "RemoteUserInfo{packageName=" + mPackageName + ", pid=" + mPid
                    + ", uid=" + mUid + "}";
        }
    }
}
