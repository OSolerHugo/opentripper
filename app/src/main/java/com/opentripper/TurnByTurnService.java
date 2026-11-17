package com.opentripper;

import android.app.Notification;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Serviço de Interceptação de Navegação Turn-by-Turn para Google Maps e Waze.
 * 
 * Utiliza o NotificationListenerService oficial do Android para ler as instruções de
 * navegação curva a curva sem precisar de APIs pagas ou engenharia reversa de mapas.
 */
public class TurnByTurnService extends NotificationListenerService {

    public static class NavStep {
        public final int iconCode;
        public final int distanceValue;
        public final int distanceUnit; // 0x01 = metros, 0x02 = km x 10
        public final int distanceMeters;
        public final int nextIconCode; // Seta pequena (Byte [7]) ou NEXT_NONE (0xFF)
        public final int etaHour;
        public final int etaMinute;
        public final String rawTitle;
        public final String rawText;
        public final long timestamp;

        public NavStep(int iconCode, int distanceValue, int distanceUnit, int distanceMeters,
                       int nextIconCode, int etaHour, int etaMinute, String rawTitle, String rawText) {
            this.iconCode = iconCode;
            this.distanceValue = distanceValue;
            this.distanceUnit = distanceUnit;
            this.distanceMeters = distanceMeters;
            this.nextIconCode = nextIconCode;
            this.etaHour = etaHour;
            this.etaMinute = etaMinute;
            this.rawTitle = rawTitle;
            this.rawText = rawText;
            this.timestamp = System.currentTimeMillis();
        }
    }

    public interface NavListener {
        void onNavStepUpdated(NavStep step);
        void onNavigationFinished();
    }

    private static NavListener activeListener = null;
    private static NavStep lastActiveStep = null;

    // Diagnósticos em Tempo Real
    public static boolean isServiceConnected = false;
    public static String lastNotificationSummary = "Nenhuma notificação capturada ainda";
    public static long lastNotificationTimestamp = 0;
    private static int simulationStepIndex = 0;

    public static void setNavListener(NavListener listener) {
        activeListener = listener;
        if (activeListener != null && lastActiveStep != null) {
            // Entrega imediatamente a última instrução conhecida se ainda for recente (< 2 min)
            if (System.currentTimeMillis() - lastActiveStep.timestamp < 120000) {
                activeListener.onNavStepUpdated(lastActiveStep);
            }
        }
    }

    public static NavStep getLastActiveStep() {
        if (lastActiveStep != null && (System.currentTimeMillis() - lastActiveStep.timestamp < 120000)) {
            return lastActiveStep;
        }
        return null;
    }

    /** Verifica se o usuário concedeu permissão de leitura de notificações para o OpenTripper */
    public static boolean isNotificationAccessGranted(Context context) {
        if (context == null) return false;
        String pkgName = context.getPackageName();
        String flat = Settings.Secure.getString(context.getContentResolver(), "enabled_notification_listeners");
        if (!TextUtils.isEmpty(flat)) {
            String[] names = flat.split(":");
            for (String name : names) {
                ComponentName cn = ComponentName.unflattenFromString(name);
                if (cn != null && TextUtils.equals(pkgName, cn.getPackageName())) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Força a reconexão do NotificationListenerService junto ao Android OS.
     * Resolve o bug crítico do Android que desvincula o serviço silenciosamente após updates/instalações de APK.
     */
    public static void ensureServiceBound(Context context) {
        if (context == null) return;
        if (!isNotificationAccessGranted(context)) return;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                requestRebind(new ComponentName(context, TurnByTurnService.class));
                TripperBridge.logStatic("🔄 Solicitado requestRebind() do Leitor de Notificações", "sys");
            } catch (Throwable ignored) {}
        }

        try {
            PackageManager pm = context.getPackageManager();
            ComponentName cn = new ComponentName(context, TurnByTurnService.class);
            pm.setComponentEnabledSetting(cn, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
            pm.setComponentEnabledSetting(cn, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP);
        } catch (Throwable ignored) {}
    }

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        isServiceConnected = true;
        TripperBridge.logStatic("🟢 [TurnByTurn] Serviço conectado e ouvindo o Android!", "sys");

        // Lê imediatamente notificações ativas caso o Maps ou Waze já estejam navegando
        try {
            StatusBarNotification[] active = getActiveNotifications();
            if (active != null) {
                for (StatusBarNotification sbn : active) {
                    if (sbn != null && ("com.google.android.apps.maps".equals(sbn.getPackageName()) || "com.waze".equals(sbn.getPackageName()))) {
                        onNotificationPosted(sbn);
                    }
                }
            }
        } catch (Throwable ignored) {}
    }

    @Override
    public void onListenerDisconnected() {
        super.onListenerDisconnected();
        isServiceConnected = false;
        TripperBridge.logStatic("⚠️ [TurnByTurn] Serviço desvinculado pelo Android! Tentando reconectar...", "err");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                requestRebind(new ComponentName(this, TurnByTurnService.class));
            } catch (Throwable ignored) {}
        }
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null) return;
        isServiceConnected = true;

        String pkg = sbn.getPackageName();
        if (!"com.google.android.apps.maps".equals(pkg) && !"com.waze".equals(pkg)) {
            return;
        }

        Notification notification = sbn.getNotification();
        if (notification == null || notification.extras == null) return;

        Bundle extras = notification.extras;

        // Extrai todos os campos padrão
        String title = getExtraString(extras, Notification.EXTRA_TITLE, "android.title.big");
        String text = getExtraString(extras, Notification.EXTRA_TEXT, Notification.EXTRA_BIG_TEXT);
        String subText = getExtraString(extras, Notification.EXTRA_SUB_TEXT, Notification.EXTRA_INFO_TEXT);
        String summary = getExtraString(extras, Notification.EXTRA_SUMMARY_TEXT);
        String ticker = notification.tickerText != null ? notification.tickerText.toString() : "";

        // Coleta textos extras dinâmicos (evita perda de dados de novas versões do Maps/Waze)
        StringBuilder allExtrasBuilder = new StringBuilder();
        try {
            for (String key : extras.keySet()) {
                Object val = extras.get(key);
                if (val instanceof CharSequence) {
                    allExtrasBuilder.append(val.toString()).append(" ");
                } else if (val instanceof CharSequence[]) {
                    for (CharSequence cs : (CharSequence[]) val) {
                        if (cs != null) allExtrasBuilder.append(cs.toString()).append(" ");
                    }
                }
            }
        } catch (Throwable ignored) {}

        String appName = pkg.contains("waze") ? "Waze" : "Maps";
        lastNotificationTimestamp = System.currentTimeMillis();
        lastNotificationSummary = "[" + appName + "] " + (!title.isEmpty() ? title : "") + (!text.isEmpty() ? " | " + text : "");
        TripperBridge.logStatic("[NAV] Interceptado " + lastNotificationSummary, "info");

        // Se a notificação indicar término de trajeto
        if (isNavigationEnded(title, text, subText, allExtrasBuilder.toString())) {
            lastActiveStep = null;
            TripperBridge.logStatic("[NAV] Trajeto finalizado no " + appName, "sys");
            if (activeListener != null) {
                activeListener.onNavigationFinished();
            }
            return;
        }

        NavStep step = parseNavigation(title, text, subText, summary, ticker, allExtrasBuilder.toString());
        if (step != null) {
            lastActiveStep = step;
            String unitStr = step.distanceUnit == 0x02 ? String.format("%.1f km", step.distanceValue / 10.0f) : (step.distanceValue + " m");
            TripperBridge.logStatic("[NAV] ✅ Manobra: " + TripperProtocol.getIconName(step.iconCode) + " a " + unitStr, "tx");
            if (activeListener != null) {
                activeListener.onNavStepUpdated(step);
            }
        } else {
            TripperBridge.logStatic("[NAV] ℹ️ Ignorado (sem manobra/distância ativa)", "sys");
        }
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        if (sbn == null) return;
        String pkg = sbn.getPackageName();
        if ("com.google.android.apps.maps".equals(pkg) || "com.waze".equals(pkg)) {
            lastActiveStep = null;
            TripperBridge.logStatic("[NAV] Notificação de navegação removida pelo " + (pkg.contains("waze") ? "Waze" : "Maps"), "sys");
            if (activeListener != null) {
                activeListener.onNavigationFinished();
            }
        }
    }

    private static String getExtraString(Bundle extras, String... keys) {
        for (String k : keys) {
            CharSequence cs = extras.getCharSequence(k);
            if (cs != null && !TextUtils.isEmpty(cs)) {
                return cs.toString().trim();
            }
        }
        return "";
    }

    private static boolean isNavigationEnded(String title, String text, String subText, String extrasText) {
        String combined = (title + " " + text + " " + subText + " " + extrasText).toLowerCase();
        return combined.contains("navegação concluída") ||
               combined.contains("navegação finalizada") ||
               combined.contains("rota concluída") ||
               combined.contains("você chegou ao seu destino") ||
               combined.contains("you have arrived") ||
               combined.contains("navigation ended");
    }

    public static NavStep parseNavigation(String title, String text, String subText, String summary, String ticker) {
        return parseNavigation(title, text, subText, summary, ticker, "");
    }

    /**
     * Decodificador Inteligente e Resiliente de Notificações do Waze e Google Maps.
     */
    public static NavStep parseNavigation(String title, String text, String subText, String summary, String ticker, String extrasText) {
        String fullPrompt = (title + " " + text + " " + subText + " " + summary + " " + ticker + " " + extrasText).toLowerCase();

        // 1. Filtra APENAS notificações explicitamente sem destino
        if (fullPrompt.contains("sem destino") ||
            fullPrompt.contains("sem rota") ||
            fullPrompt.contains("conduzindo sem destino")) {
            return null;
        }

        // 2. Extração da Distância da Manobra (ignora sufixos de velocidade como km/h)
        int distanceValue = -1;
        int distanceUnit = 0x01; // metros
        int distanceMeters = -1;

        Pattern distPattern = Pattern.compile("(\\d+([.,]\\d+)?)\\s*(m|km|metros|metro|quilômetros|quilômetro|ft|feet|pés|pé|mi|milhas|milha)\\b(?!/h)", Pattern.CASE_INSENSITIVE);
        Matcher distMatcher = distPattern.matcher(fullPrompt);

        if (distMatcher.find()) {
            String numStr = distMatcher.group(1).replace(",", ".");
            String unitStr = distMatcher.group(3).toLowerCase();
            try {
                float rawVal = Float.parseFloat(numStr);
                if (unitStr.startsWith("k")) {
                    distanceMeters = Math.round(rawVal * 1000.0f);
                    distanceUnit = 0x02; // km x 10
                    distanceValue = Math.round(rawVal * 10.0f);
                } else if (unitStr.startsWith("mi")) {
                    distanceMeters = Math.round(rawVal * 1609.34f);
                    distanceUnit = 0x02;
                    distanceValue = Math.round((distanceMeters / 1000.0f) * 10.0f);
                } else if (unitStr.startsWith("f") || unitStr.startsWith("p")) {
                    distanceMeters = Math.round(rawVal * 0.3048f);
                    distanceUnit = 0x01;
                    distanceValue = distanceMeters;
                } else {
                    distanceMeters = Math.round(rawVal);
                    distanceUnit = 0x01;
                    distanceValue = distanceMeters;
                }
            } catch (Exception ignored) {}
        }

        // 3. Verificação de eventos sem distância (Chegada ou Recálculo)
        boolean isArrived = fullPrompt.contains("chegou") || fullPrompt.contains("destino") || fullPrompt.contains("arrived");
        boolean isRerouting = fullPrompt.contains("recalcul") || fullPrompt.contains("re-rout");

        // REGRA DE OURO: Se não achou distância e não é chegada/recálculo, NÃO é uma manobra ativa
        if (distanceValue < 0 && !isArrived && !isRerouting) {
            return null;
        }

        if (distanceValue < 0) {
            distanceValue = 0;
            distanceMeters = 0;
            distanceUnit = 0x01;
        }

        // 4. Extração de Horário de Chegada (ETA)
        int etaHour = 0xFF;
        int etaMin = 0xFF;
        Pattern etaPattern = Pattern.compile("\\b([0-2]?[0-9]):([0-5][0-9])\\b");
        Matcher etaMatcher = etaPattern.matcher(subText + " " + text + " " + summary + " " + title + " " + extrasText);
        if (etaMatcher.find()) {
            try {
                etaHour = Integer.parseInt(etaMatcher.group(1));
                etaMin = Integer.parseInt(etaMatcher.group(2));
            } catch (Exception ignored) {}
        }

        // 5. Mapeamento Completo de Ícone de Manobra (WazeTripper / Hunter 350)
        int iconCode = TripperProtocol.MANEUVER_STRAIGHT; // 0x09 padrão
        int nextIconCode = TripperProtocol.NEXT_NONE;     // 0xFF

        // Alerta de Radar na via
        if (fullPrompt.contains("radar") || fullPrompt.contains("fiscalização") || fullPrompt.contains("fiscalizacao") || fullPrompt.contains("speed camera")) {
            nextIconCode = TripperProtocol.NEXT_RADAR; // 0x3C seta pequena
        }

        if (isArrived) {
            if (fullPrompt.contains("esquerda") || fullPrompt.contains("left")) {
                iconCode = TripperProtocol.MANEUVER_DESTINATION_LEFT; // 0x01
            } else if (fullPrompt.contains("direita") || fullPrompt.contains("right")) {
                iconCode = TripperProtocol.MANEUVER_DESTINATION_RIGHT; // 0x02
            } else {
                iconCode = TripperProtocol.MANEUVER_DESTINATION; // 0x00 bandeira
            }
        } else if (isRerouting) {
            iconCode = TripperProtocol.MANEUVER_RE_ROUTING; // 0x1C
        } else if (fullPrompt.contains("rotat") || fullPrompt.contains("roundabout") || fullPrompt.contains("rótula") || fullPrompt.contains("rotula")) {
            // Rotatórias Anti-Horárias (Trânsito Brasil / Mão Direita: 0x32 a 0x3A)
            if (fullPrompt.contains("1ª") || fullPrompt.contains("1a") || fullPrompt.contains("primeira") || fullPrompt.contains("1st") || fullPrompt.contains("saída 1") || fullPrompt.contains("exit 1")) {
                iconCode = TripperProtocol.MANEUVER_ROUNDABOUT_EXIT_1; // 0x32
            } else if (fullPrompt.contains("2ª") || fullPrompt.contains("2a") || fullPrompt.contains("segunda") || fullPrompt.contains("2nd") || fullPrompt.contains("saída 2") || fullPrompt.contains("exit 2")) {
                iconCode = TripperProtocol.MANEUVER_ROUNDABOUT_EXIT_2; // 0x33
            } else if (fullPrompt.contains("3ª") || fullPrompt.contains("3a") || fullPrompt.contains("terceira") || fullPrompt.contains("3rd") || fullPrompt.contains("saída 3") || fullPrompt.contains("exit 3")) {
                iconCode = TripperProtocol.MANEUVER_ROUNDABOUT_EXIT_3; // 0x34
            } else if (fullPrompt.contains("4ª") || fullPrompt.contains("4a") || fullPrompt.contains("quarta") || fullPrompt.contains("4th") || fullPrompt.contains("saída 4") || fullPrompt.contains("exit 4")) {
                iconCode = TripperProtocol.MANEUVER_ROUNDABOUT_EXIT_4; // 0x35
            } else if (fullPrompt.contains("5ª") || fullPrompt.contains("5a") || fullPrompt.contains("quinta") || fullPrompt.contains("5th") || fullPrompt.contains("saída 5") || fullPrompt.contains("exit 5")) {
                iconCode = TripperProtocol.MANEUVER_ROUNDABOUT_EXIT_5; // 0x36
            } else if (fullPrompt.contains("6ª") || fullPrompt.contains("6a") || fullPrompt.contains("sexta") || fullPrompt.contains("6th") || fullPrompt.contains("saída 6") || fullPrompt.contains("exit 6")) {
                iconCode = TripperProtocol.MANEUVER_ROUNDABOUT_EXIT_6; // 0x37
            } else if (fullPrompt.contains("7ª") || fullPrompt.contains("7a") || fullPrompt.contains("sétima") || fullPrompt.contains("7th") || fullPrompt.contains("saída 7") || fullPrompt.contains("exit 7")) {
                iconCode = TripperProtocol.MANEUVER_ROUNDABOUT_EXIT_7; // 0x38
            } else if (fullPrompt.contains("8ª") || fullPrompt.contains("8a") || fullPrompt.contains("oitava") || fullPrompt.contains("8th") || fullPrompt.contains("saída 8") || fullPrompt.contains("exit 8")) {
                iconCode = TripperProtocol.MANEUVER_ROUNDABOUT_EXIT_8; // 0x39
            } else if (fullPrompt.contains("9ª") || fullPrompt.contains("9a") || fullPrompt.contains("nona") || fullPrompt.contains("9th") || fullPrompt.contains("saída 9") || fullPrompt.contains("exit 9")) {
                iconCode = TripperProtocol.MANEUVER_ROUNDABOUT_EXIT_9; // 0x3A
            } else {
                iconCode = TripperProtocol.MANEUVER_ROUNDABOUT_CCW_BASE; // 0x31 base anti-horária
            }
        } else if (fullPrompt.contains("retorno") || fullPrompt.contains("u-turn")) {
            iconCode = TripperProtocol.MANEUVER_UTURN_CCW; // 0x3D
        } else if (fullPrompt.contains("esquerda") || fullPrompt.contains("left")) {
            if (fullPrompt.contains("acentuada") || fullPrompt.contains("fechada") || fullPrompt.contains("sharp")) {
                iconCode = TripperProtocol.MANEUVER_SHARP_LEFT; // 0x16
            } else if (fullPrompt.contains("suave") || fullPrompt.contains("slight") || fullPrompt.contains("leve")) {
                iconCode = TripperProtocol.MANEUVER_SLIGHT_LEFT; // 0x18
            } else if (fullPrompt.contains("saída") || fullPrompt.contains("saida") || fullPrompt.contains("exit") || fullPrompt.contains("rampa")) {
                iconCode = TripperProtocol.MANEUVER_EXIT_LEFT; // 0x2E
                if (nextIconCode == TripperProtocol.NEXT_NONE) nextIconCode = TripperProtocol.NEXT_KEEP_LEFT;
            } else if (fullPrompt.contains("mantenha") || fullPrompt.contains("keep") || fullPrompt.contains("faixa") || fullPrompt.contains("bifurcação")) {
                iconCode = TripperProtocol.MANEUVER_KEEP_LEFT; // 0x28
                if (nextIconCode == TripperProtocol.NEXT_NONE) nextIconCode = TripperProtocol.NEXT_KEEP_LEFT;
            } else {
                iconCode = TripperProtocol.MANEUVER_TURN_LEFT; // 0x14 (90 graus)
            }
        } else if (fullPrompt.contains("direita") || fullPrompt.contains("right")) {
            if (fullPrompt.contains("acentuada") || fullPrompt.contains("fechada") || fullPrompt.contains("sharp")) {
                iconCode = TripperProtocol.MANEUVER_SHARP_RIGHT; // 0x17
            } else if (fullPrompt.contains("suave") || fullPrompt.contains("slight") || fullPrompt.contains("leve")) {
                iconCode = TripperProtocol.MANEUVER_SLIGHT_RIGHT; // 0x19
            } else if (fullPrompt.contains("saída") || fullPrompt.contains("saida") || fullPrompt.contains("exit") || fullPrompt.contains("rampa")) {
                iconCode = TripperProtocol.MANEUVER_EXIT_RIGHT; // 0x2D
                if (nextIconCode == TripperProtocol.NEXT_NONE) nextIconCode = TripperProtocol.NEXT_KEEP_RIGHT;
            } else if (fullPrompt.contains("mantenha") || fullPrompt.contains("keep") || fullPrompt.contains("faixa") || fullPrompt.contains("bifurcação")) {
                iconCode = TripperProtocol.MANEUVER_KEEP_RIGHT; // 0x27
                if (nextIconCode == TripperProtocol.NEXT_NONE) nextIconCode = TripperProtocol.NEXT_KEEP_RIGHT;
            } else {
                iconCode = TripperProtocol.MANEUVER_TURN_RIGHT; // 0x15 (90 graus)
            }
        } else if (fullPrompt.contains("balsa") || fullPrompt.contains("ferry")) {
            iconCode = TripperProtocol.MANEUVER_FERRY; // 0x3E
        } else if (fullPrompt.contains("frente") || fullPrompt.contains("reto") || fullPrompt.contains("continue") || fullPrompt.contains("siga") || fullPrompt.contains("straight")) {
            iconCode = TripperProtocol.MANEUVER_STRAIGHT; // 0x09
        }

        return new NavStep(iconCode, distanceValue, distanceUnit, distanceMeters, nextIconCode, etaHour, etaMin, title, text);
    }

    /**
     * Simula instruções de navegação para teste imediato de bancada / garagem no painel da moto.
     * Alterna ciclicamente entre manobras reais e volta ao velocímetro.
     */
    public static void simulateNextStep() {
        simulationStepIndex = (simulationStepIndex + 1) % 5;
        NavStep step;
        switch (simulationStepIndex) {
            case 1:
                step = parseNavigation("Em 450 m", "Vire à direita na Av. Paulista", "18:45", "", "");
                break;
            case 2:
                step = parseNavigation("Em 120 m", "Entre na rotatória e pegue a 2ª saída", "18:45", "", "");
                break;
            case 3:
                step = parseNavigation("A 35 metros", "Curva acentuada à esquerda na Rua Bela Cintra", "18:46", "", "");
                break;
            case 4:
                step = parseNavigation("Você chegou", "Destino à direita", "18:47", "", "");
                break;
            default:
                lastActiveStep = null;
                TripperBridge.logStatic("🧪 [Simulação] Rota de teste finalizada. Retornando ao velocímetro.", "sys");
                if (activeListener != null) {
                    activeListener.onNavigationFinished();
                }
                return;
        }

        if (step != null) {
            lastActiveStep = step;
            TripperBridge.logStatic("🧪 [Simulação] Passo " + simulationStepIndex + ": " + 
                TripperProtocol.getIconName(step.iconCode) + " a " + step.distanceValue + (step.distanceUnit == 0x02 ? "00m" : "m"), "tx");
            if (activeListener != null) {
                activeListener.onNavStepUpdated(step);
            }
        }
    }
}
