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

    public static byte[] buildCallIconPacket() {
        byte[] pkt = new byte[18];
        pkt[0] = 0x40;
        pkt[1] = 0x05;
        return withCrc(pkt);
    }
}
