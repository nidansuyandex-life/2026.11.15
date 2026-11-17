package com.daily.healthlife;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

/**
 * 走动提醒的「强提醒」统一出口（P61）。
 *
 * 为什么单独开一个渠道：老的 walk_timer_channel_high 建的时候 setSound(null, null)，
 * 到点只有一声 ToneGenerator + 一下短震动，手机在口袋里、或者正和别人说话时基本注意不到。
 * 通知渠道的声音一旦创建就不能再改，所以这里用新的渠道 id（walk_alert_v2）：
 *   - 系统「闹钟」铃声（走闹钟音量，比提示音明显得多）
 *   - 长震动：0.5 秒震 3 次
 *   - IMPORTANCE_HIGH + CATEGORY_ALARM + 全屏 Intent：锁屏和其他 App 界面上也能弹出来
 *   - 点开带 EXTRA_WALK_UI / EXTRA_WALK_END 标记，页面知道该弹倒计时还是弹「完成」
 */
public final class WalkAlertNotifier {

    public static final String CHANNEL_ALERT = "walk_alert_v2";
    private static final int ID_START = 103;
    private static final int ID_END = 102;

    private WalkAlertNotifier() {}

    /** 渠道只需建一次；重复 createNotificationChannel 不会覆盖用户后来改过的设置 */
    public static void ensureChannel(Context ctx) {
        if (ctx == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        try {
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ALERT, "走动提醒（强提醒）", NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("起身走动 / 活动结束：响铃 + 震动 + 全屏弹出");
            ch.enableVibration(true);
            ch.setVibrationPattern(new long[]{0, 600, 250, 600, 250, 900});
            ch.enableLights(true);
            ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            Uri sound = alarmSound();
            if (sound != null) ch.setSound(sound, audioAttrs());
            nm.createNotificationChannel(ch);
        } catch (Throwable ignored) {}
    }

    private static Uri alarmSound() {
        try {
            Uri u = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
            if (u == null) u = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
            return u;
        } catch (Throwable t) {
            return null;
        }
    }

    private static AudioAttributes audioAttrs() {
        try {
            return new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build();
        } catch (Throwable t) {
            return null;
        }
    }

    /** Android 13+ 没给通知权限时 nm.notify 会抛 SecurityException，先问一句 */
    public static boolean canNotify(Context ctx) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                return ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS)
                        == PackageManager.PERMISSION_GRANTED;
            }
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            return nm == null || nm.areNotificationsEnabled();
        } catch (Throwable t) {
            return false;
        }
    }

    private static PendingIntent openApp(Context ctx, int req, boolean endUi) {
        Intent i = new Intent(ctx, MainActivity.class);
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        if (endUi) i.putExtra(MainActivity.EXTRA_WALK_END, true);
        else i.putExtra(MainActivity.EXTRA_WALK_UI, true);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getActivity(ctx, req, i, flags);
    }

    /** 该起身了 */
    public static void showStart(Context ctx, int seconds) {
        show(ctx, ID_START, "该站起来走动了",
                "站起来走 2–3 分钟 · 点开看倒计时", false);
    }

    /** 活动结束 */
    public static void showEnd(Context ctx) {
        show(ctx, ID_END, "活动结束 · 可以回去了",
                "走够了，喝口水，慢慢坐下", true);
    }

    /** 设置页「试一次」：立刻走一遍真正到点时的路径，方便确认权限和音量 */
    public static void testNow(Context ctx) {
        ensureChannel(ctx);
        showStart(ctx, 200);
        vibrate(ctx);
    }

    private static void show(Context ctx, int id, String title, String text, boolean endUi) {
        if (ctx == null) return;
        try {
            ensureChannel(ctx);
            if (!canNotify(ctx)) return;
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            PendingIntent pi = openApp(ctx, id, endUi);
            NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, CHANNEL_ALERT)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                    .setPriority(NotificationCompat.PRIORITY_MAX)
                    .setCategory(NotificationCompat.CATEGORY_ALARM)
                    .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                    .setAutoCancel(true)
                    .setDefaults(NotificationCompat.DEFAULT_ALL)
                    .setVibrate(new long[]{0, 600, 250, 600, 250, 900})
                    .setContentIntent(pi)
                    .setFullScreenIntent(pi, true);
            Uri sound = alarmSound();
            /* NotificationCompat.Builder 的二参重载是 (Uri, 音频流类型 int)，
               不是 (Uri, AudioAttributes)——传 AudioAttributes 编译不过。
               API 26+ 声音由渠道决定（渠道里已设 USAGE_ALARM），这里的 stream
               只对 Android 7 及更早生效，所以明确写「闹钟流」，别退回通知音。 */
            if (sound != null) b.setSound(sound, AudioManager.STREAM_ALARM);
            nm.notify(id, b.build());
        } catch (Throwable ignored) {}
    }

    /** 长震动：响铃被关掉时，至少身上能感觉到 */
    public static void vibrate(Context ctx) {
        if (ctx == null) return;
        try {
            Vibrator v = (Vibrator) ctx.getSystemService(Context.VIBRATOR_SERVICE);
            if (v == null) return;
            long[] pattern = new long[]{0, 500, 200, 500, 200, 800};
            if (Build.VERSION.SDK_INT >= 26) {
                v.vibrate(VibrationEffect.createWaveform(pattern, -1));
            } else {
                v.vibrate(pattern, -1);
            }
        } catch (Throwable ignored) {}
    }
}
