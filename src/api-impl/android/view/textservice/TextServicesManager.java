package android.view.textservice;

public class TextServicesManager {
	public boolean isSpellCheckerEnabled() { return false; }
	public SpellCheckerSession newSpellCheckerSession(android.os.Bundle bundle, java.util.Locale locale,
	                                                  SpellCheckerSession.SpellCheckerSessionListener listener, boolean referToSettings) {
		// No spell-checker service is installed in ATL. Android returns null in this case.
		return null;
	}
}
