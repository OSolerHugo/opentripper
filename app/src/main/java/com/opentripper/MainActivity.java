package com.opentripper;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.provider.Settings;
import android.text.method.ScrollingMovementMethod;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
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

    // Cores da Identidade Visual
    private static final int COLOR_CYAN = Color.parseColor("#00C2D9");
    private static final int COLOR_MUTED = Color.parseColor("#8B9BB4");
    private static final int COLOR_GREEN = Color.parseColor("#22C785");
    private static final int COLOR_RED = Color.parseColor("#E05252");
    private static final int COLOR_AMBER = Color.parseColor("#F5A623");

    private TripperBridge bridge;
    private DisplayCoordinator coordinator;
    private SharedPreferences prefs;

    // Barra de Status Superior
    private View statusDot;
    private TextView statusText;

    // 4 Abas Principais (Containers)
    private ScrollView tabDashboard;
    private ScrollView tabNavigation;
    private ScrollView tabCruise;
    private ScrollView tabDevice;

    // Botões de Navegação Inferior
    private Button btnTabDashboard;
    private Button btnTabNavigation;
    private Button btnTabCruise;
    private Button btnTabDevice;

    // Componentes - Tab 1: Dashboard
    private TextView tvMirrorClock;
    private TextView tvMirrorIcon;
    private TextView tvMirrorPrimary;
    private TextView tvMirrorBottom;
    private TextView tvMirrorMode;
    private TextView tvDashSpeed;
    private TextView tvDashTrip;

    // Componentes - Tab 2: Navegação
    private LinearLayout llNotificationWarning;
    private TextView tvNotificationStatus;
    private Button btnEnableNotifications;
    private Button btnAppDetailsSettings;
    private Button btnReconnectListener;
    private TextView tvNavManeuverTitle;
    private TextView tvNavDistance;
    private TextView tvNavEta;
    private TextView tvLastNotificationIntercepted;
    private Button btnSimulateManeuver;

    // Componentes - Tab 3: Cruzeiro
    private TextView tvCruiseSpeedLarge;
    private TextView tvCruiseBearing;
    private TextView tvCruiseTrip;
    private RadioGroup rgCruiseMode;
    private RadioButton rbSpeedometer;
    private RadioButton rbCompass;

    // Componentes - Tab 4: Dispositivo
    private Button btnConnect;
    private CheckBox cbAutoConnect;
    private Button btnSyncClock;
    private Button btnCall;
    private Button btnForgetDevice;

    // Validação de PIN (Só exibido no primeiro pareamento)
    private LinearLayout llPinValidationCard;
    private EditText pinInput;
    private Button btnSendPin;

    // Diagnóstico e Logs Sob Demanda
    private Button btnGenerateLogs, btnCopyLogs, btnShareLogs, btnClearLogs;
    private TextView logView;
    private static final int MAX_MEMORY_LOGS = 30;
    private final java.util.LinkedList<String> memoryLogs = new java.util.LinkedList<>();
    private String currentReport = "";

    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
    private final SimpleDateFormat clockFormat = new SimpleDateFormat("HH:mm", Locale.getDefault());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences("opentripper_prefs", Context.MODE_PRIVATE);

        // 1. Inicializa Top Bar
        statusDot = findViewById(R.id.statusDot);
        statusText = findViewById(R.id.statusText);

        // 2. Inicializa Abas e Bottom Bar
        tabDashboard = findViewById(R.id.tabDashboard);
        tabNavigation = findViewById(R.id.tabNavigation);
        tabCruise = findViewById(R.id.tabCruise);
        tabDevice = findViewById(R.id.tabDevice);

        btnTabDashboard = findViewById(R.id.btnTabDashboard);
        btnTabNavigation = findViewById(R.id.btnTabNavigation);
        btnTabCruise = findViewById(R.id.btnTabCruise);
        btnTabDevice = findViewById(R.id.btnTabDevice);

        setupBottomNavigation();

        // 3. Inicializa Tab 1 (Dashboard)
        tvMirrorClock = findViewById(R.id.tvMirrorClock);
        tvMirrorIcon = findViewById(R.id.tvMirrorIcon);
        tvMirrorPrimary = findViewById(R.id.tvMirrorPrimary);
        tvMirrorBottom = findViewById(R.id.tvMirrorBottom);
        tvMirrorMode = findViewById(R.id.tvMirrorMode);
        tvDashSpeed = findViewById(R.id.tvDashSpeed);
        tvDashTrip = findViewById(R.id.tvDashTrip);

        // 4. Inicializa Tab 2 (Navegação)
        llNotificationWarning = findViewById(R.id.llNotificationWarning);
        tvNotificationStatus = findViewById(R.id.tvNotificationStatus);
        btnEnableNotifications = findViewById(R.id.btnEnableNotifications);
        btnAppDetailsSettings = findViewById(R.id.btnAppDetailsSettings);
        btnReconnectListener = findViewById(R.id.btnReconnectListener);
        tvNavManeuverTitle = findViewById(R.id.tvNavManeuverTitle);
        tvNavDistance = findViewById(R.id.tvNavDistance);
        tvNavEta = findViewById(R.id.tvNavEta);
        tvLastNotificationIntercepted = findViewById(R.id.tvLastNotificationIntercepted);
        btnSimulateManeuver = findViewById(R.id.btnSimulateManeuver);

        btnEnableNotifications.setOnClickListener(v -> {
            startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
        });

        btnAppDetailsSettings.setOnClickListener(v -> {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        });

        btnReconnectListener.setOnClickListener(v -> {
            TurnByTurnService.ensureServiceBound(this);
            Toast.makeText(this, "🔄 Solicitando reconexão do leitor de notificações...", Toast.LENGTH_SHORT).show();
            checkNotificationPermissionStatus();
        });

        btnSimulateManeuver.setOnClickListener(v -> {
            TurnByTurnService.simulateNextStep();
            Toast.makeText(this, "🧪 Teste de manobra enviado para o painel!", Toast.LENGTH_SHORT).show();
        });

        // 5. Inicializa Tab 3 (Cruzeiro)
        tvCruiseSpeedLarge = findViewById(R.id.tvCruiseSpeedLarge);
        tvCruiseBearing = findViewById(R.id.tvCruiseBearing);
        tvCruiseTrip = findViewById(R.id.tvCruiseTrip);
        rgCruiseMode = findViewById(R.id.rgCruiseMode);
        rbSpeedometer = findViewById(R.id.rbSpeedometer);
        rbCompass = findViewById(R.id.rbCompass);

        // 6. Inicializa Tab 4 (Dispositivo)
        btnConnect = findViewById(R.id.btnConnect);
        cbAutoConnect = findViewById(R.id.cbAutoConnect);
        btnSyncClock = findViewById(R.id.btnSyncClock);
        btnCall = findViewById(R.id.btnCall);
        btnForgetDevice = findViewById(R.id.btnForgetDevice);

        // Validação de PIN (Só exibido no pareamento)
        llPinValidationCard = findViewById(R.id.llPinValidationCard);
        pinInput = findViewById(R.id.pinInput);
        btnSendPin = findViewById(R.id.btnSendPin);

        btnSendPin.setOnClickListener(v -> {
            String pin = pinInput.getText().toString().trim();
            if (pin.length() >= 4) {
                bridge.sendPin(pin);
                pinInput.setText("");
                llPinValidationCard.setVisibility(View.GONE);
                Toast.makeText(this, "PIN enviado! Validando...", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Digite os dígitos do PIN", Toast.LENGTH_SHORT).show();
            }
        });

        btnForgetDevice.setOnClickListener(v -> {
            new AlertDialog.Builder(this)
                .setTitle("Esquecer Tripper Pod")
                .setMessage("Deseja redefinir o pareamento? Na próxima conexão, um novo código PIN surgirá no visor da moto.")
                .setPositiveButton("Sim, esquecer", (dialog, which) -> {
                    bridge.setPaired(false);
                    bridge.disconnect();
                    Toast.makeText(this, "Dispositivo esquecido com sucesso!", Toast.LENGTH_LONG).show();
                    log("Dispositivo esquecido. Pareamento resetado.", "info");
                })
                .setNegativeButton("Cancelar", null)
                .show();
        });

        btnGenerateLogs = findViewById(R.id.btnGenerateLogs);
        btnCopyLogs = findViewById(R.id.btnCopyLogs);
        btnShareLogs = findViewById(R.id.btnShareLogs);
        btnClearLogs = findViewById(R.id.btnClearLogs);
        logView = findViewById(R.id.logView);
        logView.setMovementMethod(new ScrollingMovementMethod());

        // Limpa arquivos de log antigos do disco para liberar espaço e cache
        try {
            File oldLogFile = new File(getFilesDir(), LOG_FILE_NAME);
            if (oldLogFile.exists()) oldLogFile.delete();
        } catch (Exception ignored) {}

        // Inicializa Bluetooth e Coordenador via TripperService ou localmente
        initServiceAndBridge();

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

        btnSyncClock.setOnClickListener(v -> {
            bridge.syncTime();
            log("Relógio sincronizado para hora atual", "tx");
            Toast.makeText(this, "Relógio sincronizado!", Toast.LENGTH_SHORT).show();
        });

        btnCall.setOnClickListener(v -> {
            bridge.setCallOverlay();
            log("Overlay de chamada enviado", "tx");
        });

        setupLogControls();

        // Se auto-conectar estiver ativo, inicia a busca
        if (autoConnectSaved) {
            checkPermissionsAndConnect();
        }
    }

    private void setupBottomNavigation() {
        btnTabDashboard.setOnClickListener(v -> selectTab(0));
        btnTabNavigation.setOnClickListener(v -> selectTab(1));
        btnTabCruise.setOnClickListener(v -> selectTab(2));
        btnTabDevice.setOnClickListener(v -> selectTab(3));
        selectTab(0);
    }

    private void selectTab(int index) {
        tabDashboard.setVisibility(index == 0 ? View.VISIBLE : View.GONE);
        tabNavigation.setVisibility(index == 1 ? View.VISIBLE : View.GONE);
        tabCruise.setVisibility(index == 2 ? View.VISIBLE : View.GONE);
        tabDevice.setVisibility(index == 3 ? View.VISIBLE : View.GONE);

        btnTabDashboard.setTextColor(index == 0 ? COLOR_CYAN : COLOR_MUTED);
        btnTabNavigation.setTextColor(index == 1 ? COLOR_CYAN : COLOR_MUTED);
        btnTabCruise.setTextColor(index == 2 ? COLOR_CYAN : COLOR_MUTED);
        btnTabDevice.setTextColor(index == 3 ? COLOR_CYAN : COLOR_MUTED);
    }

    @Override
    protected void onResume() {
        super.onResume();
        TurnByTurnService.ensureServiceBound(this);
        checkNotificationPermissionStatus();
    }

    private void checkNotificationPermissionStatus() {
        boolean granted = TurnByTurnService.isNotificationAccessGranted(this);
        llNotificationWarning.setVisibility(View.VISIBLE);

        if (granted) {
            btnEnableNotifications.setVisibility(View.GONE);
            btnAppDetailsSettings.setVisibility(View.GONE);
            btnReconnectListener.setVisibility(View.VISIBLE);

            if (TurnByTurnService.isServiceConnected) {
                tvNotificationStatus.setText("🟢 Leitor Ativo: Conectado e escutando o Android");
                tvNotificationStatus.setTextColor(COLOR_GREEN);
            } else {
                tvNotificationStatus.setText("🟡 Leitor Autorizado: Aguardando vínculo do Android (toque em Reconectar)");
                tvNotificationStatus.setTextColor(COLOR_AMBER);
            }
        } else {
            btnEnableNotifications.setVisibility(View.VISIBLE);
            btnAppDetailsSettings.setVisibility(View.VISIBLE);
            btnReconnectListener.setVisibility(View.GONE);
            tvNotificationStatus.setText("⚠️ Para ler o Google Maps e Waze, autorize o acesso a notificações:");
            tvNotificationStatus.setTextColor(COLOR_AMBER);
        }

        if (tvLastNotificationIntercepted != null) {
            tvLastNotificationIntercepted.setText("Última notificação: " + TurnByTurnService.lastNotificationSummary);
        }
    }

    @Override
    public void onDisplayMirrorUpdated(String mode, int iconCode, String primaryText, String bottomText) {
        String currentTime = clockFormat.format(new Date());
        tvMirrorClock.setText(currentTime);
        tvMirrorMode.setText("MODO: " + mode.toUpperCase(Locale.ROOT));
        tvMirrorIcon.setText(TripperProtocol.getIconName(iconCode));
        tvMirrorPrimary.setText(primaryText);
        tvMirrorBottom.setText(bottomText.toUpperCase(Locale.ROOT));

        // Atualiza cards da Tab 1 (Dashboard)
        if (primaryText.contains("km/h")) {
            tvDashSpeed.setText(primaryText);
        }
        if (bottomText.toLowerCase().contains("viagem") || bottomText.toLowerCase().contains("sessão")) {
            tvDashTrip.setText(bottomText.replace("Viagem: ", "").replace("Sessão: ", ""));
        }

        // Atualiza Tab 2 (Navegação)
        TurnByTurnService.NavStep step = TurnByTurnService.getLastActiveStep();
        if (step != null) {
            tvNavManeuverTitle.setText(step.rawTitle.isEmpty() ? TripperProtocol.getIconName(step.iconCode) : step.rawTitle);
            tvNavDistance.setText(primaryText);
            if (step.etaHour != 0xFF) {
                tvNavEta.setText(String.format("Chegada estimada: %02d:%02d", step.etaHour, step.etaMinute));
            } else {
                tvNavEta.setText("Rota ativa em andamento");
            }
        } else {
            tvNavManeuverTitle.setText("Nenhuma rota ativa no momento");
            tvNavDistance.setText("-- m");
            tvNavEta.setText("Aguardando navegação do Waze / Maps");
        }

        if (tvLastNotificationIntercepted != null) {
            tvLastNotificationIntercepted.setText("Última notificação: " + TurnByTurnService.lastNotificationSummary);
        }

        // Atualiza Tab 3 (Cruzeiro)
        if (primaryText.contains("km/h")) {
            String speedNum = primaryText.replace(" km/h", "").trim();
            tvCruiseSpeedLarge.setText(speedNum);
        }
        tvCruiseTrip.setText(tvDashTrip.getText().toString());
    }

    private void setupLogControls() {
        btnGenerateLogs.setOnClickListener(v -> {
            generateSystemReport();
            Toast.makeText(this, "Relatório de logs gerado!", Toast.LENGTH_SHORT).show();
        });

        btnCopyLogs.setOnClickListener(v -> {
            String textToCopy = currentReport.isEmpty() ? generateReportString() : currentReport;
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = ClipData.newPlainText("OpenTripper Logs", textToCopy);
            clipboard.setPrimaryClip(clip);
            Toast.makeText(this, "Logs copiados!", Toast.LENGTH_SHORT).show();
        });

        btnShareLogs.setOnClickListener(v -> {
            String textToShare = currentReport.isEmpty() ? generateReportString() : currentReport;
            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.setType("text/plain");
            shareIntent.putExtra(Intent.EXTRA_SUBJECT, "OpenTripper Logs");
            shareIntent.putExtra(Intent.EXTRA_TEXT, textToShare);
            startActivity(Intent.createChooser(shareIntent, "Enviar logs via..."));
        });

        btnClearLogs.setOnClickListener(v -> {
            synchronized (memoryLogs) {
                memoryLogs.clear();
            }
            currentReport = "";
            logView.setText("Logs limpos da memória.\nToque em 'Gerar Relatório de Logs' para atualizar.");
            Toast.makeText(this, "Histórico limpo!", Toast.LENGTH_SHORT).show();
        });
    }

    private String generateReportString() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== OpenTripper v0.5.4 Relatório de Diagnóstico ===\n");
        sb.append("Data/Hora: ").append(new Date().toString()).append("\n");
        sb.append("Status BLE: ").append(bridge != null ? bridge.getState().name() : "N/A").append("\n");
        sb.append("Leitor Notificações: ").append(TurnByTurnService.isServiceConnected ? "CONECTADO" : "DESCONECTADO").append("\n");
        sb.append("Permissão Notificações: ").append(TurnByTurnService.isNotificationAccessGranted(this) ? "CONCEDIDA" : "NEGADA").append("\n");
        sb.append("Última Notificação Interceptada: ").append(TurnByTurnService.lastNotificationSummary).append("\n\n");
        sb.append("--- Últimos 30 Eventos do Sistema ---\n");
        synchronized (memoryLogs) {
            if (memoryLogs.isEmpty()) {
                sb.append("(Nenhum evento registrado recentemente)\n");
            } else {
                for (String logLine : memoryLogs) {
                    sb.append(logLine).append("\n");
                }
            }
        }
        return sb.toString();
    }

    private void generateSystemReport() {
        currentReport = generateReportString();
        logView.setText(currentReport);
    }

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            TripperService.LocalBinder binder = (TripperService.LocalBinder) service;
            TripperService s = binder.getService();
            if (s != null) {
                bridge = s.getBridge();
                coordinator = s.getDisplayCoordinator();
                s.setUiListener(MainActivity.this);
                coordinator.setMirrorListener(MainActivity.this);
                onStateChanged(bridge.getState());
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
        }
    };

    private void initServiceAndBridge() {
        if (TripperService.getInstance() != null) {
            bridge = TripperService.getInstance().getBridge();
            coordinator = TripperService.getInstance().getDisplayCoordinator();
            TripperService.getInstance().setUiListener(this);
            coordinator.setMirrorListener(this);
        } else {
            bridge = new TripperBridge(this, this);
            coordinator = new DisplayCoordinator(this, bridge);
            coordinator.setMirrorListener(this);
        }

        try {
            Intent serviceIntent = new Intent(this, TripperService.class);
            bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE);
        } catch (Exception ignored) {}
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            unbindService(serviceConnection);
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

        try {
            Intent serviceIntent = new Intent(this, TripperService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }
        } catch (Exception ignored) {}

        bridge.startConnect();
    }

    @Override
    public void onStateChanged(TripperBridge.State state) {
        switch (state) {
            case WAITING_PIN:
                statusDot.setBackgroundColor(COLOR_AMBER);
                statusText.setText("DIGITE O PIN EXIBIDO NA MOTO");
                if (llPinValidationCard != null) {
                    llPinValidationCard.setVisibility(View.VISIBLE);
                    pinInput.requestFocus();
                }
                break;
            case DISCONNECTED:
                statusDot.setBackgroundColor(COLOR_RED);
                statusText.setText("DESCONECTADO");
                btnConnect.setText("Conectar ao Tripper Pod");
                btnConnect.setEnabled(true);
                if (llPinValidationCard != null) {
                    llPinValidationCard.setVisibility(View.GONE);
                }
                if (coordinator != null) coordinator.onDisconnected();
                break;
            case SCANNING:
                statusDot.setBackgroundColor(COLOR_AMBER);
                statusText.setText("BUSCANDO (RE_DISP)...");
                btnConnect.setText("Buscando Pod...");
                btnConnect.setEnabled(false);
                break;
            case CONNECTING:
                statusDot.setBackgroundColor(COLOR_CYAN);
                statusText.setText("CONECTANDO...");
                btnConnect.setText("Conectando...");
                btnConnect.setEnabled(false);
                break;
            case CONNECTED:
                statusDot.setBackgroundColor(COLOR_GREEN);
                statusText.setText("CONECTADO (RE_DISP)");
                btnConnect.setText("Desconectar Pod");
                btnConnect.setEnabled(true);
                if (llPinValidationCard != null) {
                    llPinValidationCard.setVisibility(View.GONE);
                }
                if (coordinator != null) coordinator.onConnected();
                break;
        }
    }

    @Override
    public void onLog(String message, String type) {
        String time = timeFormat.format(new Date());
        String line = "[" + time + "] " + message;
        synchronized (memoryLogs) {
            memoryLogs.add(line);
            if (memoryLogs.size() > MAX_MEMORY_LOGS) {
                memoryLogs.removeFirst();
            }
        }
    }

    private void log(String msg, String type) {
        onLog(msg, type);
    }
}
