package com.example.v4l2usbcam;
import com.example.v4l2usbcam.BuildConfig;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.RectF;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.media.MediaPlayer;
import android.media.AudioManager;
import android.media.AudioDeviceInfo;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import android.content.Context;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Button;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.Queue;
import java.util.HashMap;


public class MainActivity extends AppCompatActivity implements SurfaceHolder.Callback {
    static {
        System.loadLibrary("v4l2_camera");
    }

    private SurfaceView surfaceView;
    private SurfaceHolder surfaceHolder;
    private int width = 0;
    private int height = 0;
    private String devicePath;
    private int frameCount = 0;
    private ImageView[] faceViews;
    private final List<TrackedFace> trackedFaces = new ArrayList<>();
    private final int FACE_AREA_THRESHOLD = 10000; // 100x100
    private final long FACE_HOLD_DURATION_MS = 3000;
    private final List<PendingFace> pendingFaces = new ArrayList<>();
    private final int PENDING_CONFIRM_FRAMES = 10; // 連續 3 幀出現
    private final long PENDING_MAX_TIME_MS = 2000; // 最多等 2 秒
    private Thread frameWorkerThread;
    private volatile boolean frameWorkerRunning = false;
    private final Queue<byte[]> frameQueue = new LinkedList<>();
    private final Object queueLock = new Object();
    private Bitmap reusableBitmap = null;
    private ByteBuffer reusableBuffer = null;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private boolean smartGalleryEnabled = false; // smartGallery功能開關
    private boolean autoFramingEnabled = false; // autoFraming功能開關
    private boolean drawFaceRect = true; //顯示人臉抓框

    //MP3 PLAYER
    private MediaPlayer mediaPlayer;
    private final int[] songResIds = { R.raw.music1, R.raw.music2, R.raw.music3, R.raw.music4 }; // 放在 res/raw 資料夾
    private final String[] songTitles = { "music1", "music2", "music3" , "music4"};
    private int currentSongIndex = 0;
    private TextView textTitle;


    private void initMediaPlayer() {
        if (mediaPlayer != null) {
            mediaPlayer.release();
        }
        mediaPlayer = MediaPlayer.create(this, songResIds[currentSongIndex]);
        mediaPlayer.setLooping(false); // 不要重複播放，用自動跳下一首

        mediaPlayer.setOnCompletionListener(mp -> {
            currentSongIndex = (currentSongIndex + 1) % songResIds.length;
            initMediaPlayer();
            mediaPlayer.start();
            //textTitle.setText("♪ " + songTitles[currentSongIndex]);
            textTitle.setText(getString(R.string.songTitleFormat, songTitles[currentSongIndex]));
        });

        // MediaPlayer 建立後強制設定播放裝置
        AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        AudioDeviceInfo[] devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS);

        for (AudioDeviceInfo device : devices) {
            if (device.getType() == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
                mediaPlayer.setPreferredDevice(device);
                break;
            }
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (BuildConfig.IS_CLONE) {
            // clone build is FHD
            width = 1920;
            height = 1080;
        } else {
            // original build is 4K
            width = 3840;
            height = 2160;
        }
        //Log.i("build test", "Size: " + width + "x" + height);

        setContentView(R.layout.activity_main);
        surfaceView = findViewById(R.id.surfaceView);
        surfaceHolder = surfaceView.getHolder();
        surfaceHolder.addCallback(this);

        //
        faceViews = new ImageView[4];
        faceViews[0] = findViewById(R.id.faceView0);
        faceViews[1] = findViewById(R.id.faceView1);
        faceViews[2] = findViewById(R.id.faceView2);
        faceViews[3] = findViewById(R.id.faceView3);

        Button buttonSmartGallery = findViewById(R.id.buttonToggleFace);
        Button buttonAutoFraming = findViewById(R.id.buttonOther);

        // buttonSmartGallery 按鈕預設樣式
        buttonSmartGallery.setText(getString(R.string.smartGalleryDisable));
        buttonSmartGallery.setBackgroundColor(Color.RED);
        buttonSmartGallery.setOnClickListener(v -> {
            smartGalleryEnabled = !smartGalleryEnabled;

            if (smartGalleryEnabled) {
                buttonSmartGallery.setText(getString(R.string.smartGalleryEnable));
                buttonSmartGallery.setBackgroundColor(Color.GREEN);
                buttonSmartGallery.setTextColor(Color.BLACK);
                Log.e("BTN_DBG", "Smart Gallery Enable");

                // Disable Auto Framing function
                autoFramingEnabled = false;
                buttonAutoFraming.setText(getString(R.string.autoFramingDisable));
                buttonAutoFraming.setBackgroundColor(Color.RED);
                buttonAutoFraming.setTextColor(Color.WHITE);
                disableAutoFraming();
                //

                usbXuEnable();
            } else {
                buttonSmartGallery.setText(getString(R.string.smartGalleryDisable));
                buttonSmartGallery.setBackgroundColor(Color.RED);
                buttonSmartGallery.setTextColor(Color.WHITE);
                // 同時清空人臉畫面
                for (ImageView view : faceViews) {
                    view.setImageBitmap(null);
                }

                usbXuDisable();
                Log.e("BTN_DBG", "Smart Gallery Disable");
            }
        });

        // buttonAutoFraming 按鈕預設樣式
        buttonAutoFraming.setText(getString(R.string.autoFramingDisable));
        buttonAutoFraming.setBackgroundColor(Color.RED);
        buttonAutoFraming.setOnClickListener(v -> {
            autoFramingEnabled = !autoFramingEnabled;

            if (autoFramingEnabled) {
                buttonAutoFraming.setText(getString(R.string.autoFramingEnable));
                buttonAutoFraming.setBackgroundColor(Color.GREEN);
                buttonAutoFraming.setTextColor(Color.BLACK);
                enableAutoFraming();
                Log.e("BTN_DBG", "Auto Framing Enable");

                // Disable Smart Gallery function
                smartGalleryEnabled = false;
                buttonSmartGallery.setText(getString(R.string.smartGalleryDisable));
                buttonSmartGallery.setBackgroundColor(Color.RED);
                buttonSmartGallery.setTextColor(Color.WHITE);
                for (ImageView view : faceViews) {
                    view.setImageBitmap(null);
                }
                //
            } else {
                buttonAutoFraming.setText(getString(R.string.autoFramingDisable));
                buttonAutoFraming.setBackgroundColor(Color.RED);
                buttonAutoFraming.setTextColor(Color.WHITE);
                disableAutoFraming();
                Log.e("BTN_DBG", "Auto Framing Disable");
            }
        });


        // Mp3 Player
        textTitle = findViewById(R.id.textSongTitle);
        initMediaPlayer();

        Button btnPlay = findViewById(R.id.buttonPlayPause);
        Button btnNext = findViewById(R.id.buttonNext);
        Button btnPrev = findViewById(R.id.buttonPrev);
        Button btnVolUp = findViewById(R.id.buttonVolUp);
        Button btnVolDown = findViewById(R.id.buttonVolDown);

        btnPlay.setOnClickListener(v -> {
            if (mediaPlayer.isPlaying()) {
                mediaPlayer.pause();
                btnPlay.setText(getString(R.string.play));
            } else {
                mediaPlayer.start();
                btnPlay.setText(getString(R.string.pause));
                //textTitle.setText("♪ " + songTitles[currentSongIndex]);
                textTitle.setText(getString(R.string.songTitleFormat, songTitles[currentSongIndex]));
            }
        });

        btnNext.setOnClickListener(v -> {
            currentSongIndex = (currentSongIndex + 1) % songResIds.length;
            initMediaPlayer();
            mediaPlayer.start();
            btnPlay.setText(getString(R.string.pause));
            //textTitle.setText("♪ " + songTitles[currentSongIndex]);
            textTitle.setText(getString(R.string.songTitleFormat, songTitles[currentSongIndex]));
        });

        btnPrev.setOnClickListener(v -> {
            currentSongIndex = (currentSongIndex - 1 + songResIds.length) % songResIds.length;
            initMediaPlayer();
            mediaPlayer.start();
            btnPlay.setText(getString(R.string.pause));
            //textTitle.setText("♪ " + songTitles[currentSongIndex]);
            textTitle.setText(getString(R.string.songTitleFormat, songTitles[currentSongIndex]));
        });

        AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI);


        btnVolUp.setOnClickListener(v -> {
            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                    AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI);
        });

        btnVolDown.setOnClickListener(v -> {
            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                    AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI);
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        usbXuDisable();
        stopFrameWorker();

        if (reusableBitmap != null) {
            reusableBitmap.recycle();
            reusableBitmap = null;
        }
        reusableBuffer = null;

        // Release MediaPlayer
        if (mediaPlayer != null) {
            mediaPlayer.release();
            mediaPlayer = null;
        }
    }

    public void findUsbDevice() {
        devicePath = CameraNative.nativeFindUsbCamera();
        if (devicePath != null) {
            UsbManager usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
            HashMap<String, UsbDevice> deviceList = usbManager.getDeviceList();

            for (UsbDevice device : deviceList.values()) {
                int vendorId = device.getVendorId();
                int productId = device.getProductId();

                Log.d("USB_DBG", "Found device: VID=" + vendorId + " PID=" + productId);

                // 用 vendorId/productId 比對想找的裝置
                if (vendorId == 0x413C && productId == 0xB71F) {
                    requestUsbPermission(this, usbManager, device);
                    Log.e("USB_DBG", "GET device");
                    break;
                }
            }

            Log.i("USB_DBG", "Found camera: " + devicePath);
        } else {
            Log.e("USB_DBG", "No USB camera found");
        }
    }

    public void requestUsbPermission(Context context, UsbManager usbManager, UsbDevice device) {
        PendingIntent permissionIntent = PendingIntent.getBroadcast(
                context,
                0,
                new Intent("com.example.USB_PERMISSION"),
                PendingIntent.FLAG_IMMUTABLE
        );

        usbManager.requestPermission(device, permissionIntent);
    }

    public List<int[]> getAllFaceRects() {
        List<int[]> faceRects = new ArrayList<>();

        if (devicePath == null) {
            Log.i("GetFaceInfo", "getFaceInfo devicePath null");
            return null;
        }

        byte[] data = new byte[]{
                (byte)0xFE, (byte)0xA0, (byte)0x00,
                (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00};
        CameraNative.setXuControl(data, devicePath);

        byte[] get_data;
        get_data = CameraNative.getXuControl(devicePath);
        //Log.i("GetFaceInfo", "Face number: " + get_data[3]);

        for (byte i = 1; i <= get_data[3]; i++) {
            byte[] faceCountCommand = new byte[]{
                    (byte) 0xFE, (byte) 0xA0, i, (byte) 0x01,
                    (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00
            };
            CameraNative.setXuControl(faceCountCommand, devicePath);

            byte[] getFaceInfo = CameraNative.getXuControl(devicePath);

            // Debug log
            /*
            for (byte faceData : getFaceInfo) {
                Log.i("GetFaceInfo", String.format("Face Info = 0x%02x", faceData));
            }
            */

            // Get face x, y, width, height
            if (getFaceInfo != null && getFaceInfo.length >= 8) {
                int x = ((getFaceInfo[0] & 0xFF) << 8) | (getFaceInfo[1] & 0xFF);
                int y = ((getFaceInfo[2] & 0xFF) << 8) | (getFaceInfo[3] & 0xFF);
                int width = ((getFaceInfo[4] & 0xFF) << 8) | (getFaceInfo[5] & 0xFF);
                int height = ((getFaceInfo[6] & 0xFF) << 8) | (getFaceInfo[7] & 0xFF);

                faceRects.add(new int[]{x, y, width, height});
                //Log.i("GetFaceInfo", String.format("Face %d: x=%d, y=%d, width=%d, height=%d", i, x, y, width, height));
            } else {
                Log.w("GetFaceInfo", "getFaceInfo 資料不足或為 null");
            }
        }

        return faceRects;
    }

    private void showTrackedFaces(Bitmap fullFrame) {
        if (!smartGalleryEnabled) return;

        if (trackedFaces.isEmpty()) {
            // 沒臉就清空右側小格畫面
            for (ImageView faceView : faceViews) {
                faceView.setImageBitmap(null);
            }
            return;
        }

        for (int i = 0; i < faceViews.length; i++) {
            if (i < trackedFaces.size()) {
                TrackedFace tf = trackedFaces.get(i);
                try {
                    float scaleFactor = 2.5f;  // 保證畫面比臉大 ? %

                    int desiredW = (int)(tf.w * scaleFactor);
                    int desiredH = (int)(tf.h * scaleFactor);

                    // 中心點不變
                    int centerX = tf.x + tf.w / 2;
                    int centerY = tf.y + tf.h / 2;

                    // 計算新的裁切框（左上角）
                    int newX = centerX - desiredW / 2;
                    int newY = centerY - desiredH / 2;

                    // 邊界修正
                    newX = Math.max(0, newX);
                    newY = Math.max(0, newY);
                    desiredW = Math.min(fullFrame.getWidth() - newX, desiredW);
                    desiredH = Math.min(fullFrame.getHeight() - newY, desiredH);


                    //if (newW > 0 && newH > 0) {
                    if (desiredW > 0 && desiredH > 0) {
                        // 無論有無新偵測，這邊都用當前 bitmap 裁切
                        Bitmap faceBmp = Bitmap.createBitmap(fullFrame, newX, newY, desiredW, desiredH);
                        Bitmap resized = Bitmap.createScaledBitmap(faceBmp, 256, 256, true);
                        faceViews[i].setImageBitmap(resized);
                        //Bitmap faceBmp = Bitmap.createBitmap(fullFrame, newX, newY, newW, newH);
                        //faceViews[i].setImageBitmap(faceBmp);
                    } else {
                        faceViews[i].setImageBitmap(null);
                    }


/*
                    // 計算中心點
                    int cx = tf.x + tf.w / 2;
                    int cy = tf.y + tf.h / 2;

                    int verticalPadding = 40;

                    int cropW = tf.w;
                    int cropH = tf.h + verticalPadding * 2;

                    int left = cx - cropW / 2;
                    int top = cy - cropH / 2;

                    // 邊界修正
                    if (left < 0) left = 0;
                    if (top < 0) top = 0;
                    if (left + cropW > fullFrame.getWidth()) cropW = fullFrame.getWidth() - left;
                    if (top + cropH > fullFrame.getHeight()) cropH = fullFrame.getHeight() - top;

                    // 防止負值或無效裁切
                    if (cropW <= 0 || cropH <= 0 || left < 0 || top < 0 ||
                            left + cropW > fullFrame.getWidth() || top + cropH > fullFrame.getHeight()) {
                        faceViews[i].setImageBitmap(null);
                        continue;
                    }

                    Bitmap faceBmp = Bitmap.createBitmap(fullFrame, left, top, cropW, cropH);
                    Bitmap resized = Bitmap.createScaledBitmap(faceBmp, 256, 256, true);
                    faceViews[i].setImageBitmap(resized);

 */
                } catch (Exception e) {
                    faceViews[i].setImageBitmap(null);
                    Log.e("FACE_CROP", "裁切錯誤 index=" + i, e);
                }
            } else {
                faceViews[i].setImageBitmap(null);
            }
        }
    }

    private void updateTrackedFaces(List<int[]> newRects) {
        long now = System.currentTimeMillis();

        // 更新已有的人臉
        for (int[] rect : newRects) {
            int x = rect[0], y = rect[1], w = rect[2], h = rect[3];

            if (w * h < FACE_AREA_THRESHOLD) {
                continue; // 忽略面積太小的人臉
            }

            boolean matched = false;
            for (TrackedFace tf : trackedFaces) {
                if (tf.isSimilar(x, y, w, h)) {
                    //tf.x = x;
                    //tf.y = y;
                    //tf.w = w;
                    //tf.h = h;
                    tf.x = (int)(0.7 * tf.x + 0.3 * x);
                    tf.y = (int)(0.7 * tf.y + 0.3 * y);

                    if (w > tf.w * 1.3 || h > tf.h * 1.3) {
                        int maxW = width / 2;
                        int maxH = height / 2;
                        tf.w = Math.min(w, maxW);
                        tf.h = Math.min(h, maxH);
                    }

                    tf.lastSeenTime = now;
                    matched = true;
                    break;
                }
            }

            if (!matched) {
                // 與已知臉都不相似 → 檢查是否已有 pending
                boolean foundPending = false;
                for (PendingFace pf : pendingFaces) {
                    int dx = Math.abs(pf.x - x);
                    int dy = Math.abs(pf.y - y);
                    if (dx < 100 && dy < 100) {
                        pf.seenCount++;
                        pf.firstSeenTime = now;
                        foundPending = true;

                        // 如果連續出現足夠次數，就變成正式人臉
                        if (pf.seenCount >= PENDING_CONFIRM_FRAMES) {
                            if (trackedFaces.size() < 4) {
                                trackedFaces.add(new TrackedFace(x, y, w, h, now));
                            }
                            pendingFaces.remove(pf);
                        }
                        break;
                    }
                }

                // 沒找到 pending → 加進去
                if (!foundPending) {
                    pendingFaces.add(new PendingFace(x, y, w, h, now));
                }
            }

            //if (!matched && trackedFaces.size() < 4) {
            //    trackedFaces.add(new TrackedFace(x, y, w, h, now));
            //}
        }

        // 移除過期的人臉
        trackedFaces.removeIf(tf -> now - tf.lastSeenTime > FACE_HOLD_DURATION_MS);
        pendingFaces.removeIf(pf -> now - pf.firstSeenTime > PENDING_MAX_TIME_MS);
    }

    @Override
    public void surfaceCreated(@NonNull SurfaceHolder holder) {
        findUsbDevice();
        usbXuEnable();
        new Thread(() -> CameraNative.nativeStartStream(devicePath, this)).start(); // 原本 JNI 呼叫
        startFrameWorker();
    }

    @Override
    public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {}

    @Override
    public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
        usbXuDisable();
        stopFrameWorker();

        if (reusableBitmap != null) {
            reusableBitmap.recycle();
            reusableBitmap = null;
        }
        reusableBuffer = null;

        // Release MediaPlayer
        if (mediaPlayer != null) {
            mediaPlayer.release();
            mediaPlayer = null;
        }

        Log.e("GetFaceInfo", "Surface destroyed, resources released.");
    }

    public void usbXuEnable() {
        byte[] data = new byte[]{
                (byte)0xFF, (byte)0x14, (byte)0x02,
                (byte)0x01, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00};
        CameraNative.setXuControl(data, devicePath);
    }
    public void usbXuDisable() {
        byte[] data = new byte[]{
                (byte)0xFF, (byte)0x14, (byte)0x02,
                (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00};
        CameraNative.setXuControl(data, devicePath);
    }

    public void enableAutoFraming() {
        usbXuEnable();

        byte[] data = new byte[]{
                (byte)0xFF, (byte)0x14, (byte)0x01,
                (byte)0x01, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00};
        CameraNative.setXuControl(data, devicePath);
    }

    public void disableAutoFraming() {
        byte[] data = new byte[]{
                (byte)0xFF, (byte)0x14, (byte)0x01,
                (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00, (byte)0x00};
        CameraNative.setXuControl(data, devicePath);

        usbXuDisable();
    }


    private void drawToSurfaceView(Bitmap bitmap) {
        Canvas canvas = surfaceHolder.lockCanvas();
        if (!drawFaceRect) {
            if (canvas != null) {
                float viewW = canvas.getWidth();
                float viewH = canvas.getHeight();
                float scale = Math.min(viewW / width, viewH / height);
                float drawW = width * scale;
                float drawH = height * scale;
                RectF destRect = new RectF(0, 0, drawW, drawH);
                canvas.drawBitmap(bitmap, null, destRect, null);
                surfaceHolder.unlockCanvasAndPost(canvas);
            }
        } else {
            if (canvas != null) {
                float viewW = canvas.getWidth();
                float viewH = canvas.getHeight();
                float scale = Math.min(viewW / width, viewH / height);
                float drawW = width * scale;
                float drawH = height * scale;

                RectF destRect = new RectF(0, 0, drawW, drawH);
                canvas.drawBitmap(bitmap, null, destRect, null);

                // 繪製原始人臉框與裁切框
                if (smartGalleryEnabled && !trackedFaces.isEmpty()) {
                    Paint paintOriginal = new Paint();
                    paintOriginal.setColor(Color.RED);
                    paintOriginal.setStyle(Paint.Style.STROKE);
                    paintOriginal.setStrokeWidth(4);

                    Paint paintCrop = new Paint();
                    paintCrop.setColor(Color.GREEN);
                    paintCrop.setStyle(Paint.Style.STROKE);
                    paintCrop.setStrokeWidth(4);

                    for (TrackedFace tf : trackedFaces) {
                        // 畫原始人臉框（紅色）
                        float x = tf.x * scale;
                        float y = tf.y * scale;
                        float w = tf.w * scale;
                        float h = tf.h * scale;
                        canvas.drawRect(x, y, x + w, y + h, paintOriginal);

                        // 計算右側裁切框（綠色）
                        /*
                        int cx = tf.x + tf.w / 2;
                        int cy = tf.y + tf.h / 2;
                        int verticalPadding = 30;
                        int cropW = tf.w;
                        int cropH = tf.h + verticalPadding * 2;
                        int cropLeft = Math.max(0, cx - cropW / 2);
                        int cropTop = Math.max(0, cy - cropH / 2);

                         */

                        float scaleFactor = 1.4f;  // 保證畫面比臉大 ? %

                        int cropW = (int)(tf.w * scaleFactor);
                        int cropH = (int)(tf.h * scaleFactor);

                        // 中心點不變
                        int cx = tf.x + tf.w / 2;
                        int cy = tf.y + tf.h / 2;

                        // 計算新的裁切框（左上角）
                        int cropLeft = cx - cropW / 2;
                        int cropTop = cy - cropH / 2;

                        // 畫裁切區（綠色）
                        float left = cropLeft * scale;
                        float top = cropTop * scale;
                        float right = left + cropW * scale;
                        float bottom = top + cropH * scale;
                        canvas.drawRect(left, top, right, bottom, paintCrop);
                    }
                }

                surfaceHolder.unlockCanvasAndPost(canvas);
            }
        }
    }

    private void startFrameWorker() {
        int bitmapSize = width * height * 4; // ARGB_8888
        reusableBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        reusableBuffer = ByteBuffer.allocateDirect(bitmapSize);

        frameWorkerRunning = true;
        frameWorkerThread = new Thread(() -> {
            while (frameWorkerRunning) {
                byte[] frame;
                synchronized (queueLock) {
                    while (frameQueue.isEmpty()) {
                        try {
                            queueLock.wait();
                        } catch (InterruptedException e) {
                            return;
                        }
                    }
                    frame = frameQueue.poll();
                }

                if (frame != null) {
                    reusableBuffer.clear();
                    reusableBuffer.put(frame);
                    reusableBuffer.rewind();

                    try {
                        reusableBitmap.copyPixelsFromBuffer(reusableBuffer);
                    } catch (Exception e) {
                        continue;
                    }

                    // 每 5 幀更新一次人臉座標
                    if (frameCount % 2 == 0) {
                        List<int[]> faces = getAllFaceRects();
                        updateTrackedFaces(faces);
                    }

                    frameCount++;

                    //Bitmap bitmapToDraw = Bitmap.createBitmap(reusableBitmap);
                    uiHandler.post(() -> {
                        drawToSurfaceView(reusableBitmap);     // 畫左側主畫面
                        showTrackedFaces(reusableBitmap);     //  更新右側人臉小畫面
                    });
                }
            }
        });

        frameWorkerThread.start();
    }

    private void stopFrameWorker() {
        frameWorkerRunning = false;
        if (frameWorkerThread != null && frameWorkerThread.isAlive()) {
            frameWorkerThread.interrupt();  // 強制喚醒 Thread.sleep
            try {
                frameWorkerThread.join();   // 等待執行緒完全結束
            } catch (InterruptedException e) {
                Log.e("FrameWorker", "Thread interrupted", e);
            }
            frameWorkerThread = null;
        }
    }

    public void drawFrame(byte[] rgbData) {
        synchronized (queueLock) {
            if (frameQueue.size() < 2) { // 限制 queue 長度避免佔太多記憶體
                frameQueue.offer(rgbData.clone());
                queueLock.notify();
            }
        }
    }

    static class TrackedFace {
        int x, y, w, h;
        long lastSeenTime;

        public TrackedFace(int x, int y, int w, int h, long time) {
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
            this.lastSeenTime = time;
        }

        public boolean isSimilar(int x2, int y2, int w2, int h2) {
            int cx1 = this.x + this.w / 2;
            int cy1 = this.y + this.h / 2;
            int cx2 = x2 + w2 / 2;
            int cy2 = y2 + h2 / 2;
            int dx = Math.abs(cx1 - cx2);
            int dy = Math.abs(cy1 - cy2);
            return dx < 400 && dy < 400;
        }
    }

    static class PendingFace {
        int x, y, w, h;
        long firstSeenTime;
        int seenCount = 1;

        public PendingFace(int x, int y, int w, int h, long now) {
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
            this.firstSeenTime = now;
        }
    }
}
