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
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceScreen;
import androidx.preference.TwoStatePreference;
import androidx.core.app.NavUtils;
import androidx.fragment.app.FragmentTransaction;

import android.view.Menu;
import android.view.MenuItem;

import com.android.messaging.R;
import com.android.messaging.ui.BugleActionBarActivity;
import com.android.messaging.ui.LicenseActivity;
import com.android.messaging.ui.UIIntents;
import com.android.messaging.util.BuglePrefs;
import com.android.messaging.util.DebugUtils;
import com.android.messaging.util.PhoneUtils;

import java.util.Objects;

public class ApplicationSettingsActivity extends BugleActionBarActivity {
    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Objects.requireNonNull(getSupportActionBar()).setDisplayHomeAsUpEnabled(true);
        final boolean topLevel = getIntent().getBooleanExtra(
                UIIntents.UI_INTENT_EXTRA_TOP_LEVEL_SETTINGS, false);
        if (topLevel) {
            getSupportActionBar().setTitle(getString(R.string.settings_activity_title));
        }

        FragmentTransaction ft = getSupportFragmentManager().beginTransaction();
        ft.replace(android.R.id.content, new ApplicationSettingsFragment());
        ft.commit();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        if (super.onCreateOptionsMenu(menu)) {
            return true;
        }
        getMenuInflater().inflate(R.menu.settings_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(final MenuItem item) {
        switch (item.getItemId()) {
        case android.R.id.home:
            NavUtils.navigateUpFromSameTask(this);
            return true;
        case R.id.action_license:
            final Intent intent = new Intent(this, LicenseActivity.class);
            startActivity(intent);
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    public static class ApplicationSettingsFragment extends PreferenceFragmentCompat implements
            OnSharedPreferenceChangeListener {

        private String mNotificationsEnabledPreferenceKey;
        private TwoStatePreference mNotificationsEnabledPreference;
        private Preference mSmsDisabledPreference;
        private Preference mSmsEnabledPreference;

        public ApplicationSettingsFragment() {
            // Required empty constructor
        }

        @Override
        public void onCreate(final Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
        }

        @Override
        public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
            getPreferenceManager().setSharedPreferencesName(BuglePrefs.SHARED_PREFERENCES_NAME);
            addPreferencesFromResource(R.xml.preferences_application);

            mNotificationsEnabledPreferenceKey =
                    getString(R.string.notifications_enabled_pref_key);
            mNotificationsEnabledPreference = findPreference(
                    mNotificationsEnabledPreferenceKey);
            String mSmsDisabledPrefKey = getString(R.string.sms_disabled_pref_key);
            mSmsDisabledPreference = findPreference(mSmsDisabledPrefKey);
            String mSmsEnabledPrefKey = getString(R.string.sms_enabled_pref_key);
            mSmsEnabledPreference = findPreference(mSmsEnabledPrefKey);

            if (!DebugUtils.isDebugEnabled()) {
                final Preference debugCategory = findPreference(getString(
                        R.string.debug_pref_key));
                assert debugCategory != null;
                getPreferenceScreen().removePreference(debugCategory);
            }

            final PreferenceScreen advancedScreen = findPreference(
                    getString(R.string.advanced_pref_key));
            final boolean topLevel = requireActivity().getIntent().getBooleanExtra(
                    UIIntents.UI_INTENT_EXTRA_TOP_LEVEL_SETTINGS, false);
            assert advancedScreen != null;
            if (topLevel) {
                advancedScreen.setIntent(UIIntents.get()
                        .getAdvancedSettingsIntent(getPreferenceScreen().getContext()));
            } else {
                // Hide the Advanced settings screen if this is not top-level; these are shown at
                // the parent SettingsActivity.
                getPreferenceScreen().removePreference(advancedScreen);
            }
        }

        private void updateSmsEnabledPreferences() {
            final String defaultSmsAppLabel = getString(R.string.default_sms_app,
                    PhoneUtils.getDefault().getDefaultSmsAppLabel());
            if (PhoneUtils.getDefault().isDefaultSmsApp()) {
                getPreferenceScreen().removePreference(mSmsDisabledPreference);
                mSmsEnabledPreference.setSummary(defaultSmsAppLabel);
            } else {
                getPreferenceScreen().removePreference(mSmsEnabledPreference);
                mSmsDisabledPreference.setSummary(defaultSmsAppLabel);
            }
            updateNotificationsPreferences();
        }

        private void updateNotificationsPreferences() {
            final boolean canNotify = PhoneUtils.getDefault().isDefaultSmsApp();
            mNotificationsEnabledPreference.setEnabled(canNotify);
        }

        @Override
        public void onStart() {
            super.onStart();
            // We do this on start rather than on resume because the sound picker is in a
            // separate activity.
            Objects.requireNonNull(getPreferenceScreen().getSharedPreferences())
                    .registerOnSharedPreferenceChangeListener(this);
        }

        @Override
        public void onResume() {
            super.onResume();
            updateSmsEnabledPreferences();
            updateNotificationsPreferences();
        }

        @Override
        public void onSharedPreferenceChanged(final SharedPreferences sharedPreferences,
                final String key) {
            assert key != null;
            if (key.equals(mNotificationsEnabledPreferenceKey)) {
                updateNotificationsPreferences();
            }
        }

        @Override
        public void onStop() {
            super.onStop();
            Objects.requireNonNull(getPreferenceScreen().getSharedPreferences())
                    .unregisterOnSharedPreferenceChangeListener(this);
        }
    }
}
