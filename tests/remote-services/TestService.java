/* SPDX-License-Identifier: GPL-3.0-only */
package org.atl.remoteservices;
import android.app.*;
import android.content.*;
import android.os.*;
import java.io.*;

public class TestService extends Service {
	static int creates, binds, unbinds, starts, ordered, destroys;
	public void onCreate() { ++creates; }
	public void onDestroy() { ++destroys; }
	public int onStartCommand(Intent intent, int flags, int id) {
		++starts;
		return 2; /* Android START_NOT_STICKY */
	}
	public boolean onUnbind(Intent intent) {
		++unbinds;
		return true;
	}
	public IBinder onBind(Intent intent) {
		++binds;
		if ("null".equals(intent.getAction()))
			return null;
		return new Binder() {
			{
				attachInterface(null, "org.atl.remoteservices.TestService");
			}
			protected boolean onTransact(int code, Parcel data, Parcel reply,
			                             int flags) {
				switch (code) {
					case 10:
						reply.writeInt(ProviderChecks.run(TestService.this));
						return true;
					case 1:
						reply.writeInt(android.os.Process.myPid());
						reply.writeString(Application.getProcessName());
						reply.writeInt(Binder.getCallingPid());
						reply.writeInt(creates);
						reply.writeInt(binds);
						reply.writeInt(starts);
						reply.writeInt(unbinds);
						reply.writeInt(destroys);
						return true;
					case 2:
						IBinder callback = data.readStrongBinder();
						Parcel args = Parcel.obtain(), response = Parcel.obtain();
						args.writeStrongBinder(this);
						callback.transact(1, args, response, 0);
						reply.writeInt(response.readInt());
						reply.writeStrongBinder(callback);
						args.recycle();
						response.recycle();
						return true;
					case 3:
						try {
							ParcelFileDescriptor input = data.readFileDescriptor();
							FileInputStream stream =
							    new FileInputStream(input.getFileDescriptor());
							reply.writeInt(stream.read());
							input.close();
							File file = new File(getCacheDir(), "synthetic-ipc.txt");
							FileOutputStream out = new FileOutputStream(file);
							out.write(73);
							out.close();
							FileInputStream returnedFile = new FileInputStream(file);
							reply.writeFileDescriptor(returnedFile.getFD());
							returnedFile.close(); // Parcel must own its own duplicate.
							return true;
						} catch (IOException e) {
							throw new IllegalStateException(e);
						}
					case 4:
						int next = data.readInt();
						if (next != ordered + 1)
							throw new IllegalStateException("Out of order");
						ordered = next;
						return true;
					case 5:
						reply.writeInt(ordered);
						return true;
					case 6:
						throw new IllegalArgumentException("synthetic-error");
					case 7:
						System.exit(0); // Deliberately die without replying.
						return true;
					case 9:
						long identity = Binder.clearCallingIdentity();
						reply.writeInt(Binder.getCallingPid());
						Binder.restoreCallingIdentity(identity);
						reply.writeInt(Binder.getCallingPid());
						return true;
					default:
						return super.onTransact(code, data, reply, flags);
				}
			}
		};
	}
}
