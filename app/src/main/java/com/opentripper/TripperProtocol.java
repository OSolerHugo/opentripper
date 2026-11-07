package com.opentripper;

import java.nio.charset.StandardCharsets;
import java.util.Calendar;
import java.util.Locale;

public final class TripperProtocol {

    private TripperProtocol() {}

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

    // Pacotes fixos
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

    public static byte[] buildCompassPacket(byte direction) {
        byte[] buf = new byte[18];
        buf[0] = 0x10;
        buf[1] = 0x11;
        buf[2] = 0x41;
        buf[6] = 0x00; // dia
        buf[7] = (byte) 0xFF;
        buf[11] = (byte) 0xFF;
        buf[12] = (byte) 0xFF;
        buf[13] = (byte) 0xFF;
        buf[14] = direction;
        return withCrc(buf);
    }

    public static byte[] buildNavPacket(int maneuver, int distanceM) {
        int distVal = distanceM <= 999 ? distanceM : (distanceM / 100);
        int distUnit = distanceM <= 999 ? 1 : 2;

        byte[] pkt = new byte[18];
        pkt[0] = 0x10;
        pkt[1] = 0x11;
        pkt[2] = (byte) maneuver;
        pkt[3] = (byte) ((distVal >> 8) & 0xFF);
        pkt[4] = (byte) (distVal & 0xFF);
        pkt[5] = (byte) distUnit;
        pkt[6] = 0x00; // dia
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
     * Pacote de Navegação Completo com suporte a ETA
     */
    public static byte[] buildNavFullPacket(int iconCode, int distanceVal, int distanceUnit, int etaHour, int etaMinute) {
        byte[] pkt = new byte[18];
        pkt[0] = 0x10;
        pkt[1] = 0x11;
        pkt[2] = (byte) iconCode;
        pkt[3] = (byte) ((distanceVal >> 8) & 0xFF);
        pkt[4] = (byte) (distanceVal & 0xFF);
        pkt[5] = (byte) distanceUnit;
        pkt[6] = 0x00; // dia
        pkt[7] = (byte) 0xFF;
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
     * Pacote do Modo Cruzeiro: Velocímetro Digital com hodômetro na linha inferior
     */
    public static byte[] buildSpeedometerPacket(int speedKmH, float tripDistanceKm) {
        byte[] pkt = new byte[18];
        pkt[0] = 0x10;
        pkt[1] = 0x11;
        pkt[2] = 0x09; // Seta para cima (direção de cruzeiro)
        pkt[3] = (byte) ((speedKmH >> 8) & 0xFF);
        pkt[4] = (byte) (speedKmH & 0xFF);
        pkt[5] = 0x01; // Unidade inteira direta (ex: 45)
        pkt[6] = 0x00; // Modo dia
        pkt[7] = (byte) 0xFF;
        pkt[8] = (byte) 0xFF;
        pkt[9] = (byte) 0xFF;
        pkt[10] = 0x41;

        // Se houver distância percorrida na viagem, projeta na linha inferior
        int tripTenths = Math.round(tripDistanceKm * 10.0f);
        if (tripTenths > 0 && tripTenths < 9999) {
            pkt[11] = (byte) ((tripTenths >> 8) & 0xFF);
            pkt[12] = (byte) (tripTenths & 0xFF);
            pkt[13] = 0x02; // km x 10
        } else {
            pkt[11] = (byte) 0xFF;
            pkt[12] = (byte) 0xFF;
            pkt[13] = (byte) 0xFF;
        }

        return withCrc(pkt);
    }

    public static byte[] buildCallIconPacket() {
        byte[] pkt = new byte[18];
        pkt[0] = 0x40;
        pkt[1] = 0x05;
        return withCrc(pkt);
    }

    /** Mapeamento de nomes de ícones já conhecidos para apoiar a exploração */
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
            case 0x09: return "Seguir Reto";
            case 0x0A: return "Rotatória (Horário) Base";
            case 0x0B: return "Rotatória (1ª Saída)";
            case 0x0C: return "Rotatória (2ª Saída)";
            case 0x0D: return "Rotatória (3ª Saída)";
            case 0x0E: return "Rotatória (4ª Saída)";
            case 0x0F: return "Rotatória (5ª Saída)";
            case 0x10: return "Rotatória (6ª Saída)";
            case 0x11: return "Rotatória (7ª Saída)";
            case 0x12: return "Rotatória (8ª Saída)";
            case 0x13: return "Rotatória (9ª Saída)";
            case 0x14: return "Virar à Esquerda";
            case 0x15: return "Virar à Direita";
            case 0x16: return "Curva Acentuada à Esquerda";
            case 0x17: return "Curva Acentuada à Direita";
            case 0x18: return "Curva Suave / Mantenha Esquerda";
            case 0x19: return "Curva Suave / Mantenha Direita";
            case 0x1A: return "Retorno (Horário)";
            case 0x1B: return "Fusão de Pista";
            case 0x1C: return "Recalculando Rota";
            case 0x1D: return "Entrada de Rampa Direita";
            case 0x1E: return "Entrada de Rampa Esquerda";
            case 0x1F: return "Entrada Suave Direita";
            case 0x20: return "Entrada Suave Esquerda";
            case 0x21: return "Entrada Acentuada Direita";
            case 0x22: return "Entrada Acentuada Esquerda";
            case 0x27: return "Saída Suave Direita";
            case 0x28: return "Saída Suave Esquerda";
            case 0x2B: return "Manter Faixa Esquerda";
            case 0x2C: return "Manter Faixa Direita";
            case 0x2D: return "Saída Acentuada Direita";
            case 0x2E: return "Saída Acentuada Esquerda";
            case 0x2F: return "Mantenha Direita (Sharp)";
            case 0x30: return "Mantenha Esquerda (Sharp)";
            case 0x31: return "Rotatória (Anti-horário) Base";
            case 0x32: return "Rotatória 1ª Saída";
            case 0x33: return "Rotatória 2ª Saída";
            case 0x34: return "Rotatória 3ª Saída";
            case 0x35: return "Rotatória 4ª Saída";
            case 0x36: return "Rotatória 5ª Saída";
            case 0x37: return "Rotatória 6ª Saída";
            case 0x38: return "Rotatória 7ª Saída";
            case 0x39: return "Rotatória 8ª Saída";
            case 0x3A: return "Rotatória 9ª Saída";
            case 0x3C: return "Partida / Ícone de Radar";
            case 0x3D: return "Retorno (Anti-horário)";
            case 0x3E: return "Balsa (Barco)";
            case 0x3F: return "Trem / Ferry";
            case 0x41: return "Modo Bússola";
            case 0x42: return "Sem Sinal de Dados (No Data)";
            case 0x44: return "Bateria Fraca";
            case 0x45: return "Rota Iniciada";
            case 0xFF: return "Em Branco (Blank)";
            default: return "❓ NÃO DOCUMENTADO (Novo!)";
        }
    }
}
