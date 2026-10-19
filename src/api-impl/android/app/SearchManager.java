/*
 * Copyright (C) 2007 The Android Open Source Project
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

package android.app;

import android.content.ComponentName;

public class SearchManager {
	public final static String SUGGEST_URI_PATH_QUERY = "search_suggest_query";
	public final static String SUGGEST_COLUMN_FORMAT = "suggest_format";
	public final static String SUGGEST_COLUMN_ICON_1 = "suggest_icon_1";
	public final static String SUGGEST_COLUMN_TEXT_1 = "suggest_text_1";
	public final static String SUGGEST_COLUMN_TEXT_2 = "suggest_text_2";
	public final static String SUGGEST_COLUMN_QUERY = "suggest_intent_query";
	public final static String SUGGEST_MIME_TYPE =
            "vnd.android.cursor.dir/vnd.android.search.suggest";

	public SearchableInfo getSearchableInfo(ComponentName componentName) {
		return null;
	}
}
