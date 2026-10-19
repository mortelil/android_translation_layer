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
        if (key == null || key.isEmpty()) throw new IllegalArgumentException("empty TXT key");
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (c < 0x20 || c > 0x7e || c == '=')
                throw new IllegalArgumentException("TXT key must be printable ASCII without '='");
        }
        if (key.length() + 1 + (value == null ? 0 : value.length) > 255)
            throw new IllegalArgumentException("TXT record exceeds 255 bytes");
        mTxtRecord.put(key, value);
    }

    /**
     * Add a service attribute as a key/value pair. Values may be UTF-8 strings
     * or null; the total length of key + value must be less than 255 bytes.
     * Mirrors AOSP NsdServiceInfo.setAttribute(String, String)
     * (frameworks/base core/java/android/net/nsd/NsdServiceInfo.java,
     * tag android-12.0.0_r1:240-249), which stores the UTF-8 bytes of the
     * value through the byte[] overload.
     */
    public void setAttribute(String key, String value) {
        try {
            setAttribute(key, value == null ? (byte[]) null : value.getBytes("UTF-8"));
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalArgumentException("Value must be UTF-8");
        }
    }

    /** Remove a TXT record (attribute) by key. */
    public void removeAttribute(String key) {
        mTxtRecord.remove(key);
    }

    /**
     * Returns the TXT records (attributes) as a map of key to value bytes.
     * The returned map is an unmodifiable view, matching AOSP semantics.
     */
    public Map<String, byte[]> getAttributes() {
        return java.util.Collections.unmodifiableMap(mTxtRecord);
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
