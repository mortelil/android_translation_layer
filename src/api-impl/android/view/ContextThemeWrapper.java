/*
 * Copyright (C) 2006 The Android Open Source Project
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

package android.view;

import android.annotation.UnsupportedAppUsage;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.Resources;
import android.content.res.AssetManager;
import android.content.res.Configuration;
import java.lang.reflect.InvocationTargetException;

public class ContextThemeWrapper extends ContextWrapper {
	private LayoutInflater layout_inflater = null;
	private Resources mResources;
	private Configuration mOverrideConfiguration;

	// Adapted from AOSP ContextThemeWrapper, android-17.0.0_r1,
	// 94b4c163b7dfe5ce3607f7bb8456f9573f7de57d. Apache-2.0, header above.
	public void applyOverrideConfiguration(Configuration overrideConfiguration) {
		if (mResources != null)
			throw new IllegalStateException("getResources() or getAssets() has already been called");
		if (mOverrideConfiguration != null)
			throw new IllegalStateException("Override configuration has already been set");
		mOverrideConfiguration = new Configuration(overrideConfiguration);
	}

	@Override public Resources getResources() {
		if (mResources == null) {
			mResources = mOverrideConfiguration == null ? super.getResources()
			    : createConfigurationContext(mOverrideConfiguration).getResources();
		}
		return mResources;
	}

	@Override public AssetManager getAssets() {
		return getResources().getAssets();
	}

	/**
	 * While not being part of the official Android API, some applications use it to reset/reload the theme in the context.
	 */
	@UnsupportedAppUsage
	private Resources.Theme mTheme = null;
	/**
	 * While not being part of the official Android API, some application use it to get the theme resource ID.
	 */
	@UnsupportedAppUsage
	private int mThemeResource;

	public ContextThemeWrapper(Context base) {
		super(base);
	}

	public ContextThemeWrapper(Context context, int themeResId) {
		super(context);
		mThemeResource = themeResId;
	}

	public ContextThemeWrapper(Context context, Resources.Theme theme) {
		super(context);
		mTheme = context.getResources().newTheme();
		mTheme.setTo(theme);
	}

	@Override
	public Object getSystemService(String name) {
		if ("layout_inflater".equals(name)) {
			if (layout_inflater == null) {
				layout_inflater = LayoutInflater.from(this.getBaseContext()).cloneInContext(this);
			}
			return layout_inflater;
		}
		return super.getSystemService(name);
	}

	@Override
	public Object getSystemService(Class<?> serviceClass) throws InstantiationException, IllegalAccessException, InvocationTargetException {
		if (serviceClass == LayoutInflater.class) {
			if (layout_inflater == null) {
				layout_inflater = LayoutInflater.from(this.getBaseContext()).cloneInContext(this);
			}
			return layout_inflater;
		}
		return super.getSystemService(serviceClass);
	}

	@Override
	public void setTheme(int resid) {
		mThemeResource = resid;
		boolean first = mTheme == null;
		if (first) {
			mTheme = getResources().newTheme();
			mTheme.setTo(getBaseContext().getTheme());
		}
		if (resid != 0) {
			this.onApplyThemeResource(mTheme, resid, first);
		}
	}

	@Override
	public Resources.Theme getTheme() {
		if (mTheme == null) {
			setTheme(mThemeResource);
		}
		return mTheme;
	}

	protected void onApplyThemeResource(Resources.Theme theme, int resId, boolean first) {
		theme.applyStyle(resId, true);
	}
}
