package android.provider;

import android.content.ContentResolver;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import java.io.FileNotFoundException;

public class MediaStore {

	public static class Images {

		public static class Media {

			public static final Uri EXTERNAL_CONTENT_URI = Uri.parse("content://media/external/images/media");
			public static final Uri INTERNAL_CONTENT_URI = Uri.parse("content://media/internal/images/media");
		}

		public static class Thumbnails {
			private static final java.util.Map<String, java.util.List<android.os.CancellationSignal>> pending = new java.util.HashMap<>();
			public static void cancelThumbnailRequest(ContentResolver resolver, long id) { cancelThumbnailRequest(resolver, id, 0); }
			public static void cancelThumbnailRequest(ContentResolver resolver, long id, long group) {
				synchronized (pending) {
					java.util.List<android.os.CancellationSignal> requests = pending.get(id + ":" + group);
					if (requests != null)
						for (android.os.CancellationSignal request : requests)
							request.cancel();
				}
			}
			public static final int MINI_KIND = 1;
			public static final int FULL_SCREEN_KIND = 2;
			public static final int MICRO_KIND = 3;

			public static Bitmap getThumbnail(ContentResolver resolver, long id, int kind, BitmapFactory.Options options) throws FileNotFoundException {
				return getThumbnail(resolver, id, 0, kind, options);
			}

			public static Cursor queryMiniThumbnail(ContentResolver contentResolver, long id, int kind, String[] projection) {
				return null;
			}

			public static Bitmap getThumbnail(ContentResolver contentResolver, long imageId, long groupId, int kind, BitmapFactory.Options options) throws FileNotFoundException {
				String key = imageId + ":" + groupId;
				android.os.CancellationSignal cancellation = new android.os.CancellationSignal();
				synchronized (pending) {
					java.util.List<android.os.CancellationSignal> requests = pending.get(key);
					if (requests == null) {
						requests = new java.util.ArrayList<>();
						pending.put(key, requests);
					}
					requests.add(cancellation);
				}
				Uri uri = Media.EXTERNAL_CONTENT_URI.buildUpon().appendPath(String.valueOf(imageId)).build();
				try (java.io.InputStream stream = contentResolver.openInputStream(uri)) {
					Bitmap bitmap = BitmapFactory.decodeStream(stream, null, options);
					if (bitmap == null)
						return null;
					if (cancellation.isCanceled()) {
						bitmap.recycle();
						return null;
					}
					int bound;
					switch (kind) {
						case MINI_KIND:
							bound = 512;
							break;
						case MICRO_KIND: {
							int side = Math.min(bitmap.getWidth(), bitmap.getHeight());
							int x = (bitmap.getWidth() - side) / 2, y = (bitmap.getHeight() - side) / 2;
							Bitmap small = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888);
							new android.graphics.Canvas(small).drawBitmap(bitmap, new android.graphics.Rect(x, y, x + side, y + side), new android.graphics.Rect(0, 0, 96, 96), null);
							bitmap.recycle();
							if (cancellation.isCanceled()) {
								small.recycle();
								return null;
							}
							return small;
						}
						case FULL_SCREEN_KIND:
							return bitmap;
						default:
							bitmap.recycle();
							throw new IllegalArgumentException("Unknown thumbnail kind");
					}
					float scale = Math.min(1f, (float)bound / Math.max(bitmap.getWidth(), bitmap.getHeight()));
					if (scale == 1f)
						return bitmap;
					Bitmap small = Bitmap.createScaledBitmap(bitmap, Math.max(1, Math.round(bitmap.getWidth() * scale)), Math.max(1, Math.round(bitmap.getHeight() * scale)), true);
					bitmap.recycle();
					if (cancellation.isCanceled()) {
						small.recycle();
						return null;
					}
					return small;
				} catch (java.io.IOException e) {
					return null;
				} finally {
					synchronized (pending) {
						java.util.List<android.os.CancellationSignal> requests = pending.get(key);
						requests.remove(cancellation);
						if (requests.isEmpty())
							pending.remove(key);
					}
				}
			}
		}
	}

	public static class Video {

		public static class Media {

			public static final Uri EXTERNAL_CONTENT_URI = Uri.parse("content://media/external/video/media");
			public static final Uri INTERNAL_CONTENT_URI = Uri.parse("content://media/internal/video/media");
		}

		public static class Thumbnails {
			public static final Uri EXTERNAL_CONTENT_URI = Uri.parse("content://media/external/video/media");

			public static Bitmap getThumbnail(ContentResolver contentResolver, long videoId, int kind, BitmapFactory.Options options) throws java.io.IOException {
				Uri uri = Media.EXTERNAL_CONTENT_URI.buildUpon().appendPath(String.valueOf(videoId)).build();
				try (ParcelFileDescriptor fd = contentResolver.openFileDescriptor(uri, "r")) {
					return BitmapFactory.decodeFileDescriptor(fd.getFileDescriptor(), null, options);
				}
			}
		}
	}

	public static class Audio {

		public static class Media {

			public static final Uri EXTERNAL_CONTENT_URI = Uri.parse("content://media/external/audio/media");
		}

		public static class Artists {
			public static final Uri EXTERNAL_CONTENT_URI = Uri.parse("content://media/external/audio/artists");
		}

		public static class Albums {
			public static final Uri EXTERNAL_CONTENT_URI = Uri.parse("content://media/external/audio/albums");
		}

		public static class Genres {
			public static final Uri EXTERNAL_CONTENT_URI = Uri.parse("content://media/external/audio/genres");
		}
	}

	public static class Files {

		public static Uri getContentUri(String type) {
			return Uri.parse("content://media/" + type + "/file");
		}
	}
}
