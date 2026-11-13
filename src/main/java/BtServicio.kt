package com.remoto.jvc

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

class BtServicio : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val canal = "control_jvc"
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(canal, "Control JVC", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, canal)
        else Notification.Builder(this)
        val n = b.setContentTitle("Control JVC")
            .setContentText("Bluetooth activo")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(77, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(77, n)
        }
        return START_STICKY
    }
}

// Mantiene viva la app mientras haya un TV Bluetooth guardado
fun MainActivity.iniciarServicioBt() {
    try {
        prefs.getString("bt_mac", null) ?: return
        if (Build.VERSION.SDK_INT >= 31 &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) return
        val i = Intent(this, BtServicio::class.java)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
    } catch (e: Exception) {
    }
}
