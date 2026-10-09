package android.text.method;

public interface KeyListener {
	default int getInputType() { return 0; /* TYPE_NULL */ }
	default boolean onKeyDown(android.view.View view, android.text.Editable text, int keyCode, android.view.KeyEvent event) { return false; }
	default boolean onKeyUp(android.view.View view, android.text.Editable text, int keyCode, android.view.KeyEvent event) { return false; }
	default boolean onKeyOther(android.view.View view, android.text.Editable text, android.view.KeyEvent event) { return false; }
	default void clearMetaKeyState(android.view.View view, android.text.Editable text, int states) {}
}
