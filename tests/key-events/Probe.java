// SPDX-License-Identifier: Apache-2.0
package org.atl.keyevents;
import android.app.Activity;
import android.os.Bundle;
import android.view.*;
public class Probe extends Activity {
    static void check(boolean ok) { if (!ok) throw new AssertionError("key event contract"); }
    public void onCreate(Bundle b) {
        super.onCreate(b);
        try {
            check(android.text.Editable.class.getMethod("getSpans",int.class,int.class,Class.class).getDeclaringClass()==android.text.Spanned.class);
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
        KeyCharacterMap map = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD);
        check(map.getEvents(new char[0]).length == 0);
        check(map.getEvents("雪".toCharArray()) == null);
        check(map.getEvents("a雪".toCharArray()) == null);
        try { map.getEvents(null); throw new AssertionError("null accepted"); } catch (IllegalArgumentException expected) {}
        String input = "aZ09 !@[]{}\n\t";
        StringBuilder text = new StringBuilder();
        int pressed=0;
        for (KeyEvent event : map.getEvents(input.toCharArray())) {
            android.os.Parcel parcel=android.os.Parcel.obtain();
            event.writeToParcel(parcel,0); parcel.setDataPosition(0);
            KeyEvent copy=KeyEvent.CREATOR.createFromParcel(parcel); parcel.recycle();
            check(copy.getAction()==event.getAction() && copy.getKeyCode()==event.getKeyCode());
            check(copy.getUnicodeChar()==event.getUnicodeChar() && copy.getMetaState()==event.getMetaState());
            check(event.getDeviceId()==KeyCharacterMap.VIRTUAL_KEYBOARD);
            check(event.getSource()==InputDevice.SOURCE_KEYBOARD);
            pressed += event.getAction()==KeyEvent.ACTION_DOWN?1:-1;
            check(pressed>=0);
            if (event.getAction()==KeyEvent.ACTION_DOWN && event.getKeyCode()!=KeyEvent.KEYCODE_SHIFT_LEFT) {
                check(event.getUnicodeChar()==map.get(event.getKeyCode(),event.getMetaState()));
                text.append((char)event.getUnicodeChar());
            }
        }
        check(pressed==0 && text.toString().equals(input));
        check(map.get(KeyEvent.KEYCODE_A, KeyEvent.META_SHIFT_ON|KeyEvent.META_CAPS_LOCK_ON)=='a');
        android.text.Editable editable = new android.text.SpannableStringBuilder("x");
        android.text.Selection.setSelection(editable,1);
        android.text.method.TextKeyListener listener=android.text.method.TextKeyListener.getInstance();
        for(KeyEvent e:map.getEvents("Az!".toCharArray())) {
            if(e.getAction()==KeyEvent.ACTION_DOWN) listener.onKeyDown(null,editable,e.getKeyCode(),e);
            else listener.onKeyUp(null,editable,e.getKeyCode(),e);
        }
        check(editable.toString().equals("xAz!"));
        check(android.text.method.TextKeyListener.getMetaState(editable)==0);
        listener.onKeyDown(null,editable,KeyEvent.KEYCODE_DEL,new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DEL));
        check(editable.toString().equals("xAz"));
        android.text.method.TextKeyListener.clear(editable); check(editable.length()==0);
        System.out.println("KEY_EVENTS_PASS"); System.exit(0);
    }
}
