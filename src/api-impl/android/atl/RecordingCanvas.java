// SPDX-License-Identifier: GPL-3.0-only
package android.atl;

import android.view.RenderNode;
import java.util.ArrayList;
import java.util.List;

/** Retains operations until child RenderNodes have their current content.
 * Ordinary drawing is frozen into immutable GSK nodes at recording time;
 * state operations are replayed before GTK is allowed to optimize the tree.
 */
public final class RecordingCanvas extends GskCanvas {
    private static final int DRAW = 0, CHILD = 1, SAVE = 2, RESTORE = 3,
        TRANSLATE = 4, ROTATE = 5, SCALE = 6, CONCAT = 7, CLIP_RECT = 8,
        CLIP_PATH = 9, CLIP_OUT_PATH = 10, OPACITY = 11, POP = 12;
    private static final class Command {
        final int op;
        final float a, b, c, d;
        final long resource;
        Command(int op, float a, float b, float c, float d, long resource) {
            this.op = op; this.a = a; this.b = b; this.c = c; this.d = d;
            this.resource = resource;
        }
    }
    private final List<Command> commands = new ArrayList<>();
    private final List<RenderNode> children = new ArrayList<>();
    private boolean sealed, released;
    private int pushes;

    public RecordingCanvas() { super(nativeCreateSnapshot()); }
    private void checkRecording() {
        if (sealed || released) throw new IllegalStateException("Display list is no longer recording");
    }
    // Direct GTK draws (Layout, Drawable and native widgets) also use snapshot.
    // Freeze draw-only segments before recording state or a dynamic child.
    private void flush() {
        checkRecording();
        long node = nativeFinishSnapshot(snapshot);
        snapshot = nativeCreateSnapshot();
        if (node != 0) commands.add(new Command(DRAW, 0, 0, 0, 0, node));
    }
    private void add(int op, float a, float b, float c, float d, long resource) {
        flush();
        commands.add(new Command(op, a, b, c, d, resource));
    }
    @Override public void drawRenderNode(RenderNode child) {
        checkRecording();
        if (child == null) throw new NullPointerException("child");
        flush();
        commands.add(new Command(CHILD, 0, 0, 0, 0, 0));
        children.add(child);
    }
    public List<RenderNode> getChildren() { return new ArrayList<>(children); }
    public void seal() {
        checkRecording();
        restoreToCount(1);
        if (pushes != 0) native_pop(0, pushes);
        flush();
        nativeDropSnapshot(snapshot); snapshot = 0;
        sealed = true;
    }
    public long replay(long[] childNodes) {
        if (!sealed || released) throw new IllegalStateException("Display list cannot be replayed");
        if (childNodes.length != children.size()) throw new IllegalArgumentException("Child count mismatch");
        int childIndex = 0;
        long target = nativeCreateSnapshot();
        try {
            for (Command cmd : commands) {
                switch (cmd.op) {
                    case DRAW: super.native_drawRenderNode(target, cmd.resource); break;
                    case CHILD: super.native_drawRenderNode(target, childNodes[childIndex++]); break;
                    case SAVE: super.native_save(target); break;
                    case RESTORE: super.native_restore(target); break;
                    case TRANSLATE: super.native_translate(target, cmd.a, cmd.b); break;
                    case ROTATE: super.native_rotate(target, cmd.a); break;
                    case SCALE: super.native_scale(target, cmd.a, cmd.b); break;
                    case CONCAT: super.native_concat(target, cmd.resource); break;
                    case CLIP_RECT: super.native_clipRect(target, cmd.a, cmd.b, cmd.c, cmd.d); break;
                    case CLIP_PATH: super.native_clipPath(target, cmd.resource); break;
                    case CLIP_OUT_PATH: super.native_clipOutPath(target, cmd.resource, (int)cmd.a); break;
                    case OPACITY: super.native_pushOpacity(target, cmd.a); break;
                    case POP: super.native_pop(target, (int)cmd.a); break;
                    default: throw new AssertionError("Unknown display-list command");
                }
            }
            long node = nativeFinishSnapshot(target);
            target = 0;
            return node;
        } finally {
            if (target != 0) nativeDropSnapshot(target);
        }
    }
    public void release() {
        if (released) return;
        released = true;
        for (Command cmd : commands) {
            if (cmd.op == DRAW) nativeUnrefNode(cmd.resource);
            else if (cmd.op == CLIP_PATH || cmd.op == CLIP_OUT_PATH) nativeUnrefPath(cmd.resource);
            else if (cmd.op == CONCAT) nativeFreeMatrix(cmd.resource);
        }
        commands.clear(); children.clear();
        if (snapshot != 0) { nativeDropSnapshot(snapshot); snapshot = 0; }
    }
    @Override protected void native_save(long ignored) { add(SAVE, 0, 0, 0, 0, 0); }
    @Override protected void native_restore(long ignored) { add(RESTORE, 0, 0, 0, 0, 0); }
    @Override protected void native_translate(long ignored, float x, float y) { add(TRANSLATE, x, y, 0, 0, 0); }
    @Override protected void native_rotate(long ignored, float degrees) { add(ROTATE, degrees, 0, 0, 0, 0); }
    @Override protected void native_scale(long ignored, float x, float y) { add(SCALE, x, y, 0, 0, 0); }
    @Override protected void native_concat(long ignored, long matrix) {
        checkRecording(); add(CONCAT, 0, 0, 0, 0, nativeCopyMatrix(matrix));
    }
    @Override protected void native_clipRect(long ignored, float l, float t, float r, float b) {
        add(CLIP_RECT, l, t, r, b, 0); pushes++;
    }
    @Override protected void native_clipPath(long ignored, long path) {
        checkRecording(); add(CLIP_PATH, 0, 0, 0, 0, nativeRefPath(path)); pushes++;
    }
    @Override protected void native_clipOutPath(long ignored, long path, int fillType) {
        checkRecording(); add(CLIP_OUT_PATH, fillType, 0, 0, 0, nativeRefPath(path)); pushes++;
    }
    @Override protected void native_pushOpacity(long ignored, float alpha) {
        add(OPACITY, alpha, 0, 0, 0, 0); pushes++;
    }
    @Override protected void native_pop(long ignored, int count) {
        add(POP, count, 0, 0, 0, 0); pushes -= count;
    }
    private static native long nativeCreateSnapshot();
    private static native long nativeFinishSnapshot(long snapshot);
    private static native void nativeDropSnapshot(long snapshot);
    private static native void nativeUnrefNode(long node);
    private static native long nativeRefPath(long path);
    private static native void nativeUnrefPath(long path);
    private static native long nativeCopyMatrix(long matrix);
    private static native void nativeFreeMatrix(long matrix);
}
