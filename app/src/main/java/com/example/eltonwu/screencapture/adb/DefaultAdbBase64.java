package com.example.eltonwu.screencapture.adb;

//import org.apache.commons.codec.binary.Base64;

import android.util.Base64;

public class DefaultAdbBase64 implements AdbBase64 {
    private static DefaultAdbBase64 sInstance = new DefaultAdbBase64();

    @Override
    public String encodeToString(byte[] data) {
        return Base64.encodeToString(data, Base64.DEFAULT);
    }

    public static AdbBase64 getInstance(){
        return sInstance;
    }
}
