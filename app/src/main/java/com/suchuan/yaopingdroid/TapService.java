package com.suchuan.yaopingdroid;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

/**
 * 无障碍服务：收到 "d/m/u x y"（0~1 归一化坐标）后，在本机模拟触摸。
 */
public class TapService extends AccessibilityService {

    private static final String TAG = "YaopingTap";
    private static TapService instance;

    private Path path;
    private long downAt;
    private float lastX, lastY;
    private int screenW = 1080, screenH = 1920;

    public static TapService get() {
        return instance;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        DisplayMetrics dm = getResources().getDisplayMetrics();
        screenW = dm.widthPixels;
        screenH = dm.heightPixels;
        Log.i(TAG, "无障碍服务已连接 " + screenW + "x" + screenH);
        MainActivity.log("无障碍服务已连接");
    }

    @Override
    public void onDestroy() {
        instance = null;
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) { }

    @Override
    public void onInterrupt() { }

    /** 由 CaptureService 调用 */
    public static void handle(String msg, int w, int h) {
        TapService s = instance;
        if (s == null) {
            Log.w(TAG, "无障碍服务未开启，忽略指令: " + msg);
            return;
        }
        if (w > 0) s.screenW = w;
        if (h > 0) s.screenH = h;
        s.onCommand(msg);
    }

    private void onCommand(String msg) {
        String[] parts = msg.trim().split(" ");
        if (parts.length < 3) return;
        float x, y;
        try {
            x = Float.parseFloat(parts[1]) * screenW;
            y = Float.parseFloat(parts[2]) * screenH;
        } catch (NumberFormatException e) {
            return;
        }
        String phase = parts[0];

        switch (phase) {
            case "d":
                path = new Path();
                path.moveTo(x, y);
                downAt = System.currentTimeMillis();
                lastX = x;
                lastY = y;
                break;
            case "m":
                if (path != null) {
                    path.lineTo(x, y);
                    lastX = x;
                    lastY = y;
                }
                break;
            case "u":
                if (path != null) {
                    path.lineTo(x, y);
                } else {
                    // 只收到抬手（快速点一下），当单点处理
                    path = new Path();
                    path.moveTo(x, y);
                    downAt = System.currentTimeMillis() - 60;
                }
                lastX = x;
                lastY = y;
                dispatch();
                break;
            default:
                break;
        }
    }

    private void dispatch() {
        Path p = path;
        path = null;
        if (p == null) return;
        long dur = System.currentTimeMillis() - downAt;
        if (dur < 50) dur = 50;
        if (dur > 600) dur = 600;
        try {
            GestureDescription.StrokeDescription stroke = new GestureDescription.StrokeDescription(p, 0, dur);
            GestureDescription gesture = new GestureDescription.Builder().addStroke(stroke).build();
            dispatchGesture(gesture, null, null);
        } catch (Exception e) {
            Log.w(TAG, "注入失败: " + e);
        }
    }
}
