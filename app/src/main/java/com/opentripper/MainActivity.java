package com.opentripper;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.method.ScrollingMovementMethod;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
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

public class MainActivity extends Activity implements TripperBridge.Listener, DisplayCoordinator.MirrorListener {

    private static final String LOG_FILE_NAME = "opentripper_logs.txt";

    private TripperBridge bridge;
    private DisplayCoordinator coordinator;
    private SharedPreferences prefs;

    // Barra de Status Superior
    private View statusDot;
    private TextView statusText;
    private Button btnConnect;

    // Espelho do Visor da Moto
    private TextView tvMirrorMode;
    private TextView tvMirrorIcon;
    private TextView tvMirrorPrimary;
    private TextView tvMirrorBottom;

    // Configurações de Pilotagem
    private CheckBox cbAutoConnect;
    private RadioGroup rgCruiseMode;
    private RadioButton rbSpeedometer;
    private RadioButton rbCompass;
    private LinearLayout llNotificationWarning;
    private TextView tvNotificationStatus;
    private Button btnEnableNotifications;

    // Ferramentas & Engenharia (Seção Retrátil)
    private Button btnToggleTools;
    private LinearLayout llToolsSection;
    private Button btnSyncClock, btnCall;

    // Explorador de Ícones
    private TextView tvCurrentIcon;
    private Button btnPrevIcon, btnNextIcon, btnPrev10, btnNext10, btnJumpUntested, btnRecordDiscovery;
    private int currentIconCode = 0x00;
    private static final int[] UNTESTED_JUMPS = { 0x0B, 0x14, 0x23, 0x28, 0x40, 0x46, 0x60, 0x80 };
    private int untestedJumpIdx = 0;

    // Logs Persistentes
    private Button btnCopyLogs, btnShareLogs, btnClearLogs;
    private TextView logView;

    private final StringBuilder logBuffer = new StringBuilder();
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences("opentripper_prefs", Context.MODE_PRIVATE);

        // 1. Inicializa views
        statusDot = findViewById(R.id.statusDot);
        statusText = findViewById(R.id.statusText);
        btnConnect = findViewById(R.id.btnConnect);

        // Espelho
        tvMirrorMode = findViewById(R.id.tvMirrorMode);
        tvMirrorIcon = findViewById(R.id.tvMirrorIcon);
        tvMirrorPrimary = findViewById(R.id.tvMirrorPrimary);
        tvMirrorBottom = findViewById(R.id.tvMirrorBottom);

        // Configurações
        cbAutoConnect = findViewById(R.id.cbAutoConnect);
        rgCruiseMode = findViewById(R.id.rgCruiseMode);
        rbSpeedometer = findViewById(R.id.rbSpeedometer);
        rbCompass = findViewById(R.id.rbCompass);
        llNotificationWarning = findViewById(R.id.llNotificationWarning);
        tvNotificationStatus = findViewById(R.id.tvNotificationStatus);
        btnEnableNotifications = findViewById(R.id.btnEnableNotifications);

        // Ferramentas & Retrátil
        btnToggleTools = findViewById(R.id.btnToggleTools);
        llToolsSection = findViewById(R.id.llToolsSection);
        btnSyncClock = findViewById(R.id.btnSyncClock);
        btnCall = findViewById(R.id.btnCall);

        // Explorador
        tvCurrentIcon = findViewById(R.id.tvCurrentIcon);
        btnPrevIcon = findViewById(R.id.btnPrevIcon);
        btnNextIcon = findViewById(R.id.btnNextIcon);
        btnPrev10 = findViewById(R.id.btnPrev10);
        btnNext10 = findViewById(R.id.btnNext10);
        btnJumpUntested = findViewById(R.id.btnJumpUntested);
        btnRecordDiscovery = findViewById(R.id.btnRecordDiscovery);

        // Logs
        btnCopyLogs = findViewById(R.id.btnCopyLogs);
        btnShareLogs = findViewById(R.id.btnShareLogs);
        btnClearLogs = findViewById(R.id.btnClearLogs);
        logView = findViewById(R.id.logView);
        logView.setMovementMethod(new ScrollingMovementMethod());

        // 2. Carrega logs do disco
        loadLogsFromDisk();

        // 3. Inicializa Bridge BLE e Coordenador Híbrido
        bridge = new TripperBridge(this, this);
        coordinator = new DisplayCoordinator(this, bridge);
        coordinator.setMirrorListener(this);

        // 4. Configura Listeners de UI
        btnConnect.setOnClickListener(v -> {
            if (bridge.isConnected()) {
                bridge.disconnect();
            } else {
                checkPermissionsAndConnect();
            }
        });

        // Configuração de Auto-Conectar
        boolean autoConnectSaved = prefs.getBoolean("auto_connect", false);
        cbAutoConnect.setChecked(autoConnectSaved);
        cbAutoConnect.setOnCheckedChangeListener((btn, isChecked) -> {
            prefs.edit().putBoolean("auto_connect", isChecked).apply();
            log("Configuração Auto-Conectar: " + (isChecked ? "ATIVADA" : "DESATIVADA"), "info");
        });

        // Configuração de Modo Cruzeiro
        rgCruiseMode.setOnCheckedChangeListener((group, checkedId) -> {
            if (checkedId == R.id.rbCompass) {
                coordinator.setCruiseMode(DisplayCoordinator.CruiseMode.COMPASS);
                log("Modo Cruzeiro: Bússola Digital 360° selecionada", "info");
            } else {
                coordinator.setCruiseMode(DisplayCoordinator.CruiseMode.SPEEDOMETER);
                log("Modo Cruzeiro: Velocímetro Digital km/h selecionado", "info");
            }
        });

        // Verificação de Acesso às Notificações
        btnEnableNotifications.setOnClickListener(v -> {
            startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
        });

        // Retrátil de Ferramentas
        btnToggleTools.setOnClickListener(v -> {
            if (llToolsSection.getVisibility() == View.VISIBLE) {
                llToolsSection.setVisibility(View.GONE);
                btnToggleTools.setText("🛠️ Ver Ferramentas de Teste, Explorador e Logs ▼");
            } else {
                llToolsSection.setVisibility(View.VISIBLE);
                btnToggleTools.setText("🛠️ Recolher Ferramentas ▲");
            }
        });

        // Ações de Ferramentas
        btnSyncClock.setOnClickListener(v -> {
            bridge.syncTime();
            log("Relógio sincronizado para hora atual", "tx");
            Toast.makeText(this, "Relógio sincronizado!", Toast.LENGTH_SHORT).show();
        });

        btnCall.setOnClickListener(v -> {
            bridge.setCallOverlay();
            log("Overlay de chamada enviado", "tx");
        });

        // Configuração do Explorador
        setupExplorerControls();

        // Configuração de Logs
        setupLogControls();

        // Se auto-conectar estiver ativo, inicia a busca
        if (autoConnectSaved) {
            checkPermissionsAndConnect();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        checkNotificationPermissionStatus();
    }

    private void checkNotificationPermissionStatus() {
        boolean granted = TurnByTurnService.isNotificationAccessGranted(this);
        if (granted) {
            llNotificationWarning.setBackgroundColor(Color.parseColor("#064E3B")); // Verde escuro discreto
            tvNotificationStatus.setText("✅ Leitor de Google Maps e Waze ativo e pronto");
            tvNotificationStatus.setTextColor(Color.parseColor("#A7F3D0"));
            btnEnableNotifications.setVisibility(View.GONE);
        } else {
            llNotificationWarning.setBackgroundColor(Color.parseColor("#1E1B4B")); // Azul arroxeado alerta
            tvNotificationStatus.setText("⚠️ Para ler Google Maps e Waze, autorize o OpenTripper:");
            tvNotificationStatus.setTextColor(Color.parseColor("#E0E7FF"));
            btnEnableNotifications.setVisibility(View.VISIBLE);
        }
    }

    // Callbacks do Espelho do Visor
    @Override
    public void onDisplayMirrorUpdated(String mode, int iconCode, String primaryText, String bottomText) {
        tvMirrorMode.setText("Modo: " + mode);
        tvMirrorIcon.setText(TripperProtocol.getIconName(iconCode));
        tvMirrorPrimary.setText(primaryText);
        tvMirrorBottom.setText(bottomText);
    }

    private void setupExplorerControls() {
        updateIconDisplay();

        btnNextIcon.setOnClickListener(v -> {
            currentIconCode = (currentIconCode + 1) & 0xFF;
            updateIconDisplay();
            bridge.setExplorerIcon(currentIconCode);
        });

        btnPrevIcon.setOnClickListener(v -> {
            currentIconCode = (currentIconCode - 1 + 256) & 0xFF;
            updateIconDisplay();
            bridge.setExplorerIcon(currentIconCode);
        });

        btnNext10.setOnClickListener(v -> {
            currentIconCode = (currentIconCode + 10) & 0xFF;
            updateIconDisplay();
            bridge.setExplorerIcon(currentIconCode);
        });

        btnPrev10.setOnClickListener(v -> {
            currentIconCode = (currentIconCode - 10 + 256) & 0xFF;
            updateIconDisplay();
            bridge.setExplorerIcon(currentIconCode);
        });

        btnJumpUntested.setOnClickListener(v -> {
            currentIconCode = UNTESTED_JUMPS[untestedJumpIdx % UNTESTED_JUMPS.length];
            untestedJumpIdx++;
            updateIconDisplay();
            bridge.setExplorerIcon(currentIconCode);
        });

        btnRecordDiscovery.setOnClickListener(v -> promptRecordDiscovery());
    }

    private void updateIconDisplay() {
        String name = TripperProtocol.getIconName(currentIconCode);
        tvCurrentIcon.setText(String.format(Locale.US, "Código: 0x%02X (%d) - %s", currentIconCode, currentIconCode, name));
    }

    private void promptRecordDiscovery() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(String.format(Locale.US, "Anotar Ícone 0x%02X", currentIconCode));
        builder.setMessage("O que apareceu no visor da moto?");

        final EditText input = new EditText(this);
        input.setHint("Ex: Seta curva suave, Rotatória 3ª saída...");
        builder.setView(input);

        builder.setPositiveButton("Gravar no Log", (dialog, which) -> {
            String desc = input.getText().toString().trim();
            if (!desc.isEmpty()) {
                String record = String.format(Locale.US, "🌟 [DESCOBERTA] Ícone 0x%02X: %s", currentIconCode, desc);
                log(record, "info");
                Toast.makeText(this, "Descoberta salva no histórico!", Toast.LENGTH_SHORT).show();
            }
        });
        builder.setNegativeButton("Cancelar", null);
        builder.show();
    }

    private void setupLogControls() {
        btnCopyLogs.setOnClickListener(v -> {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = ClipData.newPlainText("OpenTripper Logs", logBuffer.toString());
            clipboard.setPrimaryClip(clip);
            Toast.makeText(this, "Logs copiados!", Toast.LENGTH_SHORT).show();
        });

        btnShareLogs.setOnClickListener(v -> {
            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.setType("text/plain");
            shareIntent.putExtra(Intent.EXTRA_SUBJECT, "OpenTripper Logs");
            shareIntent.putExtra(Intent.EXTRA_TEXT, logBuffer.toString());
            startActivity(Intent.createChooser(shareIntent, "Enviar logs via..."));
        });

        btnClearLogs.setOnClickListener(v -> {
            new AlertDialog.Builder(this)
                .setTitle("Limpar Histórico")
                .setMessage("Deseja apagar todos os logs gravados?")
                .setPositiveButton("Sim, limpar", (dialog, which) -> {
                    logBuffer.setLength(0);
                    logView.setText("");
                    File file = new File(getFilesDir(), LOG_FILE_NAME);
                    if (file.exists()) file.delete();
                    log("Histórico de logs limpo.", "info");
                })
                .setNegativeButton("Cancelar", null)
                .show();
        });
    }

    private synchronized void loadLogsFromDisk() {
        File file = new File(getFilesDir(), LOG_FILE_NAME);
        if (!file.exists()) return;

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file)))) {
            String line;
            while ((line = reader.readLine()) != null) {
                logBuffer.append(line).append("\n");
            }
            logView.setText(logBuffer.toString());
        } catch (Exception ignored) {}
    }

    private synchronized void appendLogToDisk(String formattedLine) {
        File file = new File(getFilesDir(), LOG_FILE_NAME);
        try (FileOutputStream fos = new FileOutputStream(file, true)) {
            fos.write((formattedLine + "\n").getBytes());
        } catch (Exception ignored) {}
    }

    private void checkPermissionsAndConnect() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED ||
                checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED ||
                checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.ACCESS_FINE_LOCATION
                }, 1);
                return;
            }
        } else {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 1);
                return;
            }
        }
        bridge.startConnect();
    }

    @Override
    public void onStateChanged(TripperBridge.State state) {
        switch (state) {
            case DISCONNECTED:
                statusDot.setBackgroundColor(Color.parseColor("#EF4444"));
                statusText.setText("Desconectado");
                btnConnect.setText("Conectar");
                btnConnect.setEnabled(true);
                if (coordinator != null) coordinator.onDisconnected();
                break;
            case SCANNING:
                statusDot.setBackgroundColor(Color.parseColor("#F59E0B"));
                statusText.setText("Buscando Tripper Pod...");
                btnConnect.setText("Buscando...");
                btnConnect.setEnabled(false);
                break;
            case CONNECTING:
                statusDot.setBackgroundColor(Color.parseColor("#3B82F6"));
                statusText.setText("Conectando...");
                btnConnect.setText("Conectando...");
                btnConnect.setEnabled(false);
                break;
            case CONNECTED:
                statusDot.setBackgroundColor(Color.parseColor("#10B981"));
                statusText.setText("Conectado (RE_DISP)");
                btnConnect.setText("Desconectar");
                btnConnect.setEnabled(true);
                if (coordinator != null) coordinator.onConnected();
                break;
        }
    }

    @Override
    public void onLog(String message, String type) {
        String time = timeFormat.format(new Date());
        String line = "[" + time + "] " + message;
        logBuffer.append(line).append("\n");
        appendLogToDisk(line);

        logView.setText(logBuffer.toString());
        final int scrollAmount = logView.getLayout() != null ?
                logView.getLayout().getLineTop(logView.getLineCount()) - logView.getHeight() : 0;
        if (scrollAmount > 0) logView.scrollTo(0, scrollAmount);
    }

    private void log(String msg, String type) {
        onLog(msg, type);
    }
}
