package com.android.messaging.shims.chips;

import java.util.Map;
import java.util.Set;

public class RecipientAlternatesAdapter {

    public interface RecipientMatchCallback {
        public void matchesFound(Map<String, RecipientEntry> results);
        public void matchesNotFound(Set<String> unfoundAddresses);
    }
}
