package com.android.messaging.receiver;

import static android.provider.Telephony.Sms.Intents.WAP_PUSH_DELIVER_ACTION;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import java.util.Objects;

public class MmsWapPushDeliverReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        assert Objects.equals(intent.getAction(), WAP_PUSH_DELIVER_ACTION);
    }
}
