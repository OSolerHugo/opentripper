package com.opentripper;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.SystemClock;

/**
 * Provedor de Velocidade e Localização com Gestão Agressiva de Bateria.
 * 
 * REGRA DE OURO: Só requisita dados de satélite do GPS quando explicitamente iniciado.
 * Ao chamar stop(), desregistra imediatamente todos os listeners do LocationManager,
 * garantindo que o chip GPS desligue e não consuma bateria com a moto parada.
 */
public class BatteryAwareLocationProvider implements LocationListener {

    public interface SpeedListener {
        void onSpeedUpdated(int speedKmH, float bearing, float tripDistanceKm);
        void onGpsStatusChanged(boolean active, String status);
    }

    private final Context context;
    private final SpeedListener listener;
    private LocationManager locationManager;

    private boolean isRunning = false;
    private Location lastLocation = null;
    private float tripDistanceMeters = 0.0f;
    private long lastSpeedUpdateTime = 0;

    public BatteryAwareLocationProvider(Context context, SpeedListener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        this.locationManager = (LocationManager) this.context.getSystemService(Context.LOCATION_SERVICE);
    }

    /**
     * Inicia a captura de satélite do GPS.
     * Deve ser chamado SOMENTE após o Tripper Pod estar fisicamente conectado.
     */
    public synchronized boolean start() {
        if (isRunning) return true;

        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            if (listener != null) {
                listener.onGpsStatusChanged(false, "Permissão de GPS necessária");
            }
            return false;
        }

        if (locationManager == null) {
            locationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        }

        if (locationManager == null) {
            if (listener != null) {
                listener.onGpsStatusChanged(false, "Serviço de GPS indisponível");
            }
            return false;
        }

        boolean gpsEnabled = false;
        try {
            gpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
        } catch (Exception ignored) {}

        if (!gpsEnabled) {
            if (listener != null) {
                listener.onGpsStatusChanged(false, "GPS do aparelho desativado");
            }
            return false;
        }

        try {
            // Atualização a cada 1000ms (1 segundo) para coincidir com o refrescamento do visor
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                1000L,
                0.0f,
                this
            );
            isRunning = true;
            if (listener != null) {
                listener.onGpsStatusChanged(true, "Buscando satélites GPS...");
            }
            return true;
        } catch (SecurityException se) {
            if (listener != null) {
                listener.onGpsStatusChanged(false, "Erro de segurança ao iniciar GPS");
            }
            return false;
        }
    }

    /**
     * Desliga instantaneamente as requisições de satélite para poupar 100% da bateria.
     * Chamado imediatamente na desconexão BLE da moto.
     */
    public synchronized void stop() {
        if (!isRunning) return;

        if (locationManager != null) {
            try {
                locationManager.removeUpdates(this);
            } catch (Exception ignored) {}
        }

        isRunning = false;
        lastLocation = null;
        if (listener != null) {
            listener.onGpsStatusChanged(false, "GPS pausado (economia de bateria)");
        }
    }

    public synchronized void resetTripDistance() {
        this.tripDistanceMeters = 0.0f;
    }

    public float getTripDistanceKm() {
        return tripDistanceMeters / 1000.0f;
    }

    public boolean isRunning() {
        return isRunning;
    }

    @Override
    public void onLocationChanged(Location location) {
        if (location == null || !isRunning) return;

        // 1. Calcula a velocidade em km/h
        int speedKmH = 0;
        if (location.hasSpeed()) {
            speedKmH = Math.round(location.getSpeed() * 3.6f);
        } else if (lastLocation != null) {
            // Fallback manual por delta de distância se o driver não reportar hasSpeed
            long dtMs = location.getTime() - lastLocation.getTime();
            if (dtMs > 0 && dtMs < 5000) {
                float distM = lastLocation.distanceTo(location);
                speedKmH = Math.round((distM / (dtMs / 1000.0f)) * 3.6f);
            }
        }

        // Filtra ruído de satélite parado (abaixo de 2 km/h considera parado)
        if (speedKmH < 2) {
            speedKmH = 0;
        }

        // 2. Acumula distância de viagem (apenas se tiver boa precisão para evitar saltos falsos)
        if (lastLocation != null && location.hasAccuracy() && location.getAccuracy() < 25.0f) {
            float deltaDist = lastLocation.distanceTo(location);
            if (deltaDist > 1.0f && deltaDist < 300.0f) { // descarta saltos anômalos
                tripDistanceMeters += deltaDist;
            }
        }

        lastLocation = location;
        lastSpeedUpdateTime = SystemClock.elapsedRealtime();

        float bearing = location.hasBearing() ? location.getBearing() : -1.0f;

        if (listener != null) {
            listener.onSpeedUpdated(speedKmH, bearing, tripDistanceMeters / 1000.0f);
        }
    }

    @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
    @Override public void onProviderEnabled(String provider) {}
    @Override public void onProviderDisabled(String provider) {
        if (LocationManager.GPS_PROVIDER.equals(provider)) {
            stop();
        }
    }
}
