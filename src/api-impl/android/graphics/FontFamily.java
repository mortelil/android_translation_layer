// SPDX-License-Identifier: GPL-3.0-only
package android.graphics;

import android.atl.ATLFontFiles;
import android.content.res.AssetManager;
import android.graphics.fonts.FontVariationAxis;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/** Legacy family builder. Native font validation precedes successful registration. */
public class FontFamily {
    static final class Entry {
        final String path;
        final int ttcIndex, weight, italic;
        final FontVariationAxis[] axes;
        final boolean temporary;
        Entry(String path, int index, int weight, int italic, FontVariationAxis[] axes, boolean temporary) {
            this.path = path; this.ttcIndex = index; this.weight = weight; this.italic = italic;
            this.axes = axes == null ? null : axes.clone(); this.temporary = temporary;
        }
    }
    private final List<Entry> fonts = new ArrayList<>();
    private boolean closed;
    private boolean frozen;

    private void requireOpen() {
        if (closed) throw new IllegalStateException("FontFamily builder is already closed");
    }
    private boolean register(String path, int index, int weight, int italic,
                             FontVariationAxis[] axes, boolean temporary) {
        if (index < 0 || (weight != -1 && (weight < 1 || weight > 1000)) ||
            italic < -1 || italic > 1 || !Typeface.isValidFont(path, index)) {
            if (temporary) new File(path).delete();
            return false;
        }
        fonts.add(new Entry(path, index, weight, italic, axes, temporary));
        return true;
    }
    // AOSP android-5.1.1_r1: the second argument is weight, not a TTC index.
    public synchronized boolean addFontWeightStyle(String path, int weight, boolean italic) {
        requireOpen();
        return path != null && register(path, 0, weight, italic ? 1 : 0, null, false);
    }
    public synchronized boolean addFontFromAssetManager(AssetManager mgr, String path, int cookie,
            boolean isAsset, int index, int weight, int italic, FontVariationAxis[] axes) {
        requireOpen();
        if (mgr == null || path == null) return false;
        try {
            InputStream in = isAsset ? mgr.open(path, AssetManager.ACCESS_STREAMING)
                                     : mgr.openNonAsset(cookie, path, AssetManager.ACCESS_STREAMING);
            return register(ATLFontFiles.materialize(in), index, weight, italic, axes, true);
        } catch (IOException e) { return false; }
    }
    public synchronized boolean addFontFromBuffer(ByteBuffer buffer, int index,
            FontVariationAxis[] axes, int weight, int italic) {
        requireOpen();
        if (buffer == null) return false;
        try {
            return register(ATLFontFiles.materialize(buffer), index, weight, italic, axes, true);
        } catch (IOException e) { return false; }
    }
    public synchronized boolean freeze() {
        requireOpen();
        closed = true;
        frozen = !fonts.isEmpty();
        return frozen;
    }
    public synchronized void abortCreation() {
        requireOpen();
        closed = true;
        for (Entry font : fonts) if (font.temporary) new File(font.path).delete();
        fonts.clear();
    }
    synchronized int getFontCount() {
        if (!frozen) throw new IllegalStateException("FontFamily is not frozen");
        return fonts.size();
    }
    synchronized Entry getFont(int index) {
        if (!frozen) throw new IllegalStateException("FontFamily is not frozen");
        return fonts.get(index);
    }
}
