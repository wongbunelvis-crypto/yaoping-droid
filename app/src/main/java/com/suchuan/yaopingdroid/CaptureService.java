package com.suchuan.yaopingdroid;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

public class CaptureService extends Service {

    private static final String TAG = "YaopingCap";
    public static final String EXTRA_CODE = "code";
    public static final String EXTRA_DATA = "data";
    public static final String EXTRA_ROOM = "room";

    private MediaProjection projection;
    private VirtualDisplay display;
    private ImageReader reader;
    private MqttLite mqtt;

    private int captureW, captureH, density;
    private long lastFrameAt = 0;
    private static final int TARGET_W = 480;
    private static final int FRAME_GAP_MS = 110;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(1, buildNotification());

        if (intent == null) return START_STICKY;
        int code = intent.getIntExtra(EXTRA_CODE, 0);
        Intent data = intent.getParcelableExtra(EXTRA_DATA);
        String room = intent.getStringExtra(EXTRA_ROOM);
        if (room == null || room.isEmpty()) room = "8888";

        DisplayMetrics dm = new DisplayMetrics();
        WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        if (Build.VERSION.SDK_INT >= 30) {
            wm.getDefaultDisplay().getRealMetrics(dm);
        } else {
            wm.getDefaultDisplay().getMetrics(dm);
        }
        captureW = dm.widthPixels;
        captureH = dm.heightPixels;
        density = dm.densityDpi;

        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        projection = mpm.getMediaProjection(code, data);
        if (projection == null) {
            MainActivity.log("投屏授权失败");
            stopSelf();
            return START_NOT_STICKY;
        }

        reader = ImageReader.newInstance(captureW, captureH, PixelFormat.RGBA_8888, 2);
        display = projection.createVirtualDisplay("yaoping",
                captureW, captureH, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.getSurface(), null, null);

        reader.setOnImageAvailableListener(r -> {
            long now = System.currentTimeMillis();
            if (now - lastFrameAt < FRAME_GAP_MS) {
                Image img = r.acquireLatestImage();
                if (img != null) img.close();
                return;
            }
            Image img = r.acquireLatestImage();
            if (img == null) return;
            lastFrameAt = now;
            try {
                Bitmap bmp = toBitmap(img);
                if (bmp != null) {
                    int tw = TARGET_W;
                    int th = (int) ((float) bmp.getHeight() * tw / bmp.getWidth());
                    Bitmap small = Bitmap.createScaledBitmap(bmp, tw, th, true);
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    small.compress(Bitmap.CompressFormat.JPEG, 45, bos);
                    if (mqtt != null) mqtt.publish(bos.toByteArray());
                    if (small != bmp) small.recycle();
                    bmp.recycle();
                }
            } catch (Exception e) {
                Log.w(TAG, "frame error " + e);
            } finally {
                img.close();
            }
        }, null);

        // MQTT：发画面到 yaoping/<room>/v，收指令从 yaoping/<room>/t
        mqtt = new MqttLite("broker.emqx.io", 1883,
                "d" + android.os.Build.MODEL.replace(" ", "") + (int) (Math.random() * 1000),
                "yaoping/" + room + "/t",
                "yaoping/" + room + "/v");
        mqtt.setListener(payload -> {
            String s = new String(payload, java.nio.charset.StandardCharsets.UTF_8);
            TapService.handle(s, captureW, captureH);
        });
        mqtt.start();

        MainActivity.log("已开始共享 · 房间 " + room + " · " + captureW + "x" + captureH);
        return START_STICKY;
    }

    private Bitmap toBitmap(Image image) {
        Image.Plane plane = image.getPlanes()[0];
        ByteBuffer buffer = plane.getBuffer();
        int pixelStride = plane.getPixelStride();
        int rowStride = plane.getRowStride();
        int rowPadding = rowStride - pixelStride * captureW;

        Bitmap padded = Bitmap.createBitmap(captureW + rowPadding / pixelStride, captureH,
                Bitmap.Config.ARGB_8888);
        padded.copyPixelsFromBuffer(buffer);
        Bitmap cropped = Bitmap.createBitmap(padded, 0, 0, captureW, captureH);
        if (cropped != padded) padded.recycle();
        return cropped;
    }

    private Notification buildNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        String ch = "yaoping";
        if (nm.getNotificationChannel(ch) == null) {
            nm.createNotificationChannel(new NotificationChannel(ch, "屏幕共享", NotificationManager.IMPORTANCE_LOW));
        }
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, ch)
                : new Notification.Builder(this);
        return b.setContentTitle("遥屏正在共享屏幕")
                .setContentText("对方可以看到并操作这台手机")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setOngoing(true)
                .build();
    }

    @Override
    public void onDestroy() {
        try { if (reader != null) reader.close(); } catch (Exception ignored) { }
        try { if (display != null) display.release(); } catch (Exception ignored) { }
        try { if (projection != null) projection.stop(); } catch (Exception ignored) { }
        if (mqtt != null) mqtt.stop();
        MainActivity.log("已停止共享");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
