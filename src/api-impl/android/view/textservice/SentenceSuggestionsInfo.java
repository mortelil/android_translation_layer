// SPDX-License-Identifier: GPL-3.0-only
package android.view.textservice;

public final class SentenceSuggestionsInfo {
	private final SuggestionsInfo[] infos;
	private final int[] offsets, lengths;
	public SentenceSuggestionsInfo(SuggestionsInfo[] infos, int[] offsets, int[] lengths) {
		if (infos == null || offsets == null || lengths == null)
			throw new NullPointerException();
		if (infos.length != offsets.length || infos.length != lengths.length)
			throw new IllegalArgumentException();
		this.infos = infos.clone();
		this.offsets = offsets.clone();
		this.lengths = lengths.clone();
	}
	public int getSuggestionsCount() { return infos.length; }
	public SuggestionsInfo getSuggestionsInfoAt(int i) { return i >= 0 && i < infos.length ? infos[i] : null; }
	public int getOffsetAt(int i) { return i >= 0 && i < infos.length ? offsets[i] : -1; }
	public int getLengthAt(int i) { return i >= 0 && i < infos.length ? lengths[i] : -1; }
}
