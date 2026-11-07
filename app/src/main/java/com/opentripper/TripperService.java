package com.opentripper;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;

/**
 * Serviço de Segundo Plano (Foreground Service) do OpenTripper.
 * 
 * Permite que a navegação e o velocímetro continuem funcionando
 * com a tela do celular apagada no bolso da jaqueta do motociclista.
 */
public class TripperService extends Service implements TripperBridge.Listener {

    private static final String CHANNEL_ID = "opentripper_fg_channel";
    private static final int NOTIFICATION_ID = 1001;

    public class LocalBinder extends Binder {
        public TripperService getService() {
            return TripperService.this;
        }
    }

    private final IBinder binder = new LocalBinder();
    private TripperBridge bridge;
    private DisplayCoordinator displayCoordinator;
    private SharedPreferences prefs;

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences("opentripper_prefs", Context.MODE_PRIVATE);

        bridge = new TripperBridge(this, this);
        displayCoordinator = new DisplayCoordinator(this, bridge);

        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIFICATION_ID, buildForegroundNotification("Aguardando conexão com a moto..."));

        boolean autoConnect = prefs.getBoolean("auto_connect", false);
        if (autoConnect && !bridge.isConnected()) {
            bridge.startScanAndConnect();
        }

        return START_STICKY;
    }

    public TripperBridge getBridge() {
        return bridge;
    }

    public DisplayCoordinator getDisplayCoordinator() {
        return displayCoordinator;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (displayCoordinator != null) {
            displayCoordinator.onDisconnected();
        }
        if (bridge != null) {
            bridge.disconnect();
        }
    }

    @Override
    public void onStateChanged(TripperBridge.State state) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (state == TripperBridge.State.CONNECTED) {
            if (displayCoordinator != null) {
                displayCoordinator.onConnected();
            }
            if (nm != null) {
                nm.notify(NOTIFICATION_ID, buildForegroundNotification("🟢 Conectado ao Tripper Pod (Hunter 350)"));
            }
        } else if (state == TripperBridge.State.DISCONNECTED) {
            if (displayCoordinator != null) {
                displayCoordinator.onDisconnected();
            }
            if (nm != null) {
                nm.notify(NOTIFICATION_ID, buildForegroundNotification("🔴 Moto desconectada. Serviços pausados."));
            }
        }
    }

    @Override public void onLog(String message, String type) {}
    @Override public void onPinRequested() {}

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "OpenTripper Serviço de Conexão",
                NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Mantém a comunicação e navegação com o Tripper Pod no bolso");
            channel.setShowBadge(false);
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.createNotificationChannel(channel);
            }
        }
    }

    private Notification buildForegroundNotification(String statusText) {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }

        return builder
            .setContentTitle("OpenTripper")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build();
    }
}
