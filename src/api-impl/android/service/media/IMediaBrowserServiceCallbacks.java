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

package android.service.media;

import android.media.browse.MediaBrowser;
import android.media.session.MediaSession;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.IInterface;
import android.os.RemoteException;

import java.util.List;

/**
 * Callbacks from a {@link MediaBrowserService} back to the connected
 * {@link android.media.browse.MediaBrowser} client.
 *
 * <p>ATL implements this AOSP interface with in-process binder calls only; the
 * framework {@code MediaBrowser} client and our {@code MediaBrowserService} run
 * in the same VM, so {@link Stub#asInterface} hands back the local object.
 *
 * <p>The AOSP AIDL carries the children as a {@code ParceledListSlice}; ATL has
 * no such class, so the same information is passed as a plain {@link List}. Both
 * the sender and the receiver of this interface live in api-impl, so the shape
 * is internal to this implementation.
 *
 * @hide
 */
public interface IMediaBrowserServiceCallbacks extends IInterface {
    String DESCRIPTOR = "android.service.media.IMediaBrowserServiceCallbacks";

    void onConnect(String root, MediaSession.Token session, Bundle extras) throws RemoteException;

    void onConnectFailed() throws RemoteException;

    void onLoadChildren(String mediaId, List<MediaBrowser.MediaItem> list) throws RemoteException;

    void onLoadChildrenWithOptions(String mediaId, List<MediaBrowser.MediaItem> list, Bundle options) throws RemoteException;


    abstract class Stub extends Binder implements IMediaBrowserServiceCallbacks {
        public Stub() {
            attachInterface(this, DESCRIPTOR);
        }

        public static IMediaBrowserServiceCallbacks asInterface(IBinder obj) {
            if (obj == null) {
                return null;
            }
            if (obj instanceof IMediaBrowserServiceCallbacks) {
                return (IMediaBrowserServiceCallbacks) obj;
            }
            IInterface local = obj.queryLocalInterface(DESCRIPTOR);
            if (local instanceof IMediaBrowserServiceCallbacks) {
                return (IMediaBrowserServiceCallbacks) local;
            }
            return null;
        }

        @Override
        public IBinder asBinder() {
            return this;
        }
    }
}
