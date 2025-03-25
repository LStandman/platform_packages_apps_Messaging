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

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.database.Cursor;
import android.database.sqlite.SQLiteException;
import android.net.Uri;
import android.provider.Telephony;
import android.provider.Telephony.Mms;
import android.provider.Telephony.Sms;
import android.provider.Telephony.Threads;
import android.telephony.SmsMessage;
import android.text.TextUtils;

import com.android.messaging.Factory;
import com.android.messaging.R;
import com.android.messaging.datamodel.data.MessageData;
import com.android.messaging.mmslib.SqliteWrapper;
import com.android.messaging.sms.SmsSender.SendResult;
import com.android.messaging.util.BuglePrefs;
import com.android.messaging.util.DebugUtils;
import com.android.messaging.util.LogUtil;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Utils for sending sms/mms messages.
 */
public class MmsUtils {
    private static final String TAG = LogUtil.BUGLE_TAG;

    /**
     * MMS request succeeded
     */
    public static final int MMS_REQUEST_SUCCEEDED = 0;
    /**
     * MMS request failed with a transient error and can be retried automatically
     */
    public static final int MMS_REQUEST_AUTO_RETRY = 1;
    /**
     * MMS request failed with an error and can be retried manually
     */
    public static final int MMS_REQUEST_MANUAL_RETRY = 2;
    /**
     * MMS request failed with a specific error and should not be retried
     */
    public static final int MMS_REQUEST_NO_RETRY = 3;

    public static final String getRequestStatusDescription(final int status) {
        switch (status) {
            case MMS_REQUEST_SUCCEEDED:
                return "SUCCEEDED";
            case MMS_REQUEST_AUTO_RETRY:
                return "AUTO_RETRY";
            case MMS_REQUEST_MANUAL_RETRY:
                return "MANUAL_RETRY";
            case MMS_REQUEST_NO_RETRY:
                return "NO_RETRY";
            default:
                return String.valueOf(status) + " (check MmsUtils)";
        }
    }

    public static final int PDU_HEADER_VALUE_UNDEFINED = 0;

    public static final long INVALID_TIMESTAMP = 0L;
    private static String[] sNoSubjectStrings;

    // Sync all remote messages apart from drafts
    private static final String REMOTE_SMS_SELECTION = String.format(
            Locale.US,
            "(%s IN (%d, %d, %d, %d, %d))",
            Sms.TYPE,
            Sms.MESSAGE_TYPE_INBOX,
            Sms.MESSAGE_TYPE_OUTBOX,
            Sms.MESSAGE_TYPE_QUEUED,
            Sms.MESSAGE_TYPE_FAILED,
            Sms.MESSAGE_TYPE_SENT);

    /**
     * Type selection for importing sms messages.
     *
     * @return The SQL selection for importing sms messages
     */
    public static String getSmsTypeSelectionSql() {
        return REMOTE_SMS_SELECTION;
    }

    public static final String MMS_DUMP_PREFIX = "mmsdump-";
    public static final String SMS_DUMP_PREFIX = "smsdump-";

    // Code for extracting the actual phone numbers for the participants in a conversation,
    // given a thread id.

    private static final Uri ALL_THREADS_URI =
            Threads.CONTENT_URI.buildUpon().appendQueryParameter("simple", "true").build();

    private static final String[] RECIPIENTS_PROJECTION = {
        Threads._ID,
        Threads.RECIPIENT_IDS
    };

    private static final int RECIPIENT_IDS  = 1;

    public static List<String> getRecipientsByThread(final long threadId) {
        final String spaceSepIds = getRawRecipientIdsForThread(threadId);
        if (!TextUtils.isEmpty(spaceSepIds)) {
            final Context context = Factory.get().getApplicationContext();
            return getAddresses(context, spaceSepIds);
        }
        return null;
    }

    // NOTE: There are phones on which you can't get the recipients from the thread id for SMS
    // until you have a message in the conversation!
    public static String getRawRecipientIdsForThread(final long threadId) {
        if (threadId <= 0) {
            return null;
        }
        final Context context = Factory.get().getApplicationContext();
        final ContentResolver cr = context.getContentResolver();
        final Cursor thread = cr.query(
                ALL_THREADS_URI,
                RECIPIENTS_PROJECTION, "_id=?", new String[] { String.valueOf(threadId) }, null);
        if (thread != null) {
            try {
                if (thread.moveToFirst()) {
                    // recipientIds will be a space-separated list of ids into the
                    // canonical addresses table.
                    return thread.getString(RECIPIENT_IDS);
                }
            } finally {
                thread.close();
            }
        }
        return null;
    }

    private static final Uri SINGLE_CANONICAL_ADDRESS_URI =
            Uri.parse("content://mms-sms/canonical-address");

    private static List<String> getAddresses(final Context context, final String spaceSepIds) {
        final List<String> numbers = new ArrayList<String>();
        final String[] ids = spaceSepIds.split(" ");
        for (final String id : ids) {
            long longId;

            try {
                longId = Long.parseLong(id);
                if (longId < 0) {
                    LogUtil.e(TAG, "MmsUtils.getAddresses: invalid id " + longId);
                    continue;
                }
            } catch (final NumberFormatException ex) {
                LogUtil.e(TAG, "MmsUtils.getAddresses: invalid id. " + ex, ex);
                // skip this id
                continue;
            }

            // TODO: build a single query where we get all the addresses at once.
            Cursor c = null;
            try {
                c = context.getContentResolver().query(
                        ContentUris.withAppendedId(SINGLE_CANONICAL_ADDRESS_URI, longId),
                        null, null, null, null);
            } catch (final Exception e) {
                LogUtil.e(TAG, "MmsUtils.getAddresses: query failed for id " + longId, e);
            }
            if (c != null) {
                try {
                    if (c.moveToFirst()) {
                        final String number = c.getString(0);
                        if (!TextUtils.isEmpty(number)) {
                            numbers.add(number);
                        } else {
                            LogUtil.w(TAG, "Canonical MMS/SMS address is empty for id: " + longId);
                        }
                    }
                } finally {
                    c.close();
                }
            }
        }
        if (numbers.isEmpty()) {
            LogUtil.w(TAG, "No MMS addresses found from ids string [" + spaceSepIds + "]");
        }
        return numbers;
    }

    // Get telephony SMS thread ID
    public static long getOrCreateSmsThreadId(final Context context, final String dest) {
        // use destinations to determine threadId
        final Set<String> recipients = new HashSet<String>();
        recipients.add(dest);
        try {
            return MmsSmsUtils.Threads.getOrCreateThreadId(context, recipients);
        } catch (final IllegalArgumentException e) {
            LogUtil.e(TAG, "MmsUtils: getting thread id failed: " + e);
            return -1;
        }
    }

    // Get telephony SMS thread ID
    public static long getOrCreateThreadId(final Context context, final List<String> dests) {
        if (dests == null || dests.size() == 0) {
            return -1;
        }
        // use destinations to determine threadId
        final Set<String> recipients = new HashSet<String>(dests);
        try {
            return MmsSmsUtils.Threads.getOrCreateThreadId(context, recipients);
        } catch (final IllegalArgumentException e) {
            LogUtil.e(TAG, "MmsUtils: getting thread id failed: " + e);
            return -1;
        }
    }

    /**
     * Add an SMS to the given URI with thread_id specified.
     *
     * @param resolver the content resolver to use
     * @param uri the URI to add the message to
     * @param subId subId for the receiving sim
     * @param address the address of the sender
     * @param body the body of the message
     * @param subject the psuedo-subject of the message
     * @param date the timestamp for the message
     * @param read true if the message has been read, false if not
     * @param threadId the thread_id of the message
     * @return the URI for the new message
     */
    private static Uri addMessageToUri(final ContentResolver resolver,
            final Uri uri, final int subId, final String address, final String body,
            final String subject, final Long date, final boolean read, final boolean seen,
            final int status, final int type, final long threadId) {
        final ContentValues values = new ContentValues(7);

        values.put(Telephony.Sms.ADDRESS, address);
        if (date != null) {
            values.put(Telephony.Sms.DATE, date);
        }
        values.put(Telephony.Sms.READ, read ? 1 : 0);
        values.put(Telephony.Sms.SEEN, seen ? 1 : 0);
        values.put(Telephony.Sms.SUBJECT, subject);
        values.put(Telephony.Sms.BODY, body);
        values.put(Telephony.Sms.SUBSCRIPTION_ID, subId);
        if (status != Telephony.Sms.STATUS_NONE) {
            values.put(Telephony.Sms.STATUS, status);
        }
        if (type != Telephony.Sms.MESSAGE_TYPE_ALL) {
            values.put(Telephony.Sms.TYPE, type);
        }
        if (threadId != -1L) {
            values.put(Telephony.Sms.THREAD_ID, threadId);
        }
        return resolver.insert(uri, values);
    }

    // Insert an SMS message to telephony
    public static Uri insertSmsMessage(final Context context, final Uri uri, final int subId,
            final String dest, final String text, final long timestamp, final int status,
            final int type, final long threadId) {
        Uri response = null;
        try {
            response = addMessageToUri(context.getContentResolver(), uri, subId, dest,
                    text, null /* subject */, timestamp, true /* read */,
                    true /* seen */, status, type, threadId);
            if (LogUtil.isLoggable(TAG, LogUtil.DEBUG)) {
                LogUtil.d(TAG, "Mmsutils: Inserted SMS message into telephony (type = " + type + ")"
                        + ", uri: " + response);
            }
        } catch (final SQLiteException e) {
            LogUtil.e(TAG, "MmsUtils: persist sms message failure " + e, e);
        } catch (final IllegalArgumentException e) {
            LogUtil.e(TAG, "MmsUtils: persist sms message failure " + e, e);
        }
        return response;
    }

    // Update SMS message type in telephony; returns true if it succeeded.
    public static boolean updateSmsMessageSendingStatus(final Context context, final Uri uri,
            final int type, final long date) {
        try {
            final ContentResolver resolver = context.getContentResolver();
            final ContentValues values = new ContentValues(2);

            values.put(Telephony.Sms.TYPE, type);
            values.put(Telephony.Sms.DATE, date);
            final int cnt = resolver.update(uri, values, null, null);
            if (cnt == 1) {
                if (LogUtil.isLoggable(TAG, LogUtil.DEBUG)) {
                    LogUtil.d(TAG, "Mmsutils: Updated sending SMS " + uri + "; type = " + type
                            + ", date = " + date + " (millis since epoch)");
                }
                return true;
            }
        } catch (final SQLiteException e) {
            LogUtil.e(TAG, "MmsUtils: update sms message failure " + e, e);
        } catch (final IllegalArgumentException e) {
            LogUtil.e(TAG, "MmsUtils: update sms message failure " + e, e);
        }
        return false;
    }

    /**
     * Parse values from a received sms message
     *
     * @param context
     * @param msgs The received sms message content
     * @param error The received sms error
     * @return Parsed values from the message
     */
    public static ContentValues parseReceivedSmsMessage(
            final Context context, final SmsMessage[] msgs, final int error) {
        final SmsMessage sms = msgs[0];
        final ContentValues values = new ContentValues();

        values.put(Sms.ADDRESS, sms.getDisplayOriginatingAddress());
        values.put(Sms.BODY, buildMessageBodyFromPdus(msgs));
        if (MmsUtils.hasSmsDateSentColumn()) {
            // TODO:: The boxing here seems unnecessary.
            values.put(Sms.DATE_SENT, Long.valueOf(sms.getTimestampMillis()));
        }
        values.put(Sms.PROTOCOL, sms.getProtocolIdentifier());
        if (sms.getPseudoSubject().length() > 0) {
            values.put(Sms.SUBJECT, sms.getPseudoSubject());
        }
        values.put(Sms.REPLY_PATH_PRESENT, sms.isReplyPathPresent() ? 1 : 0);
        values.put(Sms.SERVICE_CENTER, sms.getServiceCenterAddress());
        // Error code
        values.put(Sms.ERROR_CODE, error);

        return values;
    }

    // Some providers send formfeeds in their messages. Convert those formfeeds to newlines.
    private static String replaceFormFeeds(final String s) {
        return s == null ? "" : s.replace('\f', '\n');
    }

    // Parse the message body from message PDUs
    private static String buildMessageBodyFromPdus(final SmsMessage[] msgs) {
        if (msgs.length == 1) {
            // There is only one part, so grab the body directly.
            return replaceFormFeeds(msgs[0].getDisplayMessageBody());
        } else {
            // Build up the body from the parts.
            final StringBuilder body = new StringBuilder();
            for (final SmsMessage msg : msgs) {
                try {
                    // getDisplayMessageBody() can NPE if mWrappedMessage inside is null.
                    body.append(msg.getDisplayMessageBody());
                } catch (final NullPointerException e) {
                    // Nothing to do
                }
            }
            return replaceFormFeeds(body.toString());
        }
    }

    // Parse the message date
    public static Long getMessageDate(final SmsMessage sms, long now) {
        // Use now for the timestamp to avoid confusion with clock
        // drift between the handset and the SMSC.
        // Check to make sure the system is giving us a non-bogus time.
        final Calendar buildDate = new GregorianCalendar(2011, 8, 18);    // 18 Sep 2011
        final Calendar nowDate = new GregorianCalendar();
        nowDate.setTimeInMillis(now);
        if (nowDate.before(buildDate)) {
            // It looks like our system clock isn't set yet because the current time right now
            // is before an arbitrary time we made this build. Instead of inserting a bogus
            // receive time in this case, use the timestamp of when the message was sent.
            now = sms.getTimestampMillis();
        }
        return now;
    }

    /**
     * cleanseMmsSubject will take a subject that's says, "<Subject: no subject>", and return
     * a null string. Otherwise it will return the original subject string.
     * @param resources So the function can grab string resources
     * @param subject the raw subject
     * @return
     */
    public static String cleanseMmsSubject(final Resources resources, final String subject) {
        if (TextUtils.isEmpty(subject)) {
            return null;
        }
        if (sNoSubjectStrings == null) {
            sNoSubjectStrings =
                    resources.getStringArray(R.array.empty_subject_strings);
        }
        for (final String noSubjectString : sNoSubjectStrings) {
            if (subject.equalsIgnoreCase(noSubjectString)) {
                return null;
            }
        }
        return subject;
    }

    public static SmsMessage getSmsMessageFromDeliveryReport(final Intent intent) {
        final byte[] pdu = intent.getByteArrayExtra("pdu");
        final String format = intent.getStringExtra("format");
        return SmsMessage.createFromPdu(pdu, format);
    }

    /**
     * Update the status and date_sent column of sms message in telephony provider
     *
     * @param smsMessageUri
     * @param status
     * @param timeSentInMillis
     */
    public static void updateSmsStatusAndDateSent(final Uri smsMessageUri, final int status,
            final long timeSentInMillis) {
        if (smsMessageUri == null) {
            return;
        }
        final ContentValues values = new ContentValues();
        values.put(Sms.STATUS, status);
        if (MmsUtils.hasSmsDateSentColumn()) {
            values.put(Sms.DATE_SENT, timeSentInMillis);
        }
        final ContentResolver resolver = Factory.get().getApplicationContext().getContentResolver();
        resolver.update(smsMessageUri, values, null/*where*/, null/*selectionArgs*/);
    }

    // Max number of operands per SQL query for deleting SMS messages
    public static final int MAX_IDS_PER_QUERY = 128;

    /**
     * Get the (?,?,...) thing for the SQL IN operator by a count
     *
     * @param count
     * @return
     */
    public static String getSqlInOperand(final int count) {
        if (count <= 0) {
            return null;
        }
        final StringBuilder sb = new StringBuilder();
        sb.append("(?");
        for (int i = 0; i < count - 1; i++) {
            sb.append(",?");
        }
        sb.append(")");
        return sb.toString();
    }

    /**
     * Delete SMS and MMS messages that are earlier than a specific timestamp
     *
     * @param cutOffTimestampInMillis The cut-off timestamp
     * @return Total number of messages deleted.
     */
    public static int deleteMessagesOlderThan(final long cutOffTimestampInMillis) {
        int deleted = 0;
        final ContentResolver resolver = Factory.get().getApplicationContext().getContentResolver();
        // Delete old SMS
        final String smsSelection = String.format(
                Locale.US,
                "%s AND (%s<=%d)",
                getSmsTypeSelectionSql(),
                Sms.DATE,
                cutOffTimestampInMillis);
        deleted += resolver.delete(Sms.CONTENT_URI, smsSelection, null/*selectionArgs*/);
        return deleted;
    }

    /**
     * Update the read status of SMS/MMS messages by thread and timestamp
     *
     * @param threadId The thread of sms/mms to change
     * @param timestampInMillis Change the status before this timestamp
     */
    public static void updateSmsReadStatus(final long threadId, final long timestampInMillis) {
        final ContentResolver resolver = Factory.get().getApplicationContext().getContentResolver();
        final ContentValues values = new ContentValues();
        values.put("read", 1);
        values.put("seen", 1); /* If you read it you saw it */
        final String smsSelection = String.format(
                Locale.US,
                "%s=%d AND %s<=%d AND %s=0",
                Sms.THREAD_ID,
                threadId,
                Sms.DATE,
                timestampInMillis,
                Sms.READ);
        resolver.update(
                Sms.CONTENT_URI,
                values,
                smsSelection,
                null/*selectionArgs*/);
        final String mmsSelection = String.format(
                Locale.US,
                "%s=%d AND %s<=%d AND %s=0",
                Mms.THREAD_ID,
                threadId,
                Mms.DATE,
                timestampInMillis / 1000L,
                Mms.READ);
        resolver.update(
                Mms.CONTENT_URI,
                values,
                mmsSelection,
                null/*selectionArgs*/);
    }

    private static final String[] TEST_DATE_SENT_PROJECTION = new String[] { Sms.DATE_SENT };
    private static Boolean sHasSmsDateSentColumn = null;
    /**
     * Check if date_sent column exists on ICS and above devices. We need to do a test
     * query to figure that out since on some ICS+ devices, somehow the date_sent column does
     * not exist. http://b/17629135 tracks the associated compliance test.
     *
     * @return Whether "date_sent" column exists in sms table
     */
    public static boolean hasSmsDateSentColumn() {
        if (sHasSmsDateSentColumn == null) {
            Cursor cursor = null;
            try {
                final Context context = Factory.get().getApplicationContext();
                final ContentResolver resolver = context.getContentResolver();
                cursor = SqliteWrapper.query(
                        resolver,
                        Sms.CONTENT_URI,
                        TEST_DATE_SENT_PROJECTION,
                        null/*selection*/,
                        null/*selectionArgs*/,
                        Sms.DATE_SENT + " ASC LIMIT 1");
                sHasSmsDateSentColumn = true;
            } catch (final SQLiteException e) {
                LogUtil.w(TAG, "date_sent in sms table does not exist", e);
                sHasSmsDateSentColumn = false;
            } finally {
                if (cursor != null) {
                    cursor.close();
                }
            }
        }
        return sHasSmsDateSentColumn;
    }

    /**
     * Checks if we should dump sms, based on both the setting and the global debug
     * flag
     *
     * @return if dump sms is enabled
     */
    public static boolean isDumpSmsEnabled() {
        if (!DebugUtils.isDebugEnabled()) {
            return false;
        }
        return getDumpSmsOrMmsPref(R.string.dump_sms_pref_key, R.bool.dump_sms_pref_default);
    }

    /**
     * Load the value of dump sms or mms setting preference
     */
    private static boolean getDumpSmsOrMmsPref(final int prefKeyRes, final int defaultKeyRes) {
        final Context context = Factory.get().getApplicationContext();
        final Resources resources = context.getResources();
        final BuglePrefs prefs = BuglePrefs.getApplicationPrefs();
        final String key = resources.getString(prefKeyRes);
        final boolean defaultValue = resources.getBoolean(defaultKeyRes);
        return prefs.getBoolean(key, defaultValue);
    }

    private static boolean isSmsDataAvailable() {
        // L_MR1 above may support sending sms via wifi
        return true;
    }

    public static boolean isDeliveryReportRequired(final int subId) {
        if (!MmsConfig.get(subId).getSMSDeliveryReportsEnabled()) {
            return false;
        }
        final Context context = Factory.get().getApplicationContext();
        final Resources res = context.getResources();
        final BuglePrefs prefs = BuglePrefs.getSubscriptionPrefs(subId);
        final String deliveryReportKey = res.getString(R.string.delivery_reports_pref_key);
        final boolean defaultValue = res.getBoolean(R.bool.delivery_reports_pref_default);
        return prefs.getBoolean(deliveryReportKey, defaultValue);
    }

    public static int sendSmsMessage(final String recipient, final String messageText,
            final Uri requestUri, final int subId,
            final String smsServiceCenter, final boolean requireDeliveryReport) {
        if (!isSmsDataAvailable()) {
            LogUtil.w(TAG, "MmsUtils: can't send SMS without radio");
            return MMS_REQUEST_MANUAL_RETRY;
        }
        final Context context = Factory.get().getApplicationContext();
        int status = MMS_REQUEST_MANUAL_RETRY;
        try {
            // Send a single message
            final SendResult result = SmsSender.sendMessage(
                    context,
                    subId,
                    recipient,
                    messageText,
                    smsServiceCenter,
                    requireDeliveryReport,
                    requestUri);
            if (!result.hasPending()) {
                // not timed out, check failures
                final int failureLevel = result.getHighestFailureLevel();
                switch (failureLevel) {
                    case SendResult.FAILURE_LEVEL_NONE:
                        status = MMS_REQUEST_SUCCEEDED;
                        break;
                    case SendResult.FAILURE_LEVEL_TEMPORARY:
                        status = MMS_REQUEST_AUTO_RETRY;
                        LogUtil.e(TAG, "MmsUtils: SMS temporary failure");
                        break;
                    case SendResult.FAILURE_LEVEL_PERMANENT:
                        LogUtil.e(TAG, "MmsUtils: SMS permanent failure");
                        break;
                }
            } else {
                // Timed out
                LogUtil.e(TAG, "MmsUtils: sending SMS timed out");
            }
        } catch (final Exception e) {
            LogUtil.e(TAG, "MmsUtils: failed to send SMS " + e, e);
        }
        return status;
    }

    /**
     * Delete SMS and MMS messages in a particular thread
     *
     * @return the number of messages deleted
     */
    public static int deleteThread(final long threadId, final long cutOffTimestampInMillis) {
        final ContentResolver resolver = Factory.get().getApplicationContext().getContentResolver();
        final Uri threadUri = ContentUris.withAppendedId(Telephony.Threads.CONTENT_URI, threadId);
        if (cutOffTimestampInMillis < Long.MAX_VALUE) {
            return resolver.delete(threadUri, Sms.DATE + "<=?",
                    new String[] { Long.toString(cutOffTimestampInMillis) });
        } else {
            return resolver.delete(threadUri, null /* smsSelection */, null /* selectionArgs */);
        }
    }

    /**
     * Delete single SMS and MMS message
     *
     * @return number of rows deleted (should be 1 or 0)
     */
    public static int deleteMessage(final Uri messageUri) {
        final ContentResolver resolver = Factory.get().getApplicationContext().getContentResolver();
        return resolver.delete(messageUri, null /* selection */, null /* selectionArgs */);
    }

    public static int mapRawStatusToErrorResourceId(final int bugleStatus, final int rawStatus) {
        int stringResId = R.string.message_status_send_failed;
        if (rawStatus == MessageData.RAW_TELEPHONY_STATUS_MESSAGE_TOO_BIG) {
            stringResId = R.string.mms_failure_outgoing_too_large;
        }
        return stringResId;
    }
}
