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
		Uri audio = Uri.parse("content://media/external/audio/media");
		long audioId;
		try (Cursor c = context.getContentResolver().query(audio,
		    new String[]{"_id", "title", "artist", "album", "duration", "is_music", "artist_id", "album_id"}, null, null, null)) {
			if (c.getCount() != 1 || !c.moveToFirst()) throw new AssertionError("Expected one valid visible audio file");
			audioId = c.getLong(0);
			if (!"Test Song".equals(c.getString(1)) || !"Test Artist".equals(c.getString(2)) || !"Test Album".equals(c.getString(3)))
				throw new AssertionError("Audio tags were not extracted");
			if (c.getLong(4) != 1000 || c.getInt(5) != 1 || c.getLong(6) <= 0 || c.getLong(7) <= 0)
				throw new AssertionError("Audio duration/category/group IDs");
		}
		try (Cursor c = context.getContentResolver().query(Uri.withAppendedPath(collection, Long.toString(audioId)), new String[]{"_id"}, null, null, null)) {
			if (c.getCount() != 0) throw new AssertionError("Audio ID leaked into image collection");
		}
		try (InputStream in = context.getContentResolver().openInputStream(Uri.withAppendedPath(audio, Long.toString(audioId)))) {
			if (in.read() != 'R') throw new AssertionError("Audio URI did not resolve to the WAV file");
		}
		Uri genres = MediaStore.Audio.Genres.getContentUriForAudioId("external", (int)audioId);
		if (!genres.toString().equals("content://media/external/audio/media/" + audioId + "/genres"))
			throw new AssertionError("Genre URI contract");
		try (Cursor c = context.getContentResolver().query(genres, new String[]{"_id", "name"}, null, null, null)) {
			if (c.getCount() != 1 || !c.moveToFirst() || !"Test Genre".equals(c.getString(1)))
				throw new AssertionError("Per-track genre metadata");
		}
		System.out.println("PASS: audio tags, duration, hidden/corrupt/symlink filtering and URI type isolation");
		System.out.println("PASS: synthetic media index, .nomedia, nullable GPS, read and thumbnail");
	}
}
