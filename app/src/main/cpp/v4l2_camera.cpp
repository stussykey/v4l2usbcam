#include <jni.h>
#include <string>
#include <fcntl.h>
#include <linux/videodev2.h>
#include <linux/uvcvideo.h>
#include <unistd.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <android/log.h>
#include <cstring>
#include <turbojpeg.h>


#define TAG "V4L2_SUPPORT"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#define UVC_SET_CUR 0x01
#define UVC_GET_CUR 0x81



void logFlavor() {
#ifdef IS_CLONE
#if IS_CLONE == 1
    LOGI("Running in clone flavor");
#else
    LOGI("Running in original flavor");
#endif
#else
    LOGI("IS_CLONE not defined");
#endif
}

extern "C"
JNIEXPORT void JNICALL
Java_com_example_v4l2usbcam_CameraNative_nativeListFormats(JNIEnv *env, jobject thiz, jstring devicePath) {
    const char *device = env->GetStringUTFChars(devicePath, 0);
    int fd = open(device, O_RDWR);
    LOGI("Opened video device %s (fd=%d)", device, fd);
    if (fd < 0) {
        LOGE("Failed to open %s", device);
        env->ReleaseStringUTFChars(devicePath, device);
        return;
    }

    LOGI("=== Supported Formats for %s ===", device);
    struct v4l2_fmtdesc fmt;
    memset(&fmt, 0, sizeof(fmt));
    fmt.type = V4L2_BUF_TYPE_VIDEO_CAPTURE;
    fmt.index = 0;

    for (int i = 0; i < 10; ++i) {
        char path[32];
        snprintf(path, sizeof(path), "/dev/video%d", i);
        int fd = open(path, O_RDWR);
        if (fd < 0) {
            LOGE("Failed to open %s", path);
            continue;
        }

        struct v4l2_capability cap;
        if (ioctl(fd, VIDIOC_QUERYCAP, &cap) == 0) {
            LOGI("✅ %s - Card: %s | Capabilities: 0x%x", path, cap.card, cap.capabilities);
            if (cap.capabilities & V4L2_CAP_VIDEO_CAPTURE) {
                LOGI("👉 FOUND CAPTURE DEVICE: %s", path);
            }
        } else {
            perror("VIDIOC_QUERYCAP failed");
        }

        close(fd);
    }

    while (ioctl(fd, VIDIOC_ENUM_FMT, &fmt) == 0) {
        LOGI("Format #%d: %s (%c%c%c%c)", fmt.index, fmt.description,
        fmt.pixelformat & 0xff,
        (fmt.pixelformat >> 8) & 0xff,
        (fmt.pixelformat >> 16) & 0xff,
        (fmt.pixelformat >> 24) & 0xff);

        // 列出解析度
        struct v4l2_frmsizeenum frmsize;
        memset(&frmsize, 0, sizeof(frmsize));
        frmsize.pixel_format = fmt.pixelformat;
        frmsize.index = 0;

        while (ioctl(fd, VIDIOC_ENUM_FRAMESIZES, &frmsize) == 0) {
            if (frmsize.type == V4L2_FRMSIZE_TYPE_DISCRETE) {
                LOGI("   %ux%u", frmsize.discrete.width, frmsize.discrete.height);
            }
            frmsize.index++;
        }
        fmt.index++;
    }

    close(fd);
    env->ReleaseStringUTFChars(devicePath, device);
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_example_v4l2usbcam_CameraNative_nativeFindUsbCamera(JNIEnv *env, jobject thiz) {
    logFlavor();

    const int MAX_DEVICES = 200;
    char path[32];

    for (int i = 0; i < MAX_DEVICES; ++i) {
        snprintf(path, sizeof(path), "/dev/video%d", i);

        int fd = open(path, O_RDWR);
        if (fd < 0) {
            continue;
        }

        struct v4l2_capability cap;
        if (ioctl(fd, VIDIOC_QUERYCAP, &cap) == 0) {
            LOGI("Checking %s - Card: %s | Capabilities: 0x%x", path, cap.card, cap.capabilities);

            bool isCapture = cap.capabilities & V4L2_CAP_VIDEO_CAPTURE;
            bool isUsb = strstr((const char *)cap.card, "USB") || strstr((const char *)cap.card, "Camera");

            if (isCapture /* && isUsb */) {
                close(fd);
                LOGI("👉 FOUND CAMERA DEVICE (regardless of name): %s", path);
                return env->NewStringUTF(path);
            }
        }

        close(fd);
    }

    LOGE("No USB camera device with V4L2_CAP_VIDEO_CAPTURE found.");
    return nullptr;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_example_v4l2usbcam_CameraNative_nativeStartStream(JNIEnv *env, jclass clazz, jstring devicePath, jobject activityInstance) {
    const char *device = env->GetStringUTFChars(devicePath, nullptr);
    //int width = 3840, height = 2160;

#ifdef IS_CLONE
#if IS_CLONE == 1
    int width = 1920, height = 1080; //need usb 2.0 cable
    LOGE("Clone size: %d x %d", width, height);
#else
    int width = 3840, height = 2160;
    LOGE("Original size: %d x %d", width, height);
#endif
#else
    LOGI("IS_CLONE not defined");
#endif

    int fd = open(device, O_RDWR);
    if (fd < 0) {
        LOGE("Failed to open device: %s", device);
        return;
    }

    // Set format
    struct v4l2_format fmt = {};
    fmt.type = V4L2_BUF_TYPE_VIDEO_CAPTURE;
    fmt.fmt.pix.width = width;
    fmt.fmt.pix.height = height;
    fmt.fmt.pix.pixelformat = V4L2_PIX_FMT_MJPEG;
    fmt.fmt.pix.field = V4L2_FIELD_NONE;

    if (ioctl(fd, VIDIOC_S_FMT, &fmt) < 0) {
        LOGE("Failed to set format");
        close(fd);
        return;
    }

    // Request buffer
    struct v4l2_requestbuffers req = {};
    req.count = 1;
    req.type = V4L2_BUF_TYPE_VIDEO_CAPTURE;
    req.memory = V4L2_MEMORY_MMAP;

    if (ioctl(fd, VIDIOC_REQBUFS, &req) < 0) {
        LOGE("Failed to request buffer");
        close(fd);
        return;
    }

    // Query buffer
    struct v4l2_buffer buf = {};
    buf.type = V4L2_BUF_TYPE_VIDEO_CAPTURE;
    buf.memory = V4L2_MEMORY_MMAP;
    buf.index = 0;

    if (ioctl(fd, VIDIOC_QUERYBUF, &buf) < 0) {
        LOGE("Failed to query buffer");
        close(fd);
        return;
    }

    void *bufferStart = mmap(NULL, buf.length, PROT_READ | PROT_WRITE, MAP_SHARED, fd, buf.m.offset);
    if (bufferStart == MAP_FAILED) {
        LOGE("Failed to mmap buffer");
        close(fd);
        return;
    }

    // Queue buffer
    if (ioctl(fd, VIDIOC_QBUF, &buf) < 0) {
        LOGE("Failed to queue buffer");
        munmap(bufferStart, buf.length);
        close(fd);
        return;
    }

    // Start streaming
    int type = buf.type;
    if (ioctl(fd, VIDIOC_STREAMON, &type) < 0) {
        LOGE("Failed to start stream");
        munmap(bufferStart, buf.length);
        close(fd);
        return;
    }

    tjhandle tj = tjInitDecompress();
    //int rgbSize = width * height * 2; // RGB565
    int rgbSize = width * height * 4;
    jbyteArray rgbArray = env->NewByteArray(rgbSize);
    jclass mainClass = env->FindClass("com/example/v4l2usbcam/MainActivity");
    jmethodID drawMethod = env->GetMethodID(mainClass, "drawFrame", "([B)V");

    while (true) {
        if (ioctl(fd, VIDIOC_DQBUF, &buf) == 0) {
            jbyte *rgbBuf = env->GetByteArrayElements(rgbArray, nullptr);
            tjDecompress2(tj, (unsigned char *)bufferStart, buf.bytesused,
            (unsigned char *)rgbBuf, width, 0, height, TJPF_RGBA, 0);
            //env->CallVoidMethod(clazz, drawMethod, rgbArray);
            env->CallVoidMethod(activityInstance, drawMethod, rgbArray);
            env->ReleaseByteArrayElements(rgbArray, rgbBuf, 0);
            ioctl(fd, VIDIOC_QBUF, &buf); // Requeue buffer
        }
    }

    tjDestroy(tj);
    munmap(bufferStart, buf.length);
    close(fd);
    env->ReleaseStringUTFChars(devicePath, device);
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_example_v4l2usbcam_CameraNative_setXuControl(JNIEnv *env, jobject /* this */, jbyteArray controlData, jstring devicePath) {
    const char *device = env->GetStringUTFChars(devicePath, nullptr);
    int fd = open(device, O_RDWR);
    if (fd < 0) {
        LOGI("SetXU - Failed to open video device");
        return JNI_FALSE;
    }

    jsize len = env->GetArrayLength(controlData);
    if (len <= 0 || len > 64) {
        LOGI("SetXU - Invalid data length: %d", len);
        close(fd);
        return JNI_FALSE;
    }

    jbyte *data = env->GetByteArrayElements(controlData, nullptr);

    struct uvc_xu_control_query xu;
    xu.unit = 6;        // Replace with your XU unit ID
    xu.selector = 1;           // Replace with your XU control ID
    xu.query = UVC_SET_CUR;    // UVC_SET_CUR to write
    xu.size = len;
    xu.data = reinterpret_cast<__u8 *>(data);

    //for (int i = 0; i < xu.size; i++) {
    //    LOGI("SetXU - data[%d]: 0x%x", i, xu.data[i]);
    //}

    int ret = ioctl(fd, UVCIOC_CTRL_QUERY, &xu);
    env->ReleaseByteArrayElements(controlData, data, 0);
    close(fd);

    if (ret < 0) {
        LOGI("SET_CUR ioctl failed: %s (errno=%d)", strerror(errno), errno);
        return JNI_FALSE;
    }

    //LOGI("SET_CUR success, ret = %d", ret);
    return JNI_TRUE;
}

extern "C"
JNIEXPORT jbyteArray JNICALL
Java_com_example_v4l2usbcam_CameraNative_getXuControl(JNIEnv *env, jobject thiz, jstring devicePath) {
    const char *device = env->GetStringUTFChars(devicePath, nullptr);
    int fd = open(device, O_RDWR);
    if (fd < 0) {
        LOGI("GetXU - Failed to open video device");
        return nullptr;
    }

    struct uvc_xu_control_query xu;
    __u8 data[8];

    xu.unit = 6;  // your unit ID
    xu.selector = 2;  // your control ID
    xu.query = UVC_GET_CUR;
    xu.size = sizeof(data);
    xu.data = data;

    if (ioctl(fd, UVCIOC_CTRL_QUERY, &xu) < 0) {
        LOGI("GET_CUR ioctl failed: %s (errno=%d)", strerror(errno), errno);
        close(fd);
        return nullptr;
    }


    //for (int i = 0; i < xu.size; i++) {
    //    LOGI("GET_CUR success, Data = %d", xu.data[i]);
    //}

    close(fd);

    jbyteArray result = env->NewByteArray(xu.size);
    env->SetByteArrayRegion(result, 0, xu.size, (jbyte*)data);

    return result;
}