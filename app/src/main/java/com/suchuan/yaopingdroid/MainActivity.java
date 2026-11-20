package com.suchuan.yaopingdroid;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public class MainActivity extends Activity {

    private static TextView statusView;
    private EditText roomInput;
    private static final int REQ_CAPTURE = 1001;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(28), dp(20), dp(20));
        root.setBackgroundColor(0xFF0B0D12);

        TextView title = new TextView(this);
        title.setText("遥屏 · 安卓被控端");
        title.setTextColor(0xFFEDF2FF);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("被控的是这台安卓手机。iPad / iPhone 端用「遥屏」App 填同一个房间号即可看到画面并点击。");
        sub.setTextColor(0xFF8A93A6);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        sub.setPadding(0, dp(8), 0, dp(18));
        root.addView(sub);

        TextView label = new TextView(this);
        label.setText("房间号（4 位数字）");
        label.setTextColor(0xFFB9C2D6);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        root.addView(label);

        roomInput = new EditText(this);
        roomInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        roomInput.setText("8888");
        roomInput.setTextColor(0xFFEDF2FF);
        roomInput.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        roomInput.setPadding(dp(14), dp(10), dp(14), dp(10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        root.addView(roomInput, lp);

        Button start = new Button(this);
        start.setText("开始共享屏幕");
        start.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(50));
        slp.topMargin = dp(18);
        root.addView(start, slp);
        start.setOnClickListener(v -> requestCapture());

        Button acc = new Button(this);
        acc.setText("② 打开无障碍设置（允许模拟点击）");
        acc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48));
        alp.topMargin = dp(12);
        root.addView(acc, alp);
        acc.setOnClickListener(v -> {
            Intent i = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            startActivity(i);
            log("请在列表里找到「遥屏安卓端」并打开开关");
        });

        View line = new View(this);
        line.setBackgroundColor(0xFF1E2430);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1));
        llp.topMargin = dp(20);
        root.addView(line, llp);

        statusView = new TextView(this);
        statusView.setTextColor(0xFF6DE3B9);
        statusView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        statusView.setGravity(Gravity.TOP);
        statusView.setPadding(0, dp(14), 0, 0);
        ScrollView sc = new ScrollView(this);
        sc.addView(statusView);
        root.addView(sc, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        TextView foot = new TextView(this);
        foot.setText("提示：共享屏幕时后台不能被杀，最好插电使用；公共中转没有密码，房间号别用 8888 这种。");
        foot.setTextColor(0xFF596070);
        foot.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        root.addView(foot);

        setContentView(root);
        log("等待开始...");
    }

    private void requestCapture() {
        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        Intent i = mpm.createScreenCaptureIntent();
        startActivityForResult(i, REQ_CAPTURE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_CAPTURE) return;
        if (resultCode != RESULT_OK || data == null) {
            log("你拒绝了屏幕录制授权");
            return;
        }
        String room = roomInput.getText().toString().trim();
        if (room.isEmpty()) room = "8888";
        Intent svc = new Intent(this, CaptureService.class);
        svc.putExtra(CaptureService.EXTRA_CODE, resultCode);
        svc.putExtra(CaptureService.EXTRA_DATA, data);
        svc.putExtra(CaptureService.EXTRA_ROOM, room);
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            startForegroundService(svc);
        } else {
            startService(svc);
        }
        log("正在连接中转 · 房间 " + room);
    }

    public static void log(String s) {
        if (statusView == null) return;
        statusView.post(() -> statusView.append("\n" + s));
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
