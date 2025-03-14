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

import android.content.ContentUris;
import android.database.Cursor;
import android.os.Parcel;
import android.os.Parcelable;
import android.provider.Telephony.Sms;
import android.text.TextUtils;

import com.android.messaging.datamodel.data.MessageData;
import com.android.messaging.util.LogUtil;
import com.android.messaging.util.PhoneUtils;

/**
 * Class contains various SMS/MMS database entities from telephony provider
 */
public class DatabaseMessages {
    private static final String TAG = LogUtil.BUGLE_TAG;

    public abstract static class DatabaseMessage {
        public abstract int getProtocol();
        public abstract String getUri();
        public abstract long getTimestampInMillis();

        @Override
        public boolean equals(final Object other) {
            if (other == null || !(other instanceof DatabaseMessage)) {
                return false;
            }
            final DatabaseMessage otherDbMsg = (DatabaseMessage) other;
            // No need to check timestamp since we only need this when we compare
            // messages at the same timestamp
            return TextUtils.equals(getUri(), otherDbMsg.getUri());
        }

        @Override
        public int hashCode() {
            // No need to check timestamp since we only need this when we compare
            // messages at the same timestamp
            return getUri().hashCode();
        }
    }

    /**
     * SMS message
     */
    public static class SmsMessage extends DatabaseMessage implements Parcelable {
        private static int sIota = 0;
        public static final int INDEX_ID = sIota++;
        public static final int INDEX_TYPE = sIota++;
        public static final int INDEX_ADDRESS = sIota++;
        public static final int INDEX_BODY = sIota++;
        public static final int INDEX_DATE = sIota++;
        public static final int INDEX_THREAD_ID = sIota++;
        public static final int INDEX_STATUS = sIota++;
        public static final int INDEX_READ = sIota++;
        public static final int INDEX_SEEN = sIota++;
        public static final int INDEX_DATE_SENT = sIota++;
        public static final int INDEX_SUB_ID = sIota++;

        private static String[] sProjection;

        public static String[] getProjection() {
            if (sProjection == null) {
                String[] projection = new String[] {
                        Sms._ID,
                        Sms.TYPE,
                        Sms.ADDRESS,
                        Sms.BODY,
                        Sms.DATE,
                        Sms.THREAD_ID,
                        Sms.STATUS,
                        Sms.READ,
                        Sms.SEEN,
                        Sms.DATE_SENT,
                        Sms.SUBSCRIPTION_ID,
                    };
                if (!MmsUtils.hasSmsDateSentColumn()) {
                    projection[INDEX_DATE_SENT] = Sms.DATE;
                }

                sProjection = projection;
            }

            return sProjection;
        }

        public String mUri;
        public String mAddress;
        public String mBody;
        private long mRowId;
        public long mTimestampInMillis;
        public long mTimestampSentInMillis;
        public int mType;
        public long mThreadId;
        public int mStatus;
        public boolean mRead;
        public boolean mSeen;
        public int mSubId;

        private SmsMessage() {
        }

        /**
         * Load from a cursor of a query that returns the SMS to import
         *
         * @param cursor
         */
        private void load(final Cursor cursor) {
            mRowId = cursor.getLong(INDEX_ID);
            mAddress = cursor.getString(INDEX_ADDRESS);
            mBody = cursor.getString(INDEX_BODY);
            mTimestampInMillis = cursor.getLong(INDEX_DATE);
            // Before ICS, there is no "date_sent" so use copy of "date" value
            mTimestampSentInMillis = cursor.getLong(INDEX_DATE_SENT);
            mType = cursor.getInt(INDEX_TYPE);
            mThreadId = cursor.getLong(INDEX_THREAD_ID);
            mStatus = cursor.getInt(INDEX_STATUS);
            mRead = cursor.getInt(INDEX_READ) == 0 ? false : true;
            mSeen = cursor.getInt(INDEX_SEEN) == 0 ? false : true;
            mUri = ContentUris.withAppendedId(Sms.CONTENT_URI, mRowId).toString();
            mSubId = PhoneUtils.getDefault().getSubIdFromTelephony(cursor, INDEX_SUB_ID);
        }

        /**
         * Get a new SmsMessage by loading from the cursor of a query
         * that returns the SMS to import
         *
         * @param cursor
         * @return
         */
        public static SmsMessage get(final Cursor cursor) {
            final SmsMessage msg = new SmsMessage();
            msg.load(cursor);
            return msg;
        }

        @Override
        public String getUri() {
            return mUri;
        }

        public int getSubId() {
            return mSubId;
        }

        @Override
        public int getProtocol() {
            return MessageData.PROTOCOL_SMS;
        }

        @Override
        public long getTimestampInMillis() {
            return mTimestampInMillis;
        }

        @Override
        public int describeContents() {
            return 0;
        }

        private SmsMessage(final Parcel in) {
            mUri = in.readString();
            mRowId = in.readLong();
            mTimestampInMillis = in.readLong();
            mTimestampSentInMillis = in.readLong();
            mType = in.readInt();
            mThreadId = in.readLong();
            mStatus = in.readInt();
            mRead = in.readInt() != 0;
            mSeen = in.readInt() != 0;
            mSubId = in.readInt();

            // SMS specific
            mAddress = in.readString();
            mBody = in.readString();
        }

        public static final Parcelable.Creator<SmsMessage> CREATOR
                = new Parcelable.Creator<SmsMessage>() {
            @Override
            public SmsMessage createFromParcel(final Parcel in) {
                return new SmsMessage(in);
            }

            @Override
            public SmsMessage[] newArray(final int size) {
                return new SmsMessage[size];
            }
        };

        @Override
        public void writeToParcel(final Parcel out, final int flags) {
            out.writeString(mUri);
            out.writeLong(mRowId);
            out.writeLong(mTimestampInMillis);
            out.writeLong(mTimestampSentInMillis);
            out.writeInt(mType);
            out.writeLong(mThreadId);
            out.writeInt(mStatus);
            out.writeInt(mRead ? 1 : 0);
            out.writeInt(mSeen ? 1 : 0);
            out.writeInt(mSubId);

            // SMS specific
            out.writeString(mAddress);
            out.writeString(mBody);
        }
    }

    /**
     * This class provides the same DatabaseMessage interface over a local SMS db message
     */
    public static class LocalDatabaseMessage extends DatabaseMessage implements Parcelable {
        private final int mProtocol;
        private final String mUri;
        private final long mTimestamp;
        private final long mLocalId;
        private final String mConversationId;

        public LocalDatabaseMessage(final long localId, final int protocol, final String uri,
                final long timestamp, final String conversationId) {
            mLocalId = localId;
            mProtocol = protocol;
            mUri = uri;
            mTimestamp = timestamp;
            mConversationId = conversationId;
        }

        @Override
        public int getProtocol() {
            return mProtocol;
        }

        @Override
        public long getTimestampInMillis() {
            return mTimestamp;
        }

        @Override
        public String getUri() {
            return mUri;
        }

        public long getLocalId() {
            return mLocalId;
        }

        public String getConversationId() {
            return mConversationId;
        }

        @Override
        public int describeContents() {
            return 0;
        }

        private LocalDatabaseMessage(final Parcel in) {
            mUri = in.readString();
            mConversationId = in.readString();
            mLocalId = in.readLong();
            mTimestamp = in.readLong();
            mProtocol = in.readInt();
        }

        public static final Parcelable.Creator<LocalDatabaseMessage> CREATOR
                = new Parcelable.Creator<LocalDatabaseMessage>() {
            @Override
            public LocalDatabaseMessage createFromParcel(final Parcel in) {
                return new LocalDatabaseMessage(in);
            }

            @Override
            public LocalDatabaseMessage[] newArray(final int size) {
                return new LocalDatabaseMessage[size];
            }
        };

        @Override
        public void writeToParcel(final Parcel out, final int flags) {
            out.writeString(mUri);
            out.writeString(mConversationId);
            out.writeLong(mLocalId);
            out.writeLong(mTimestamp);
            out.writeInt(mProtocol);
        }
    }
}
