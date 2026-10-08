// SPDX-License-Identifier: GPL-3.0-only
package android.view.textservice;

public abstract class SpellCheckerSession {
	public interface SpellCheckerSessionListener {
		void onGetSuggestions(SuggestionsInfo[] results);
		void onGetSentenceSuggestions(SentenceSuggestionsInfo[] results);
	}
	public abstract void close();
	public abstract void cancel();
	public abstract void getSentenceSuggestions(TextInfo[] textInfos, int suggestionsLimit);
	public abstract void getSuggestions(TextInfo[] textInfos, int suggestionsLimit, boolean sequentialWords);
}
