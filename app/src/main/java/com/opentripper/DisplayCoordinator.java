package com.opentripper;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

/**
 * Coordenador Híbrido de Exibição do Tripper Pod.
 * 
 * Arbitra a prioridade de exibição na tela da moto:
 * 1. PRIORIDADE 1: Se houver navegação ativa no Waze ou Google Maps (recebida nos últimos 15s),
 *    projeta a manobra, distância decrescente e ETA.
 * 2. PRIORIDADE 2: Se não houver rota ativa, projeta o Modo Cruzeiro:
 *    - Velocímetro Digital (padrão) com velocidade em km/h e hodômetro parcial de sessão.
 *    - Bússola Digital 360° (se configurado pelo usuário).
 */
public class DisplayCoordinator implements TurnByTurnService.NavListener, BatteryAwareLocationProvider.SpeedListener {

    public enum CruiseMode {
        SPEEDOMETER,
        COMPASS
    }

    public interface MirrorListener {
        void onDisplayMirrorUpdated(String mode, int iconCode, String primaryText, String bottomText);
    }

    private final Context context;
    private final TripperBridge bridge;
    private final BatteryAwareLocationProvider locationProvider;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private CruiseMode cruiseMode = CruiseMode.SPEEDOMETER;
    private MirrorListener mirrorListener = null;

    private int currentSpeedKmH = 0;
    private float currentBearing = 0.0f;
    private float tripDistanceKm = 0.0f;
    private TurnByTurnService.NavStep currentNavStep = null;

    public DisplayCoordinator(Context context, TripperBridge bridge) {
        this.context = context.getApplicationContext();
        this.bridge = bridge;
        this.locationProvider = new BatteryAwareLocationProvider(this.context, this);

        // Conecta ao interceptador de notificações
        TurnByTurnService.setNavListener(this);
    }

    public void setMirrorListener(MirrorListener listener) {
        this.mirrorListener = listener;
        updateScreenAndMirror();
    }

    public void setCruiseMode(CruiseMode mode) {
        this.cruiseMode = mode;
        updateScreenAndMirror();
    }

    public CruiseMode getCruiseMode() {
        return cruiseMode;
    }

    /**
     * Chamado quando o TripperBridge estabelece conexão física com a moto.
     */
    public void onConnected() {
        // Inicia o GPS de baixo consumo sob demanda
        locationProvider.start();
        updateScreenAndMirror();
    }

    /**
     * Chamado quando a moto desliga ou perde a conexão BLE.
     */
    public void onDisconnected() {
        // REGRA DE OURO: Desliga imediatamente o GPS para poupar 100% da bateria do piloto
        locationProvider.stop();
        currentNavStep = null;
        updateScreenAndMirror();
    }

    @Override
    public void onNavStepUpdated(TurnByTurnService.NavStep step) {
        this.currentNavStep = step;
        updateScreenAndMirror();
    }

    @Override
    public void onNavigationFinished() {
        this.currentNavStep = null;
        updateScreenAndMirror();
    }

    @Override
    public void onSpeedUpdated(int speedKmH, float bearing, float tripDistanceKm) {
        this.currentSpeedKmH = speedKmH;
        this.currentBearing = bearing;
        this.tripDistanceKm = tripDistanceKm;

        // Se estiver no modo cruzeiro, atualiza a tela
        if (currentNavStep == null) {
            updateScreenAndMirror();
        }
    }

    @Override
    public void onGpsStatusChanged(boolean active, String status) {}

    /**
     * Constrói o pacote atual e atualiza tanto o hardware do Tripper quanto o espelho do celular.
     */
    public synchronized void updateScreenAndMirror() {
        boolean isNavigating = (currentNavStep != null && (System.currentTimeMillis() - currentNavStep.timestamp < 15000));

        String modeName;
        int iconCode;
        String primaryText;
        String bottomText;
        byte[] packet;

        if (isNavigating) {
            modeName = "Navegação GPS Ativa";
            iconCode = currentNavStep.iconCode;
            
            if (currentNavStep.distanceUnit == 0x02) {
                float km = currentNavStep.distanceValue / 10.0f;
                primaryText = String.format("%.1f km", km);
            } else {
                primaryText = currentNavStep.distanceValue + " m";
            }

            if (currentNavStep.etaHour != 0xFF) {
                bottomText = String.format("Chegada: %02d:%02d", currentNavStep.etaHour, currentNavStep.etaMinute);
            } else {
                bottomText = currentNavStep.rawTitle;
            }

            packet = TripperProtocol.buildNavFullPacket(
                currentNavStep.iconCode,
                currentNavStep.distanceValue,
                currentNavStep.distanceUnit,
                currentNavStep.etaHour,
                currentNavStep.etaMinute
            );
        } else {
            // Modo Cruzeiro
            if (cruiseMode == CruiseMode.SPEEDOMETER) {
                modeName = "Cruzeiro: Velocímetro Digital";
                iconCode = 0x09; // Seta para cima
                primaryText = currentSpeedKmH + " km/h";
                bottomText = String.format("Viagem: %.1f km", tripDistanceKm);
                packet = TripperProtocol.buildSpeedometerPacket(currentSpeedKmH, tripDistanceKm);
            } else {
                modeName = "Cruzeiro: Bússola 360°";
                iconCode = 0x41; // Bússola
                int headingInt = Math.round(currentBearing);
                if (headingInt < 0) headingInt += 360;
                primaryText = headingInt + "° " + getHeadingCompassName(headingInt);
                bottomText = String.format("Viagem: %.1f km", tripDistanceKm);

                byte dirByte = (byte) Math.round((headingInt / 360.0f) * 255.0f);
                packet = TripperProtocol.buildCompassPacket(dirByte);
            }
        }

        // 1. Atualiza o pacote ativo no loop keepalive do TripperBridge (para o visor da moto)
        if (bridge != null && bridge.isConnected()) {
            bridge.setActiveDisplayPacket(packet);
        }

        // 2. Atualiza o espelho do visor na UI do celular
        if (mirrorListener != null) {
            mainHandler.post(() -> {
                if (mirrorListener != null) {
                    mirrorListener.onDisplayMirrorUpdated(modeName, iconCode, primaryText, bottomText);
                }
            });
        }
    }

    private String getHeadingCompassName(int degrees) {
        String[] directions = {"N", "NE", "E", "SE", "S", "SO", "O", "NO", "N"};
        return directions[Math.round(((degrees % 360) / 45.0f))];
    }
}
