package com.opentripper;

import android.app.Notification;
import android.content.ComponentName;
import android.content.Context;
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
        public final int etaHour;
        public final int etaMinute;
        public final String rawTitle;
        public final String rawText;
        public final long timestamp;

        public NavStep(int iconCode, int distanceValue, int distanceUnit, int etaHour, int etaMinute, String rawTitle, String rawText) {
            this.iconCode = iconCode;
            this.distanceValue = distanceValue;
            this.distanceUnit = distanceUnit;
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

    public static void setNavListener(NavListener listener) {
        activeListener = listener;
        if (activeListener != null && lastActiveStep != null) {
            // Entrega imediatamente a última instrução conhecida se ainda for recente (< 15s)
            if (System.currentTimeMillis() - lastActiveStep.timestamp < 15000) {
                activeListener.onNavStepUpdated(lastActiveStep);
            }
        }
    }

    public static NavStep getLastActiveStep() {
        if (lastActiveStep != null && (System.currentTimeMillis() - lastActiveStep.timestamp < 15000)) {
            return lastActiveStep;
        }
        return null;
    }

    /** Verifica se o usuário concedeu permissão de leitura de notificações para o OpenTripper */
    public static boolean isNotificationAccessGranted(Context context) {
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

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null) return;

        String pkg = sbn.getPackageName();
        if (!"com.google.android.apps.maps".equals(pkg) && !"com.waze".equals(pkg)) {
            return;
        }

        Notification notification = sbn.getNotification();
        if (notification == null || notification.extras == null) return;

        Bundle extras = notification.extras;
        CharSequence titleCs = extras.getCharSequence(Notification.EXTRA_TITLE);
        CharSequence textCs = extras.getCharSequence(Notification.EXTRA_TEXT);
        CharSequence subTextCs = extras.getCharSequence(Notification.EXTRA_SUB_TEXT);

        String title = titleCs != null ? titleCs.toString() : "";
        String text = textCs != null ? textCs.toString() : "";
        String subText = subTextCs != null ? subTextCs.toString() : "";

        // Se a notificação for apenas informativa ("Navegação ativa" ou vazia), ignora
        if (TextUtils.isEmpty(title) && TextUtils.isEmpty(text)) return;

        NavStep step = parseNavigation(title, text, subText);
        if (step != null) {
            lastActiveStep = step;
            if (activeListener != null) {
                activeListener.onNavStepUpdated(step);
            }
        }
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        if (sbn == null) return;
        String pkg = sbn.getPackageName();
        if ("com.google.android.apps.maps".equals(pkg) || "com.waze".equals(pkg)) {
            lastActiveStep = null;
            if (activeListener != null) {
                activeListener.onNavigationFinished();
            }
        }
    }

    /**
     * Decodificador Inteligente de Texto do Waze e Google Maps para o Protocolo Tripper Pod
     */
    public static NavStep parseNavigation(String title, String text, String subText) {
        String fullPrompt = (title + " " + text).toLowerCase();

        // 1. Extração da Distância da Manobra
        int distanceValue = 100;
        int distanceUnit = 0x01; // metros

        Pattern distPattern = Pattern.compile("(\\d+([.,]\\d+)?)\\s*(m|km|metros|quilômetros)", Pattern.CASE_INSENSITIVE);
        Matcher distMatcher = distPattern.matcher(title + " " + text);

        if (distMatcher.find()) {
            String numStr = distMatcher.group(1).replace(",", ".");
            String unitStr = distMatcher.group(3).toLowerCase();
            try {
                float rawVal = Float.parseFloat(numStr);
                if (unitStr.startsWith("k")) {
                    distanceUnit = 0x02; // km x 10
                    distanceValue = Math.round(rawVal * 10.0f);
                } else {
                    distanceUnit = 0x01; // metros
                    distanceValue = Math.round(rawVal);
                }
            } catch (Exception ignored) {}
        }

        // 2. Extração de Horário de Chegada (ETA)
        int etaHour = 0xFF;
        int etaMin = 0xFF;
        Pattern etaPattern = Pattern.compile("\\b([0-2]?[0-9]):([0-5][0-9])\\b");
        Matcher etaMatcher = etaPattern.matcher(subText + " " + text + " " + title);
        if (etaMatcher.find()) {
            try {
                etaHour = Integer.parseInt(etaMatcher.group(1));
                etaMin = Integer.parseInt(etaMatcher.group(2));
            } catch (Exception ignored) {}
        }

        // 3. Mapeamento de Ícone por Palavras-Chave (incluindo rotatórias descobertas na Hunter 350)
        int iconCode = 0x09; // padrão: seguir reto

        if (fullPrompt.contains("chegou") || fullPrompt.contains("destino") || fullPrompt.contains("arrived")) {
            iconCode = 0x01; // bandeira de destino
        } else if (fullPrompt.contains("rotat") || fullPrompt.contains("roundabout")) {
            // Rotatórias 1ª a 9ª saída (Descoberta confirmada!)
            if (fullPrompt.contains("1ª") || fullPrompt.contains("1a") || fullPrompt.contains("primeira") || fullPrompt.contains("saída 1")) {
                iconCode = 0x0B;
            } else if (fullPrompt.contains("2ª") || fullPrompt.contains("2a") || fullPrompt.contains("segunda") || fullPrompt.contains("saída 2")) {
                iconCode = 0x0C;
            } else if (fullPrompt.contains("3ª") || fullPrompt.contains("3a") || fullPrompt.contains("terceira") || fullPrompt.contains("saída 3")) {
                iconCode = 0x0D;
            } else if (fullPrompt.contains("4ª") || fullPrompt.contains("4a") || fullPrompt.contains("quarta") || fullPrompt.contains("saída 4")) {
                iconCode = 0x0E;
            } else if (fullPrompt.contains("5ª") || fullPrompt.contains("5a") || fullPrompt.contains("quinta") || fullPrompt.contains("saída 5")) {
                iconCode = 0x0F;
            } else if (fullPrompt.contains("6ª") || fullPrompt.contains("6a") || fullPrompt.contains("sexta") || fullPrompt.contains("saída 6")) {
                iconCode = 0x10;
            } else if (fullPrompt.contains("7ª") || fullPrompt.contains("7a") || fullPrompt.contains("sétima") || fullPrompt.contains("saída 7")) {
                iconCode = 0x11;
            } else if (fullPrompt.contains("8ª") || fullPrompt.contains("8a") || fullPrompt.contains("oitava") || fullPrompt.contains("saída 8")) {
                iconCode = 0x12;
            } else if (fullPrompt.contains("9ª") || fullPrompt.contains("9a") || fullPrompt.contains("nona") || fullPrompt.contains("saída 9")) {
                iconCode = 0x13;
            } else {
                iconCode = 0x0A; // rotatória genérica
            }
        } else if (fullPrompt.contains("retorno") || fullPrompt.contains("u-turn")) {
            iconCode = 0x3D;
        } else if (fullPrompt.contains("esquerda") || fullPrompt.contains("left")) {
            if (fullPrompt.contains("acentuada") || fullPrompt.contains("fechada") || fullPrompt.contains("sharp")) {
                iconCode = 0x16;
            } else if (fullPrompt.contains("suave") || fullPrompt.contains("mantenha") || fullPrompt.contains("slight") || fullPrompt.contains("bifurcação")) {
                iconCode = 0x18;
            } else {
                iconCode = 0x14; // virar 90 graus
            }
        } else if (fullPrompt.contains("direita") || fullPrompt.contains("right")) {
            if (fullPrompt.contains("acentuada") || fullPrompt.contains("fechada") || fullPrompt.contains("sharp")) {
                iconCode = 0x17;
            } else if (fullPrompt.contains("suave") || fullPrompt.contains("mantenha") || fullPrompt.contains("slight") || fullPrompt.contains("bifurcação")) {
                iconCode = 0x19;
            } else {
                iconCode = 0x15; // virar 90 graus
            }
        } else if (fullPrompt.contains("frente") || fullPrompt.contains("reto") || fullPrompt.contains("continue") || fullPrompt.contains("siga")) {
            iconCode = 0x09;
        }

        return new NavStep(iconCode, distanceValue, distanceUnit, etaHour, etaMin, title, text);
    }
}
