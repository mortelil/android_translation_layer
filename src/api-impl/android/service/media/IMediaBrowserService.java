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

import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.IInterface;
import android.os.RemoteException;
import android.os.ResultReceiver;

/**
 * The service-side half of the AOSP media browse protocol. ATL implements the
 * AOSP interface with in-process binder calls only.
 *
 * @hide
 */
public interface IMediaBrowserService extends IInterface {
    String DESCRIPTOR = "android.service.media.IMediaBrowserService";

    void connect(String pkg, Bundle rootHints, IMediaBrowserServiceCallbacks callbacks) throws RemoteException;

    void disconnect(IMediaBrowserServiceCallbacks callbacks) throws RemoteException;

    void addSubscriptionDeprecated(String uri, IMediaBrowserServiceCallbacks callbacks) throws RemoteException;

    void removeSubscriptionDeprecated(String uri, IMediaBrowserServiceCallbacks callbacks) throws RemoteException;

    void getMediaItem(String uri, ResultReceiver cb, IMediaBrowserServiceCallbacks callbacks) throws RemoteException;

    void addSubscription(String uri, IBinder token, Bundle options,
            IMediaBrowserServiceCallbacks callbacks) throws RemoteException;

    void removeSubscription(String uri, IBinder token, IMediaBrowserServiceCallbacks callbacks) throws RemoteException;

    abstract class Stub extends Binder implements IMediaBrowserService {
        public Stub() {
            attachInterface(this, DESCRIPTOR);
        }

        public static IMediaBrowserService asInterface(IBinder obj) {
            if (obj == null) {
                return null;
            }
            if (obj instanceof IMediaBrowserService) {
                return (IMediaBrowserService) obj;
            }
            IInterface local = obj.queryLocalInterface(DESCRIPTOR);
            if (local instanceof IMediaBrowserService) {
                return (IMediaBrowserService) local;
            }
            return null;
        }

        @Override
        public IBinder asBinder() {
            return this;
        }
    }
}
