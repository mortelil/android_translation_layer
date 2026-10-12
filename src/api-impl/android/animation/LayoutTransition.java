/*
 * Copyright (C) 2010 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.animation;

import android.view.View;
import android.view.ViewGroup;
import java.util.ArrayList;

public class LayoutTransition {
	public static final int CHANGE_APPEARING = 0;
	public static final int CHANGE_DISAPPEARING = 1;
	public static final int APPEARING = 2;
	public static final int DISAPPEARING = 3;
	public static final int CHANGING = 4;

	private ArrayList<TransitionListener> mListeners;

	public void enableTransitionType(int transitionType) {}

	public void setStartDelay(int transitionType, long startDelay) {}

	public void setAnimator(int transitionType, Animator animator) {}

	public void setDuration(long duration) {}

	public Animator getAnimator(int transitionType) {
		return new ObjectAnimator();
	}

	public void setDuration(int transitionType, long duration) {}

	public void setInterpolator(int transitionType, TimeInterpolator interpolator) {}

	public void disableTransitionType(int transitionType) {}

	public void addTransitionListener(TransitionListener listener) {
		if (mListeners == null) {
			mListeners = new ArrayList<TransitionListener>();
		}
		mListeners.add(listener);
	}

	public void removeTransitionListener(TransitionListener listener) {
		if (mListeners == null) {
			return;
		}
		mListeners.remove(listener);
	}

	/**
	 * This interface is used for listening to starting and ending events for transitions.
	 * ATL's layout transitions are currently no-ops, so registered listeners are retained
	 * for bookkeeping but no events are ever dispatched.
	 */
	public interface TransitionListener {
		/**
		 * This event is sent to listeners when any type of transition animation begins.
		 *
		 * @param transition The LayoutTransition sending out the event.
		 * @param container The ViewGroup on which the transition is playing.
		 * @param view The View object being affected by the transition animation.
		 * @param transitionType The type of transition that is beginning, one of
		 * {@link LayoutTransition#APPEARING}, {@link LayoutTransition#DISAPPEARING},
		 * {@link LayoutTransition#CHANGE_APPEARING} or {@link LayoutTransition#CHANGE_DISAPPEARING}.
		 */
		public void startTransition(LayoutTransition transition, ViewGroup container,
				View view, int transitionType);

		/**
		 * This event is sent to listeners when any type of transition animation ends.
		 *
		 * @param transition The LayoutTransition sending out the event.
		 * @param container The ViewGroup on which the transition is playing.
		 * @param view The View object being affected by the transition animation.
		 * @param transitionType The type of transition that is ending, one of
		 * {@link LayoutTransition#APPEARING}, {@link LayoutTransition#DISAPPEARING},
		 * {@link LayoutTransition#CHANGE_APPEARING} or {@link LayoutTransition#CHANGE_DISAPPEARING}.
		 */
		public void endTransition(LayoutTransition transition, ViewGroup container,
				View view, int transitionType);
	}
}