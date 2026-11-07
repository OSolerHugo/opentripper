package com.opentripper;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
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

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity implements TripperBridge.Listener {

    private static final String LOG_FILE_NAME = "opentripper_logs.txt";

    private TripperBridge bridge;

    private View statusDot;
    private TextView statusText;
    private Button btnConnect;
    private EditText pinInput;
    private Button btnSendPin;

    // Explorador de Ícones
    private TextView tvCurrentIcon;
    private Button btnPrevIcon, btnNextIcon, btnPrev10, btnNext10, btnJumpUntested, btnRecordDiscovery;
    private int currentIconCode = 0x00;
    private static final int[] UNTESTED_JUMPS = { 0x0B, 0x12, 0x23, 0x28, 0x40, 0x46, 0x60, 0x80 };
    private int untestedJumpIdx = 0;

    // Manobras rápidas
    private Button btnLeft, btnRight, btnStraight, btnRound;
    private Button btnSyncClock, btnCall;

    // Logs
    private Button btnCopyLogs, btnShareLogs, btnClearLogs;
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

        // Explorador
        tvCurrentIcon = findViewById(R.id.tvCurrentIcon);
        btnPrevIcon = findViewById(R.id.btnPrevIcon);
        btnNextIcon = findViewById(R.id.btnNextIcon);
        btnPrev10 = findViewById(R.id.btnPrev10);
        btnNext10 = findViewById(R.id.btnNext10);
        btnJumpUntested = findViewById(R.id.btnJumpUntested);
        btnRecordDiscovery = findViewById(R.id.btnRecordDiscovery);

        // Manobras
        btnLeft = findViewById(R.id.btnLeft);
        btnRight = findViewById(R.id.btnRight);
        btnStraight = findViewById(R.id.btnStraight);
        btnRound = findViewById(R.id.btnRound);
        btnSyncClock = findViewById(R.id.btnSyncClock);
        btnCall = findViewById(R.id.btnCall);

        // Logs
        btnCopyLogs = findViewById(R.id.btnCopyLogs);
        btnShareLogs = findViewById(R.id.btnShareLogs);
        btnClearLogs = findViewById(R.id.btnClearLogs);
        logView = findViewById(R.id.logView);
        logView.setMovementMethod(new ScrollingMovementMethod());

        // 1. Carrega histórico de logs do disco (NÃO perde ao fechar!)
        loadLogsFromDisk();

        bridge = new TripperBridge(this, this);

        btnConnect.setOnClickListener(v -> checkPermissionsAndConnect());

        btnSendPin.setOnClickListener(v -> {
            String pin = pinInput.getText().toString();
            if (!pin.isEmpty()) {
                bridge.sendPin(pin);
            }
        });

        // Configuração do Explorador de Ícones
        updateIconDisplay();

        btnNextIcon.setOnClickListener(v -> {
            currentIconCode = (currentIconCode + 1) & 0xFF;
            updateIconDisplay();
            sendCurrentExplorerIcon();
        });

        btnPrevIcon.setOnClickListener(v -> {
            currentIconCode = (currentIconCode - 1 + 256) & 0xFF;
            updateIconDisplay();
            sendCurrentExplorerIcon();
        });

        btnNext10.setOnClickListener(v -> {
            currentIconCode = (currentIconCode + 10) & 0xFF;
            updateIconDisplay();
            sendCurrentExplorerIcon();
        });

        btnPrev10.setOnClickListener(v -> {
            currentIconCode = (currentIconCode - 10 + 256) & 0xFF;
            updateIconDisplay();
            sendCurrentExplorerIcon();
        });

        btnJumpUntested.setOnClickListener(v -> {
            currentIconCode = UNTESTED_JUMPS[untestedJumpIdx];
            untestedJumpIdx = (untestedJumpIdx + 1) % UNTESTED_JUMPS.length;
            updateIconDisplay();
            sendCurrentExplorerIcon();
            Toast.makeText(this, "Saltando para faixa oculta: 0x" + Integer.toHexString(currentIconCode).toUpperCase(), Toast.LENGTH_SHORT).show();
        });

        btnRecordDiscovery.setOnClickListener(v -> showRecordDiscoveryDialog());

        // Manobras Rápidas
        btnLeft.setOnClickListener(v -> bridge.setNavManeuver(0x14, 150, "Virar Esquerda"));
        btnRight.setOnClickListener(v -> bridge.setNavManeuver(0x15, 150, "Virar Direita"));
        btnStraight.setOnClickListener(v -> bridge.setNavManeuver(0x09, 300, "Seguir Reto"));
        btnRound.setOnClickListener(v -> bridge.setNavManeuver(0x32, 200, "Rotatória 1ª"));
        btnSyncClock.setOnClickListener(v -> bridge.syncTime());
        btnCall.setOnClickListener(v -> bridge.setCallOverlay());

        // Ações de Log
        btnCopyLogs.setOnClickListener(v -> copyLogsToClipboard());
        btnShareLogs.setOnClickListener(v -> shareLogsText());
        btnClearLogs.setOnClickListener(v -> confirmClearLogs());

        onLog("=== OpenTripper v0.3.1 Iniciado ===", "sys");
    }

    private void updateIconDisplay() {
        String hex = String.format(Locale.US, "0x%02X (%d)", currentIconCode, currentIconCode);
        String name = TripperProtocol.getIconName(currentIconCode);
        tvCurrentIcon.setText(String.format("Código: %s\n%s", hex, name));
    }

    private void sendCurrentExplorerIcon() {
        if (bridge != null && bridge.getState() == TripperBridge.State.CONNECTED) {
            bridge.setExplorerIcon(currentIconCode);
        } else {
            Toast.makeText(this, "Conecte à moto para exibir o ícone", Toast.LENGTH_SHORT).show();
        }
    }

    private void showRecordDiscoveryDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        String hexStr = String.format(Locale.US, "0x%02X", currentIconCode);
        builder.setTitle("Descoberta do Ícone " + hexStr);
        builder.setMessage("O que apareceu na tela da Hunter 350?");

        final EditText input = new EditText(this);
        input.setHint("Ex: Apareceu um pino com câmera, bomba de combustível, etc.");
        builder.setView(input);

        builder.setPositiveButton("Salvar", (dialog, which) -> {
            String desc = input.getText().toString().trim();
            if (!desc.isEmpty()) {
                String record = String.format("[DESCOBERTA] Ícone %s: %s", hexStr, desc);
                onLog("🌟 " + record, "sys");
                Toast.makeText(this, "Anotado e gravado no histórico!", Toast.LENGTH_SHORT).show();
            }
        });
        builder.setNegativeButton("Cancelar", (dialog, which) -> dialog.cancel());
        builder.show();
    }

    // --- Persistência de Logs em Disco (Arquivo) ---

    private void loadLogsFromDisk() {
        try {
            File file = new File(getFilesDir(), LOG_FILE_NAME);
            if (file.exists()) {
                FileInputStream fis = openFileInput(LOG_FILE_NAME);
                BufferedReader reader = new BufferedReader(new InputStreamReader(fis));
                String line;
                while ((line = reader.readLine()) != null) {
                    logBuffer.append(line).append("\n");
                }
                reader.close();
                logView.setText(logBuffer.toString());
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private synchronized void saveLogLineToDisk(String line) {
        try {
            FileOutputStream fos = openFileOutput(LOG_FILE_NAME, Context.MODE_APPEND);
            fos.write(line.getBytes());
            fos.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void copyLogsToClipboard() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            ClipData clip = ClipData.newPlainText("OpenTripper Logs", logBuffer.toString());
            cm.setPrimaryClip(clip);
            Toast.makeText(this, "Logs copiados para a área de transferência!", Toast.LENGTH_SHORT).show();
        }
    }

    private void shareLogsText() {
        Intent sendIntent = new Intent();
        sendIntent.setAction(Intent.ACTION_SEND);
        sendIntent.putExtra(Intent.EXTRA_TEXT, logBuffer.toString());
        sendIntent.setType("text/plain");
        startActivity(Intent.createChooser(sendIntent, "Enviar Logs do OpenTripper"));
    }

    private void confirmClearLogs() {
        new AlertDialog.Builder(this)
                .setTitle("Limpar Histórico de Logs")
                .setMessage("Deseja apagar os logs salvos?")
                .setPositiveButton("Sim, apagar", (dialog, which) -> {
                    logBuffer.setLength(0);
                    logView.setText("");
                    File file = new File(getFilesDir(), LOG_FILE_NAME);
                    if (file.exists()) file.delete();
                    onLog("Histórico de logs limpo.", "sys");
                    Toast.makeText(this, "Logs apagados.", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancelar", null)
                .show();
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
            saveLogLineToDisk(entry);
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
