package com.opentripper;

import java.nio.charset.StandardCharsets;
import java.util.Calendar;
import java.util.Locale;

/**
 * Protocolo Bluetooth Low Energy (BLE) do Royal Enfield Tripper Pod.
 * 
 * Compatível com o firmware original da Royal Enfield Hunter 350 / Meteor 350
 * e com as descobertas de engenharia reversa do projeto WazeTripper.
 */
public final class TripperProtocol {

    private TripperProtocol() {}

    // --- Códigos de Ícones de Manobra (Byte [2] e Byte [7]) ---
    public static final int MANEUVER_DESTINATION = 0x00;        // Chegada ao Destino (Bandeira)
    public static final int MANEUVER_DESTINATION_LEFT = 0x01;   // Destino à Esquerda
    public static final int MANEUVER_DESTINATION_RIGHT = 0x02;  // Destino à Direita
    public static final int MANEUVER_MERGE_RIGHT = 0x03;        // Fusão à Direita
    public static final int MANEUVER_MERGE_LEFT = 0x04;         // Fusão à Esquerda
    public static final int MANEUVER_FORK_RIGHT = 0x05;         // Bifurcação à Direita
    public static final int MANEUVER_FORK_LEFT = 0x06;          // Bifurcação à Esquerda
    public static final int MANEUVER_OFFRAMP_RIGHT = 0x07;      // Saída/Rampa Direita
    public static final int MANEUVER_OFFRAMP_LEFT = 0x08;       // Saída/Rampa Esquerda
    public static final int MANEUVER_STRAIGHT = 0x09;           // Seguir em Frente
    public static final int MANEUVER_ROUNDABOUT_CW_BASE = 0x0A; // Rotatória Horária (Mão inglesa)
    public static final int MANEUVER_TURN_LEFT = 0x14;          // Virar à Esquerda (90°)
    public static final int MANEUVER_TURN_RIGHT = 0x15;         // Virar à Direita (90°)
    public static final int MANEUVER_SHARP_LEFT = 0x16;         // Curva Acentuada à Esquerda
    public static final int MANEUVER_SHARP_RIGHT = 0x17;        // Curva Acentuada à Direita
    public static final int MANEUVER_SLIGHT_LEFT = 0x18;        // Curva Suave à Esquerda
    public static final int MANEUVER_SLIGHT_RIGHT = 0x19;       // Curva Suave à Direita
    public static final int MANEUVER_UTURN_CW = 0x1A;           // Retorno Horário
    public static final int MANEUVER_MERGE = 0x1B;              // Fusão de Pista
    public static final int MANEUVER_RE_ROUTING = 0x1C;         // Recalculando Rota (Ícone carregando)
    public static final int MANEUVER_KEEP_RIGHT = 0x27;         // Mantenha à Direita (Via expressa)
    public static final int MANEUVER_KEEP_LEFT = 0x28;          // Mantenha à Esquerda (Via expressa)
    public static final int NEXT_KEEP_LEFT = 0x2B;              // Mantenha Faixa Esquerda (Seta pequena [7])
    public static final int NEXT_KEEP_RIGHT = 0x2C;             // Mantenha Faixa Direita (Seta pequena [7])
    public static final int MANEUVER_EXIT_RIGHT = 0x2D;         // Saída de Rodovia à Direita
    public static final int MANEUVER_EXIT_LEFT = 0x2E;          // Saída de Rodovia à Esquerda
    public static final int MANEUVER_ROUNDABOUT_CCW_BASE = 0x31;// Rotatória Anti-Horária Base (Brasil / Mão direita)
    public static final int MANEUVER_ROUNDABOUT_EXIT_1 = 0x32;  // Rotatória 1ª Saída (Anti-horária)
    public static final int MANEUVER_ROUNDABOUT_EXIT_2 = 0x33;  // Rotatória 2ª Saída
    public static final int MANEUVER_ROUNDABOUT_EXIT_3 = 0x34;  // Rotatória 3ª Saída
    public static final int MANEUVER_ROUNDABOUT_EXIT_4 = 0x35;  // Rotatória 4ª Saída
    public static final int MANEUVER_ROUNDABOUT_EXIT_5 = 0x36;  // Rotatória 5ª Saída
    public static final int MANEUVER_ROUNDABOUT_EXIT_6 = 0x37;  // Rotatória 6ª Saída
    public static final int MANEUVER_ROUNDABOUT_EXIT_7 = 0x38;  // Rotatória 7ª Saída
    public static final int MANEUVER_ROUNDABOUT_EXIT_8 = 0x39;  // Rotatória 8ª Saída
    public static final int MANEUVER_ROUNDABOUT_EXIT_9 = 0x3A;  // Rotatória 9ª Saída
    public static final int NEXT_RADAR = 0x3C;                  // Radar / Partida (Seta pequena [7])
    public static final int MANEUVER_UTURN_CCW = 0x3D;          // Retorno Anti-Horário (Brasil / Mão direita)
    public static final int MANEUVER_FERRY = 0x3E;              // Balsa (Barco)
    public static final int SCREEN_COMPASS = 0x41;              // Tela Especial da Bússola
    public static final int MANEUVER_ROUTE_STARTED = 0x45;      // Tela Grande de "Rota Iniciada"
    public static final int NEXT_NONE = 0xFF;                   // Sem próxima manobra secundária

    /** CRC-16/CCITT-FALSE: poly 0x1021, init 0xFFFF, MSB-first, sem XOR final. */
    public static int crc16(byte[] buf) {
        int crc = 0xFFFF;
        for (byte b : buf) {
            crc ^= (b & 0xFF) << 8;
            for (int i = 0; i < 8; i++) {
                if ((crc & 0x8000) != 0) {
                    crc = ((crc << 1) ^ 0x1021) & 0xFFFF;
                } else {
                    crc = (crc << 1) & 0xFFFF;
                }
            }
        }
        return crc & 0xFFFF;
    }

    public static byte[] withCrc(byte[] first18) {
        if (first18.length != 18) {
            throw new IllegalArgumentException("esperava 18 bytes, recebi " + first18.length);
        }
        int crc = crc16(first18);
        byte[] out = new byte[20];
        System.arraycopy(first18, 0, out, 0, 18);
        out[18] = (byte) ((crc >> 8) & 0xFF);
        out[19] = (byte) (crc & 0xFF);
        return out;
    }

    public static byte[] hex(String s) {
        String[] parts = s.trim().split("\\s+");
        byte[] out = new byte[parts.length];
        for (int i = 0; i < parts.length; i++) {
            out[i] = (byte) Integer.parseInt(parts[i], 16);
        }
        return out;
    }

    public static String toHexString(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return "";
        StringBuilder sb = new StringBuilder(bytes.length * 3);
        for (byte b : bytes) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(String.format(Locale.US, "%02X", b));
        }
        return sb.toString();
    }

    // Pacotes fixos de controle
    public static final byte[] HANDSHAKE_SHOW_PIN =
            hex("21 01 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 50 A7");

    public static final byte[] HANDSHAKE_RESUME =
            hex("21 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 40 45");

    public static final byte[] PING_FW =
            hex("03 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 45 D9");

    public static byte[] buildPinPacket(String pin) {
        byte[] buf = new byte[18];
        buf[0] = 0x20;
        byte[] pinBytes = pin.getBytes(StandardCharsets.UTF_8);
        int n = Math.min(pinBytes.length, 6);
        System.arraycopy(pinBytes, 0, buf, 1, n);
        return withCrc(buf);
    }

    public static byte[] buildSetTimeNowPacket() {
        Calendar now = Calendar.getInstance();
        byte[] buf = new byte[18];
        buf[0] = 0x50;
        buf[1] = (byte) now.get(Calendar.HOUR_OF_DAY);
        buf[2] = (byte) now.get(Calendar.MINUTE);
        return withCrc(buf);
    }

    /**
     * Mapeia um ângulo de bússola em graus (0° = Norte) para os setores discretos
     * esperados pelo hardware do Tripper Pod no byte [14].
     */
    public static byte bearingToDirection(float bearing) {
        int sector = (int) (((bearing + 22.5f) % 360f) / 45f);
        sector = Math.max(0, Math.min(7, sector));
        switch (sector) {
            case 0: return (byte) 0x10; // N
            case 1: return (byte) 0x50; // NE
            case 2: return (byte) 0x20; // E
            case 3: return (byte) 0x70; // SE
            case 4: return (byte) 0x40; // S
            case 5: return (byte) 0x80; // SW
            case 6: return (byte) 0x30; // W
            default: return (byte) 0x60; // NW
        }
    }

    /**
     * Pacote da Bússola Digital 360° no Tripper Pod (CMD 0x10 0x11, tela 0x41)
     */
    public static byte[] buildCompassPacket(byte direction) {
        return buildCompassPacket(direction, false);
    }

    public static byte[] buildCompassPacket(byte direction, boolean nightMode) {
        byte[] buf = new byte[18];
        buf[0] = 0x10;
        buf[1] = 0x11;
        buf[2] = (byte) SCREEN_COMPASS;
        buf[6] = (byte) (nightMode ? 1 : 0);
        buf[7] = (byte) 0xFF;
        buf[11] = (byte) 0xFF;
        buf[12] = (byte) 0xFF;
        buf[13] = (byte) 0xFF;
        buf[14] = direction;
        return withCrc(buf);
    }

    /**
     * Calcula a intensidade do anel de progresso em torno da manobra (Byte [6]).
     * O aro acende progressivamente conforme a moto se aproxima da curva.
     */
    public static int distanceIntensity(int meters, boolean night) {
        int v;
        if (meters <= 10) v = 0x50;
        else if (meters <= 20) v = 0x40;
        else if (meters <= 45) v = 0x30;
        else if (meters <= 70) v = 0x20;
        else if (meters <= 95) v = 0x10;
        else v = 0x00;
        return v + (night ? 1 : 0);
    }

    /**
     * Codifica uma distância em metros para o barramento do Pod:
     * Até 999m -> metros com unidade 1
     * Acima de 999m -> (metros / 100) com unidade 2 (km x 10)
     */
    public static int[] encodeDistance(int meters) {
        int value;
        int unit;
        if (meters <= 999) {
            value = Math.max(0, meters);
            unit = 1;
        } else {
            value = Math.max(0, meters / 100);
            unit = 2;
        }
        int v = Math.min(value, 0xFFFF);
        return new int[]{(v >> 8) & 0xFF, v & 0xFF, unit};
    }

    public static byte[] buildNavPacket(int maneuver, int distanceM) {
        int[] dist = encodeDistance(distanceM);
        byte[] pkt = new byte[18];
        pkt[0] = 0x10;
        pkt[1] = 0x11;
        pkt[2] = (byte) maneuver;
        pkt[3] = (byte) dist[0];
        pkt[4] = (byte) dist[1];
        pkt[5] = (byte) dist[2];
        pkt[6] = (byte) distanceIntensity(distanceM, false);
        pkt[7] = (byte) 0xFF; // sem próxima manobra
        pkt[8] = (byte) 0xFF;
        pkt[9] = (byte) 0xFF;
        pkt[10] = 0x41;
        pkt[11] = (byte) 0xFF;
        pkt[12] = (byte) 0xFF;
        pkt[13] = (byte) 0xFF;
        return withCrc(pkt);
    }

    /**
     * Pacote de Navegação Completo com intensidade, próxima manobra e ETA
     */
    public static byte[] buildNavFullPacket(int iconCode, int distanceVal, int distanceUnit, int byte6Intensity, int nextIconCode, int etaHour, int etaMinute) {
        byte[] pkt = new byte[18];
        pkt[0] = 0x10;
        pkt[1] = 0x11;
        pkt[2] = (byte) iconCode;
        pkt[3] = (byte) ((distanceVal >> 8) & 0xFF);
        pkt[4] = (byte) (distanceVal & 0xFF);
        pkt[5] = (byte) distanceUnit;
        pkt[6] = (byte) byte6Intensity;
        pkt[7] = (byte) (nextIconCode >= 0 ? nextIconCode : 0xFF);
        pkt[8] = (byte) 0xFF;
        pkt[9] = (byte) 0xFF;
        pkt[10] = 0x41;

        if (etaHour >= 0 && etaHour <= 23 && etaMinute >= 0 && etaMinute <= 59) {
            pkt[11] = (byte) etaHour;
            pkt[12] = (byte) etaMinute;
            pkt[13] = 0x00;
        } else {
            pkt[11] = (byte) 0xFF;
            pkt[12] = (byte) 0xFF;
            pkt[13] = (byte) 0xFF;
        }

        return withCrc(pkt);
    }

    /**
     * Pacote do Modo Cruzeiro: Velocímetro Digital Emulado com hodômetro na linha inferior.
     * Como o firmware do Tripper Pod não possui tela nativa de 'km/h', usamos o campo
     * de metragem com unidade inteira (m) para exibir a velocidade numérica em tempo real.
     */
    public static byte[] buildSpeedometerPacket(int speedKmH, float tripDistanceKm) {
        byte[] pkt = new byte[18];
        pkt[0] = 0x10;
        pkt[1] = 0x11;
        pkt[2] = (byte) MANEUVER_STRAIGHT; // 0x09 Seta para cima (direção de cruzeiro)
        pkt[3] = (byte) ((speedKmH >> 8) & 0xFF);
        pkt[4] = (byte) (speedKmH & 0xFF);
        pkt[5] = 0x01; // Unidade 'm' no visor métrico
        pkt[6] = 0x00; // Modo dia / anel apagado em cruzeiro
        pkt[7] = (byte) 0xFF;
        pkt[8] = (byte) 0xFF;
        pkt[9] = (byte) 0xFF;
        pkt[10] = 0x41;

        // Se houver distância percorrida na viagem, projeta na linha inferior (ex: 12.4 km)
        int tripTenths = Math.round(tripDistanceKm * 10.0f);
        if (tripTenths > 0 && tripTenths < 9999) {
            pkt[11] = (byte) ((tripTenths >> 8) & 0xFF);
            pkt[12] = (byte) (tripTenths & 0xFF);
            pkt[13] = 0x02; // Unidade 2 = km x 10
        } else {
            pkt[11] = (byte) 0xFF;
            pkt[12] = (byte) 0xFF;
            pkt[13] = (byte) 0xFF;
        }

        return withCrc(pkt);
    }

    /** Pacote de telas especiais (ex: 0x45 Rota Iniciada, 0x1C Recalculando) */
    public static byte[] buildScreenPacket(int screenCode, boolean night) {
        byte[] pkt = new byte[18];
        pkt[0] = 0x10;
        pkt[1] = 0x11;
        pkt[2] = (byte) screenCode;
        pkt[6] = (byte) (night ? 1 : 0);
        pkt[7] = (byte) 0xFF;
        pkt[8] = (byte) 0xFF;
        pkt[9] = (byte) 0xFF;
        pkt[10] = 0x41;
        pkt[11] = (byte) 0xFF;
        pkt[12] = (byte) 0xFF;
        pkt[13] = (byte) 0xFF;
        return withCrc(pkt);
    }

    public static byte[] buildCallIconPacket() {
        byte[] pkt = new byte[18];
        pkt[0] = 0x40;
        pkt[1] = 0x05;
        return withCrc(pkt);
    }

    /** Mapeamento de nomes de ícones conhecidos */
    public static String getIconName(int code) {
        int c = code & 0xFF;
        switch (c) {
            case 0x00: return "Destino (Chegada)";
            case 0x01: return "Destino à Esquerda";
            case 0x02: return "Destino à Direita";
            case 0x03: return "Fusão à Direita (Merge Right)";
            case 0x04: return "Fusão à Esquerda (Merge Left)";
            case 0x05: return "Bifurcação à Direita (Fork Right)";
            case 0x06: return "Bifurcação à Esquerda (Fork Left)";
            case 0x07: return "Saída/Rampa à Direita";
            case 0x08: return "Saída/Rampa à Esquerda";
            case 0x09: return "Seguir Reto (Cruzeiro)";
            case 0x0A: return "Rotatória (Horário) Base";
            case 0x0B: return "Rotatória (1ª Saída - Horário)";
            case 0x0C: return "Rotatória (2ª Saída - Horário)";
            case 0x0D: return "Rotatória (3ª Saída - Horário)";
            case 0x0E: return "Rotatória (4ª Saída - Horário)";
            case 0x0F: return "Rotatória (5ª Saída - Horário)";
            case 0x10: return "Rotatória (6ª Saída - Horário)";
            case 0x11: return "Rotatória (7ª Saída - Horário)";
            case 0x12: return "Rotatória (8ª Saída - Horário)";
            case 0x13: return "Rotatória (9ª Saída - Horário)";
            case 0x14: return "Virar à Esquerda";
            case 0x15: return "Virar à Direita";
            case 0x16: return "Curva Acentuada à Esquerda";
            case 0x17: return "Curva Acentuada à Direita";
            case 0x18: return "Curva Suave à Esquerda";
            case 0x19: return "Curva Suave à Direita";
            case 0x1A: return "Retorno (Horário)";
            case 0x1B: return "Fusão de Pista";
            case 0x1C: return "Recalculando Rota (Carregando)";
            case 0x1D: return "Entrada de Rampa Direita";
            case 0x1E: return "Entrada de Rampa Esquerda";
            case 0x1F: return "Entrada Suave Direita";
            case 0x20: return "Entrada Suave Esquerda";
            case 0x21: return "Entrada Acentuada Direita";
            case 0x22: return "Entrada Acentuada Esquerda";
            case 0x27: return "Mantenha à Direita (Keep Right)";
            case 0x28: return "Mantenha à Esquerda (Keep Left)";
            case 0x2B: return "Manter Faixa Esquerda (Seta Pequena)";
            case 0x2C: return "Manter Faixa Direita (Seta Pequena)";
            case 0x2D: return "Saída de Rodovia à Direita";
            case 0x2E: return "Saída de Rodovia à Esquerda";
            case 0x2F: return "Mantenha Direita (Sharp)";
            case 0x30: return "Mantenha Esquerda (Sharp)";
            case 0x31: return "Rotatória Anti-Horária (Base Brasil)";
            case 0x32: return "Rotatória 1ª Saída (Anti-Horária)";
            case 0x33: return "Rotatória 2ª Saída (Anti-Horária)";
            case 0x34: return "Rotatória 3ª Saída (Anti-Horária)";
            case 0x35: return "Rotatória 4ª Saída (Anti-Horária)";
            case 0x36: return "Rotatória 5ª Saída (Anti-Horária)";
            case 0x37: return "Rotatória 6ª Saída (Anti-Horária)";
            case 0x38: return "Rotatória 7ª Saída (Anti-Horária)";
            case 0x39: return "Rotatória 8ª Saída (Anti-Horária)";
            case 0x3A: return "Rotatória 9ª Saída (Anti-Horária)";
            case 0x3C: return "Radar / Alerta de Velocidade";
            case 0x3D: return "Retorno Anti-Horário (Brasil)";
            case 0x3E: return "Balsa (Barco)";
            case 0x3F: return "Trem / Ferry";
            case 0x41: return "Modo Bússola 360°";
            case 0x42: return "Sem Sinal de Dados (No Data)";
            case 0x44: return "Bateria Fraca";
            case 0x45: return "Rota Iniciada";
            case 0xFF: return "Em Branco (Blank)";
            default: return "Opcode 0x" + Integer.toHexString(c).toUpperCase(Locale.US);
        }
    }
}
