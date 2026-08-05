package com.walltech.callkiller;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity {

    public static final int PERM_REQUEST_CODE = 100;

    private Switch swEnabled;
    private TextView tvStatus, tvLog;
    private Button btnGrant, btnTest;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        swEnabled   = findViewById(R.id.swEnabled);
        tvStatus    = findViewById(R.id.tvStatus);
        tvLog       = findViewById(R.id.tvLog);
        btnGrant    = findViewById(R.id.btnGrant);
        btnTest     = findViewById(R.id.btnTest);

        // 读取开关状态
        swEnabled.setChecked(AppPrefs.isEnabled(this));

        swEnabled.setOnCheckedChangeListener((btn, checked) -> {
            AppPrefs.setEnabled(this, checked);
            if (checked) {
                appendLog("已开启监控");
                PhoneStateReceiver.startService(this);
            } else {
                appendLog("已关闭监控");
                PhoneStateReceiver.stopService(this);
            }
        });

        btnGrant.setOnClickListener(v -> requestAllPermissions());

        btnTest.setOnClickListener(v -> {
            if (AppPrefs.isEnabled(this)) {
                appendLog("模拟：启动监控服务（测试）");
                PhoneStateReceiver.testHangup(this);
            } else {
                appendLog("请先开启监控开关");
            }
        });

        // 启动时检查权限
        if (!hasAllPermissions()) {
            tvStatus.setText("⚠️ 请先授予全部权限");
            btnGrant.setVisibility(Button.VISIBLE);
        } else {
            tvStatus.setText("✅ 权限就绪");
            btnGrant.setVisibility(Button.GONE);
        }

        // 如果已开启，提示服务状态
        if (AppPrefs.isEnabled(this)) {
            appendLog("监控服务已就绪，等待通话...");
        }
    }

    private boolean hasAllPermissions() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE)
                    == PackageManager.PERMISSION_GRANTED
            && ContextCompat.checkSelfPermission(this, Manifest.permission.ANSWER_PHONE_CALLS)
                    == PackageManager.PERMISSION_GRANTED
            && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED;
    }

    private void requestAllPermissions() {
        ActivityCompat.requestPermissions(this, new String[]{
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.ANSWER_PHONE_CALLS,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.POST_NOTIFICATIONS,
        }, PERM_REQUEST_CODE);
    }

    @Override
    public void onRequestPermissionsResult(int req, @NonNull String[] perms, @NonNull int[] grants) {
        super.onRequestPermissionsResult(req, perms, grants);
        if (req == PERM_REQUEST_CODE) {
            boolean all = true;
            for (int g : grants) {
                if (g != PackageManager.PERMISSION_GRANTED) { all = false; break; }
            }
            if (all) {
                tvStatus.setText("✅ 权限已全部授予");
                btnGrant.setVisibility(Button.GONE);
                appendLog("权限授予完成，可以开启监控了");
            } else {
                tvStatus.setText("⚠️ 部分权限被拒绝，请手动开启");
                btnGrant.setVisibility(Button.VISIBLE);
                appendLog("权限不足，部分功能无法使用");
            }
        }
    }

    public void appendLog(String msg) {
        runOnUiThread(() -> {
            String stamp = java.text.SimpleDateFormat.getTimeInstance().format(new java.util.Date());
            String line = "[" + stamp + "] " + msg + "\n";
            tvLog.setText(tvLog.getText() + line);
            // 滚动到底部
            ScrollView sv = findViewById(R.id.svLog);
            sv.post(() -> sv.fullScroll(ScrollView.FOCUS_DOWN));
        });
    }
}
