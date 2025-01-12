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
import android.content.res.AssetFileDescriptor;
import android.content.res.Resources;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Telephony;
import android.provider.Telephony.Mms;
import android.provider.Telephony.Sms;
import android.provider.Telephony.Threads;
import android.telephony.SmsManager;
import android.telephony.SmsMessage;
import android.text.TextUtils;
import android.text.util.Rfc822Token;
import android.text.util.Rfc822Tokenizer;

import com.android.messaging.Factory;
import com.android.messaging.R;
import com.android.messaging.datamodel.MediaScratchFileProvider;
import com.android.messaging.datamodel.action.SendMessageAction;
import com.android.messaging.datamodel.data.MessageData;
import com.android.messaging.datamodel.data.MessagePartData;
import com.android.messaging.mmslib.InvalidHeaderValueException;
import com.android.messaging.mmslib.MmsException;
import com.android.messaging.mmslib.SqliteWrapper;
import com.android.messaging.mmslib.pdu.CharacterSets;
import com.android.messaging.mmslib.pdu.EncodedStringValue;
import com.android.messaging.mmslib.pdu.GenericPdu;
import com.android.messaging.mmslib.pdu.NotificationInd;
import com.android.messaging.mmslib.pdu.PduBody;
import com.android.messaging.mmslib.pdu.PduComposer;
import com.android.messaging.mmslib.pdu.PduHeaders;
import com.android.messaging.mmslib.pdu.PduParser;
import com.android.messaging.mmslib.pdu.PduPart;
import com.android.messaging.mmslib.pdu.PduPersister;
import com.android.messaging.mmslib.pdu.RetrieveConf;
import com.android.messaging.mmslib.pdu.SendConf;
import com.android.messaging.mmslib.pdu.SendReq;
import com.android.messaging.sms.SmsSender.SendResult;
import com.android.messaging.util.Assert;
import com.android.messaging.util.BugleGservices;
import com.android.messaging.util.BugleGservicesKeys;
import com.android.messaging.util.BuglePrefs;
import com.android.messaging.util.ContentType;
import com.android.messaging.util.DebugUtils;
import com.android.messaging.util.EmailAddress;
import com.android.messaging.util.ImageUtils;
import com.android.messaging.util.ImageUtils.ImageResizer;
import com.android.messaging.util.LogUtil;
import com.android.messaging.util.MediaMetadataRetrieverWrapper;
import com.android.messaging.util.OsUtil;
import com.android.messaging.util.PhoneUtils;
import com.google.common.base.Joiner;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Utils for sending sms/mms messages.
 */
public class MmsUtils {
    private static final String TAG = LogUtil.BUGLE_TAG;

    public static final boolean DEFAULT_DELIVERY_REPORT_MODE  = false;
    public static final boolean DEFAULT_READ_REPORT_MODE = false;
    public static final long DEFAULT_EXPIRY_TIME_IN_SECONDS = 7 * 24 * 60 * 60;
    public static final int DEFAULT_PRIORITY = PduHeaders.PRIORITY_NORMAL;

    public static final int MAX_SMS_RETRY = 3;

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

    private static final int DEFAULT_DURATION = 5000; //ms

    // amount of space to leave in a MMS for text and overhead.
    private static final int MMS_MAX_SIZE_SLOP = 1024;
    public static final long INVALID_TIMESTAMP = 0L;
    private static String[] sNoSubjectStrings;

    public static class MmsInfo {
        public Uri mUri;
        public int mMessageSize;
        public PduBody mPduBody;
    }

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

    private static final String REMOTE_MMS_SELECTION = String.format(
            Locale.US,
            "((%s IN (%d, %d, %d, %d)) AND (%s IN (%d, %d, %d)))",
            Mms.MESSAGE_BOX,
            Mms.MESSAGE_BOX_INBOX,
            Mms.MESSAGE_BOX_OUTBOX,
            Mms.MESSAGE_BOX_SENT,
            Mms.MESSAGE_BOX_FAILED,
            Mms.MESSAGE_TYPE,
            PduHeaders.MESSAGE_TYPE_SEND_REQ,
            PduHeaders.MESSAGE_TYPE_NOTIFICATION_IND,
            PduHeaders.MESSAGE_TYPE_RETRIEVE_CONF);

    /**
     * Type selection for importing sms messages.
     *
     * @return The SQL selection for importing sms messages
     */
    public static String getSmsTypeSelectionSql() {
        return REMOTE_SMS_SELECTION;
    }

    /**
     * Type selection for importing mms messages.
     *
     * @return The SQL selection for importing mms messages. This selects the message type,
     * not including the selection on timestamp.
     */
    public static String getMmsTypeSelectionSql() {
        return REMOTE_MMS_SELECTION;
    }

    // SMIL spec: http://www.w3.org/TR/SMIL3

    private static final String sSmilImagePart =
            "<par dur=\"" + DEFAULT_DURATION + "ms\">" +
                "<img src=\"%s\" region=\"Image\" />" +
            "</par>";

    private static final String sSmilVideoPart =
            "<par dur=\"%2$dms\">" +
                "<video src=\"%1$s\" dur=\"%2$dms\" region=\"Image\" />" +
            "</par>";

    private static final String sSmilAudioPart =
            "<par dur=\"%2$dms\">" +
                    "<audio src=\"%1$s\" dur=\"%2$dms\" />" +
            "</par>";

    private static final String sSmilTextPart =
            "<par dur=\"" + DEFAULT_DURATION + "ms\">" +
                "<text src=\"%s\" region=\"Text\" />" +
            "</par>";

    private static final String sSmilPart =
            "<par dur=\"" + DEFAULT_DURATION + "ms\">" +
                "<ref src=\"%s\" />" +
            "</par>";

    private static final String sSmilTextOnly =
            "<smil>" +
                "<head>" +
                    "<layout>" +
                        "<root-layout/>" +
                        "<region id=\"Text\" top=\"0\" left=\"0\" "
                          + "height=\"100%%\" width=\"100%%\"/>" +
                    "</layout>" +
                "</head>" +
                "<body>" +
                       "%s" +  // constructed body goes here
                "</body>" +
            "</smil>";

    private static final String sSmilVisualAttachmentsOnly =
            "<smil>" +
                "<head>" +
                    "<layout>" +
                        "<root-layout/>" +
                        "<region id=\"Image\" fit=\"meet\" top=\"0\" left=\"0\" "
                          + "height=\"100%%\" width=\"100%%\"/>" +
                    "</layout>" +
                "</head>" +
                "<body>" +
                       "%s" +  // constructed body goes here
                "</body>" +
            "</smil>";

    private static final String sSmilVisualAttachmentsWithText =
            "<smil>" +
                "<head>" +
                    "<layout>" +
                        "<root-layout/>" +
                        "<region id=\"Image\" fit=\"meet\" top=\"0\" left=\"0\" "
                          + "height=\"80%%\" width=\"100%%\"/>" +
                        "<region id=\"Text\" top=\"80%%\" left=\"0\" height=\"20%%\" "
                          + "width=\"100%%\"/>" +
                    "</layout>" +
                "</head>" +
                "<body>" +
                       "%s" +  // constructed body goes here
                "</body>" +
            "</smil>";

    private static final String sSmilNonVisualAttachmentsOnly =
            "<smil>" +
                "<head>" +
                    "<layout>" +
                        "<root-layout/>" +
                    "</layout>" +
                "</head>" +
                "<body>" +
                       "%s" +  // constructed body goes here
                "</body>" +
            "</smil>";

    private static final String sSmilNonVisualAttachmentsWithText = sSmilTextOnly;

    public static final String MMS_DUMP_PREFIX = "mmsdump-";
    public static final String SMS_DUMP_PREFIX = "smsdump-";

    public static final int MIN_VIDEO_BYTES_PER_SECOND = 4 * 1024;
    public static final int MIN_IMAGE_BYTE_SIZE = 16 * 1024;
    public static final int MAX_VIDEO_ATTACHMENT_COUNT = 1;

    private static void setPartContentLocationAndId(final PduPart part, final String srcName) {
        // Set Content-Location.
        part.setContentLocation(srcName.getBytes());

        // Set Content-Id.
        final int index = srcName.lastIndexOf(".");
        final String contentId = (index == -1) ? srcName : srcName.substring(0, index);
        part.setContentId(contentId.getBytes());
    }

    private static void addPartForUri(final Context context, final PduBody pb,
            final String srcName, final Uri uri, final String contentType) {
        final PduPart part = new PduPart();
        part.setDataUri(uri);
        part.setContentType(contentType.getBytes());

        setPartContentLocationAndId(part, srcName);

        pb.addPart(part);
    }

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
        if (OsUtil.isAtLeastL_MR1()) {
            values.put(Telephony.Sms.SUBSCRIPTION_ID, subId);
        }
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

    // Persist a sent MMS message in telephony
    private static Uri insertSendReq(final Context context, final GenericPdu pdu, final int subId,
            final String subPhoneNumber) {
        final PduPersister persister = PduPersister.getPduPersister(context);
        Uri uri = null;
        try {
            // Persist the PDU
            uri = persister.persist(
                    pdu,
                    Mms.Sent.CONTENT_URI,
                    subId,
                    subPhoneNumber,
                    null/*preOpenedFiles*/);
            // Update mms table to reflect sent messages are always seen and read
            final ContentValues values = new ContentValues(1);
            values.put(Mms.READ, 1);
            values.put(Mms.SEEN, 1);
            SqliteWrapper.update(context, context.getContentResolver(), uri, values, null, null);
        } catch (final MmsException e) {
            LogUtil.e(TAG, "MmsUtils: persist mms sent message failure " + e, e);
        }
        return uri;
    }

    // Persist a received MMS message in telephony
    public static Uri insertReceivedMmsMessage(final Context context,
            final RetrieveConf retrieveConf, final int subId, final String subPhoneNumber,
            final long receivedTimestampInSeconds, final long expiry, final String transactionId) {
        final PduPersister persister = PduPersister.getPduPersister(context);
        Uri uri = null;
        try {
            uri = persister.persist(
                    retrieveConf,
                    Mms.Inbox.CONTENT_URI,
                    subId,
                    subPhoneNumber,
                    null/*preOpenedFiles*/);

            final ContentValues values = new ContentValues(3);
            // Update mms table with local time instead of PDU time
            values.put(Mms.DATE, receivedTimestampInSeconds);
            // Also update the transaction id and the expiry from NotificationInd so that
            // wap push dedup would work even after the wap push is deleted.
            values.put(Mms.TRANSACTION_ID, transactionId);
            values.put(Mms.EXPIRY, expiry);
            SqliteWrapper.update(context, context.getContentResolver(), uri, values, null, null);
            if (LogUtil.isLoggable(TAG, LogUtil.DEBUG)) {
                LogUtil.d(TAG, "MmsUtils: Inserted MMS message into telephony, uri: " + uri);
            }
        } catch (final MmsException e) {
            LogUtil.e(TAG, "MmsUtils: persist mms received message failure " + e, e);
            // Just returns empty uri to RetrieveMmsRequest, which triggers a permanent failure
        } catch (final SQLiteException e) {
            LogUtil.e(TAG, "MmsUtils: update mms received message failure " + e, e);
            // Time update failure is ignored.
        }
        return uri;
    }

    // Update MMS message type in telephony; returns true if it succeeded.
    public static boolean updateMmsMessageSendingStatus(final Context context, final Uri uri,
            final int box, final long timestampInMillis) {
        try {
            final ContentResolver resolver = context.getContentResolver();
            final ContentValues values = new ContentValues();

            final long timestampInSeconds = timestampInMillis / 1000L;
            values.put(Telephony.Mms.MESSAGE_BOX, box);
            values.put(Telephony.Mms.DATE, timestampInSeconds);
            final int cnt = resolver.update(uri, values, null, null);
            if (cnt == 1) {
                if (LogUtil.isLoggable(TAG, LogUtil.DEBUG)) {
                    LogUtil.d(TAG, "Mmsutils: Updated sending MMS " + uri + "; box = " + box
                            + ", date = " + timestampInSeconds + " (secs since epoch)");
                }
                return true;
            }
        } catch (final SQLiteException e) {
            LogUtil.e(TAG, "MmsUtils: update mms message failure " + e, e);
        } catch (final IllegalArgumentException e) {
            LogUtil.e(TAG, "MmsUtils: update mms message failure " + e, e);
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

    /**
     * @return Whether to auto retrieve MMS
     */
    public static boolean allowMmsAutoRetrieve(final int subId) {
        final Context context = Factory.get().getApplicationContext();
        final Resources resources = context.getResources();
        final BuglePrefs prefs = BuglePrefs.getSubscriptionPrefs(subId);
        final boolean autoRetrieve = prefs.getBoolean(
                resources.getString(R.string.auto_retrieve_mms_pref_key),
                resources.getBoolean(R.bool.auto_retrieve_mms_pref_default));
        if (autoRetrieve) {
            final boolean autoRetrieveInRoaming = prefs.getBoolean(
                    resources.getString(R.string.auto_retrieve_mms_when_roaming_pref_key),
                    resources.getBoolean(R.bool.auto_retrieve_mms_when_roaming_pref_default));
            final PhoneUtils phoneUtils = PhoneUtils.get(subId);
            if ((autoRetrieveInRoaming && phoneUtils.isDataRoamingEnabled())
                    || !phoneUtils.isRoaming()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Parse the message row id from a message Uri.
     *
     * @param messageUri The input Uri
     * @return The message row id if valid, otherwise -1
     */
    public static long parseRowIdFromMessageUri(final Uri messageUri) {
        try {
            if (messageUri != null) {
                return ContentUris.parseId(messageUri);
            }
        } catch (final UnsupportedOperationException e) {
            // Nothing to do
        } catch (final NumberFormatException e) {
            // Nothing to do
        }
        return -1;
    }

    public static SmsMessage getSmsMessageFromDeliveryReport(final Intent intent) {
        final byte[] pdu = intent.getByteArrayExtra("pdu");
        final String format = intent.getStringExtra("format");
        return OsUtil.isAtLeastM()
                ? SmsMessage.createFromPdu(pdu, format)
                : SmsMessage.createFromPdu(pdu);
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

    /**
     * Get the SQL selection statement for matching messages with media.
     *
     * Example for MMS part table:
     * "((ct LIKE 'image/%')
     *   OR (ct LIKE 'video/%')
     *   OR (ct LIKE 'audio/%')
     *   OR (ct='application/ogg'))
     *
     * @param contentTypeColumn The content-type column name
     * @return The SQL selection statement for matching media types: image, video, audio
     */
    public static String getMediaTypeSelectionSql(final String contentTypeColumn) {
        return String.format(
                Locale.US,
                "((%s LIKE '%s') OR (%s LIKE '%s') OR (%s LIKE '%s') OR (%s='%s'))",
                contentTypeColumn,
                "image/%",
                contentTypeColumn,
                "video/%",
                contentTypeColumn,
                "audio/%",
                contentTypeColumn,
                ContentType.AUDIO_OGG);
    }

    // Max number of operands per SQL query for deleting SMS messages
    public static final int MAX_IDS_PER_QUERY = 128;

    /**
     * Delete MMS messages with media parts.
     *
     * Because the telephony provider constraints, we can't use JOIN and delete messages in one
     * shot. We have to do a query first and then batch delete the messages based on IDs.
     *
     * @return The count of messages deleted.
     */
    public static int deleteMediaMessages() {
        // Do a query first
        //
        // The WHERE clause has two parts:
        // The first part is to select the exact same types of MMS messages as when we import them
        // (so that we don't delete messages that are not in local database)
        // The second part is to select MMS with media parts, including image, video and audio
        final String selection = String.format(
                Locale.US,
                "%s AND (%s IN (SELECT %s FROM part WHERE %s))",
                getMmsTypeSelectionSql(),
                Mms._ID,
                Mms.Part.MSG_ID,
                getMediaTypeSelectionSql(Mms.Part.CONTENT_TYPE));
        final ContentResolver resolver = Factory.get().getApplicationContext().getContentResolver();
        final Cursor cursor = resolver.query(Mms.CONTENT_URI,
                new String[]{ Mms._ID },
                selection,
                null/*selectionArgs*/,
                null/*sortOrder*/);
        int deleted = 0;
        if (cursor != null) {
            final long[] messageIds = new long[cursor.getCount()];
            try {
                int i = 0;
                while (cursor.moveToNext()) {
                    messageIds[i++] = cursor.getLong(0);
                }
            } finally {
                cursor.close();
            }
            final int totalIds = messageIds.length;
            if (totalIds > 0) {
                // Batch delete the messages using IDs
                // We don't want to send all IDs at once since there is a limit on SQL statement
                for (int start = 0; start < totalIds; start += MAX_IDS_PER_QUERY) {
                    final int end = Math.min(start + MAX_IDS_PER_QUERY, totalIds); // excluding
                    final int count = end - start;
                    final String batchSelection = String.format(
                            Locale.US,
                            "%s IN %s",
                            Mms._ID,
                            getSqlInOperand(count));
                    final String[] batchSelectionArgs =
                            getSqlInOperandArgs(messageIds, start, count);
                    final int deletedForBatch = resolver.delete(
                            Mms.CONTENT_URI,
                            batchSelection,
                            batchSelectionArgs);
                    if (LogUtil.isLoggable(TAG, LogUtil.DEBUG)) {
                        LogUtil.d(TAG, "deleteMediaMessages: deleting IDs = "
                                + Joiner.on(',').skipNulls().join(batchSelectionArgs)
                                + ", deleted = " + deletedForBatch);
                    }
                    deleted += deletedForBatch;
                }
            }
        }
        return deleted;
    }

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
     * Get the args for SQL IN operator from a long ID array
     *
     * @param ids The original long id array
     * @param start Start of the ids to fill the args
     * @param count Number of ids to pack
     * @return The long array with the id args
     */
    private static String[] getSqlInOperandArgs(
            final long[] ids, final int start, final int count) {
        if (count <= 0) {
            return null;
        }
        final String[] args = new String[count];
        for (int i = 0; i < count; i++) {
            args[i] = Long.toString(ids[start + i]);
        }
        return args;
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
        // Delete old MMS
        final String mmsSelection = String.format(
                Locale.US,
                "%s AND (%s<=%d)",
                getMmsTypeSelectionSql(),
                Mms.DATE,
                cutOffTimestampInMillis / 1000L);
        deleted += resolver.delete(Mms.CONTENT_URI, mmsSelection, null/*selectionArgs*/);
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

    /**
     * Update the read status of a single MMS message by its URI
     *
     * @param mmsUri
     * @param read
     */
    public static void updateReadStatusForMmsMessage(final Uri mmsUri, final boolean read) {
        final ContentResolver resolver = Factory.get().getApplicationContext().getContentResolver();
        final ContentValues values = new ContentValues();
        values.put(Mms.READ, read ? 1 : 0);
        resolver.update(mmsUri, values, null/*where*/, null/*selectionArgs*/);
    }

    public static class AttachmentInfo {
        public String mUrl;
        public String mContentType;
        public int mWidth;
        public int mHeight;
    }

    /**
     * Convert byte array to Java String using a charset name
     *
     * @param bytes
     * @param charsetName
     * @return
     */
    public static String bytesToString(final byte[] bytes, final String charsetName) {
        if (bytes == null) {
            return null;
        }
        try {
            return new String(bytes, charsetName);
        } catch (final UnsupportedEncodingException e) {
            LogUtil.e(TAG, "MmsUtils.bytesToString: " + e, e);
            return new String(bytes);
        }
    }

    /**
     * Convert a Java String to byte array using a charset name
     *
     * @param string
     * @param charsetName
     * @return
     */
    public static byte[] stringToBytes(final String string, final String charsetName) {
        if (string == null) {
            return null;
        }
        try {
            return string.getBytes(charsetName);
        } catch (final UnsupportedEncodingException e) {
            LogUtil.e(TAG, "MmsUtils.stringToBytes: " + e, e);
            return string.getBytes();
        }
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
                        context,
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

    private static final String[] TEST_CARRIERS_PROJECTION =
            new String[] { Telephony.Carriers.MMSC };
    private static Boolean sUseSystemApn = null;
    /**
     * Check if we can access the APN data in the Telephony provider. Access was restricted in
     * JB MR1 (and some JB MR2) devices. If we can't access the APN, we have to fall back and use
     * a private table in our own app.
     *
     * @return Whether we can access the system APN table
     */
    public static boolean useSystemApnTable() {
        if (sUseSystemApn == null) {
            Cursor cursor = null;
            try {
                final Context context = Factory.get().getApplicationContext();
                final ContentResolver resolver = context.getContentResolver();
                cursor = SqliteWrapper.query(
                        context,
                        resolver,
                        Telephony.Carriers.CONTENT_URI,
                        TEST_CARRIERS_PROJECTION,
                        null/*selection*/,
                        null/*selectionArgs*/,
                        null);
                sUseSystemApn = true;
            } catch (final SecurityException e) {
                LogUtil.w(TAG, "Can't access system APN, using internal table", e);
                sUseSystemApn = false;
            } finally {
                if (cursor != null) {
                    cursor.close();
                }
            }
        }
        return sUseSystemApn;
    }

    // For the internal debugger only
    public static void setUseSystemApnTable(final boolean turnOn) {
        if (!turnOn) {
            // We're not turning on to the system table. Instead, we're using our internal table.
            final int osVersion = OsUtil.getApiVersion();
            if (osVersion != android.os.Build.VERSION_CODES.JELLY_BEAN_MR1) {
                // We're turning on local APNs on a device where we wouldn't normally have the
                // local APN table. Build it here.

                final SQLiteDatabase database = ApnDatabase.getApnDatabase().getWritableDatabase();

                // Do we already have the table?
                Cursor cursor = null;
                try {
                    cursor = database.query(ApnDatabase.APN_TABLE,
                            ApnDatabase.APN_PROJECTION,
                            null, null, null, null, null, null);
                } catch (final Exception e) {
                    // Apparently there's no table, create it now.
                    ApnDatabase.forceBuildAndLoadApnTables();
                } finally {
                    if (cursor != null) {
                        cursor.close();
                    }
                }
            }
        }
        sUseSystemApn = turnOn;
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
     * Checks if we should dump mms, based on both the setting and the global debug
     * flag
     *
     * @return if dump mms is enabled
     */
    public static boolean isDumpMmsEnabled() {
        if (!DebugUtils.isDebugEnabled()) {
            return false;
        }
        return getDumpSmsOrMmsPref(R.string.dump_mms_pref_key, R.bool.dump_mms_pref_default);
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

    public static final Uri MMS_PART_CONTENT_URI = Uri.parse("content://mms/part");

    /**
     * Get the sender of an MMS message
     *
     * @param recipients The recipient list of the message
     * @param mmsUri The pdu uri of the MMS
     * @return The sender phone number of the MMS
     */
    public static String getMmsSender(final List<String> recipients, final String mmsUri) {
        final Context context = Factory.get().getApplicationContext();
        // We try to avoid the database query.
        // If this is a 1v1 conv., then the other party is the sender
        if (recipients != null && recipients.size() == 1) {
            return recipients.get(0);
        }
        // Otherwise, we have to query the MMS addr table for sender address
        // This should only be done for a received group mms message
        final Cursor cursor = SqliteWrapper.query(
                context,
                context.getContentResolver(),
                Uri.withAppendedPath(Uri.parse(mmsUri), "addr"),
                new String[] { Mms.Addr.ADDRESS, Mms.Addr.CHARSET },
                Mms.Addr.TYPE + "=" + PduHeaders.FROM,
                null/*selectionArgs*/,
                null/*sortOrder*/);
        if (cursor != null) {
            try {
                if (cursor.moveToFirst()) {
                    return DatabaseMessages.MmsAddr.get(cursor);
                }
            } finally {
                cursor.close();
            }
        }
        return null;
    }

    public static int bugleStatusForMms(final boolean isOutgoing, final boolean isNotification,
            final int messageBox) {
        int bugleStatus = MessageData.BUGLE_STATUS_UNKNOWN;
        // For a message we sync either
        if (isOutgoing) {
            if (messageBox == Mms.MESSAGE_BOX_OUTBOX || messageBox == Mms.MESSAGE_BOX_FAILED) {
                // Not sent counts as failed and available for manual resend
                bugleStatus = MessageData.BUGLE_STATUS_OUTGOING_FAILED;
            } else {
                // Otherwise outgoing message is complete
                bugleStatus = MessageData.BUGLE_STATUS_OUTGOING_COMPLETE;
            }
        } else if (isNotification) {
            // Incoming MMS notifications we sync count as failed and available for manual download
            bugleStatus = MessageData.BUGLE_STATUS_INCOMING_YET_TO_MANUAL_DOWNLOAD;
        } else {
            // Other incoming MMS messages are complete
            bugleStatus = MessageData.BUGLE_STATUS_INCOMING_COMPLETE;
        }
        return bugleStatus;
    }

    public static MessageData createMmsMessage(final DatabaseMessages.MmsMessage mms,
            final String conversationId, final String participantId, final String selfId,
            final int bugleStatus) {
        Assert.notNull(mms);
        final boolean isNotification = (mms.mMmsMessageType ==
                PduHeaders.MESSAGE_TYPE_NOTIFICATION_IND);
        final int rawMmsStatus = (bugleStatus < MessageData.BUGLE_STATUS_FIRST_INCOMING
                ? mms.mRetrieveStatus : mms.mResponseStatus);

        final MessageData message = MessageData.createMmsMessage(mms.getUri(),
                participantId, selfId, conversationId, isNotification, bugleStatus,
                mms.mContentLocation, mms.mTransactionId, mms.mPriority, mms.mSubject,
                mms.mSeen, mms.mRead, mms.getSize(), rawMmsStatus,
                mms.mExpiryInMillis, mms.mSentTimestampInMillis, mms.mTimestampInMillis);

        for (final DatabaseMessages.MmsPart part : mms.mParts) {
            final MessagePartData messagePart = MmsUtils.createMmsMessagePart(part);
            // Import media and text parts (skip SMIL and others)
            if (messagePart != null) {
                message.addPart(messagePart);
            }
        }

        if (!message.getParts().iterator().hasNext()) {
            message.addPart(MessagePartData.createEmptyMessagePart());
        }

        return message;
    }

    public static MessagePartData createMmsMessagePart(final DatabaseMessages.MmsPart part) {
        MessagePartData messagePart = null;
        if (part.isText()) {
            final int mmsTextLengthLimit =
                    BugleGservices.get().getInt(BugleGservicesKeys.MMS_TEXT_LIMIT,
                            BugleGservicesKeys.MMS_TEXT_LIMIT_DEFAULT);
            String text = part.mText;
            if (text != null && text.length() > mmsTextLengthLimit) {
                // Limit the text to a reasonable value. We ran into a situation where a vcard
                // with a photo was sent as plain text. The massive amount of text caused the
                // app to hang, ANR, and eventually crash in native text code.
                text = text.substring(0, mmsTextLengthLimit);
            }
            messagePart = MessagePartData.createTextMessagePart(text);
        } else if (part.isMedia()) {
            messagePart = MessagePartData.createMediaMessagePart(part.mContentType,
                    part.getDataUri(), MessagePartData.UNSPECIFIED_SIZE,
                    MessagePartData.UNSPECIFIED_SIZE);
        }
        return messagePart;
    }

    public static class StatusPlusUri {
        // The request status to be as the result of the operation
        // e.g. MMS_REQUEST_MANUAL_RETRY
        public final int status;
        // The raw telephony status
        public final int rawStatus;
        // The raw telephony URI
        public final Uri uri;
        // The operation result code from system api invocation (sent by system)
        // or mapped from internal exception (sent by app)
        public final int resultCode;

        public StatusPlusUri(final int status, final int rawStatus, final Uri uri) {
            this.status = status;
            this.rawStatus = rawStatus;
            this.uri = uri;
            resultCode = MessageData.UNKNOWN_RESULT_CODE;
        }

        public StatusPlusUri(final int status, final int rawStatus, final Uri uri,
                final int resultCode) {
            this.status = status;
            this.rawStatus = rawStatus;
            this.uri = uri;
            this.resultCode = resultCode;
        }
    }

    public static class SendReqResp {
        public SendReq mSendReq;
        public SendConf mSendConf;

        public SendReqResp(final SendReq sendReq, final SendConf sendConf) {
            mSendReq = sendReq;
            mSendConf = sendConf;
        }
    }

    /**
     * Returned when sending/downloading MMS via platform APIs. In that case, we have to wait to
     * receive the pending intent to determine status.
     */
    public static final StatusPlusUri STATUS_PENDING = new StatusPlusUri(-1, -1, null);

    /**
     * Try parsing a PDU without knowing the carrier. This is useful for importing
     * MMS or storing draft when carrier info is not available
     *
     * @param data The PDU data
     * @return Parsed PDU, null if failed to parse
     */
    private static GenericPdu parsePduForAnyCarrier(final byte[] data) {
        GenericPdu pdu = null;
        try {
            pdu = (new PduParser(data, true/*parseContentDisposition*/)).parse();
        } catch (final RuntimeException e) {
            LogUtil.d(TAG, "parsePduForAnyCarrier: Failed to parse PDU with content disposition",
                    e);
        }
        if (pdu == null) {
            try {
                pdu = (new PduParser(data, false/*parseContentDisposition*/)).parse();
            } catch (final RuntimeException e) {
                LogUtil.d(TAG,
                        "parsePduForAnyCarrier: Failed to parse PDU without content disposition",
                        e);
            }
        }
        return pdu;
    }

    private static RetrieveConf receiveFromDumpFile(final byte[] data) throws MmsFailureException {
        final GenericPdu pdu = parsePduForAnyCarrier(data);
        if (pdu == null || !(pdu instanceof RetrieveConf)) {
            LogUtil.e(TAG, "receiveFromDumpFile: Parsing retrieved PDU failure");
            throw new MmsFailureException(MMS_REQUEST_MANUAL_RETRY, "Failed reading dump file");
        }
        return (RetrieveConf) pdu;
    }

    private static boolean isSmsDataAvailable(final int subId) {
        if (OsUtil.isAtLeastL_MR1()) {
            // L_MR1 above may support sending sms via wifi
            return true;
        }
        final PhoneUtils phoneUtils = PhoneUtils.get(subId);
        return !phoneUtils.isAirplaneModeOn();
    }

    // Selection for dedup algorithm:
    // ((m_type=NOTIFICATION_IND) OR (m_type=RETRIEVE_CONF)) AND (exp>NOW)) AND (t_id=xxxxxx)
    // i.e. If it is NotificationInd or RetrieveConf and not expired
    //      AND transaction id is the input id
    private static final String DUP_NOTIFICATION_QUERY_SELECTION =
            "((" + Mms.MESSAGE_TYPE + "=?) OR (" + Mms.MESSAGE_TYPE + "=?)) AND ("
                    + Mms.EXPIRY + ">?) AND (" + Mms.TRANSACTION_ID + "=?)";

    private static final int MAX_RETURN = 32;
    private static String[] getDupNotifications(final Context context, final NotificationInd nInd) {
        final byte[] rawTransactionId = nInd.getTransactionId();
        if (rawTransactionId != null) {
            // dedup algorithm
            String selection = DUP_NOTIFICATION_QUERY_SELECTION;
            final long nowSecs = System.currentTimeMillis() / 1000;
            String[] selectionArgs = new String[] {
                    Integer.toString(PduHeaders.MESSAGE_TYPE_NOTIFICATION_IND),
                    Integer.toString(PduHeaders.MESSAGE_TYPE_RETRIEVE_CONF),
                    Long.toString(nowSecs),
                    new String(rawTransactionId)
            };

            Cursor cursor = null;
            try {
                cursor = SqliteWrapper.query(
                        context, context.getContentResolver(),
                        Mms.CONTENT_URI, new String[] { Mms._ID },
                        selection, selectionArgs, null);
                final int dupCount = cursor.getCount();
                if (dupCount > 0) {
                    // We already received the same notification before.
                    // Don't want to return too many dups. It is only for debugging.
                    final int returnCount = dupCount < MAX_RETURN ? dupCount : MAX_RETURN;
                    final String[] dups = new String[returnCount];
                    for (int i = 0; cursor.moveToNext() && i < returnCount; i++) {
                        dups[i] = cursor.getString(0);
                    }
                    return dups;
                }
            } catch (final SQLiteException e) {
                LogUtil.e(TAG, "query failure: " + e, e);
            } finally {
                cursor.close();
            }
        }
        return null;
    }

    /**
     * Try parse the address using RFC822 format. If it fails to parse, then return the
     * original address
     *
     * @param address The MMS ind sender address to parse
     * @return The real address. If in RFC822 format, returns the correct email.
     */
    private static String parsePotentialRfc822EmailAddress(final String address) {
        if (address == null || !address.contains("@") || !address.contains("<")) {
            return address;
        }
        final Rfc822Token[] tokens = Rfc822Tokenizer.tokenize(address);
        if (tokens != null && tokens.length > 0) {
            for (final Rfc822Token token : tokens) {
                if (token != null && !TextUtils.isEmpty(token.getAddress())) {
                    return token.getAddress();
                }
            }
        }
        return address;
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
        if (!isSmsDataAvailable(subId)) {
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

    public static byte[] createDebugNotificationInd(final String fileName) {
        byte[] pduData = null;
        try {
            final Context context = Factory.get().getApplicationContext();
            // Load the message file
            final byte[] data = DebugUtils.receiveFromDumpFile(fileName);
            final RetrieveConf retrieveConf = receiveFromDumpFile(data);
            // Create the notification
            final NotificationInd notification = new NotificationInd();
            final long expiry = System.currentTimeMillis() / 1000 + 600;
            notification.setTransactionId(fileName.getBytes());
            notification.setMmsVersion(retrieveConf.getMmsVersion());
            notification.setFrom(retrieveConf.getFrom());
            notification.setSubject(retrieveConf.getSubject());
            notification.setExpiry(expiry);
            notification.setMessageSize(data.length);
            notification.setMessageClass(retrieveConf.getMessageClass());

            final Uri.Builder builder = MediaScratchFileProvider.getUriBuilder();
            builder.appendPath(fileName);
            final Uri contentLocation = builder.build();
            notification.setContentLocation(contentLocation.toString().getBytes());

            // Serialize
            pduData = new PduComposer(context, notification).make();
            if (pduData == null || pduData.length < 1) {
                throw new IllegalArgumentException("Empty or zero length PDU data");
            }
        } catch (final MmsFailureException e) {
            // Nothing to do
        } catch (final InvalidHeaderValueException e) {
            // Nothing to do
        }
        return pduData;
    }

    public static int mapRawStatusToErrorResourceId(final int bugleStatus, final int rawStatus) {
        int stringResId = R.string.message_status_send_failed;
        switch (rawStatus) {
            case PduHeaders.RESPONSE_STATUS_ERROR_SERVICE_DENIED:
            case PduHeaders.RESPONSE_STATUS_ERROR_PERMANENT_SERVICE_DENIED:
            //case PduHeaders.RESPONSE_STATUS_ERROR_PERMANENT_REPLY_CHARGING_LIMITATIONS_NOT_MET:
            //case PduHeaders.RESPONSE_STATUS_ERROR_PERMANENT_REPLY_CHARGING_REQUEST_NOT_ACCEPTED:
            //case PduHeaders.RESPONSE_STATUS_ERROR_PERMANENT_REPLY_CHARGING_FORWARDING_DENIED:
            //case PduHeaders.RESPONSE_STATUS_ERROR_PERMANENT_REPLY_CHARGING_NOT_SUPPORTED:
            //case PduHeaders.RESPONSE_STATUS_ERROR_PERMANENT_ADDRESS_HIDING_NOT_SUPPORTED:
            //case PduHeaders.RESPONSE_STATUS_ERROR_PERMANENT_LACK_OF_PREPAID:
                stringResId = R.string.mms_failure_outgoing_service;
                break;
            case PduHeaders.RESPONSE_STATUS_ERROR_SENDING_ADDRESS_UNRESOLVED:
            case PduHeaders.RESPONSE_STATUS_ERROR_TRANSIENT_SENDNG_ADDRESS_UNRESOLVED:
            case PduHeaders.RESPONSE_STATUS_ERROR_PERMANENT_SENDING_ADDRESS_UNRESOLVED:
                stringResId = R.string.mms_failure_outgoing_address;
                break;
            case PduHeaders.RESPONSE_STATUS_ERROR_MESSAGE_FORMAT_CORRUPT:
            case PduHeaders.RESPONSE_STATUS_ERROR_PERMANENT_MESSAGE_FORMAT_CORRUPT:
                stringResId = R.string.mms_failure_outgoing_corrupt;
                break;
            case PduHeaders.RESPONSE_STATUS_ERROR_CONTENT_NOT_ACCEPTED:
            case PduHeaders.RESPONSE_STATUS_ERROR_PERMANENT_CONTENT_NOT_ACCEPTED:
                stringResId = R.string.mms_failure_outgoing_content;
                break;
            case PduHeaders.RESPONSE_STATUS_ERROR_UNSUPPORTED_MESSAGE:
            //case PduHeaders.RESPONSE_STATUS_ERROR_MESSAGE_NOT_FOUND:
            //case PduHeaders.RESPONSE_STATUS_ERROR_TRANSIENT_MESSAGE_NOT_FOUND:
                stringResId = R.string.mms_failure_outgoing_unsupported;
                break;
            case MessageData.RAW_TELEPHONY_STATUS_MESSAGE_TOO_BIG:
                stringResId = R.string.mms_failure_outgoing_too_large;
                break;
        }
        return stringResId;
    }

    /**
     * Dump the raw MMS data into a file
     *
     * @param rawPdu The raw pdu data
     * @param pdu The parsed pdu, used to construct a dump file name
     */
    public static void dumpPdu(final byte[] rawPdu, final GenericPdu pdu) {
        if (rawPdu == null || rawPdu.length < 1) {
            return;
        }
        final String dumpFileName = MmsUtils.MMS_DUMP_PREFIX + getDumpFileId(pdu);
        final File dumpFile = DebugUtils.getDebugFile(dumpFileName, true);
        if (dumpFile != null) {
            try {
                final FileOutputStream fos = new FileOutputStream(dumpFile);
                final BufferedOutputStream bos = new BufferedOutputStream(fos);
                try {
                    bos.write(rawPdu);
                    bos.flush();
                } finally {
                    bos.close();
                }
                DebugUtils.ensureReadable(dumpFile);
            } catch (final IOException e) {
                LogUtil.e(TAG, "dumpPdu: " + e, e);
            }
        }
    }

    /**
     * Get the dump file id based on the parsed PDU
     * 1. Use message id if not empty
     * 2. Use transaction id if message id is empty
     * 3. If all above is empty, use random UUID
     *
     * @param pdu the parsed PDU
     * @return the id of the dump file
     */
    private static String getDumpFileId(final GenericPdu pdu) {
        String fileId = null;
        if (pdu != null && pdu instanceof RetrieveConf) {
            final RetrieveConf retrieveConf = (RetrieveConf) pdu;
            if (retrieveConf.getMessageId() != null) {
                fileId = new String(retrieveConf.getMessageId());
            } else if (retrieveConf.getTransactionId() != null) {
                fileId = new String(retrieveConf.getTransactionId());
            }
        }
        if (TextUtils.isEmpty(fileId)) {
            fileId = UUID.randomUUID().toString();
        }
        return fileId;
    }
}
