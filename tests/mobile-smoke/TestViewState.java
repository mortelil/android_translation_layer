// SPDX-License-Identifier: GPL-3.0-only
import android.content.Context;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.SparseArray;
import android.view.AbsSavedState;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

public final class TestViewState {
    public static class Wrapper extends View.BaseSavedState {
        final int value;
        Wrapper(Parcelable parent, int value) { super(parent); this.value = value; }
        Wrapper(Parcel in, ClassLoader loader) { super(in, loader); value = in.readInt(); }
        public void writeToParcel(Parcel out, int flags) { super.writeToParcel(out, flags); out.writeInt(value); }
        static ClassLoader receivedLoader;
        public static final Parcelable.ClassLoaderCreator<Wrapper> CREATOR = new Parcelable.ClassLoaderCreator<Wrapper>() {
            public Wrapper createFromParcel(Parcel in) { throw new AssertionError("loader overload not used"); }
            public Wrapper createFromParcel(Parcel in, ClassLoader loader) { receivedLoader = loader; return new Wrapper(in, loader); }
            public Wrapper[] newArray(int n) { return new Wrapper[n]; }
        };
    }
    static final class WrappedView extends View {
        WrappedView(Context context) { super(context); setId(123); }
        protected Parcelable onSaveInstanceState() { return new Wrapper(super.onSaveInstanceState(), 42); }
    }
    static class CountingView extends View {
        int saves, restores;
        CountingView(Context context, int id) { super(context); setId(id); }
        protected Parcelable onSaveInstanceState() { saves++; return super.onSaveInstanceState(); }
        protected void onRestoreInstanceState(Parcelable state) { restores++; super.onRestoreInstanceState(state); }
    }
    static final class SelfOnlyGroup extends ViewGroup {
        SelfOnlyGroup(Context context) { super(context); setId(456); }
        protected void dispatchSaveInstanceState(SparseArray<Parcelable> values) { dispatchFreezeSelfOnly(values); }
        protected void dispatchRestoreInstanceState(SparseArray<Parcelable> values) { dispatchThawSelfOnly(values); }
    }
    static void check(boolean value, String label) { if (!value) throw new AssertionError(label); }
    static Parcelable roundTrip(Parcelable value, ClassLoader loader) {
        Parcel parcel = Parcel.obtain();
        try { parcel.writeParcelable(value, 0); parcel.setDataPosition(0); return parcel.readParcelable(loader); }
        finally { parcel.recycle(); }
    }
    static void run(Context context) {
        SparseArray<Parcelable> saved = new SparseArray<>();
        new WrappedView(context).saveHierarchyState(saved);
        check(saved.get(123) instanceof Wrapper, "subclass must wrap non-null base state");
        ClassLoader loader = TestViewState.class.getClassLoader();
        Wrapper copy = (Wrapper)roundTrip(saved.get(123), loader);
        check(copy.value == 42 && copy.getSuperState() == AbsSavedState.EMPTY_STATE, "wrapper state round-trip");
        check(Wrapper.receivedLoader == loader, "class-loader delivery");
        Wrapper nested = (Wrapper)roundTrip(new Wrapper(copy, 9), loader);
        check(nested.value == 9 && ((Wrapper)nested.getSuperState()).value == 42, "nested superclass state");
        // Bootstrap types work with a null loader; anonymous EMPTY_STATE resolves
        // its inherited CREATOR and must return the singleton.
        check(roundTrip(AbsSavedState.EMPTY_STATE, null) == AbsSavedState.EMPTY_STATE, "empty-state identity");
        boolean refused = false;
        try { new Wrapper((Parcelable)null, 0); } catch (IllegalArgumentException expected) { refused = true; }
        check(refused, "null superclass must be rejected");
        TextView text = new TextView(context); text.setText("saved text");
        TextView.SavedState textState = (TextView.SavedState)roundTrip(text.onSaveInstanceState(), null);
        check("saved text".contentEquals(textState.text), "TextView must use its own CREATOR");
        ViewGroup group = new ViewGroup(context);
        CountingView child = new CountingView(context, 789);
        group.addView(child); saved.clear();
        child.setSaveEnabled(false); group.saveHierarchyState(saved);
        check(child.saves == 0 && saved.size() == 0, "disabled save must not call subclass");
        child.setSaveEnabled(true); child.setSaveFromParentEnabled(false); group.saveHierarchyState(saved);
        check(child.saves == 0, "parent-disabled child traversed");
        child.saveHierarchyState(saved); child.restoreHierarchyState(saved);
        check(child.saves == 1 && child.restores == 1, "direct save/restore bypasses parent flag");
        child.setSaveFromParentEnabled(true); group.saveHierarchyState(saved); group.restoreHierarchyState(saved);
        check(child.saves == 2 && child.restores == 2, "normal child traversal");
        group.removeView(child);
        SelfOnlyGroup self = new SelfOnlyGroup(context); self.addView(child);
        saved.clear(); self.saveHierarchyState(saved); self.restoreHierarchyState(saved);
        check(saved.size() == 1 && saved.get(456) == AbsSavedState.EMPTY_STATE, "self-only state");
        check(child.saves == 2 && child.restores == 2, "self-only must not traverse adapter children");
        System.out.println("PASS: view superclass state, nested parcels and loader-aware restoration");
    }
}
