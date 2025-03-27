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

package com.android.messaging.sms;

import android.os.Bundle;
import androidx.appcompat.mms.CarrierConfigValuesLoader;
import android.telephony.SubscriptionInfo;

import com.android.messaging.Factory;
import com.android.messaging.datamodel.data.ParticipantData;
import com.android.messaging.util.Assert;
import com.android.messaging.util.LogUtil;
import com.android.messaging.util.PhoneUtils;
import com.google.common.collect.Maps;

import java.util.List;
import java.util.Map;

/**
 * MMS configuration.
 * <p>
 * This is now a wrapper around the BugleCarrierConfigValuesLoader, which does
 * the actual loading and stores the values in a Bundle. This class provides getter
 * methods for values used in the app, which is easier to use than the raw loader
 * class.
 */
public class MmsConfig {
    private static final String TAG = LogUtil.BUGLE_TAG;

    // A map that stores all MmsConfigs, one per active subscription. For pre-LMSim, this will
    // contain just one entry with the default self sub id; for LMSim and above, this will contain
    // all active sub ids but the default subscription id - the default subscription id will be
    // resolved to an active sub id during runtime.
    private static final Map<Integer, MmsConfig> sSubIdToMmsConfigMap = Maps.newHashMap();
    // The fallback values
    private static final MmsConfig sFallback =
            new MmsConfig(ParticipantData.DEFAULT_SELF_SUB_ID, new Bundle());

    // Per-subscription configuration values.
    private final Bundle mValues;
    private final int mSubId;

    /**
     * Retrieves the MmsConfig instance associated with the given {@code subId}
     */
    public static MmsConfig get(final int subId) {
        final int realSubId = PhoneUtils.getDefault().getEffectiveSubId(subId);
        synchronized (sSubIdToMmsConfigMap) {
            final MmsConfig mmsConfig = sSubIdToMmsConfigMap.get(realSubId);
            if (mmsConfig == null) {
                // The subId is no longer valid. Fall back to the default config.
                LogUtil.e(LogUtil.BUGLE_TAG, "Get mms config failed: invalid subId. subId=" + subId
                        + ", real subId=" + realSubId
                        + ", map=" + sSubIdToMmsConfigMap.keySet());
                return sFallback;
            }
            return mmsConfig;
        }
    }

    private MmsConfig(final int subId, final Bundle values) {
        mSubId = subId;
        mValues = values;
    }

    /**
     * Reload the device and per-subscription settings.
     */
    public static synchronized void load() {
        final BugleCarrierConfigValuesLoader loader = Factory.get().getCarrierConfigValuesLoader();
        // Rebuild the entire MmsConfig map.
        sSubIdToMmsConfigMap.clear();
        loader.reset();
        final List<SubscriptionInfo> subInfoRecords =
                PhoneUtils.getDefault().toLMr1().getActiveSubscriptionInfoList();
        if (subInfoRecords == null) {
            LogUtil.w(TAG, "Loading mms config failed: no active SIM");
            return;
        }
        for (SubscriptionInfo subInfoRecord : subInfoRecords) {
            final int subId = subInfoRecord.getSubscriptionId();
            final Bundle values = loader.get(subId);
            addMmsConfig(new MmsConfig(subId, values));
        }
    }

    private static void addMmsConfig(MmsConfig mmsConfig) {
        Assert.isTrue(mmsConfig.mSubId != ParticipantData.DEFAULT_SELF_SUB_ID);
        sSubIdToMmsConfigMap.put(mmsConfig.mSubId, mmsConfig);
    }

    public int getSmsToMmsTextThreshold() {
        return mValues.getInt(CarrierConfigValuesLoader.CONFIG_SMS_TO_MMS_TEXT_THRESHOLD,
                CarrierConfigValuesLoader.CONFIG_SMS_TO_MMS_TEXT_THRESHOLD_DEFAULT);
    }

    public int getMaxMessageSize() {
        return mValues.getInt(CarrierConfigValuesLoader.CONFIG_MAX_MESSAGE_SIZE,
                CarrierConfigValuesLoader.CONFIG_MAX_MESSAGE_SIZE_DEFAULT);
    }

    public boolean getSendMultipartSmsAsSeparateMessages() {
        return mValues.getBoolean(
                CarrierConfigValuesLoader.CONFIG_SEND_MULTIPART_SMS_AS_SEPARATE_MESSAGES,
                CarrierConfigValuesLoader.CONFIG_SEND_MULTIPART_SMS_AS_SEPARATE_MESSAGES_DEFAULT);
    }

    public boolean getSMSDeliveryReportsEnabled() {
        return mValues.getBoolean(CarrierConfigValuesLoader.CONFIG_ENABLE_SMS_DELIVERY_REPORTS,
                CarrierConfigValuesLoader.CONFIG_ENABLE_SMS_DELIVERY_REPORTS_DEFAULT);
    }

    public boolean isAliasEnabled() {
        return mValues.getBoolean(CarrierConfigValuesLoader.CONFIG_ALIAS_ENABLED,
                CarrierConfigValuesLoader.CONFIG_ALIAS_ENABLED_DEFAULT);
    }

    public int getAliasMinChars() {
        return mValues.getInt(CarrierConfigValuesLoader.CONFIG_ALIAS_MIN_CHARS,
                CarrierConfigValuesLoader.CONFIG_ALIAS_MIN_CHARS_DEFAULT);
    }

    public int getAliasMaxChars() {
        return mValues.getInt(CarrierConfigValuesLoader.CONFIG_ALIAS_MAX_CHARS,
                CarrierConfigValuesLoader.CONFIG_ALIAS_MAX_CHARS_DEFAULT);
    }

    public boolean getAllowAttachAudio() {
        return mValues.getBoolean(CarrierConfigValuesLoader.CONFIG_ALLOW_ATTACH_AUDIO,
                CarrierConfigValuesLoader.CONFIG_ALLOW_ATTACH_AUDIO_DEFAULT);
    }

    public int getMaxSubjectLength() {
        return mValues.getInt(CarrierConfigValuesLoader.CONFIG_MAX_SUBJECT_LENGTH,
                CarrierConfigValuesLoader.CONFIG_MAX_SUBJECT_LENGTH_DEFAULT);
    }

    public Object getValue(final String key) {
        return mValues.get(key);
    }

    public void update(final String type, final String key, final String value) {
        BugleCarrierConfigValuesLoader.update(mValues, type, key, value);
    }
}
