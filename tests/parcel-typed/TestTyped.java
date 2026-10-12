// SPDX-License-Identifier: GPL-3.0-only
import android.os.Parcel;
import android.os.Parcelable;
import java.util.ArrayList;
import java.util.Arrays;

public class TestTyped {
    static class Item implements Parcelable {
        final int value;
        Item(int v) { value = v; }
        public int describeContents() { return 0; }
        public void writeToParcel(Parcel p, int flags) { p.writeInt(value); }
    }
    static final Parcelable.Creator<Parcelable> CREATOR = new Parcelable.Creator<Parcelable>() {
        public Parcelable createFromParcel(Parcel p) { return new Item(p.readInt()); }
        public Parcelable[] newArray(int n) { return new Item[n]; }
    };
    static void check(boolean v, String message) { if (!v) throw new AssertionError(message); }
    static Parcel encoded() {
        Parcel p = Parcel.obtain();
        p.writeInt(3);
        // SafeParcel uses a nonzero byte length instead of the ordinary 1.
        p.writeInt(4); p.writeInt(37);
        p.writeInt(0);
        p.writeInt(4); p.writeInt(81);
        p.writeInt(12345);
        p.setDataPosition(0);
        return p;
    }
    static void values(Object[] a) {
        check(a.length == 3 && a[0] instanceof Item && ((Item)a[0]).value == 37
            && a[1] == null && a[2] instanceof Item && ((Item)a[2]).value == 81, "Typed values lost");
    }
    public static void main(String[] args) throws Exception {
        Parcel p = encoded(); values(p.createTypedArray(CREATOR)); check(p.readInt() == 12345, "Array position");
        p = encoded(); Parcelable[] a = new Parcelable[3]; p.readTypedArray(a, CREATOR); values(a); check(p.readInt() == 12345, "Read-array position");
        p = encoded(); values(p.createTypedArrayList(CREATOR).toArray()); check(p.readInt() == 12345, "List position");
        p = encoded(); ArrayList<Parcelable> list = new ArrayList<Parcelable>(); list.add(new Item(0));
        p.readTypedList(list, CREATOR); values(list.toArray()); check(p.readInt() == 12345, "Read-list position");
        for (boolean asList : new boolean[] {false, true}) {
            p = Parcel.obtain();
            if (asList) p.writeTypedList(Arrays.<Parcelable>asList(new Item(37), null, new Item(81)));
            else p.writeTypedArray(new Parcelable[] {new Item(37), null, new Item(81)}, 0);
            check(p.dataPosition() == 24, "Wrong marker width");
            p.setDataPosition(0);
            check(p.readInt() == 3 && p.readInt() == 1 && p.readInt() == 37 && p.readInt() == 0
                && p.readInt() == 1 && p.readInt() == 81, "Wrong typed encoding");
            p.setDataPosition(0); values(p.createTypedArray(CREATOR));
        }
        p = Parcel.obtain(); p.writeTypedArray(null, 0); p.setDataPosition(0);
        check(p.createTypedArray(CREATOR) == null, "Null array");
        p = Parcel.obtain(); p.writeTypedList(null); p.setDataPosition(0);
        check(p.createTypedArrayList(CREATOR) == null, "Null list");
        p = Parcel.obtain(); p.writeTypedArray(new Parcelable[0], 0); p.setDataPosition(0);
        check(p.createTypedArray(CREATOR).length == 0, "Empty array");
        System.out.println("PARCEL_TYPED_PASS four-byte markers, non-one markers, nulls, alignment, writers");
    }
}
