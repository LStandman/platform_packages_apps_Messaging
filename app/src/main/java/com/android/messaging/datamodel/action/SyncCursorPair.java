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

package com.android.messaging.datamodel.action;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteException;
import android.provider.Telephony.Sms;

import com.android.messaging.Factory;
import com.android.messaging.datamodel.DatabaseHelper;
import com.android.messaging.datamodel.DatabaseWrapper;
import com.android.messaging.datamodel.DatabaseHelper.MessageColumns;
import com.android.messaging.datamodel.SyncManager.ThreadInfoCache;
import com.android.messaging.mmslib.SqliteWrapper;
import com.android.messaging.sms.DatabaseMessages.DatabaseMessage;
import com.android.messaging.sms.DatabaseMessages.LocalDatabaseMessage;
import com.android.messaging.sms.DatabaseMessages.SmsMessage;
import com.android.messaging.sms.MmsUtils;
import com.android.messaging.util.Assert;
import com.android.messaging.util.LogUtil;
import com.google.common.collect.Sets;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Class holding a pair of cursors - one for local db and one for telephony provider - allowing
 * synchronous stepping through messages as part of sync.
 */
class SyncCursorPair {
    private static final String TAG = LogUtil.BUGLE_TAG;

    static final long SYNC_COMPLETE = -1L;
    static final long SYNC_STARTING = Long.MAX_VALUE;

    private CursorIterator mLocalCursorIterator;
    private CursorIterator mRemoteCursorsIterator;

    private final String mLocalSelection;
    private final String mRemoteSmsSelection;

    SyncCursorPair(final long lowerBound, final long upperBound) {
        mLocalSelection = getTimeConstrainedQuery(
                LOCAL_MESSAGES_SELECTION,
                MessageColumns.RECEIVED_TIMESTAMP,
                lowerBound,
                upperBound
                /* threadColumn */  /* threadId */);
        mRemoteSmsSelection = getTimeConstrainedQuery(
                getSmsTypeSelectionSql(),
                "date",
                lowerBound,
                upperBound
                /* threadColumn */  /* threadId */);
    }

    void query(final DatabaseWrapper db) {
        // Load local messages in the sync window
        mLocalCursorIterator = new LocalCursorIterator(db, mLocalSelection);
        // Load remote messages in the sync window
        mRemoteCursorsIterator = new RemoteCursorsIterator(mRemoteSmsSelection);
    }

    boolean isSynchronized(final DatabaseWrapper db) {
        return isSynchronized(db, mLocalSelection, mRemoteSmsSelection);
    }

    void close() {
        if (mLocalCursorIterator != null) {
            mLocalCursorIterator.close();
        }
        if (mRemoteCursorsIterator != null) {
            mRemoteCursorsIterator.close();
        }
    }

    long scan(final int maxMessagesToScan,
            final int maxMessagesToUpdate, final ArrayList<SmsMessage> smsToAdd,
            final ArrayList<LocalDatabaseMessage> messagesToDelete,
            final ThreadInfoCache threadInfoCache) {
        // Set of local messages matched with the timestamp of a remote message
        final Set<DatabaseMessage> matchedLocalMessages = Sets.newHashSet();
        // Set of remote messages matched with the timestamp of a local message
        final Set<DatabaseMessage> matchedRemoteMessages = Sets.newHashSet();
        long lastTimestampMillis = SYNC_STARTING;
        // Number of messages scanned local and remote
        int localCount = 0;
        int remoteCount = 0;
        // Seed the initial values of remote and local messages for comparison
        DatabaseMessage remoteMessage = mRemoteCursorsIterator.next();
        DatabaseMessage localMessage = mLocalCursorIterator.next();
        // Iterate through messages on both sides in reverse time order
        // Import messages in remote not in local, delete messages in local not in remote
        while (localCount + remoteCount < maxMessagesToScan && smsToAdd.size()
                + messagesToDelete.size() < maxMessagesToUpdate) {
            if (remoteMessage == null && localMessage == null) {
                // No more message on both sides - scan complete
                lastTimestampMillis = SYNC_COMPLETE;
                break;
            } else if (remoteMessage == null || localMessage != null && localMessage.getTimestampInMillis()
                    > remoteMessage.getTimestampInMillis()) {
                // Found a local message that is not in remote db
                // Delete the local message
                messagesToDelete.add((LocalDatabaseMessage) localMessage);
                lastTimestampMillis = Math.min(lastTimestampMillis,
                        localMessage.getTimestampInMillis());
                // Advance to next local message
                localMessage = mLocalCursorIterator.next();
                localCount += 1;
            } else if (localMessage == null || localMessage.getTimestampInMillis()
                    < remoteMessage.getTimestampInMillis()) {
                // Found a remote message that is not in local db
                // Add the remote message
                saveMessageToAdd(smsToAdd, remoteMessage, threadInfoCache);
                lastTimestampMillis = Math.min(lastTimestampMillis,
                        remoteMessage.getTimestampInMillis());
                // Advance to next remote message
                remoteMessage = mRemoteCursorsIterator.next();
                remoteCount += 1;
            } else {
                // Found remote and local messages at the same timestamp
                final long matchedTimestamp = localMessage.getTimestampInMillis();
                lastTimestampMillis = Math.min(lastTimestampMillis, matchedTimestamp);
                // Get the next local and remote messages
                final DatabaseMessage remoteMessagePeek = mRemoteCursorsIterator.next();
                final DatabaseMessage localMessagePeek = mLocalCursorIterator.next();
                // Check if only one message on each side matches the current timestamp
                // by looking at the next messages on both sides. If they are either null
                // (meaning no more messages) or having a different timestamp. We want
                // to optimize for this since this is the most common case when majority
                // of the messages are in sync (so they one-to-one pair up at each timestamp),
                // by not allocating the data structures required to compare a set of
                // messages from both sides.
                if ((remoteMessagePeek == null ||
                        remoteMessagePeek.getTimestampInMillis() != matchedTimestamp) &&
                        (localMessagePeek == null ||
                            localMessagePeek.getTimestampInMillis() != matchedTimestamp)) {
                    // Optimize the common case where only one message on each side
                    // that matches the same timestamp
                    if (!remoteMessage.equals(localMessage)) {
                        // local != remote
                        // Delete local message
                        messagesToDelete.add((LocalDatabaseMessage) localMessage);
                        // Add remote message
                        saveMessageToAdd(smsToAdd, remoteMessage, threadInfoCache);
                    }
                    // Get next local and remote messages
                    localMessage = localMessagePeek;
                    remoteMessage = remoteMessagePeek;
                    localCount += 1;
                    remoteCount += 1;
                } else {
                    // Rare case in which multiple messages are in the same timestamp
                    // on either or both sides
                    // Gather all the matched remote messages
                    matchedRemoteMessages.clear();
                    matchedRemoteMessages.add(remoteMessage);
                    remoteCount += 1;
                    remoteMessage = remoteMessagePeek;
                    while (remoteMessage != null &&
                        remoteMessage.getTimestampInMillis() == matchedTimestamp) {
                        Assert.isTrue(!matchedRemoteMessages.contains(remoteMessage));
                        matchedRemoteMessages.add(remoteMessage);
                        remoteCount += 1;
                        remoteMessage = mRemoteCursorsIterator.next();
                    }
                    // Gather all the matched local messages
                    matchedLocalMessages.clear();
                    matchedLocalMessages.add(localMessage);
                    localCount += 1;
                    localMessage = localMessagePeek;
                    while (localMessage != null &&
                            localMessage.getTimestampInMillis() == matchedTimestamp) {
                        if (matchedLocalMessages.contains(localMessage)) {
                            // Duplicate message is local database is deleted
                            messagesToDelete.add((LocalDatabaseMessage) localMessage);
                        } else {
                            matchedLocalMessages.add(localMessage);
                        }
                        localCount += 1;
                        localMessage = mLocalCursorIterator.next();
                    }
                    // Delete messages local only
                    for (final DatabaseMessage msg : Sets.difference(
                            matchedLocalMessages, matchedRemoteMessages)) {
                        messagesToDelete.add((LocalDatabaseMessage) msg);
                    }
                    // Add messages remote only
                    for (final DatabaseMessage msg : Sets.difference(
                            matchedRemoteMessages, matchedLocalMessages)) {
                        saveMessageToAdd(smsToAdd, msg, threadInfoCache);
                    }
                }
            }
        }
        return lastTimestampMillis;
    }

    int getLocalPosition() {
        return mLocalCursorIterator.getPosition();
    }

    int getRemotePosition() {
        return mRemoteCursorsIterator.getPosition();
    }

    int getLocalCount() {
        return mLocalCursorIterator.getCount();
    }

    int getRemoteCount() {
        return mRemoteCursorsIterator.getCount();
    }

    /**
     * An iterator for a database cursor
     */
    interface CursorIterator {
        /**
         * Move to next element in the cursor
         *
         * @return The next element (which becomes the current)
         */
        DatabaseMessage next();
        /**
         * Close the cursor
         */
        void close();
        /**
         * Get the position
         */
        int getPosition();
        /**
         * Get the count
         */
        int getCount();
    }

    private static final String ORDER_BY_DATE_DESC = "date DESC";

    // A subquery that selects SMS/MMS messages in Bugle which are also in telephony
    private static final String LOCAL_MESSAGES_SELECTION = String.format(
            Locale.US,
            "(%s NOTNULL)",
            MessageColumns.SMS_MESSAGE_URI);

    private static final String ORDER_BY_TIMESTAMP_DESC =
            MessageColumns.RECEIVED_TIMESTAMP + " DESC";

    // TODO : This should move into the provider
    private static class LocalMessageQuery {
        private static final String[] PROJECTION = new String[] {
                MessageColumns._ID,
                MessageColumns.RECEIVED_TIMESTAMP,
                MessageColumns.SMS_MESSAGE_URI,
                MessageColumns.PROTOCOL,
                MessageColumns.CONVERSATION_ID,
        };
        private static final int INDEX_MESSAGE_ID = 0;
        private static final int INDEX_MESSAGE_TIMESTAMP = 1;
        private static final int INDEX_SMS_MESSAGE_URI = 2;
        private static final int INDEX_MESSAGE_SMS_TYPE = 3;
        private static final int INDEX_CONVERSATION_ID = 4;
    }

    /**
     * This class provides the same DatabaseMessage interface over a local SMS db message
     */
    private static LocalDatabaseMessage getLocalDatabaseMessage(final Cursor cursor) {
        if (cursor == null) {
            return null;
        }
        return new LocalDatabaseMessage(
                cursor.getLong(LocalMessageQuery.INDEX_MESSAGE_ID),
                cursor.getInt(LocalMessageQuery.INDEX_MESSAGE_SMS_TYPE),
                cursor.getString(LocalMessageQuery.INDEX_SMS_MESSAGE_URI),
                cursor.getLong(LocalMessageQuery.INDEX_MESSAGE_TIMESTAMP),
                cursor.getString(LocalMessageQuery.INDEX_CONVERSATION_ID));
    }

    /**
     * The buffered cursor iterator for local SMS
     */
    private static class LocalCursorIterator implements CursorIterator {
        private Cursor mCursor;

        LocalCursorIterator(final DatabaseWrapper database, final String selection)
                throws SQLiteException {
            try {
                if (LogUtil.isLoggable(TAG, LogUtil.VERBOSE)) {
                    LogUtil.v(TAG, "SyncCursorPair: Querying for local messages; selection = "
                            + selection);
                }
                mCursor = database.query(
                        DatabaseHelper.MESSAGES_TABLE,
                        LocalMessageQuery.PROJECTION,
                        selection,
                        null /*selectionArgs*/,
                        null/*groupBy*/,
                        null/*having*/,
                        ORDER_BY_TIMESTAMP_DESC);
            } catch (final SQLiteException e) {
                LogUtil.e(TAG, "SyncCursorPair: failed to query local sms/mms", e);
                // Can't query local database. So let's throw up the exception and abort sync
                // because we may end up import duplicate messages.
                throw e;
            }
        }

        @Override
        public DatabaseMessage next() {
            if (mCursor != null && mCursor.moveToNext()) {
                return getLocalDatabaseMessage(mCursor);
            }
            return null;
        }

        @Override
        public int getCount() {
            return (mCursor == null ? 0 : mCursor.getCount());
        }

        @Override
        public int getPosition() {
            return (mCursor == null ? 0 : mCursor.getPosition());
        }

        @Override
        public void close() {
            if (mCursor != null) {
                mCursor.close();
                mCursor = null;
            }
        }
    }

    /**
     * The cursor iterator for remote sms.
     * Since SMS and MMS are stored in different tables in telephony provider,
     * this class merges the two cursors and provides a unified view of messages
     * from both cursors. Note that the order is DESC.
     */
    private static class RemoteCursorsIterator implements CursorIterator {
        private Cursor mSmsCursor;
        private DatabaseMessage mNextSms;

        RemoteCursorsIterator(final String smsSelection)
                throws SQLiteException {
            mSmsCursor = null;
            try {
                final Context context = Factory.get().getApplicationContext();
                if (LogUtil.isLoggable(TAG, LogUtil.VERBOSE)) {
                    LogUtil.v(TAG, "SyncCursorPair: Querying for remote SMS; selection = "
                            + smsSelection);
                }
                mSmsCursor = SqliteWrapper.query(
                        context,
                        context.getContentResolver(),
                        Sms.CONTENT_URI,
                        SmsMessage.getProjection(),
                        smsSelection,
                        null /* selectionArgs */,
                        ORDER_BY_DATE_DESC);
                if (mSmsCursor == null) {
                    LogUtil.w(TAG, "SyncCursorPair: Remote SMS query returned null cursor; "
                            + "need to cancel sync");
                    throw new RuntimeException("Null cursor from remote SMS query");
                }
                // Move to the first element in the combined stream from both cursors
                mNextSms = getSmsCursorNext();
            } catch (final SQLiteException e) {
                LogUtil.e(TAG, "SyncCursorPair: failed to query remote messages", e);
                // If we ignore this, the following code would think there is no remote message
                // and will delete all the local sms. We should be cautious here. So instead,
                // let's throw the exception to the caller and abort sms sync. We do the same
                // thing if either of the remote cursors is null.
                throw e;
            }
        }

        @Override
        public DatabaseMessage next() {
            DatabaseMessage result = null;
            if (mNextSms != null) {
                result = mNextSms;
                mNextSms = getSmsCursorNext();
            }
            return result;
        }

        private DatabaseMessage getSmsCursorNext() {
            if (mSmsCursor != null && mSmsCursor.moveToNext()) {
                return SmsMessage.get(mSmsCursor);
            }
            return null;
        }

        @Override
        // Return approximate cursor position allowing for read ahead on two cursors (hence -1)
        public int getPosition() {
            return (mSmsCursor == null ? 0 : mSmsCursor.getPosition()) - 1;
        }

        @Override
        public int getCount() {
            return (mSmsCursor == null ? 0 : mSmsCursor.getCount());
        }

        @Override
        public void close() {
            if (mSmsCursor != null) {
                mSmsCursor.close();
                mSmsCursor = null;
            }
        }
    }

    /**
     * Type selection for importing sms messages. Only SENT and INBOX messages are imported.
     *
     * @return The SQL selection for importing sms messages
     */
    public static String getSmsTypeSelectionSql() {
        return MmsUtils.getSmsTypeSelectionSql();
    }

    /**
     * Get a SQL selection string using an existing selection and time window limits
     * The limits are not applied if the value is < 0
     *
     * @param typeSelection The existing selection
     * @param from          The inclusive lower bound
     * @param to            The exclusive upper bound
     * @return The created SQL selection
     */
    private static String getTimeConstrainedQuery(final String typeSelection,
            final String timeColumn, final long from, final long to) {
        final StringBuilder queryBuilder = new StringBuilder();
        queryBuilder.append(typeSelection);
        if (from > 0) {
            queryBuilder.append(" AND ").append(timeColumn).append(">=").append(from);
        }
        if (to > 0) {
            queryBuilder.append(" AND ").append(timeColumn).append("<").append(to);
        }
        return queryBuilder.toString();
    }

    private static final String[] COUNT_PROJECTION = new String[] { "count()" };

    private static int getCountFromCursor(final Cursor cursor) {
        if (cursor != null && cursor.moveToFirst()) {
            return cursor.getInt(0);
        }
        // We should only return a number if we were able to read it from the cursor.
        // Otherwise, we throw an exception to cancel the sync.
        String cursorDesc = "";
        if (cursor == null) {
            cursorDesc = "null";
        } else if (cursor.getCount() == 0) {
            cursorDesc = "empty";
        }
        throw new IllegalArgumentException("Cannot get count from " + cursorDesc + " cursor");
    }

    private void saveMessageToAdd(final List<SmsMessage> smsToAdd,
            final DatabaseMessage message, final ThreadInfoCache threadInfoCache) {
        long threadId;
        final SmsMessage sms = (SmsMessage) message;
        smsToAdd.add(sms);
        threadId = sms.mThreadId;
        // Cache the lookup and canonicalization of the phone number outside of the transaction...
        threadInfoCache.getThreadRecipients(threadId);
    }

    /**
     * Check if SMS has been synchronized. We compare the counts of messages on both
     * sides and return true if they are equal.
     * <p>
     * Note that this may not be the most reliable way to tell if messages are in sync.
     * For example, the local misses one message and has one obsolete message.
     * However, we have background sms sync once a while, also some other events might
     * trigger a full sync. So we will eventually catch up. And this should be rare to
     * happen.
     *
     * @return If sms is in sync with telephony sms/mms providers
     */
    private static boolean isSynchronized(final DatabaseWrapper db, final String localSelection,
                                          final String smsSelection) {
        final Context context = Factory.get().getApplicationContext();
        Cursor localCursor = null;
        Cursor remoteSmsCursor = null;
        try {
            localCursor = db.query(
                    DatabaseHelper.MESSAGES_TABLE,
                    COUNT_PROJECTION,
                    localSelection,
                    null,
                    null/*groupBy*/,
                    null/*having*/,
                    null/*orderBy*/);
            final int localCount = getCountFromCursor(localCursor);
            remoteSmsCursor = SqliteWrapper.query(
                    context,
                    context.getContentResolver(),
                    Sms.CONTENT_URI,
                    COUNT_PROJECTION,
                    smsSelection,
                    null,
                    null/*orderBy*/);
            final int remoteCount = getCountFromCursor(remoteSmsCursor);
            final boolean isInSync = (localCount == remoteCount);
            if (isInSync) {
                if (LogUtil.isLoggable(TAG, LogUtil.DEBUG)) {
                    LogUtil.d(TAG, "SyncCursorPair: Same # of local and remote messages = "
                            + localCount);
                }
            } else {
                LogUtil.i(TAG, "SyncCursorPair: Not in sync; # local messages = " + localCount
                        + ", # remote message = " + remoteCount);
            }
            return isInSync;
        } catch (final Exception e) {
            LogUtil.e(TAG, "SyncCursorPair: failed to query local or remote message counts", e);
            // If something is wrong in querying database, assume we are synced so
            // we don't retry indefinitely
        } finally {
            if (localCursor != null) {
                localCursor.close();
            }
            if (remoteSmsCursor != null) {
                remoteSmsCursor.close();
            }
        }
        return true;
    }
}
