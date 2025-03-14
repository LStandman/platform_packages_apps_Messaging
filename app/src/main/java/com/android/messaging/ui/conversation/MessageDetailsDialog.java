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

package com.android.messaging.ui.conversation;

import android.app.AlertDialog;
import android.content.Context;
import android.content.res.Resources;
import android.text.TextUtils;

import com.android.messaging.Factory;
import com.android.messaging.R;
import com.android.messaging.datamodel.BugleDatabaseOperations;
import com.android.messaging.datamodel.DataModel;
import com.android.messaging.datamodel.data.ConversationMessageData;
import com.android.messaging.datamodel.data.ConversationParticipantsData;
import com.android.messaging.datamodel.data.ParticipantData;
import com.android.messaging.sms.MmsUtils;
import com.android.messaging.util.Assert;
import com.android.messaging.util.Assert.DoesNotRunOnMainThread;
import com.android.messaging.util.Dates;
import com.android.messaging.util.DebugUtils;
import com.android.messaging.util.SafeAsyncTask;

import java.util.List;

public class MessageDetailsDialog {
    private static final String RECIPIENT_SEPARATOR = ", ";

    // All methods are static, no creating this class
    private MessageDetailsDialog() {
    }

    public static void show(final Context context, final ConversationMessageData data,
            final ConversationParticipantsData participants, final ParticipantData self) {
        if (DebugUtils.isDebugEnabled()) {
            new SafeAsyncTask<Void, Void, String>() {
                @Override
                protected String doInBackgroundTimed(Void... params) {
                    return getMessageDetails(context, data, participants, self);
                }

                @Override
                protected void onPostExecute(String messageDetails) {
                    showDialog(context, messageDetails);
                }
            }.executeOnThreadPool(null, null, null);
        } else {
            String messageDetails = getMessageDetails(context, data, participants, self);
            showDialog(context, messageDetails);
        }
    }

    private static String getMessageDetails(final Context context,
            final ConversationMessageData data,
            final ConversationParticipantsData participants, final ParticipantData self) {
        String messageDetails = null;
        messageDetails = getSmsMessageDetails(data, participants, self);

        return messageDetails;
    }

    private static void showDialog(final Context context, String messageDetails) {
        if (!TextUtils.isEmpty(messageDetails)) {
            new AlertDialog.Builder(context)
                    .setTitle(R.string.message_details_title)
                    .setMessage(messageDetails)
                    .setCancelable(true)
                    .show();
        }
    }

    /**
     * Return a string, separated by newlines, that contains a number of labels and values
     * for this sms message. The string will be displayed in a modal dialog.
     * @return string list of various message properties
     */
    private static String getSmsMessageDetails(final ConversationMessageData data,
            final ConversationParticipantsData participants, final ParticipantData self) {
        final Resources res = Factory.get().getApplicationContext().getResources();
        final StringBuilder details = new StringBuilder();

        // Type: Text message
        details.append(res.getString(R.string.message_type_label));
        details.append(res.getString(R.string.text_message));

        // From: +1425xxxxxxx
        // or To: +1425xxxxxxx
        final String rawSender = data.getSenderNormalizedDestination();
        if (!TextUtils.isEmpty(rawSender)) {
            details.append('\n');
            details.append(res.getString(R.string.from_label));
            details.append(rawSender);
        }
        final String rawRecipients = getRecipientParticipantString(participants,
                data.getParticipantId(), data.getIsIncoming(), data.getSelfParticipantId());
        if (!TextUtils.isEmpty(rawRecipients)) {
            details.append('\n');
            details.append(res.getString(R.string.to_address_label));
            details.append(rawRecipients);
        }

        // Sent: Mon 11:42AM
        if (data.getIsIncoming()) {
            if (data.getSentTimeStamp() != MmsUtils.INVALID_TIMESTAMP) {
                details.append('\n');
                details.append(res.getString(R.string.sent_label));
                details.append(
                        Dates.getMessageDetailsTimeString(data.getSentTimeStamp()).toString());
            }
        }

        // Sent: Mon 11:43AM
        // or Received: Mon 11:43AM
        appendSentOrReceivedTimestamp(res, details, data);

        appendSimInfo(res, self, details);

        if (DebugUtils.isDebugEnabled()) {
            appendDebugInfo(details, data);
        }

        return details.toString();
    }

    private static void appendSentOrReceivedTimestamp(Resources res, StringBuilder details,
            ConversationMessageData data) {
        int labelId = -1;
        if (data.getIsIncoming()) {
            labelId = R.string.received_label;
        } else if (data.getIsSendComplete()) {
            labelId = R.string.sent_label;
        }
        if (labelId >= 0) {
            details.append('\n');
            details.append(res.getString(labelId));
            details.append(
                    Dates.getMessageDetailsTimeString(data.getReceivedTimeStamp()).toString());
        }
    }

    @DoesNotRunOnMainThread
    private static void appendDebugInfo(StringBuilder details, ConversationMessageData data) {
        // We grab the thread id from the database, so this needs to run in the background
        Assert.isNotMainThread();
        details.append("\n\n");
        details.append("DEBUG");

        details.append('\n');
        details.append("Message id: ");
        details.append(data.getMessageId());

        final String telephonyUri = data.getSmsMessageUri();
        details.append('\n');
        details.append("Telephony uri: ");
        details.append(telephonyUri);

        final String conversationId = data.getConversationId();

        if (conversationId == null) {
            return;
        }

        details.append('\n');
        details.append("Conversation id: ");
        details.append(conversationId);

        final long threadId = BugleDatabaseOperations.getThreadId(DataModel.get().getDatabase(),
                conversationId);

        details.append('\n');
        details.append("Conversation telephony thread id: ");
        details.append(threadId);

        final String recipientsString = MmsUtils.getRawRecipientIdsForThread(threadId);
        if (recipientsString != null) {
            details.append('\n');
            details.append("Thread recipient ids: ");
            details.append(recipientsString);
        }

        final List<String> recipients = MmsUtils.getRecipientsByThread(threadId);
        if (recipients != null) {
            details.append('\n');
            details.append("Thread recipients: ");
            details.append(recipients.toString());
        }
    }

    private static String getRecipientParticipantString(
            final ConversationParticipantsData participants, final String senderId,
            final boolean addSelf, final String selfId) {
        final StringBuilder recipients = new StringBuilder();
        for (final ParticipantData participant : participants) {
            if (TextUtils.equals(participant.getId(), senderId)) {
                // Don't add sender
                continue;
            }
            if (participant.isSelf() &&
                    (!participant.getId().equals(selfId) || !addSelf)) {
                // For self participants, don't add the one that's not relevant to this message
                // or if we are asked not to add self
                continue;
            }
            final String phoneNumber = participant.getNormalizedDestination();
            // Don't add empty number. This should not happen. But if that happens
            // we should not add it.
            if (!TextUtils.isEmpty(phoneNumber)) {
                if (recipients.length() > 0) {
                    recipients.append(RECIPIENT_SEPARATOR);
                }
                recipients.append(phoneNumber);
            }
        }
        return recipients.toString();
    }

    private static void appendSimInfo(final Resources res,
            final ParticipantData self, final StringBuilder outString) {
        // The appended SIM info would look like:
        // SIM: SUB 01
        // or SIM: SIM 1
        // or SIM: Unknown
        Assert.isTrue(self.isSelf());
        outString.append('\n');
        outString.append(res.getString(R.string.sim_label));
        if (self.isActiveSubscription() && !self.isDefaultSelf()) {
            final String subscriptionName = self.getSubscriptionName();
            if (TextUtils.isEmpty(subscriptionName)) {
                outString.append(res.getString(R.string.sim_slot_identifier,
                        self.getDisplaySlotId()));
            } else {
                outString.append(subscriptionName);
            }
        }
    }
}
