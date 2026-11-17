package com.daily.healthlife;

import android.app.AlarmManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import java.util.Calendar;

/**
 * P66 陪聊「主动消息」的系统级调度。
 *
 * 页面开着时由前端定时器判断时机、让模型现写一句；App 被杀或在后台被冻结时前端跑不了，
 * 就由这里按同样间隔用 AlarmManager 叫醒，发一条「TA 发来一条消息」。
 * 通知正文用本机存下的 TA 最后一句；点开以后由页面让模型现场再写一句新的——
 * 不为它编台词，也不在广播里做网络请求（Doze 下拿不到结果）。
 *
 * 故意不用精确闹钟：晚几分钟到，反而更像一个真人在忙完之后想起你。
 */
public class CompanionPingReceiver extends BroadcastReceiver {

    public static final String ACTION_FIRE = "com.daily.healthlife.COMPANION_PING";
    private static final String CHANNEL = "companion_msg";
    private static final String SP = "CompanionPing";
    private static final int REQ_ALARM = 5301;
    private static final int REQ_NOTIFY = 5302;

    /** 页面同步参数：minutes=0 表示关掉主动消息 */
    public static void sync(Context context, int minutes, String name, String last) {
        try {
            SharedPreferences sp = context.getSharedPreferences(SP, Context.MODE_PRIVATE);
            sp.edit()
                    .putInt("minutes", Math.max(0, minutes))
                    .putString("name", name == null ? "" : name)
                    .putString("last", last == null ? "" : last)
                    .apply();
            if (minutes <= 0) cancel(context);
            else scheduleNext(context, minutes);
        } catch (Throwable ignored) {
        }
    }

    public static void cancel(Context context) {
        try {
            AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            Intent intent = new Intent(context, CompanionPingReceiver.class).setAction(ACTION_FIRE);
            int flags = PendingIntent.FLAG_NO_CREATE;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
            PendingIntent pi = PendingIntent.getBroadcast(context, REQ_ALARM, intent, flags);
            if (pi != null) {
                am.cancel(pi);
                pi.cancel();
            }
        } catch (Throwable ignored) {
        }
    }

    private static void scheduleNext(Context context, int minutes) {
        try {
            AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            Intent intent = new Intent(context, CompanionPingReceiver.class).setAction(ACTION_FIRE);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
            PendingIntent pi = PendingIntent.getBroadcast(context, REQ_ALARM, intent, flags);
            long at = System.currentTimeMillis() + Math.max(10, minutes) * 60000L;
            // 用 inexact：允许系统攒到合适的时机再送，省电也更像真人回消息的节奏
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        try {
            String action = (intent == null || intent.getAction() == null) ? "" : intent.getAction();
            SharedPreferences sp = context.getSharedPreferences(SP, Context.MODE_PRIVATE);
            int minutes = sp.getInt("minutes", 0);
            if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                    || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
                if (minutes > 0) scheduleNext(context, minutes);
                return;
            }
            if ("android.intent.action.TIME_SET".equals(action)) {
                if (minutes > 0) scheduleNext(context, minutes);
                return;
            }
            if (minutes <= 0) return;
            if (!inQuietHours()) {
                notifyPing(context, sp.getString("name", ""), sp.getString("last", ""));
            }
            scheduleNext(context, minutes);
        } catch (Throwable ignored) {
        }
    }

    /** 23:30–07:00 不打扰：和页面里的判断保持一致，省得半夜被叫醒 */
    private static boolean inQuietHours() {
        try {
            Calendar c = Calendar.getInstance();
            int t = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
            return t >= (23 * 60 + 30) || t < (7 * 60);
        } catch (Throwable t) {
            return false;
        }
    }

    private static void notifyPing(Context context, String name, String last) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS)
                        != android.content.pm.PackageManager.PERMISSION_GRANTED) return;
            }
            NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel ch = new NotificationChannel(CHANNEL, "TA 的消息", NotificationManager.IMPORTANCE_HIGH);
                ch.setDescription("陪聊角色主动发来的消息");
                ch.enableVibration(true);
                ch.setVibrationPattern(new long[]{0, 120, 90, 120});
                nm.createNotificationChannel(ch);
            }
            Intent open = new Intent(context, MainActivity.class);
            open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            open.putExtra(MainActivity.EXTRA_COMPANION_PING, true);
            open.putExtra("healthlife_jump", "companion");
            int cflags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) cflags |= PendingIntent.FLAG_IMMUTABLE;
            PendingIntent content = PendingIntent.getActivity(context, REQ_NOTIFY, open, cflags);

            String who = (name == null || name.trim().isEmpty()) ? "TA" : name.trim();
            String body = (last == null || last.trim().isEmpty()) ? "……" : last.trim();
            NotificationCompat.Builder b = new NotificationCompat.Builder(context, CHANNEL)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle(who + " 发来一条消息")
                    .setContentText(body)
                    .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                    .setAutoCancel(true)
                    .setVibrate(new long[]{0, 120, 90, 120})
                    .setContentIntent(content);
            nm.notify(REQ_NOTIFY, b.build());
            vibrate(context);
        } catch (Throwable ignored) {
        }
    }

    private static void vibrate(Context context) {
        try {
            Vibrator v = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
            if (v == null) return;
            if (Build.VERSION.SDK_INT >= 26) {
                v.vibrate(VibrationEffect.createWaveform(new long[]{0, 120, 90, 120}, -1));
            } else {
                v.vibrate(new long[]{0, 120, 90, 120}, -1);
            }
        } catch (Throwable ignored) {
        }
    }
}
