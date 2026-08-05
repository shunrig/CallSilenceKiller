package com.walltech.callkiller;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

/**
 * 前台服务：通话静音 / 意外接听检测 + 自动挂断
 *
 * 核心检测逻辑（VAD 方案 A+B 组合）：
 *
 *   阶段1：接听缓冲期（2秒）
 *     └─ 对方说"喂"的时间，不计入静音计时
 *
 *   阶段2：VAD 检测（每 500ms）
 *     ├─ 能量阈值：-50dB ~ -8dB  范围内 → 语音候选
 *     ├─ 过零率（ZCR）：50~150 次/100ms → 人声特征
 *     └─ 综合评分 → 有/无语音活动
 *
 *   阶段3：最小语音时长闸门
 *     ├─ 检测到 ≥ 1.5 秒连续语音活动 → 判定正常对话 → 取消挂断
 *     └─ 持续 ≥ 18 秒无有效语音活动 → 执行挂断
 *
 * 可调参数（类顶部常量）：
 *   BUFFER_AFTER_PICKUP_MS   接听缓冲期（毫秒）
 *   CHECK_INTERVAL_MS        音频检测间隔
 *   DB_MIN / DB_MAX          有效语音能量范围
 *   ZCR_MIN / ZCR_MAX        有效人声过零率范围
 *   MIN_SPEECH_DURATION_MS   判定正常对话所需最短语音持续时间
 *   SILENCE_TIMEOUT_MS       无语音多久后触发挂断
 */
public class CallMonitorService extends Service {

    private static final String TAG = "CallKiller-Service";

    // ══════════════════════════════════════════════════════════════
    //  可调参数（按需修改）
    // ══════════════════════════════════════════════════════════════

    /** 接听后缓冲期（毫秒）：对方说"喂"的时间，不计入静音计时 */
    private static final long BUFFER_AFTER_PICKUP_MS = 2000L;

    /** 音频检测间隔（毫秒） */
    private static final long CHECK_INTERVAL_MS = 500L;

    /** 每帧音频采样时长（毫秒），用于计算过零率 */
    private static final int   FRAME_SIZE_MS    = 100;

    /** 有效语音能量下限（dB）：低于此视为静音 */
    private static final double DB_MIN          = -50.0;

    /** 有效语音能量上限（dB）：高于此视为异常（音乐/尖叫） */
    private static final double DB_MAX          = -8.0;

    /** 人声过零率下限（每 FRAME_SIZE_MS 的过零次数）：低频噪音（空调300Hz）极少过零 */
    private static final int   ZCR_MIN         = 10;

    /** 人声过零率上限（每 FRAME_SIZE_MS 的过零次数）：防止误识别高频噪声 */
    private static final int   ZCR_MAX         = 200;

    /** 连续多少次检测为"有语音活动"才算一个语音段落 */
    private static final int   SPEECH_FRAMES_THRESHOLD = 3; // 3 × 500ms = 1.5s

    /** 检测到有效语音段落后多久内不挂断（毫秒）：检测到即取消，不额外等 */
    private static final long  GRACE_CANCEL_MS  = 0L; // 有语音就立即取消

    /** 无语音活动多久后触发挂断（毫秒）：接听缓冲期结束后开始计时 */
    private static final long  SILENCE_TIMEOUT_MS = 18_000L;

    /** 音频采样率 */
    private static final int   SAMPLE_RATE     = 16000;

    // ══════════════════════════════════════════════════════════════

    // 状态机
    private static final int STATE_BUFFER      = 0; // 接听缓冲期（等2秒）
    private static final int STATE_MONITORING  = 1; // VAD 监控中
    private static final int STATE_GRACE       = 2; // 检测到静音，进入宽限期
    private static final int STATE_DONE        = 3; // 已处理（挂断或取消）

    private volatile boolean mRunning   = false;
    private int              mState    = STATE_BUFFER;

    private AudioRecord      mAudioRecord;
    private Thread           mMonitorThread;
    private PowerManager.WakeLock mWakeLock;

    // 计时器
    private long mPickupTimeMs    = 0;  // 接听时刻
    private long mLastSpeechMs   = 0;  // 最近一次有效语音的时间
    private long mSilenceStartMs = 0;  // 最近一次"无语音活动"的时间戳（连续）
    private int  mConsecutiveSpeechFrames = 0; // 连续语音帧数

    // 通知
    private static final int    NOTIF_ID_MAIN  = 1;
    private static final int    NOTIF_ID_WARN  = 2;
    private static final String CHANNEL_ID     = "call_killer_channel";

    private NotificationManager mNotifMgr;

    // ─────────────────────────────────────────────────────────────
    //  Service 生命周期
    // ─────────────────────────────────────────────────────────────

    @Override
    public void onCreate() {
        super.onCreate();
        mNotifMgr = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        createNotificationChannel();

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        mWakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "CallKiller::WakeLock");
        mWakeLock.acquire(10 * 60 * 1000L); // 最多持锁10分钟
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String label = intent != null ? intent.getStringExtra("call_label") : "通话监控";
        Log.d(TAG, "服务启动: " + label);

        startForeground(NOTIF_ID_MAIN, buildNotification("接听中: " + label));

        if (!mRunning) {
            mRunning = true;
            mState   = STATE_BUFFER;
            mPickupTimeMs = System.currentTimeMillis();
            mLastSpeechMs = mPickupTimeMs;
            mSilenceStartMs = 0;
            mConsecutiveSpeechFrames = 0;

            mMonitorThread = new Thread(this::monitorLoop, "VADMonitor");
            mMonitorThread.start();
        }

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        mRunning = false;
        if (mMonitorThread != null) {
            mMonitorThread.interrupt();
            try { mMonitorThread.join(2000); } catch (InterruptedException ignored) {}
        }
        releaseAudioRecord();
        if (mWakeLock != null && mWakeLock.isHeld()) mWakeLock.release();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }

    // ─────────────────────────────────────────────────────────────
    //  核心监控循环
    // ─────────────────────────────────────────────────────────────

    private void monitorLoop() {
        if (!initAudioRecord()) {
            Log.e(TAG, "AudioRecord 初始化失败，退出");
            stopSelf();
            return;
        }

        Log.d(TAG, "音频采集已启动，开始VAD检测");
        updateNotification("等待对方说话...");

        try {
            mAudioRecord.startRecording();

            while (mRunning && !Thread.currentThread().isInterrupted()) {
                long now = System.currentTimeMillis();
                long elapsed = now - mPickupTimeMs;

                // ══ 阶段1：接听缓冲期（等2秒，不计入静音计时） ══
                if (mState == STATE_BUFFER) {
                    if (elapsed >= BUFFER_AFTER_PICKUP_MS) {
                        mState = STATE_MONITORING;
                        mSilenceStartMs = now;
                        Log.d(TAG, "缓冲期结束，进入VAD监控模式");
                        updateNotification("检测通话中...");
                    }
                    Thread.sleep(CHECK_INTERVAL_MS);
                    continue;
                }

                // ══ 阶段2：VAD 检测 ══
                boolean hasSpeech = detectSpeech();

                switch (mState) {
                    case STATE_MONITORING:
                        if (hasSpeech) {
                            // 有语音 → 重置静音计时
                            mSilenceStartMs = now;
                            mConsecutiveSpeechFrames++;
                            mLastSpeechMs = now;

                            Log.v(TAG, "语音活动 +" + mConsecutiveSpeechFrames
                                    + "帧 | 分贝:" + lastDb() + " | ZCR:" + lastZcr());

                            // 最小语音时长闸门：连续 SPEECH_FRAMES_THRESHOLD 帧（1.5s）
                            if (mConsecutiveSpeechFrames >= SPEECH_FRAMES_THRESHOLD) {
                                // 判定为正常对话 → 取消挂断
                                mState = STATE_DONE;
                                Log.i(TAG, "✅ 检测到正常对话（" +
                                        (mConsecutiveSpeechFrames * CHECK_INTERVAL_MS / 1000.0) +
                                        "s 连续语音），取消挂断");
                                onNormalConversation();
                                break;
                            }
                        } else {
                            // 无语音 → 重置连续语音计数
                            if (mConsecutiveSpeechFrames > 0) {
                                Log.v(TAG, "语音中断，当前段:" + mConsecutiveSpeechFrames + "帧");
                            }
                            mConsecutiveSpeechFrames = 0;
                            long silenceDuration = now - mSilenceStartMs;

                            // 检查静音超时
                            if (silenceDuration >= SILENCE_TIMEOUT_MS) {
                                mState = STATE_GRACE;
                                Log.w(TAG, "⚠️ 静音已达 " + silenceDuration + "ms，进入宽限期...");
                                showWarningNotification();
                            } else {
                                updateNotification("等待对方说话... "
                                        + ((SILENCE_TIMEOUT_MS - silenceDuration) / 1000) + "s");
                            }
                        }
                        break;

                    case STATE_GRACE:
                        if (hasSpeech) {
                            // 宽限期内有语音 → 取消挂断
                            mState = STATE_MONITORING;
                            mSilenceStartMs = now;
                            mConsecutiveSpeechFrames = 1;
                            mLastSpeechMs = now;
                            cancelWarningNotification();
                            Log.i(TAG, "宽限期内检测到语音，取消挂断");
                            updateNotification("检测到语音，通话正常");
                        } else {
                            long graceDuration = now - mSilenceStartMs;
                            if (graceDuration >= SILENCE_TIMEOUT_MS) {
                                // 宽限期已满 → 执行挂断
                                mState = STATE_DONE;
                                Log.w(TAG, "宽限期已满，执行挂断！");
                                doHangup();
                                mRunning = false;
                            }
                        }
                        break;

                    case STATE_DONE:
                        // 已处理完毕，退出循环
                        mRunning = false;
                        break;
                }

                Thread.sleep(CHECK_INTERVAL_MS);
            }

        } catch (InterruptedException e) {
            Log.d(TAG, "监控线程被中断");
        } finally {
            releaseAudioRecord();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        }
    }

    // ─────────────────────────────────────────────────────────────
    //  VAD：语音活动检测
    // ─────────────────────────────────────────────────────────────

    // 用于日志输出（避免每次 new）
    private double lastDb  = -99;
    private int   lastZcr = 0;

    /**
     * 检测当前帧是否有语音活动。
     *
     * 双重判决：
     *   1. 能量判决：dB 在 [DB_MIN, DB_MAX] 范围内
     *   2. 过零率判决：ZCR 在 [ZCR_MIN, ZCR_MAX] 范围内
     *
     * 人声特征：
     *   - 能量：适中（-50dB ~ -8dB），太低是静音，太高是音乐/尖叫
     *   - ZCR：50~150 次/100ms，人声在300Hz~3kHz，过零率适中
     *
     * 噪音特征（会被过滤）：
     *   - 空调/引擎：低频，极少过零（ZCR < 10）
     *   - 电视/背景音乐：低能量但频谱宽，ZCR 偏低
     *   - 碰撞声/咳嗽：能量高但 ZCR 极低（低频脉冲）
     *
     * @return true = 有语音活动，false = 无语音活动
     */
    private boolean detectSpeech() {
        if (mAudioRecord == null ||
                mAudioRecord.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
            return false;
        }

        // 读取一帧音频（100ms）
        int frameBytes = (SAMPLE_RATE * FRAME_SIZE_MS / 1000) * 2; // 16-bit PCM
        short[] buf = new short[frameBytes / 2];
        int r = mAudioRecord.read(buf, 0, buf.length);
        if (r <= 0) return false;

        // ── 判决1：RMS 能量 ──
        double rms = 0;
        for (int i = 0; i < r; i++) {
            rms += (double) buf[i] * buf[i];
        }
        rms = Math.sqrt(rms / r);

        if (rms < 1) {
            lastDb = -99;
            return false;
        }
        lastDb = 20 * Math.log10(rms / 32768.0);

        // 能量过滤：太低（静音）或太高（异常噪声）
        if (lastDb < DB_MIN || lastDb > DB_MAX) {
            Log.v(TAG, "能量过滤: " + String.format("%.1f", lastDb) + " dB"
                    + " (范围:[" + DB_MIN + "," + DB_MAX + "])");
            return false;
        }

        // ── 判决2：过零率（Zero Crossing Rate） ──
        int zcr = 0;
        for (int i = 1; i < r; i++) {
            // 符号变化 = 过一次零
            if ((buf[i-1] >= 0 && buf[i] < 0) || (buf[i-1] < 0 && buf[i] >= 0)) {
                zcr++;
            }
        }
        // 归一化到 FRAME_SIZE_MS
        lastZcr = zcr;

        if (lastZcr < ZCR_MIN || lastZcr > ZCR_MAX) {
            Log.v(TAG, "ZCR过滤: " + lastZcr + " (范围:[" + ZCR_MIN + "," + ZCR_MAX + "])");
            return false;
        }

        // ══ 双重判决通过 → 有语音活动 ══
        Log.v(TAG, "语音活动: dB=" + String.format("%.1f", lastDb)
                + " ZCR=" + lastZcr);
        return true;
    }

    // ─────────────────────────────────────────────────────────────
    //  动作：正常对话 / 挂断
    // ─────────────────────────────────────────────────────────────

    private void onNormalConversation() {
        NotificationCompat.Builder nb = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("✅ 检测到正常对话")
                .setContentText("通话已确认，自动挂断已取消")
                .setSmallIcon(android.R.drawable.ic_menu_call)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setAutoCancel(true);
        mNotifMgr.notify(NOTIF_ID_MAIN, nb.build());

        // 延迟停止服务（保留通知2秒）
        new android.os.Handler(getMainLooper()).postDelayed(() -> {
            mRunning = false;
        }, 2000);
    }

    private void doHangup() {
        // 最终通知
        NotificationCompat.Builder nb = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("📞 已自动挂断")
                .setContentText("检测到持续无语音活动，已结束通话")
                .setSmallIcon(android.R.drawable.ic_menu_close_clear_cancel)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true);
        mNotifMgr.notify(NOTIF_ID_WARN, nb.build());

        // 在主线程执行挂断
        new android.os.Handler(getMainLooper()).post(() -> {
            try {
                android.telecom.TelecomManager tm =
                        (android.telecom.TelecomManager) getSystemService(Context.TELECOM_SERVICE);
                if (tm != null) {
                    boolean ok = tm.endCall();
                    Log.w(TAG, "挂断结果: " + ok);
                }
            } catch (Exception e) {
                Log.e(TAG, "TelecomManager 挂断失败: " + e.getMessage());
                try {
                    Runtime.getRuntime().exec("input keyevent "
                            + android.view.KeyEvent.KEYCODE_ENDCALL);
                    Log.w(TAG, "备选按键挂断成功");
                } catch (Exception ex) {
                    Log.e(TAG, "备选按键挂断也失败: " + ex.getMessage());
                }
            }
        });
    }

    // ─────────────────────────────────────────────────────────────
    //  音频采集初始化
    // ─────────────────────────────────────────────────────────────

    private boolean initAudioRecord() {
        int minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);

        if (minBuf == AudioRecord.ERROR || minBuf == AudioRecord.ERROR_BAD_VALUE) {
            Log.e(TAG, "无法获取最小缓冲区大小");
            return false;
        }

        int bufSize = Math.max(minBuf, SAMPLE_RATE * 2);

        try {
            // VOICE_COMMUNICATION：采集通话中对方的音频流
            // 需要 RECORD_AUDIO 权限
            mAudioRecord = new AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufSize);

            if (mAudioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord 未初始化");
                return false;
            }

            Log.d(TAG, "AudioRecord OK，缓冲:" + bufSize + " bytes");
            return true;

        } catch (SecurityException e) {
            Log.e(TAG, "权限不足: " + e.getMessage());
            return false;
        } catch (Exception e) {
            Log.e(TAG, "AudioRecord 异常: " + e.getMessage());
            return false;
        }
    }

    private void releaseAudioRecord() {
        try {
            if (mAudioRecord != null) {
                if (mAudioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                    mAudioRecord.stop();
                }
                mAudioRecord.release();
                mAudioRecord = null;
            }
        } catch (Exception e) {
            Log.d(TAG, "releaseAudioRecord: " + e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────
    //  通知
    // ─────────────────────────────────────────────────────────────

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID,
                    "通话监控",
                    NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("后台通话VAD监控");
            ch.setSound(null, null);
            ch.setShowBadge(false);
            mNotifMgr.createNotificationChannel(ch);
        }
    }

    private Notification buildNotification(String text) {
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(
                this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("📞 通话VAD监控")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_call)
                .setContentIntent(pi)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .build();
    }

    private void updateNotification(String text) {
        mNotifMgr.notify(NOTIF_ID_MAIN, buildNotification(text));
    }

    private void showWarningNotification() {
        NotificationCompat.Builder nb = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("⚠️ 通话无响应警告")
                .setContentText("对方持续无语音，即将自动挂断")
                .setSmallIcon(android.R.drawable.ic_menu_close_clear_cancel)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(false);
        mNotifMgr.notify(NOTIF_ID_WARN, nb.build());
    }

    private void cancelWarningNotification() {
        mNotifMgr.cancel(NOTIF_ID_WARN);
    }
}
