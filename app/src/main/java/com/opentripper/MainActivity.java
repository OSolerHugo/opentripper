package com.opentripper;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.text.method.ScrollingMovementMethod;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity implements TripperBridge.Listener {

    private TripperBridge bridge;

    private View statusDot;
    private TextView statusText;
    private Button btnConnect;
    private EditText pinInput;
    private Button btnSendPin;
    private Button btnLeft, btnRight, btnStraight, btnRound;
    private Button btnSyncClock, btnCall;
    private Button btnCopyLogs;
    private TextView logView;

    private final StringBuilder logBuffer = new StringBuilder();
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusDot = findViewById(R.id.statusDot);
        statusText = findViewById(R.id.statusText);
        btnConnect = findViewById(R.id.btnConnect);
        pinInput = findViewById(R.id.pinInput);
        btnSendPin = findViewById(R.id.btnSendPin);
        btnLeft = findViewById(R.id.btnLeft);
        btnRight = findViewById(R.id.btnRight);
        btnStraight = findViewById(R.id.btnStraight);
        btnRound = findViewById(R.id.btnRound);
        btnSyncClock = findViewById(R.id.btnSyncClock);
        btnCall = findViewById(R.id.btnCall);
        btnCopyLogs = findViewById(R.id.btnCopyLogs);
        logView = findViewById(R.id.logView);
        logView.setMovementMethod(new ScrollingMovementMethod());

        bridge = new TripperBridge(this, this);

        btnConnect.setOnClickListener(v -> checkPermissionsAndConnect());

        btnSendPin.setOnClickListener(v -> {
            String pin = pinInput.getText().toString();
            if (!pin.isEmpty()) {
                bridge.sendPin(pin);
            }
        });

        btnLeft.setOnClickListener(v -> bridge.setNavManeuver(0x14, 150, "Virar Esquerda"));
        btnRight.setOnClickListener(v -> bridge.setNavManeuver(0x15, 150, "Virar Direita"));
        btnStraight.setOnClickListener(v -> bridge.setNavManeuver(0x09, 300, "Seguir Reto"));
        btnRound.setOnClickListener(v -> bridge.setNavManeuver(0x32, 200, "Rotatória 1ª"));

        btnSyncClock.setOnClickListener(v -> bridge.syncTime());
        btnCall.setOnClickListener(v -> bridge.setCallOverlay());

        btnCopyLogs.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                ClipData clip = ClipData.newPlainText("OpenTripper Logs", logBuffer.toString());
                cm.setPrimaryClip(clip);
                Toast.makeText(this, "Logs copiados!", Toast.LENGTH_SHORT).show();
            }
        });

        onLog("OpenTripper v0.3.0 iniciado", "sys");
    }

    private void checkPermissionsAndConnect() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED ||
                checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{
                        Manifest.permission.BLUETOOTH_SCAN,
                        Manifest.permission.BLUETOOTH_CONNECT,
                        Manifest.permission.ACCESS_FINE_LOCATION
                }, 101);
                return;
            }
        } else {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 101);
                return;
            }
        }

        bridge.startConnect();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 101) {
            bridge.startConnect();
        }
    }

    @Override
    public void onStateChanged(TripperBridge.State state) {
        runOnUiThread(() -> {
            switch (state) {
                case DISCONNECTED:
                    statusDot.setBackgroundColor(Color.parseColor("#EF4444"));
                    statusText.setText("Desconectado");
                    btnConnect.setEnabled(true);
                    btnConnect.setText("Conectar");
                    break;
                case SCANNING:
                    statusDot.setBackgroundColor(Color.parseColor("#F59E0B"));
                    statusText.setText("Buscando RE_DISP...");
                    btnConnect.setEnabled(false);
                    break;
                case CONNECTING:
                    statusDot.setBackgroundColor(Color.parseColor("#F59E0B"));
                    statusText.setText("Conectando...");
                    btnConnect.setEnabled(false);
                    break;
                case WAITING_PIN:
                    statusDot.setBackgroundColor(Color.parseColor("#38BDF8"));
                    statusText.setText("Aguardando PIN");
                    Toast.makeText(this, "Digite o PIN mostrado na moto!", Toast.LENGTH_LONG).show();
                    break;
                case CONNECTED:
                    statusDot.setBackgroundColor(Color.parseColor("#10B981"));
                    statusText.setText("Conectado!");
                    btnConnect.setEnabled(true);
                    btnConnect.setText("Desconectar");
                    btnConnect.setOnClickListener(v -> {
                        bridge.disconnect();
                        btnConnect.setOnClickListener(v2 -> checkPermissionsAndConnect());
                    });
                    break;
            }
        });
    }

    @Override
    public void onLog(String line, String type) {
        runOnUiThread(() -> {
            String time = timeFormat.format(new Date());
            String entry = "[" + time + "] " + line + "\n";
            logBuffer.append(entry);
            logView.setText(logBuffer.toString());
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (bridge != null) {
            bridge.disconnect();
        }
    }
}
