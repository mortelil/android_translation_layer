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

package android.os;

// Unified lifecycle-foundation merge (lane A, reuse-ledger R06): the PR #31
// feat/remote-services baa9b489005995efed6d18d199d22683483a5258 surface —
// transaction constants and the linkToDeath defaults with the same public
// semantics the base Binder overrides implemented — supersedes the base
// interface. AOSP provenance notice kept.
public interface IBinder {

	int FIRST_CALL_TRANSACTION = 1;
	int LAST_CALL_TRANSACTION = 0x00ffffff;
	int INTERFACE_TRANSACTION = 0x5f4e5446;
	int FLAG_ONEWAY = 1;
	public interface DeathRecipient {
		void binderDied();
	}
	default boolean pingBinder() { return true; }
	default boolean isBinderAlive() { return true; }
	default void linkToDeath(DeathRecipient recipient, int flags) throws RemoteException {}
	default boolean unlinkToDeath(DeathRecipient recipient, int flags) { return true; }

	public IInterface queryLocalInterface(String descriptor);

	public boolean transact(int code, Parcel data, Parcel reply, int flags);

	public String getInterfaceDescriptor();
}
