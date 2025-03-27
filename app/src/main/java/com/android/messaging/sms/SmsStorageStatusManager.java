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

import androidx.core.app.NotificationManagerCompat;

import com.android.messaging.Factory;
import com.android.messaging.util.PendingIntentConstants;

/**
 * Class that handles SMS auto delete and notification when storage is low
 */
public class SmsStorageStatusManager {

    /**
     * Cancel the notification
     */
    public static void cancelStorageLowNotification() {
        final NotificationManagerCompat notificationManager =
                NotificationManagerCompat.from(Factory.get().getApplicationContext());
        notificationManager.cancel(getNotificationTag(),
                PendingIntentConstants.SMS_STORAGE_LOW_NOTIFICATION_ID);
    }

    private static String getNotificationTag() {
        return Factory.get().getApplicationContext().getPackageName() + ":smsstoragelow";
    }
}
