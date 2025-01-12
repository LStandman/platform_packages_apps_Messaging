package com.android.messaging.shims.chips;

public interface PhotoManager {

    interface PhotoManagerCallback {
        void onPhotoBytesPopulated();
        void onPhotoBytesAsynchronouslyPopulated();
        void onPhotoBytesAsyncLoadFailed();
    }
}
