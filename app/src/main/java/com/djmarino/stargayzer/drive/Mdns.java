package com.djmarino.stargayzer.drive;

import java.io.ByteArrayOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Looks up a .local name (stargayzer.local) with one multicast DNS question, without Android's resolver.
 * Sent from an ordinary port, it is a "legacy unicast" query (RFC 6762 section 6.7): the Pi's avahi answers
 * straight back to this socket, so no multicast lock is needed. Oct 19 2026: after a Wi-Fi reconnect the X10's
 * own resolver stopped answering .local names (the web view and Drive's retry both failed) while the Pi still
 * answered here; a Wi-Fi reconnect cleared it.
 */
final class Mdns {
    private Mdns() {}

    /** The IPv4 address for host, or null when nothing answers within timeoutMs. */
    static InetAddress resolve(String host, int timeoutMs) {
        try (DatagramSocket s = new DatagramSocket()) {
            s.setSoTimeout(timeoutMs);
            byte[] q = question(host);
            s.send(new DatagramPacket(q, q.length, InetAddress.getByName("224.0.0.251"), 5353));
            byte[] buf = new byte[1500];
            long end = System.currentTimeMillis() + timeoutMs;
            while (System.currentTimeMillis() < end) {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                s.receive(p);
                InetAddress a = firstA(buf, p.getLength());
                if (a != null) return a;
            }
        } catch (Exception ignored) {
            // timeout, no network: not found
        }
        return null;
    }

    private static byte[] question(String host) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] head = {0x53, 0x47, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0};   // id "SG", standard query, one question
        o.write(head, 0, head.length);
        for (String label : host.split("\\.")) {
            byte[] b = label.getBytes(StandardCharsets.US_ASCII);
            o.write(b.length);
            o.write(b, 0, b.length);
        }
        o.write(0);
        o.write(0); o.write(1);   // type A
        o.write(0); o.write(1);   // class IN
        return o.toByteArray();
    }

    private static int u16(byte[] b, int i) { return ((b[i] & 0xff) << 8) | (b[i + 1] & 0xff); }

    private static int skipName(byte[] b, int i, int n) {
        while (i < n) {
            int c = b[i] & 0xff;
            if (c == 0) return i + 1;
            if ((c & 0xC0) == 0xC0) return i + 2;
            i += c + 1;
        }
        return n;
    }

    private static InetAddress firstA(byte[] b, int n) throws Exception {
        if (n < 12 || (b[2] & 0x80) == 0) return null;   // not a response
        int qd = u16(b, 4), an = u16(b, 6), i = 12;
        for (int k = 0; k < qd && i < n; k++) i = skipName(b, i, n) + 4;
        for (int k = 0; k < an && i < n; k++) {
            i = skipName(b, i, n);
            if (i + 10 > n) return null;
            int type = u16(b, i), len = u16(b, i + 8);
            i += 10;
            if (type == 1 && len == 4 && i + 4 <= n) return InetAddress.getByAddress(Arrays.copyOfRange(b, i, i + 4));
            i += len;
        }
        return null;
    }
}
