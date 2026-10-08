// SPDX-License-Identifier: GPL-3.0-only
import android.graphics.Bitmap;
import android.net.wifi.WifiManager;
import java.nio.*;

public class TestBitmap {
	static void check(boolean value) {
		if (!value)
			throw new AssertionError();
	}
	public static void main(String[] args) {
		Bitmap b = Bitmap.createBitmap(2, 1, Bitmap.Config.ARGB_8888);
		ByteBuffer bytes = ByteBuffer.wrap(new byte[] {9, (byte)255, 0, 0, (byte)255, 0, (byte)255, 0, (byte)255});
		bytes.position(1);
		b.copyPixelsFromBuffer(bytes);
		check(bytes.position() == 9);
		int[] pixels = new int[2];
		b.getPixels(pixels, 0, 2, 0, 0, 2, 1);
		check(pixels[0] == 0xffff0000 && pixels[1] == 0xff00ff00);
		ByteBuffer packed = ByteBuffer.allocate(8).order(ByteOrder.nativeOrder());
		packed.put(new byte[] {0, 0, (byte)255, (byte)255, (byte)255, (byte)255, (byte)255, (byte)255}).rewind();
		IntBuffer ints = packed.asIntBuffer();
		b.copyPixelsFromBuffer(ints);
		check(ints.position() == 2);
		b.getPixels(pixels, 0, 2, 0, 0, 2, 1);
		check(pixels[0] == 0xff0000ff && pixels[1] == 0xffffffff);
		ShortBuffer shorts = packed.asShortBuffer();
		b.copyPixelsFromBuffer(shorts);
		check(shorts.position() == 4);
		boolean rejected = false;
		try {
			b.copyPixelsFromBuffer(ByteBuffer.allocate(7));
		} catch (RuntimeException e) {
			rejected = true;
		}
		check(rejected);
		b.recycle();
		rejected = false;
		try {
			b.copyPixelsFromBuffer(bytes);
		} catch (IllegalStateException e) {
			rejected = true;
		}
		check(rejected);
		WifiManager.MulticastLock lock = new WifiManager().createMulticastLock("test");
		check(!lock.isHeld());
		lock.acquire();
		lock.acquire();
		lock.release();
		check(lock.isHeld());
		lock.release();
		check(!lock.isHeld());
		rejected = false;
		try {
			lock.release();
		} catch (RuntimeException e) {
			rejected = true;
		}
		check(rejected);
		lock.setReferenceCounted(false);
		lock.acquire();
		lock.acquire();
		lock.release();
		check(!lock.isHeld());
		System.out.println("PASS: RGBA pixel content, buffer positions, bounds, recycled bitmap and multicast lock");
	}
}
