package com.example.v4l2usbcam;

public class CameraNative {
    static {
        System.loadLibrary("v4l2_camera"); // 確保你 NDK 的 .so 名稱正確
    }

    public static native void nativeListFormats(String devicePath);
    public static native String nativeFindUsbCamera();
    public static native void nativeStartStream(String devicePath, MainActivity activityInstance);
    public static native boolean setXuControl(byte[] controlData, String devicePath);
    public static native byte[] getXuControl(String devicePath);
}