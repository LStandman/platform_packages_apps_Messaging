/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.messaging.ui.appsettings;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.EditTextPreference;
import androidx.core.text.BidiFormatter;
import androidx.core.text.TextDirectionHeuristicsCompat;
import android.text.InputType;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.widget.EditText;

import com.android.messaging.R;
import com.android.messaging.util.PhoneUtils;

/**
 * Preference that displays a phone number and allows editing via a dialog.
 * <p>
 * A default number can be assigned, which is shown in the preference view and
 * used to populate the dialog editor when the preference value is not set. If
 * the user sets the preference to a number equivalent to the default, the
 * underlying preference is cleared.
 */
public class PhoneNumberPreference extends EditTextPreference {

    private int mSubId;

    public PhoneNumberPreference(final Context context, final AttributeSet attrs) {
        super(context, attrs);
    }

    public void setDefaultPhoneNumber(final String phoneNumber, final int subscriptionId) {
        final BidiFormatter bidiFormatter = BidiFormatter.getInstance();

        final String defaultPhoneNumber = bidiFormatter.unicodeWrap
                (phoneNumber, TextDirectionHeuristicsCompat.LTR);
        setDefaultValue(defaultPhoneNumber);
        mSubId = subscriptionId;
    }

    public int getSubId() {
        return mSubId;
    }

    public static final class SimpleOnBindEditTextListener implements OnBindEditTextListener {

        private static PhoneNumberPreference.SimpleOnBindEditTextListener sSimpleOnBindEditTextListener;

        /**
         * Retrieve a singleton instance of this simple
         * {@link androidx.preference.EditTextPreference.OnBindEditTextListener} implementation.
         *
         * @return a singleton instance of this simple
         * {@link androidx.preference.EditTextPreference.OnBindEditTextListener} implementation
         */
        @NonNull
        public static PhoneNumberPreference.SimpleOnBindEditTextListener getInstance() {
            if (sSimpleOnBindEditTextListener == null) {
                sSimpleOnBindEditTextListener = new PhoneNumberPreference.SimpleOnBindEditTextListener();
            }
            return sSimpleOnBindEditTextListener;
        }

        @Override
        public void onBindEditText(@NonNull EditText editText) {
            editText.setInputType(InputType.TYPE_CLASS_PHONE);
        }
    }

    /**
     * A simple {@link androidx.preference.Preference.SummaryProvider} implementation for an
     * {@link PhoneNumberPreference}. If no value has been set, the summary displayed will be
     * 'Unknown', otherwise the summary displayed will be the value set for this preference.
     */
    public static final class SimpleSummaryProvider implements SummaryProvider<PhoneNumberPreference> {

        private static PhoneNumberPreference.SimpleSummaryProvider sSimpleSummaryProvider;

        /**
         * Retrieve a singleton instance of this simple
         * {@link androidx.preference.Preference.SummaryProvider} implementation.
         *
         * @return a singleton instance of this simple
         * {@link androidx.preference.Preference.SummaryProvider} implementation
         */
        @NonNull
        public static PhoneNumberPreference.SimpleSummaryProvider getInstance() {
            if (sSimpleSummaryProvider == null) {
                sSimpleSummaryProvider = new PhoneNumberPreference.SimpleSummaryProvider();
            }
            return sSimpleSummaryProvider;
        }

        @Nullable
        @Override
        public CharSequence provideSummary(@NonNull PhoneNumberPreference preference) {
            String value = preference.getText();
            final String displayValue = (!TextUtils.isEmpty(value))
                    ? PhoneUtils.get(preference.getSubId()).formatForDisplay(value)
                    : preference.getContext().getString(R.string.unknown_phone_number_pref_display_value);
            final BidiFormatter bidiFormatter = BidiFormatter.getInstance();

            return bidiFormatter.unicodeWrap
                    (displayValue, TextDirectionHeuristicsCompat.LTR);
        }
    }
}
