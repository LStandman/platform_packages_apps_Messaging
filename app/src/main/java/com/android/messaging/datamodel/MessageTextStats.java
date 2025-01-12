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

package com.android.messaging.datamodel;

import android.telephony.SmsMessage;

public class MessageTextStats {
    private int mMessageCount;
    private int mCodePointsRemainingInCurrentMessage;

    public MessageTextStats() {
        mCodePointsRemainingInCurrentMessage = Integer.MAX_VALUE;
    }

    public int getNumMessagesToBeSent() {
        return mMessageCount;
    }

    public int getCodePointsRemainingInCurrentMessage() {
        return mCodePointsRemainingInCurrentMessage;
    }

    public void updateMessageTextStats(final int selfSubId, final String messageText) {
        final int[] params = SmsMessage.calculateLength(messageText, false);
        /* SmsMessage.calculateLength returns an int[4] with:
         *   int[0] being the number of SMS's required,
         *   int[1] the number of code points used,
         *   int[2] is the number of code points remaining until the next message.
         *   int[3] is the encoding type that should be used for the message.
         */
        mMessageCount = params[0];
        mCodePointsRemainingInCurrentMessage = params[2];
    }
}
