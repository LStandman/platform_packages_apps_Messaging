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

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceFragmentCompat;
import android.text.TextUtils;
import android.view.MenuItem;

import androidx.core.app.NavUtils;
import androidx.fragment.app.FragmentTransaction;

import com.android.messaging.Factory;
import com.android.messaging.R;
import com.android.messaging.datamodel.ParticipantRefresh;
import com.android.messaging.datamodel.data.ParticipantData;
import com.android.messaging.ui.BugleActionBarActivity;
import com.android.messaging.ui.UIIntents;
import com.android.messaging.util.Assert;
import com.android.messaging.util.BuglePrefs;
import com.android.messaging.util.PhoneUtils;

import java.util.Objects;

public class PerSubscriptionSettingsActivity extends BugleActionBarActivity {
    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Objects.requireNonNull(getSupportActionBar()).setDisplayHomeAsUpEnabled(true);
        final String title = getIntent().getStringExtra(
                UIIntents.UI_INTENT_EXTRA_PER_SUBSCRIPTION_SETTING_TITLE);
        if (!TextUtils.isEmpty(title)) {
            getSupportActionBar().setTitle(title);
        } else {
            // This will fall back to the default title, i.e. "Messaging settings," so No-op.
        }

        final FragmentTransaction ft = getSupportFragmentManager().beginTransaction();
        final PerSubscriptionSettingsFragment fragment = new PerSubscriptionSettingsFragment();
        ft.replace(android.R.id.content, fragment);
        ft.commit();
    }

    @Override
    public boolean onOptionsItemSelected(final MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            NavUtils.navigateUpFromSameTask(this);
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    public static class PerSubscriptionSettingsFragment extends PreferenceFragmentCompat
            implements OnSharedPreferenceChangeListener {
        private PhoneNumberPreference mPhoneNumberPreference;
        private String mPhoneNumberKey;
        private int mSubId;

        public PerSubscriptionSettingsFragment() {
            // Required empty constructor
        }

        @Override
        public void onCreate(final Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
        }

        @Override
        public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
            // Get sub id from launch intent
            final Intent intent = requireActivity().getIntent();
            Assert.notNull(intent);
            mSubId = (intent != null) ? intent.getIntExtra(UIIntents.UI_INTENT_EXTRA_SUB_ID,
                    ParticipantData.DEFAULT_SELF_SUB_ID) : ParticipantData.DEFAULT_SELF_SUB_ID;

            final BuglePrefs subPrefs = Factory.get().getSubscriptionPrefs(mSubId);
            getPreferenceManager().setSharedPreferencesName(subPrefs.getSharedPreferencesName());
            addPreferencesFromResource(R.xml.preferences_per_subscription);

            mPhoneNumberKey = getString(R.string.mms_phone_number_pref_key);
            mPhoneNumberPreference = findPreference(mPhoneNumberKey);
            final PreferenceCategory advancedCategory = findPreference(getString(R.string.advanced_category_pref_key));

            mPhoneNumberPreference.setDefaultPhoneNumber(
                    PhoneUtils.get(mSubId).getCanonicalForSelf(false/*allowOverride*/), mSubId);
            mPhoneNumberPreference.setSummaryProvider(PhoneNumberPreference.SimpleSummaryProvider.getInstance());
            mPhoneNumberPreference.setOnBindEditTextListener(PhoneNumberPreference.SimpleOnBindEditTextListener.getInstance());

            // We want to disable preferences if we are not the default app, but we do all of the
            // above first so that the user sees the correct information on the screen
            if (!PhoneUtils.getDefault().isDefaultSmsApp()) {
                final Preference autoRetrieveMmsPreference =
                        findPreference(getString(R.string.auto_retrieve_mms_pref_key));
                assert autoRetrieveMmsPreference != null;
                autoRetrieveMmsPreference.setEnabled(false);
                final Preference deliveryReportsPreference =
                        findPreference(getString(R.string.delivery_reports_pref_key));
                if (deliveryReportsPreference != null) {
                    deliveryReportsPreference.setEnabled(false);
                }
            }

            assert advancedCategory != null;
            if (advancedCategory.getPreferenceCount() == 0) {
                getPreferenceScreen().removePreference(advancedCategory);
            }
        }

        @Override
        public void onResume() {
            super.onResume();
            Objects.requireNonNull(getPreferenceScreen().getSharedPreferences())
                    .registerOnSharedPreferenceChangeListener(this);
        }

        @Override
        public void onSharedPreferenceChanged(final SharedPreferences sharedPreferences,
                final String key) {
            assert key != null;
            if (key.equals(mPhoneNumberKey)) {
                // Save the changed phone number in preferences specific to the sub id
                final String newPhoneNumber = mPhoneNumberPreference.getText();
                final BuglePrefs subPrefs = BuglePrefs.getSubscriptionPrefs(mSubId);
                if (TextUtils.isEmpty(newPhoneNumber)) {
                    subPrefs.remove(mPhoneNumberKey);
                } else {
                    subPrefs.putString(getString(R.string.mms_phone_number_pref_key),
                            newPhoneNumber);
                }
                // Update the self participants so the new phone number will be reflected
                // everywhere in the UI.
                ParticipantRefresh.refreshSelfParticipants();
            }
        }

        @Override
        public void onPause() {
            super.onPause();
            Objects.requireNonNull(getPreferenceScreen().getSharedPreferences())
                    .unregisterOnSharedPreferenceChangeListener(this);
        }
    }
}
