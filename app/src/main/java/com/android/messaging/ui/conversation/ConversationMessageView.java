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

import android.content.Context;
import android.content.res.Resources;
import android.database.Cursor;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import androidx.annotation.Nullable;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.text.format.Formatter;
import android.text.util.Linkify;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView.ScaleType;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.android.messaging.R;
import com.android.messaging.datamodel.data.ConversationMessageData;
import com.android.messaging.datamodel.data.MessageData;
import com.android.messaging.datamodel.data.MessagePartData;
import com.android.messaging.datamodel.data.SubscriptionListData.SubscriptionListEntry;
import com.android.messaging.sms.MmsUtils;
import com.android.messaging.ui.AsyncImageView;
import com.android.messaging.ui.AsyncImageView.AsyncImageViewDelayLoader;
import com.android.messaging.ui.ContactIconView;
import com.android.messaging.ui.ConversationDrawables;
import com.android.messaging.util.AccessibilityUtil;
import com.android.messaging.util.Assert;
import com.android.messaging.util.AvatarUriUtil;
import com.android.messaging.util.ContentType;
import com.android.messaging.util.ImageUtils;
import com.android.messaging.util.OsUtil;
import com.android.messaging.util.PhoneUtils;
import com.android.messaging.util.UiUtils;
import com.google.common.base.Predicate;

import java.util.Comparator;

/**
 * The view for a single entry in a conversation.
 */
public class ConversationMessageView extends FrameLayout implements View.OnClickListener,
        View.OnLongClickListener {
    public interface ConversationMessageViewHost {
        SubscriptionListEntry getSubscriptionEntryForSelfParticipant(String selfParticipantId,
                boolean excludeDefault);
    }

    private final ConversationMessageData mData;
    private AsyncImageView mMessageImageView;
    private TextView mMessageTextView;
    private boolean mMessageTextHasLinks;
    private boolean mMessageHasYouTubeLink;
    private TextView mStatusTextView;
    private TextView mTitleTextView;
    private TextView mMmsInfoTextView;
    private LinearLayout mMessageTitleLayout;
    private TextView mSenderNameTextView;
    private ContactIconView mContactIconView;
    private ConversationMessageBubbleView mMessageBubble;
    private View mSubjectView;
    private TextView mSubjectLabel;
    private TextView mSubjectText;
    private View mDeliveredBadge;
    private ViewGroup mMessageMetadataView;
    private ViewGroup mMessageTextAndInfoView;
    private TextView mSimNameView;

    private boolean mOneOnOne;
    private ConversationMessageViewHost mHost;

    public ConversationMessageView(final Context context, final AttributeSet attrs) {
        super(context, attrs);
        // TODO: we should switch to using Binding and DataModel factory methods.
        mData = new ConversationMessageData();
    }

    @Override
    protected void onFinishInflate() {
        mContactIconView = (ContactIconView) findViewById(R.id.conversation_icon);
        mContactIconView.setOnLongClickListener(new OnLongClickListener() {
            @Override
            public boolean onLongClick(final View view) {
                ConversationMessageView.this.performLongClick();
                return true;
            }
        });

        mMessageImageView = (AsyncImageView) findViewById(R.id.message_image);
        mMessageImageView.setOnClickListener(this);
        mMessageImageView.setOnLongClickListener(this);

        mMessageTextView = (TextView) findViewById(R.id.message_text);
        mMessageTextView.setOnClickListener(this);
        IgnoreLinkLongClickHelper.ignoreLinkLongClick(mMessageTextView, this);

        mStatusTextView = (TextView) findViewById(R.id.message_status);
        mTitleTextView = (TextView) findViewById(R.id.message_title);
        mMmsInfoTextView = (TextView) findViewById(R.id.mms_info);
        mMessageTitleLayout = (LinearLayout) findViewById(R.id.message_title_layout);
        mSenderNameTextView = (TextView) findViewById(R.id.message_sender_name);
        mMessageBubble = (ConversationMessageBubbleView) findViewById(R.id.message_content);
        mSubjectView = findViewById(R.id.subject_container);
        mSubjectLabel = (TextView) mSubjectView.findViewById(R.id.subject_label);
        mSubjectText = (TextView) mSubjectView.findViewById(R.id.subject_text);
        mDeliveredBadge = findViewById(R.id.smsDeliveredBadge);
        mMessageMetadataView = (ViewGroup) findViewById(R.id.message_metadata);
        mMessageTextAndInfoView = (ViewGroup) findViewById(R.id.message_text_and_info);
        mSimNameView = (TextView) findViewById(R.id.sim_name);
    }

    @Override
    protected void onMeasure(final int widthMeasureSpec, final int heightMeasureSpec) {
        final int horizontalSpace = MeasureSpec.getSize(widthMeasureSpec);
        final int iconSize = getResources()
                .getDimensionPixelSize(R.dimen.conversation_message_contact_icon_size);

        final int unspecifiedMeasureSpec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
        final int iconMeasureSpec = MeasureSpec.makeMeasureSpec(iconSize, MeasureSpec.EXACTLY);

        mContactIconView.measure(iconMeasureSpec, iconMeasureSpec);

        final int arrowWidth =
                getResources().getDimensionPixelSize(R.dimen.message_bubble_arrow_width);

        // We need to subtract contact icon width twice from the horizontal space to get
        // the max leftover space because we want the message bubble to extend no further than the
        // starting position of the message bubble in the opposite direction.
        final int maxLeftoverSpace = horizontalSpace - mContactIconView.getMeasuredWidth() * 2
                - arrowWidth - getPaddingLeft() - getPaddingRight();
        final int messageContentWidthMeasureSpec = MeasureSpec.makeMeasureSpec(maxLeftoverSpace,
                MeasureSpec.AT_MOST);

        mMessageBubble.measure(messageContentWidthMeasureSpec, unspecifiedMeasureSpec);

        final int maxHeight = Math.max(mContactIconView.getMeasuredHeight(),
                mMessageBubble.getMeasuredHeight());
        setMeasuredDimension(horizontalSpace, maxHeight + getPaddingBottom() + getPaddingTop());
    }

    @Override
    protected void onLayout(final boolean changed, final int left, final int top, final int right,
            final int bottom) {
        final boolean isRtl = AccessibilityUtil.isLayoutRtl(this);

        final int iconWidth = mContactIconView.getMeasuredWidth();
        final int iconHeight = mContactIconView.getMeasuredHeight();
        final int iconTop = getPaddingTop();
        final int contentWidth = (right -left) - iconWidth - getPaddingLeft() - getPaddingRight();
        final int contentHeight = mMessageBubble.getMeasuredHeight();
        final int contentTop = iconTop;

        final int iconLeft;
        final int contentLeft;
        if (mData.getIsIncoming()) {
            if (isRtl) {
                iconLeft = (right - left) - getPaddingRight() - iconWidth;
                contentLeft = iconLeft - contentWidth;
            } else {
                iconLeft = getPaddingLeft();
                contentLeft = iconLeft + iconWidth;
            }
        } else {
            if (isRtl) {
                iconLeft = getPaddingLeft();
                contentLeft = iconLeft + iconWidth;
            } else {
                iconLeft = (right - left) - getPaddingRight() - iconWidth;
                contentLeft = iconLeft - contentWidth;
            }
        }

        mContactIconView.layout(iconLeft, iconTop, iconLeft + iconWidth, iconTop + iconHeight);

        mMessageBubble.layout(contentLeft, contentTop, contentLeft + contentWidth,
                contentTop + contentHeight);
    }

    /**
     * Fills in the data associated with this view.
     *
     * @param cursor The cursor from a MessageList that this view is in, pointing to its entry.
     */
    public void bind(final Cursor cursor) {
        bind(cursor, true, null);
    }

    /**
     * Fills in the data associated with this view.
     *
     * @param cursor The cursor from a MessageList that this view is in, pointing to its entry.
     * @param oneOnOne Whether this is a 1:1 conversation
     */
    public void bind(final Cursor cursor,
            final boolean oneOnOne, final String selectedMessageId) {
        mOneOnOne = oneOnOne;

        // Update our UI model
        mData.bind(cursor);
        setSelected(TextUtils.equals(mData.getMessageId(), selectedMessageId));

        // Update text and image content for the view.
        updateViewContent();

        // Update colors and layout parameters for the view.
        updateViewAppearance();

        updateContentDescription();
    }

    public void setHost(final ConversationMessageViewHost host) {
        mHost = host;
    }

    /**
     * Sets a delay loader instance to manage loading / resuming of image attachments.
     */
    public void setImageViewDelayLoader(final AsyncImageViewDelayLoader delayLoader) {
        Assert.notNull(mMessageImageView);
        mMessageImageView.setDelayLoader(delayLoader);
    }

    public ConversationMessageData getData() {
        return mData;
    }

    /**
     * Returns whether we should show simplified visual style for the message view (i.e. hide the
     * avatar and bubble arrow, reduce padding).
     */
    private boolean shouldShowSimplifiedVisualStyle() {
        return mData.getCanClusterWithPreviousMessage();
    }

    /**
     * Returns whether we need to show message bubble arrow. We don't show arrow if the message
     * contains media attachments or if shouldShowSimplifiedVisualStyle() is true.
     */
    private boolean shouldShowMessageBubbleArrow() {
        return !shouldShowSimplifiedVisualStyle()
                && !(mData.hasAttachments() || mMessageHasYouTubeLink);
    }

    /**
     * Returns whether we need to show a message bubble for text content.
     */
    private boolean shouldShowMessageTextBubble() {
        if (mData.hasText()) {
            return true;
        }
        final String subjectText = MmsUtils.cleanseMmsSubject(getResources(),
                mData.getMmsSubject());
        if (!TextUtils.isEmpty(subjectText)) {
            return true;
        }
        return false;
    }

    private void updateViewContent() {
        updateMessageContent();
        int titleResId = -1;
        int statusResId = -1;
        String statusText = null;
        switch(mData.getStatus()) {
            case MessageData.BUGLE_STATUS_INCOMING_AUTO_DOWNLOADING:
            case MessageData.BUGLE_STATUS_INCOMING_MANUAL_DOWNLOADING:
            case MessageData.BUGLE_STATUS_INCOMING_RETRYING_AUTO_DOWNLOAD:
            case MessageData.BUGLE_STATUS_INCOMING_RETRYING_MANUAL_DOWNLOAD:
                titleResId = R.string.message_title_downloading;
                statusResId = R.string.message_status_downloading;
                break;

            case MessageData.BUGLE_STATUS_INCOMING_YET_TO_MANUAL_DOWNLOAD:
                if (!OsUtil.isSecondaryUser()) {
                    titleResId = R.string.message_title_manual_download;
                    if (isSelected()) {
                        statusResId = R.string.message_status_download_action;
                    } else {
                        statusResId = R.string.message_status_download;
                    }
                }
                break;

            case MessageData.BUGLE_STATUS_INCOMING_EXPIRED_OR_NOT_AVAILABLE:
                if (!OsUtil.isSecondaryUser()) {
                    titleResId = R.string.message_title_download_failed;
                    statusResId = R.string.message_status_download_error;
                }
                break;

            case MessageData.BUGLE_STATUS_INCOMING_DOWNLOAD_FAILED:
                if (!OsUtil.isSecondaryUser()) {
                    titleResId = R.string.message_title_download_failed;
                    if (isSelected()) {
                        statusResId = R.string.message_status_download_action;
                    } else {
                        statusResId = R.string.message_status_download;
                    }
                }
                break;

            case MessageData.BUGLE_STATUS_OUTGOING_YET_TO_SEND:
            case MessageData.BUGLE_STATUS_OUTGOING_SENDING:
                statusResId = R.string.message_status_sending;
                break;

            case MessageData.BUGLE_STATUS_OUTGOING_RESENDING:
            case MessageData.BUGLE_STATUS_OUTGOING_AWAITING_RETRY:
                statusResId = R.string.message_status_send_retrying;
                break;

            case MessageData.BUGLE_STATUS_OUTGOING_FAILED_EMERGENCY_NUMBER:
                statusResId = R.string.message_status_send_failed_emergency_number;
                break;

            case MessageData.BUGLE_STATUS_OUTGOING_FAILED:
                // don't show the error state unless we're the default sms app
                if (PhoneUtils.getDefault().isDefaultSmsApp()) {
                    if (isSelected()) {
                        statusResId = R.string.message_status_resend;
                    } else {
                        statusResId = MmsUtils.mapRawStatusToErrorResourceId(
                                mData.getStatus(), mData.getRawTelephonyStatus());
                    }
                    break;
                }
                // FALL THROUGH HERE

            case MessageData.BUGLE_STATUS_OUTGOING_COMPLETE:
            case MessageData.BUGLE_STATUS_OUTGOING_DELIVERED:
            case MessageData.BUGLE_STATUS_INCOMING_COMPLETE:
            default:
                if (!mData.getCanClusterWithNextMessage()) {
                    statusText = mData.getFormattedReceivedTimeStamp();
                }
                break;
        }

        final boolean titleVisible = (titleResId >= 0);
        if (titleVisible) {
            final String titleText = getResources().getString(titleResId);
            mTitleTextView.setText(titleText);

            final String mmsInfoText = getResources().getString(
                    R.string.mms_info,
                    Formatter.formatFileSize(getContext(), mData.getSmsMessageSize()),
                    DateUtils.formatDateTime(
                            getContext(),
                            mData.getMmsExpiry(),
                            DateUtils.FORMAT_SHOW_DATE |
                            DateUtils.FORMAT_SHOW_TIME |
                            DateUtils.FORMAT_NUMERIC_DATE |
                            DateUtils.FORMAT_NO_YEAR));
            mMmsInfoTextView.setText(mmsInfoText);
            mMessageTitleLayout.setVisibility(View.VISIBLE);
        } else {
            mMessageTitleLayout.setVisibility(View.GONE);
        }

        final String subjectText = MmsUtils.cleanseMmsSubject(getResources(),
                mData.getMmsSubject());
        final boolean subjectVisible = !TextUtils.isEmpty(subjectText);

        final boolean senderNameVisible = !mOneOnOne && !mData.getCanClusterWithNextMessage()
                && mData.getIsIncoming();
        if (senderNameVisible) {
            mSenderNameTextView.setText(mData.getSenderDisplayName());
            mSenderNameTextView.setVisibility(View.VISIBLE);
        } else {
            mSenderNameTextView.setVisibility(View.GONE);
        }

        if (statusResId >= 0) {
            statusText = getResources().getString(statusResId);
        }

        // We set the text even if the view will be GONE for accessibility
        mStatusTextView.setText(statusText);
        final boolean statusVisible = !TextUtils.isEmpty(statusText);
        if (statusVisible) {
            mStatusTextView.setVisibility(View.VISIBLE);
        } else {
            mStatusTextView.setVisibility(View.GONE);
        }

        final boolean deliveredBadgeVisible =
                mData.getStatus() == MessageData.BUGLE_STATUS_OUTGOING_DELIVERED;
        mDeliveredBadge.setVisibility(deliveredBadgeVisible ? View.VISIBLE : View.GONE);

        // Update the sim indicator.
        final boolean showSimIconAsIncoming = mData.getIsIncoming() &&
                (!mData.hasAttachments() || shouldShowMessageTextBubble());
        final SubscriptionListEntry subscriptionEntry =
                mHost.getSubscriptionEntryForSelfParticipant(mData.getSelfParticipantId(),
                        true /* excludeDefault */);
        final boolean simNameVisible = subscriptionEntry != null &&
                !TextUtils.isEmpty(subscriptionEntry.displayName) &&
                !mData.getCanClusterWithNextMessage();
        if (simNameVisible) {
            final String simNameText = mData.getIsIncoming() ? getResources().getString(
                    R.string.incoming_sim_name_text, subscriptionEntry.displayName) :
                        subscriptionEntry.displayName;
            mSimNameView.setText(simNameText);
            mSimNameView.setTextColor(showSimIconAsIncoming ? getResources().getColor(
                    R.color.timestamp_text_incoming) : subscriptionEntry.displayColor);
            mSimNameView.setVisibility(VISIBLE);
        } else {
            mSimNameView.setText(null);
            mSimNameView.setVisibility(GONE);
        }

        final boolean metadataVisible = senderNameVisible || statusVisible
                || deliveredBadgeVisible || simNameVisible;
        mMessageMetadataView.setVisibility(metadataVisible ? View.VISIBLE : View.GONE);

        final boolean messageTextAndOrInfoVisible = titleVisible || subjectVisible
                || mData.hasText() || metadataVisible;
        mMessageTextAndInfoView.setVisibility(
                messageTextAndOrInfoVisible ? View.VISIBLE : View.GONE);

        if (shouldShowSimplifiedVisualStyle()) {
            mContactIconView.setVisibility(View.GONE);
            mContactIconView.setImageResourceUri(null);
        } else {
            mContactIconView.setVisibility(View.VISIBLE);
            final Uri avatarUri = AvatarUriUtil.createAvatarUri(
                    mData.getSenderProfilePhotoUri(),
                    mData.getSenderFullName(),
                    mData.getSenderNormalizedDestination(),
                    mData.getSenderContactLookupKey());
            mContactIconView.setImageResourceUri(avatarUri, mData.getSenderContactId(),
                    mData.getSenderContactLookupKey(), mData.getSenderNormalizedDestination());
        }
    }

    private void updateMessageContent() {
        // We must update the text before the attachments since we search the text to see if we
        // should make a preview youtube image in the attachments
        updateMessageText();
        updateMessageSubject();
        mMessageBubble.bind(mData);
    }

    private void updateMessageSubject() {
        final String subjectText = MmsUtils.cleanseMmsSubject(getResources(),
                mData.getMmsSubject());
        final boolean subjectVisible = !TextUtils.isEmpty(subjectText);

        if (subjectVisible) {
            mSubjectText.setText(subjectText);
            mSubjectView.setVisibility(View.VISIBLE);
        } else {
            mSubjectView.setVisibility(View.GONE);
        }
    }

    private void updateMessageText() {
        final String text = mData.getText();
        if (!TextUtils.isEmpty(text)) {
            mMessageTextView.setText(text);
            // Linkify phone numbers, web urls, emails, and map addresses to allow users to
            // click on them and take the default intent.
            mMessageTextHasLinks = Linkify.addLinks(mMessageTextView, Linkify.ALL);
            mMessageTextView.setVisibility(View.VISIBLE);
        } else {
            mMessageTextView.setVisibility(View.GONE);
            mMessageTextHasLinks = false;
        }
    }

    private void updateViewAppearance() {
        final Resources res = getResources();
        final ConversationDrawables drawableProvider = ConversationDrawables.get();
        final boolean incoming = mData.getIsIncoming();
        final boolean outgoing = !incoming;
        final boolean showArrow =  shouldShowMessageBubbleArrow();

        final int messageTopPaddingClustered =
                res.getDimensionPixelSize(R.dimen.message_padding_same_author);
        final int messageTopPaddingDefault =
                res.getDimensionPixelSize(R.dimen.message_padding_default);
        final int arrowWidth = res.getDimensionPixelOffset(R.dimen.message_bubble_arrow_width);
        final int messageTextMinHeightDefault = res.getDimensionPixelSize(
                R.dimen.conversation_message_contact_icon_size);
        final int messageTextLeftRightPadding = res.getDimensionPixelOffset(
                R.dimen.message_text_left_right_padding);
        final int textTopPaddingDefault = res.getDimensionPixelOffset(
                R.dimen.message_text_top_padding);
        final int textBottomPaddingDefault = res.getDimensionPixelOffset(
                R.dimen.message_text_bottom_padding);

        // These values depend on whether the message has text, attachments, or both.
        // We intentionally don't set defaults, so the compiler will tell us if we forget
        // to set one of them, or if we set one more than once.
        final int contentLeftPadding, contentRightPadding;
        final Drawable textBackground;
        final int textMinHeight;
        final int textTopMargin;
        final int textTopPadding, textBottomPadding;
        final int textLeftPadding, textRightPadding;

        if (mData.hasAttachments()) {
            if (shouldShowMessageTextBubble()) {
                // Text and attachment(s)
                contentLeftPadding = incoming ? arrowWidth : 0;
                contentRightPadding = outgoing ? arrowWidth : 0;
                textBackground = drawableProvider.getBubbleDrawable(
                        isSelected(),
                        incoming,
                        false /* needArrow */,
                        mData.hasIncomingErrorStatus());
                textMinHeight = messageTextMinHeightDefault;
                textTopMargin = messageTopPaddingClustered;
                textTopPadding = textTopPaddingDefault;
                textBottomPadding = textBottomPaddingDefault;
                textLeftPadding = messageTextLeftRightPadding;
                textRightPadding = messageTextLeftRightPadding;
                mMessageTextView.setTextIsSelectable(isSelected());
            } else {
                // Attachment(s) only
                contentLeftPadding = incoming ? arrowWidth : 0;
                contentRightPadding = outgoing ? arrowWidth : 0;
                textBackground = null;
                textMinHeight = 0;
                textTopMargin = 0;
                textTopPadding = 0;
                textBottomPadding = 0;
                textLeftPadding = 0;
                textRightPadding = 0;
            }
        } else {
            // Text only
            contentLeftPadding = (!showArrow && incoming) ? arrowWidth : 0;
            contentRightPadding = (!showArrow && outgoing) ? arrowWidth : 0;
            textBackground = drawableProvider.getBubbleDrawable(
                    isSelected(),
                    incoming,
                    shouldShowMessageBubbleArrow(),
                    mData.hasIncomingErrorStatus());
            textMinHeight = messageTextMinHeightDefault;
            textTopMargin = 0;
            textTopPadding = textTopPaddingDefault;
            textBottomPadding = textBottomPaddingDefault;
            mMessageTextView.setTextIsSelectable(isSelected());
            if (showArrow && incoming) {
                textLeftPadding = messageTextLeftRightPadding + arrowWidth;
            } else {
                textLeftPadding = messageTextLeftRightPadding;
            }
            if (showArrow && outgoing) {
                textRightPadding = messageTextLeftRightPadding + arrowWidth;
            } else {
                textRightPadding = messageTextLeftRightPadding;
            }
        }

        // These values do not depend on whether the message includes attachments
        final int gravity = incoming ? (Gravity.START | Gravity.CENTER_VERTICAL) :
                (Gravity.END | Gravity.CENTER_VERTICAL);
        final int messageTopPadding = shouldShowSimplifiedVisualStyle() ?
                messageTopPaddingClustered : messageTopPaddingDefault;
        final int metadataTopPadding = res.getDimensionPixelOffset(
                R.dimen.message_metadata_top_padding);

        // Update the message text/info views
        ImageUtils.setBackgroundDrawableOnView(mMessageTextAndInfoView, textBackground);
        mMessageTextAndInfoView.setMinimumHeight(textMinHeight);
        final LinearLayout.LayoutParams textAndInfoLayoutParams =
                (LinearLayout.LayoutParams) mMessageTextAndInfoView.getLayoutParams();
        textAndInfoLayoutParams.topMargin = textTopMargin;

        if (UiUtils.isRtlMode()) {
            // Need to switch right and left padding in RtL mode
            mMessageTextAndInfoView.setPadding(textRightPadding, textTopPadding, textLeftPadding,
                    textBottomPadding);
            mMessageBubble.setPadding(contentRightPadding, 0, contentLeftPadding, 0);
        } else {
            mMessageTextAndInfoView.setPadding(textLeftPadding, textTopPadding, textRightPadding,
                    textBottomPadding);
            mMessageBubble.setPadding(contentLeftPadding, 0, contentRightPadding, 0);
        }

        // Update the message row and message bubble views
        setPadding(getPaddingLeft(), messageTopPadding, getPaddingRight(), 0);
        mMessageBubble.setGravity(gravity);

        mMessageMetadataView.setPadding(0, metadataTopPadding, 0, 0);

        updateTextAppearance();

        requestLayout();
    }

    private void updateContentDescription() {
        StringBuilder description = new StringBuilder();

        Resources res = getResources();
        String separator = res.getString(R.string.enumeration_comma);

        // Sender information
        boolean hasPlainTextMessage = !(TextUtils.isEmpty(mData.getText()) ||
                mMessageTextHasLinks);
        if (mData.getIsIncoming()) {
            int senderResId = hasPlainTextMessage
                ? R.string.incoming_text_sender_content_description
                : R.string.incoming_sender_content_description;
            description.append(res.getString(senderResId, mData.getSenderDisplayName()));
        } else {
            int senderResId = hasPlainTextMessage
                ? R.string.outgoing_text_sender_content_description
                : R.string.outgoing_sender_content_description;
            description.append(res.getString(senderResId));
        }

        if (mSubjectView.getVisibility() == View.VISIBLE) {
            description.append(separator);
            description.append(mSubjectText.getText());
        }

        if (mMessageTextView.getVisibility() == View.VISIBLE) {
            // If the message has hyperlinks, we will let the user navigate to the text message so
            // that the hyperlink can be clicked. Otherwise, the text message does not need to
            // be reachable.
            if (mMessageTextHasLinks) {
                mMessageTextView.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
            } else {
                mMessageTextView.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                description.append(separator);
                description.append(mMessageTextView.getText());
            }
        }

        if (mMessageTitleLayout.getVisibility() == View.VISIBLE) {
            description.append(separator);
            description.append(mTitleTextView.getText());

            description.append(separator);
            description.append(mMmsInfoTextView.getText());
        }

        if (mStatusTextView.getVisibility() == View.VISIBLE) {
            description.append(separator);
            description.append(mStatusTextView.getText());
        }

        if (mSimNameView.getVisibility() == View.VISIBLE) {
            description.append(separator);
            description.append(mSimNameView.getText());
        }

        if (mDeliveredBadge.getVisibility() == View.VISIBLE) {
            description.append(separator);
            description.append(res.getString(R.string.delivered_status_content_description));
        }

        setContentDescription(description);
    }

    private void updateTextAppearance() {
        int messageColorResId;
        int statusColorResId = -1;
        int infoColorResId = -1;
        int timestampColorResId;
        int subjectLabelColorResId;
        if (isSelected()) {
            messageColorResId = R.color.message_text_color_incoming;
            statusColorResId = R.color.message_action_status_text;
            infoColorResId = R.color.message_action_info_text;
            if (shouldShowMessageTextBubble()) {
                timestampColorResId = R.color.message_action_timestamp_text;
                subjectLabelColorResId = R.color.message_action_timestamp_text;
            } else {
                // If there's no text, the timestamp will be shown below the attachments,
                // against the conversation view background.
                timestampColorResId = R.color.timestamp_text_outgoing;
                subjectLabelColorResId = R.color.timestamp_text_outgoing;
            }
        } else {
            messageColorResId = (mData.getIsIncoming() ?
                    R.color.message_text_color_incoming : R.color.message_text_color_outgoing);
            statusColorResId = messageColorResId;
            infoColorResId = R.color.timestamp_text_incoming;
            switch(mData.getStatus()) {

                case MessageData.BUGLE_STATUS_OUTGOING_FAILED:
                case MessageData.BUGLE_STATUS_OUTGOING_FAILED_EMERGENCY_NUMBER:
                    timestampColorResId = R.color.message_failed_timestamp_text;
                    subjectLabelColorResId = R.color.timestamp_text_outgoing;
                    break;

                case MessageData.BUGLE_STATUS_OUTGOING_YET_TO_SEND:
                case MessageData.BUGLE_STATUS_OUTGOING_SENDING:
                case MessageData.BUGLE_STATUS_OUTGOING_RESENDING:
                case MessageData.BUGLE_STATUS_OUTGOING_AWAITING_RETRY:
                case MessageData.BUGLE_STATUS_OUTGOING_COMPLETE:
                case MessageData.BUGLE_STATUS_OUTGOING_DELIVERED:
                    timestampColorResId = R.color.timestamp_text_outgoing;
                    subjectLabelColorResId = R.color.timestamp_text_outgoing;
                    break;

                case MessageData.BUGLE_STATUS_INCOMING_EXPIRED_OR_NOT_AVAILABLE:
                case MessageData.BUGLE_STATUS_INCOMING_DOWNLOAD_FAILED:
                    messageColorResId = R.color.message_text_color_incoming_download_failed;
                    timestampColorResId = R.color.message_download_failed_timestamp_text;
                    subjectLabelColorResId = R.color.message_text_color_incoming_download_failed;
                    statusColorResId = R.color.message_download_failed_status_text;
                    infoColorResId = R.color.message_info_text_incoming_download_failed;
                    break;

                case MessageData.BUGLE_STATUS_INCOMING_AUTO_DOWNLOADING:
                case MessageData.BUGLE_STATUS_INCOMING_MANUAL_DOWNLOADING:
                case MessageData.BUGLE_STATUS_INCOMING_RETRYING_AUTO_DOWNLOAD:
                case MessageData.BUGLE_STATUS_INCOMING_RETRYING_MANUAL_DOWNLOAD:
                case MessageData.BUGLE_STATUS_INCOMING_YET_TO_MANUAL_DOWNLOAD:
                    timestampColorResId = R.color.message_text_color_incoming;
                    subjectLabelColorResId = R.color.message_text_color_incoming;
                    infoColorResId = R.color.timestamp_text_incoming;
                    break;

                case MessageData.BUGLE_STATUS_INCOMING_COMPLETE:
                default:
                    timestampColorResId = R.color.timestamp_text_incoming;
                    subjectLabelColorResId = R.color.timestamp_text_incoming;
                    infoColorResId = -1; // Not used
                    break;
            }
        }
        final int messageColor = getResources().getColor(messageColorResId);
        mMessageTextView.setTextColor(messageColor);
        mMessageTextView.setLinkTextColor(messageColor);
        mSubjectText.setTextColor(messageColor);
        if (statusColorResId >= 0) {
            mTitleTextView.setTextColor(getResources().getColor(statusColorResId));
        }
        if (infoColorResId >= 0) {
            mMmsInfoTextView.setTextColor(getResources().getColor(infoColorResId));
        }
        if (timestampColorResId == R.color.timestamp_text_incoming &&
                mData.hasAttachments() && !shouldShowMessageTextBubble()) {
            timestampColorResId = R.color.timestamp_text_outgoing;
        }
        mStatusTextView.setTextColor(getResources().getColor(timestampColorResId));

        mSubjectLabel.setTextColor(getResources().getColor(subjectLabelColorResId));
        mSenderNameTextView.setTextColor(getResources().getColor(timestampColorResId));
    }

    /**
     * If we don't know the size of the image, we want to show it in a fixed-sized frame to
     * avoid janks when the image is loaded and resized. Otherwise, we can set the imageview to
     * take on normal layout params.
     */
    private void adjustImageViewBounds(final MessagePartData imageAttachment) {
        Assert.isTrue(ContentType.isImageType(imageAttachment.getContentType()));
        final ViewGroup.LayoutParams layoutParams = mMessageImageView.getLayoutParams();
        if (imageAttachment.getWidth() == MessagePartData.UNSPECIFIED_SIZE ||
                imageAttachment.getHeight() == MessagePartData.UNSPECIFIED_SIZE) {
            // We don't know the size of the image attachment, enable letterboxing on the image
            // and show a fixed sized attachment. This should happen at most once per image since
            // after the image is loaded we then save the image dimensions to the db so that the
            // next time we can display the full size.
            layoutParams.width = getResources()
                    .getDimensionPixelSize(R.dimen.image_attachment_fallback_width);
            layoutParams.height = getResources()
                    .getDimensionPixelSize(R.dimen.image_attachment_fallback_height);
            mMessageImageView.setScaleType(ScaleType.CENTER_CROP);
        } else {
            layoutParams.width = ViewGroup.LayoutParams.MATCH_PARENT;
            layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            // ScaleType.CENTER_INSIDE and FIT_CENTER behave similarly for most images. However,
            // FIT_CENTER works better for small images as it enlarges the image such that the
            // minimum size ("android:minWidth" etc) is honored.
            mMessageImageView.setScaleType(ScaleType.FIT_CENTER);
        }
    }

    @Override
    public void onClick(final View view) {}

    @Override
    public boolean onLongClick(final View view) {
        if (view == mMessageTextView) {
            // Avoid trying to reselect the message
            if (isSelected()) {
                return false;
            }

            // Preemptively handle the long click event on message text so it's not handled by
            // the link spans.
            return performLongClick();
        }

        return false;
    }

    public ContactIconView getContactIconView() {
        return mContactIconView;
    }

    // Sort photos in MultiAttachLayout in the same order as the ConversationImagePartsView
    static final Comparator<MessagePartData> sImageComparator = new Comparator<MessagePartData>(){
        @Override
        public int compare(final MessagePartData x, final MessagePartData y) {
            return x.getPartId().compareTo(y.getPartId());
        }
    };

    static final Predicate<MessagePartData> sVideoFilter = new Predicate<MessagePartData>() {
        @Override
        public boolean apply(final MessagePartData part) {
            return part.isVideo();
        }
    };

    static final Predicate<MessagePartData> sAudioFilter = new Predicate<MessagePartData>() {
        @Override
        public boolean apply(final MessagePartData part) {
            return part.isAudio();
        }
    };

    static final Predicate<MessagePartData> sVCardFilter = new Predicate<MessagePartData>() {
        @Override
        public boolean apply(final MessagePartData part) {
            return part.isVCard();
        }
    };

    static final Predicate<MessagePartData> sImageFilter = new Predicate<MessagePartData>() {
        @Override
        public boolean apply(final MessagePartData part) {
            return part.isImage();
        }
    };

    interface AttachmentViewBinder {
        void bindView(View view, MessagePartData attachment);
        void unbind(View view);
    }

    /**
     * A helper class that allows us to handle long clicks on linkified message text view (i.e. to
     * select the message) so it's not handled by the link spans to launch apps for the links.
     */
    private static class IgnoreLinkLongClickHelper implements OnLongClickListener, OnTouchListener {
        private boolean mIsLongClick;
        private final OnLongClickListener mDelegateLongClickListener;

        /**
         * Ignore long clicks on linkified texts for a given text view.
         * @param textView the TextView to ignore long clicks on
         * @param longClickListener a delegate OnLongClickListener to be called when the view is
         *        long clicked.
         */
        public static void ignoreLinkLongClick(final TextView textView,
                @Nullable final OnLongClickListener longClickListener) {
            final IgnoreLinkLongClickHelper helper =
                    new IgnoreLinkLongClickHelper(longClickListener);
            textView.setOnLongClickListener(helper);
            textView.setOnTouchListener(helper);
        }

        private IgnoreLinkLongClickHelper(@Nullable final OnLongClickListener longClickListener) {
            mDelegateLongClickListener = longClickListener;
        }

        @Override
        public boolean onLongClick(final View v) {
            // Record that this click is a long click.
            mIsLongClick = true;
            if (mDelegateLongClickListener != null) {
                return mDelegateLongClickListener.onLongClick(v);
            }
            return false;
        }

        @Override
        public boolean onTouch(final View v, final MotionEvent event) {
            if (event.getActionMasked() == MotionEvent.ACTION_UP && mIsLongClick) {
                // This touch event is a long click, preemptively handle this touch event so that
                // the link span won't get a onClicked() callback.
                mIsLongClick = false;
                return false;
            }

            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                mIsLongClick = false;
            }
            return false;
        }
    }
}
