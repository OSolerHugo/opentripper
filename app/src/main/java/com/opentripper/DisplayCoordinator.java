package com.opentripper;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import java.util.Locale;

/**
 * Coordenador Híbrido de Exibição do Tripper Pod.
 * 
 * Arbitra a prioridade de exibição na tela da moto:
 * 1. PRIORIDADE 1: Se houver navegação ativa no Waze ou Google Maps (recebida nos últimos 15s),
 *    projeta a manobra, distância decrescente, anel de aproximação e ETA.
 * 2. PRIORIDADE 2: Se não houver rota ativa ou a instrução expirar, projeta o Modo Cruzeiro:
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

    public int getCurrentSpeedKmH() {
        return currentSpeedKmH;
    }

    public float getTripDistanceKm() {
        return tripDistanceKm;
    }

    public float getCurrentBearing() {
        return currentBearing;
    }

    /**
     * Chamado quando o TripperBridge estabelece conexão física com a moto.
     */
    public void onConnected() {
        // Inicia o GPS sob demanda
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

        boolean isNavigating = (currentNavStep != null && (System.currentTimeMillis() - currentNavStep.timestamp < 15000));

        // Se a instrução expirou (> 15s sem novas notificações do Waze/Maps), limpa
        if (!isNavigating && currentNavStep != null) {
            currentNavStep = null;
        }

        // Se estiver no modo cruzeiro, atualiza imediatamente o velocímetro
        if (!isNavigating) {
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
                primaryText = String.format(Locale.US, "%.1f km", km);
            } else {
                primaryText = currentNavStep.distanceValue + " m";
            }

            if (currentNavStep.etaHour != 0xFF) {
                bottomText = String.format(Locale.US, "Chegada: %02d:%02d", currentNavStep.etaHour, currentNavStep.etaMinute);
            } else if (!TextUtils.isEmpty(currentNavStep.rawTitle)) {
                bottomText = currentNavStep.rawTitle;
            } else if (!TextUtils.isEmpty(currentNavStep.rawText)) {
                bottomText = currentNavStep.rawText;
            } else {
                bottomText = "Em navegação";
            }

            // Calcula intensidade por proximidade (anel LED em volta da tela do Tripper)
            int distMeters = currentNavStep.distanceMeters > 0
                ? currentNavStep.distanceMeters
                : (currentNavStep.distanceUnit == 0x02 ? currentNavStep.distanceValue * 100 : currentNavStep.distanceValue);
            int intensityByte = TripperProtocol.distanceIntensity(distMeters, false);

            packet = TripperProtocol.buildNavFullPacket(
                currentNavStep.iconCode,
                currentNavStep.distanceValue,
                currentNavStep.distanceUnit,
                intensityByte,
                currentNavStep.nextIconCode,
                currentNavStep.etaHour,
                currentNavStep.etaMinute
            );
        } else {
            // Modo Cruzeiro
            if (cruiseMode == CruiseMode.SPEEDOMETER) {
                modeName = "Cruzeiro: Velocímetro Digital";
                iconCode = TripperProtocol.MANEUVER_STRAIGHT; // 0x09 Seta para cima
                primaryText = currentSpeedKmH + " km/h";
                bottomText = String.format(Locale.US, "Viagem: %.1f km", tripDistanceKm);
                packet = TripperProtocol.buildSpeedometerPacket(currentSpeedKmH, tripDistanceKm);
            } else {
                modeName = "Cruzeiro: Bússola 360°";
                iconCode = TripperProtocol.SCREEN_COMPASS; // 0x41 Bússola
                int headingInt = Math.round(currentBearing);
                if (headingInt < 0) headingInt += 360;
                primaryText = headingInt + "° " + getHeadingCompassName(headingInt);
                bottomText = String.format(Locale.US, "Viagem: %.1f km", tripDistanceKm);

                byte dirByte = TripperProtocol.bearingToDirection(currentBearing);
                packet = TripperProtocol.buildCompassPacket(dirByte, false);
            }
        }

        // 1. Atualiza o pacote ativo no loop keepalive do TripperBridge (para o visor da moto)
        if (bridge != null && bridge.isConnected()) {
            bridge.setActiveDisplayPacket(packet);
        }

        // 2. Atualiza o espelho do visor na UI do celular
        if (mirrorListener != null) {
            final String finalModeName = modeName;
            final int finalIconCode = iconCode;
            final String finalPrimaryText = primaryText;
            final String finalBottomText = bottomText;
            mainHandler.post(() -> {
                if (mirrorListener != null) {
                    mirrorListener.onDisplayMirrorUpdated(finalModeName, finalIconCode, finalPrimaryText, finalBottomText);
                }
            });
        }
    }

    private String getHeadingCompassName(int degrees) {
        String[] directions = {"N", "NE", "E", "SE", "S", "SO", "O", "NO", "N"};
        return directions[Math.round(((degrees % 360) / 45.0f))];
    }
}
