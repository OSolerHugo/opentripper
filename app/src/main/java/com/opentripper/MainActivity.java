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
    private TextView tvNavManeuverTitle;
    private TextView tvNavDistance;
    private TextView tvNavEta;

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
        tvNavManeuverTitle = findViewById(R.id.tvNavManeuverTitle);
        tvNavDistance = findViewById(R.id.tvNavDistance);
        tvNavEta = findViewById(R.id.tvNavEta);

        btnEnableNotifications.setOnClickListener(v -> {
            startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
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

        tvCurrentIcon = findViewById(R.id.tvCurrentIcon);
        btnPrevIcon = findViewById(R.id.btnPrevIcon);
        btnNextIcon = findViewById(R.id.btnNextIcon);
        btnPrev10 = findViewById(R.id.btnPrev10);
        btnNext10 = findViewById(R.id.btnNext10);
        btnJumpUntested = findViewById(R.id.btnJumpUntested);
        btnRecordDiscovery = findViewById(R.id.btnRecordDiscovery);

        btnCopyLogs = findViewById(R.id.btnCopyLogs);
        btnShareLogs = findViewById(R.id.btnShareLogs);
        btnClearLogs = findViewById(R.id.btnClearLogs);
        logView = findViewById(R.id.logView);
        logView.setMovementMethod(new ScrollingMovementMethod());

        // 7. Carrega logs do disco
        loadLogsFromDisk();

        // 8. Inicializa Bluetooth e Coordenador
        bridge = new TripperBridge(this, this);
        coordinator = new DisplayCoordinator(this, bridge);
        coordinator.setMirrorListener(this);

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

        setupExplorerControls();
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
        checkNotificationPermissionStatus();
    }

    private void checkNotificationPermissionStatus() {
        boolean granted = TurnByTurnService.isNotificationAccessGranted(this);
        if (granted) {
            llNotificationWarning.setVisibility(View.GONE);
        } else {
            llNotificationWarning.setVisibility(View.VISIBLE);
            tvNotificationStatus.setText("⚠️ Para ler o Google Maps e Waze, autorize o acesso:");
            tvNotificationStatus.setTextColor(COLOR_AMBER);
            btnEnableNotifications.setVisibility(View.VISIBLE);
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

        // Atualiza Tab 3 (Cruzeiro)
        if (primaryText.contains("km/h")) {
            String speedNum = primaryText.replace(" km/h", "").trim();
            tvCruiseSpeedLarge.setText(speedNum);
        }
        tvCruiseTrip.setText(tvDashTrip.getText().toString());
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
