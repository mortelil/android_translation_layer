// SPDX-License-Identifier: GPL-3.0-only
package android.atl;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;

/**
 * Turns the bytes an {@link android.content.res.AssetManager} or
 * {@link android.content.res.Resources} serves for a font resource into a real file.
 *
 * ATL's native typeface loader (api-impl-jni/graphics/android_graphics_Typeface.c) opens a font by
 * path, but an APK resource entry is at a nonzero offset inside the archive and may be compressed,
 * so neither the entry path ("res/foo.ttf", relative to the APK) nor its FileDescriptor is a
 * loadable path. Reading the entry's bytes and writing them to a temporary file is the same
 * conversion AOSP does in TypefaceCompatUtil.getTempFile/copyToFile
 * (frameworks-base 94b4c163 core/java/androidx-less graphics support) for exactly this reason.
 */
public final class ATLFontFiles {
	private ATLFontFiles() {}

	/** Copies the remaining bytes of IN into a temporary font file and returns its path. Takes ownership of IN. */
	public static String materialize(InputStream in) throws IOException {
		File file = File.createTempFile("atl-font", ".ttf");
		try (InputStream source = in; OutputStream out = new FileOutputStream(file)) {
			byte[] buffer = new byte[64 * 1024];
			int count;
			while ((count = source.read(buffer)) > 0) out.write(buffer, 0, count);
		} catch (IOException e) {
			file.delete();
			throw e;
		}
		file.deleteOnExit();
		return file.getAbsolutePath();
	}

	/** Copies the remaining bytes of BUFFER into a temporary font file and returns its path. */
	public static String materialize(ByteBuffer buffer) throws IOException {
		ByteBuffer source = buffer.duplicate();
		byte[] bytes = new byte[source.remaining()];
		source.get(bytes);
		return materialize(new ByteArrayInputStream(bytes));
	}
}
