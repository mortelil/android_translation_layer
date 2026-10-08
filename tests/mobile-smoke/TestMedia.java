// SPDX-License-Identifier: GPL-3.0-only
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.provider.MediaStore;
import java.io.InputStream;

public class TestMedia {
	public static void run(Context context) throws Exception {
		Uri collection = Uri.parse("content://media/external/images/media");
		long id;
		try (Cursor cursor = context.getContentResolver().query(collection,
		                                                        new String[] {"_id", "width", "height", "latitude", "longitude"}, null, null, null)) {
			if (cursor.getCount() != 1 || !cursor.moveToFirst())
				throw new AssertionError("Expected one visible synthetic image");
			id = cursor.getLong(0);
			if (cursor.getInt(1) != 2 || cursor.getInt(2) != 2 || !cursor.isNull(3) || !cursor.isNull(4))
				throw new AssertionError("Image dimensions or unknown coordinates");
		}
		try (InputStream stream = context.getContentResolver().openInputStream(Uri.withAppendedPath(collection, Long.toString(id)))) {
			if (stream.read() != 137)
				throw new AssertionError("Expected PNG data");
		}
		Bitmap preview = MediaStore.Images.Thumbnails.getThumbnail(context.getContentResolver(), id,
		                                                           MediaStore.Images.Thumbnails.MINI_KIND, null);
		if (preview == null || preview.getWidth() < 1 || preview.getHeight() < 1)
			throw new AssertionError("No thumbnail");
		System.out.println("PASS: synthetic media index, .nomedia, nullable GPS, read and thumbnail");
	}
}
