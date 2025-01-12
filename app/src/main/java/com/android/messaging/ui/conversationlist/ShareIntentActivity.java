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

package com.android.messaging.ui.conversationlist;

import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;

import com.android.messaging.datamodel.data.ConversationListItemData;
import com.android.messaging.datamodel.data.MessageData;
import com.android.messaging.ui.BaseBugleActivity;
import com.android.messaging.ui.UIIntents;

public class ShareIntentActivity extends BaseBugleActivity implements
        ShareIntentFragment.HostInterface {

    private MessageData mDraftMessage;

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        final Intent intent = getIntent();
        if (Intent.ACTION_SEND.equals(intent.getAction()) &&
                (!TextUtils.isEmpty(intent.getStringExtra("address")) ||
                !TextUtils.isEmpty(intent.getStringExtra(Intent.EXTRA_EMAIL)))) {
            // This is really more like a SENDTO intent because a destination is supplied.
            // It's coming through the SEND intent because that's the intent that is used
            // when invoking the chooser with Intent.createChooser().
            final Intent convIntent = UIIntents.get().getLaunchConversationActivityIntent(this);
            // Copy the important items from the original intent to the new intent.
            convIntent.putExtras(intent);
            convIntent.setAction(Intent.ACTION_SENDTO);
            convIntent.setDataAndType(intent.getData(), intent.getType());
            // We have to fire off the intent and finish before trying to show the fragment,
            // otherwise we get some flashing.
            startActivity(convIntent);
            finish();
            return;
        }
        new ShareIntentFragment().show(getFragmentManager(), "ShareIntentFragment");
    }

    @Override
    public void onConversationClick(final ConversationListItemData conversationListItemData) {
        UIIntents.get().launchConversationActivity(
                this, conversationListItemData.getConversationId(), mDraftMessage);
        finish();
    }

    @Override
    public void onCreateConversationClick() {
        UIIntents.get().launchCreateNewConversationActivity(this, mDraftMessage);
        finish();
    }
}
