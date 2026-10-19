package android.graphics;

/** Bitmap-backed paint source. Coordinates are bitmap pixels, not density-scaled units. */
public class BitmapShader extends Shader {
    final Bitmap bitmap;
    final TileMode tileX, tileY;
    private Matrix localMatrix = new Matrix();

    public BitmapShader(Bitmap bitmap, TileMode tileX, TileMode tileY) {
        if (bitmap == null) throw new IllegalArgumentException("Bitmap must be non-null");
        if (tileX == null || tileY == null) throw new NullPointerException("tile mode");
        this.bitmap = bitmap;
        this.tileX = tileX;
        this.tileY = tileY;
    }

    @Override public void setLocalMatrix(Matrix matrix) {
        localMatrix.set(matrix);
    }

    // Read at draw time: mutable bitmaps and matrix replacement must remain visible.
    private long texture() { return bitmap.getTexture(); }
    private float[] matrixValues() {
        float[] values = new float[9];
        localMatrix.getValues(values);
        return values;
    }
    private int tileMode() { return tileX == tileY ? tileX.ordinal() : -1; }
}
