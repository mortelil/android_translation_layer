// SPDX-License-Identifier: GPL-3.0-only
import android.content.Context;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.EditText;

public class TestEditText {
	static class Field extends EditText {
		Field(Context context) { super(context, null, 0); }
		void nativeInput(String value) { native_setText(widget, value); }
		String nativeValue() { return native_getText(widget); }
	}
	public static void run(Context context) {
		final Field field = new Field(context);
		if (field.getInputType() != 1) throw new AssertionError("default editable input type");
		final StringBuilder events = new StringBuilder();
		TextWatcher watcher = new TextWatcher() {
			String previous;
			public void beforeTextChanged(CharSequence s, int start, int count, int after) {
				previous = s.toString();
				if (!field.getText().toString().equals(previous)) throw new AssertionError("before must see old text");
				events.append('b');
			}
			public void onTextChanged(CharSequence s, int start, int before, int count) {
				if (previous == null) throw new AssertionError("missing before callback");
				String expected = previous.substring(0, start) + s.subSequence(start, start + count)
				                + previous.substring(start + before);
				if (!expected.equals(s.toString())) throw new AssertionError("incorrect UTF-16 change range");
				events.append('o');
			}
			public void afterTextChanged(Editable s) {
				if (s != field.getText()) throw new AssertionError("Editable must be live");
				events.append('a');
			}
		};
		field.addTextChangedListener(watcher);
		field.setText("test@example.invalid");
		if (!events.toString().equals("boa")) throw new AssertionError("watcher order " + events);
		for (String value : new String[] {"æ😀abc", "æ😀aXc", "æ😀", "", "secret-not-a-password"}) {
			events.setLength(0);
			field.nativeInput(value);
			if (!field.getText().toString().equals(value) || !field.nativeValue().equals(value))
				throw new AssertionError("native/Java text mismatch");
			if (!events.toString().equals("boa")) throw new AssertionError("native watcher order " + events);
		}
		Editable live = field.getText();
		live.replace(0, live.length(), "updated");
		if (!field.nativeValue().equals("updated")) throw new AssertionError("Editable write did not reach GTK");
		field.removeTextChangedListener(watcher);
		events.setLength(0);
		field.nativeInput("removed");
		if (events.length() != 0) throw new AssertionError("removed watcher still called");
		field.setText("æ😀abc");
		field.setSelection(1, 3);
		if (field.getSelectionStart() != 1 || field.getSelectionEnd() != 3)
			throw new AssertionError("UTF-16 selection");
		field.setSelection(4);
		if (field.getSelectionStart() != 4 || field.getSelectionEnd() != 4)
			throw new AssertionError("UTF-16 cursor");
		field.setText("removed");
		field.setFilters(new android.text.InputFilter[] {new android.text.InputFilter.LengthFilter(7)});
		field.nativeInput("removed-too-long");
		if (!field.getText().toString().equals("removed") || !field.nativeValue().equals("removed"))
			throw new AssertionError("rejected native edit must restore GTK text");
		field.setInputType(0x81);
		if (field.getInputType() != 0x81 || !(field.getTransformationMethod() instanceof android.text.method.PasswordTransformationMethod))
			throw new AssertionError("password configuration");
		if (!field.getTransformationMethod().getTransformation(field.getText(), field).toString().equals("•••••••"))
			throw new AssertionError("password transformation");
		final int[] copyEvents = {0};
		field.addTextChangedListener(new TextWatcher() {
			public void beforeTextChanged(CharSequence s, int start, int before, int after) { copyEvents[0]++; }
			public void onTextChanged(CharSequence s, int start, int before, int count) {}
			public void afterTextChanged(Editable s) {}
		});
		Field other = new Field(context);
		other.setText(field.getText());
		other.getText().replace(other.getText().length(), other.getText().length(), "!");
		if (copyEvents[0] != 0) throw new AssertionError("Internal watcher copied to another field");
		field.setInputType(0x21);
		if (field.getTransformationMethod() != null) throw new AssertionError("email must clear password masking");
		System.out.println("PASS: EditText live buffer, native Unicode edits, before/on/after callbacks, listener removal and password configuration");
	}
}
