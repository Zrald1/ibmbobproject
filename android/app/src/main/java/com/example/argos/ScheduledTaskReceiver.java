package com.example.argos;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.app.NotificationManager;
import android.app.NotificationChannel;
import android.app.PendingIntent;
import android.os.Build;
import android.util.Log;

public class ScheduledTaskReceiver extends BroadcastReceiver {

    private static final String TAG = "ArgosScheduler";
    private static final String CHANNEL_ID = "argos_tasks";

    @Override
    public void onReceive(Context context, Intent intent) {
        String taskText = intent.getStringExtra("task_text");
        int taskId = intent.getIntExtra("task_id", 0);

        if (taskText == null || taskText.isEmpty()) {
            Log.w(TAG, "Received scheduled task with empty text");
            return;
        }

        Log.i(TAG, "Scheduled task triggered: " + taskText);

        // Show notification
        showTaskNotification(context, taskId, taskText);

        // If FloatingRobotService is running, add message to chat
        FloatingRobotService svc = FloatingRobotService.getInstance();
        if (svc != null) {
            svc.onScheduledTaskFired(taskText);
        }
    }

    private void showTaskNotification(Context context, int taskId, String taskText) {
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);

        // Create channel for Android O+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "Argos Tasks", NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription("Scheduled task reminders from Argos");
            nm.createNotificationChannel(channel);
        }

        android.app.Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new android.app.Notification.Builder(context, CHANNEL_ID);
        } else {
            builder = new android.app.Notification.Builder(context);
        }

        Intent launchIntent = new Intent(context, MainActivity.class);
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pi = PendingIntent.getActivity(context, taskId, launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        builder.setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Argos Reminder")
            .setContentText(taskText)
            .setStyle(new android.app.Notification.BigTextStyle().bigText(taskText))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(android.app.Notification.PRIORITY_HIGH);

        nm.notify(taskId, builder.build());
    }
}
