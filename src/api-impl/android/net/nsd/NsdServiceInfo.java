/*
 * Copyright (C) 2023 The Android Open Source Project
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

package android.net.nsd;

import java.net.InetAddress;
import java.util.HashMap;
import java.util.Map;

/**
 * A class representing service information for network service discovery.
 *
 * ATL implementation note: the public surface mirrors
 * android.net.nsd.NsdServiceInfo from AOSP (frameworks/base, tag
 * android-17.0.0_r1). The extra package-private fields carry the avahi/DNS-SD
 * resolve key (interface, protocol, domain) captured from the browse event so
 * that {@link NsdManager#resolveService} can resolve exactly the discovered
 * instance. They are not part of the public API.
 */
public class NsdServiceInfo {
    private String mServiceName;
    private String mServiceType;

    private InetAddress mHost;
    private int mPort;

    private final Map<String, byte[]> mTxtRecord = new HashMap<String, byte[]>();

    // ATL internal resolve identity. -1 / null means "not discovered via browse".
    /* package */ int mNativeIface = -1;
    /* package */ int mNativeProto = -1;
    /* package */ String mNativeDomain;

    public NsdServiceInfo() {
    }

    /** Get the service name. */
    public String getServiceName() {
        return mServiceName;
    }

    /** Set the service name. */
    public void setServiceName(String s) {
        mServiceName = s;
    }

    /** Get the service type. */
    public String getServiceType() {
        return mServiceType;
    }

    /** Set the service type. */
    public void setServiceType(String s) {
        mServiceType = s;
    }

    /** Get the host address. The host address is valid only after a successful resolve. */
    public InetAddress getHost() {
        return mHost;
    }

    /** Set the host address. */
    public void setHost(InetAddress s) {
        mHost = s;
    }

    /** Get the port. The port is only valid after a successful resolve. */
    public int getPort() {
        return mPort;
    }

    /** Set the port. */
    public void setPort(int p) {
        mPort = p;
    }

    /**
     * Set a TXT record (attribute) for this service. The key may not be null.
     * The value may be null (an empty-string value).
     */
    public void setAttribute(String key, byte[] value) {
        if (key == null) {
            throw new IllegalArgumentException("key cannot be null");
        }
        mTxtRecord.put(key, value);
    }

    /** Remove a TXT record (attribute) by key. */
    public void removeAttribute(String key) {
        mTxtRecord.remove(key);
    }

    /**
     * Returns the TXT records (attributes) as a map of key to value bytes.
     * The returned map is the live backing store, matching AOSP semantics.
     */
    public Map<String, byte[]> getAttributes() {
        return mTxtRecord;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("name: ").append(mServiceName)
          .append(" type: ").append(mServiceType)
          .append(" host: ").append(mHost)
          .append(" port: ").append(mPort);
        if (!mTxtRecord.isEmpty()) {
            sb.append(" txt: ").append(mTxtRecord.keySet());
        }
        return sb.toString();
    }
}
