// SPDX-License-Identifier: GPL-3.0-only
package android.nfc.tech;

import android.nfc.Tag;
import java.io.Closeable;
import java.io.IOException;

public interface TagTechnology extends Closeable {
	Tag getTag();
	void connect() throws IOException;
	boolean isConnected();
	void close() throws IOException;
}
