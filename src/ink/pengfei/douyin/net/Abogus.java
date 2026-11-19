package ink.pengfei.douyin.net;

import ink.pengfei.douyin.util.Sm3;

import java.nio.charset.StandardCharsets;

/**
 * Pure-Java a_bogus signer for Douyin web (bdms 1.0.1.20).
 * No WebView, no native libs.
 */
public final class Abogus {
    public static final String S3 = "ckdp1h4ZKsUB80/Mfvw36XIgR25+WQAlEi7NLboqYTOPuzmFjJnryx9HVGDaStCe";
    public static final String S4 = "Dkdpgh2ZmsQB80/MfvV36XI1R45-WUAlEixNLwoqYTOPuzKFjJnry79HbGcaStCe";
    public static final String SALT = "dhzx";
    public static final int AID = 6383;
    public static final int PAGE_ID = 41079;
    public static final String SDK_VERSION = "1.0.1.20";

    private static final int[] TLIST = {
            34, 44, 56, 61, 73, 29, 70, 45, 35, 49,
            38, 66, 51, 68, 28, 48, 64, 47, 30, 71,
            26, 55, 31, 69, 59, 40, 62, 63, 27, 72,
            41, 74, 57, 52, 42, 39, 33, 67, 53, 43,
            65, 46, 36, 24, 60, 32, 79, 80, 84, 85
    };

    private Abogus() {}

    public static byte[] rc4(byte[] key, byte[] text) {
        int[] s = new int[256];
        int[] k = new int[256];
        for (int i = 0; i < 256; i++) {
            s[i] = 255 - i;
            k[i] = key[i % key.length] & 0xff;
        }
        int j = 0;
        for (int i = 0; i < 256; i++) {
            j = (j * s[i] + j + k[i]) % 256;
            int t = s[i]; s[i] = s[j]; s[j] = t;
        }
        byte[] out = new byte[text.length];
        int i = 0;
        j = 0;
        for (int n = 0; n < text.length; n++) {
            i = (i + 1) % 256;
            j = (j + s[i]) % 256;
            int t = s[i]; s[i] = s[j]; s[j] = t;
            out[n] = (byte) (text[n] ^ s[(s[i] + s[j]) % 256]);
        }
        return out;
    }

    public static String base64(byte[] data, String table) {
        StringBuilder sb = new StringBuilder(data.length * 4 / 3 + 4);
        int i = 0;
        while (i < data.length) {
            int c1 = data[i++] & 0xff;
            int c2 = i < data.length ? data[i++] & 0xff : -1;
            int c3 = i < data.length ? data[i++] & 0xff : -1;
            int cc = c1 << 16;
            if (c2 >= 0) cc |= c2 << 8;
            if (c3 >= 0) cc |= c3;
            sb.append(table.charAt((cc & 16515072) >> 18));
            sb.append(table.charAt((cc & 258048) >> 12));
            if (c2 >= 0) sb.append(table.charAt((cc & 4032) >> 6));
            if (c3 >= 0) sb.append(table.charAt(cc & 63));
            if (c2 < 0) sb.append('=');
            if (c3 < 0) sb.append('=');
        }
        return sb.toString();
    }

    private static byte[] u88Transform(int[] u88, int[] x) {
        byte[] out = new byte[((u88.length + 2) / 3) * 4];
        int oi = 0, xi = 0, i = 0;
        while (i < u88.length) {
            if (i + 2 < u88.length) {
                int r = x[xi++] & 0xff;
                out[oi++] = (byte) ((r & 145) | (u88[i] & 110));
                out[oi++] = (byte) ((r & 66) | (u88[i + 1] & 189));
                out[oi++] = (byte) ((r & 44) | (u88[i + 2] & 211));
                out[oi++] = (byte) ((u88[i] & 145) | (u88[i + 1] & 66) | (u88[i + 2] & 44));
            } else {
                out[oi++] = (byte) u88[i];
                if (i + 1 < u88.length) out[oi++] = (byte) u88[i + 1];
            }
            i += 3;
        }
        if (oi == out.length) return out;
        byte[] trim = new byte[oi];
        System.arraycopy(out, 0, trim, 0, oi);
        return trim;
    }

    /** Builds the 96-byte U88 payload (excluding u86). */
    private static int[] buildU88(String query, String form, String userAgent,
                                 long now, long enterPageTs, String env, int[] u86) {
        boolean salted = query.endsWith(SALT);
        byte[] u18 = Sm3.twice((salted ? query : query + SALT).getBytes(StandardCharsets.UTF_8));
        byte[] u19 = Sm3.twice((form + SALT).getBytes(StandardCharsets.UTF_8));

        byte[] uaKey = {0, (byte) 129, 14};
        byte[] rc = rc4(uaKey, userAgent.trim().getBytes(StandardCharsets.UTF_8));
        String uaEncode = base64(rc, S3);
        byte[] u21 = Sm3.hash(uaEncode.getBytes(StandardCharsets.UTF_8));

        int[] ver = new int[4];
        String[] seg = SDK_VERSION.split("\\.");
        for (int i = 0; i < seg.length && i < 4; i++) ver[i] = parseIntSafe(seg[i]);

        int u16 = 129;
        long ink = now - 1;

        int[] u = new int[88];
        u[8] = PAGE_ID;
        u[9] = AID;
        u[12] = 3;
        u[14] = (int) now;
        u[16] = u16;
        u[17] = 14;
        u[22] = (int) ink;
        u[24] = 41;
        u[26] = (int) ((now - 1721836800000L) / 1000L / 60L / 60L / 24L / 14L);
        u[27] = 6;
        u[28] = (int) ((now - enterPageTs + 3) & 255);
        u[29] = (int) (now & 255);
        u[30] = (int) ((now >> 8) & 255);
        u[31] = (int) ((now >> 16) & 255);
        u[32] = (int) ((now >> 24) & 255);
        u[33] = (int) ((now >> 32) & 255);
        u[34] = (int) ((now >> 40) & 255);
        u[35] = u16 & 255;
        u[36] = (u16 >> 8) & 255;
        u[38] = 75;
        u[39] = 0;
        u[44] = u[17] & 255;
        u[45] = (u[17] >> 8) & 255;
        u[46] = (u[17] >> 16) & 255;
        u[47] = (u[17] >> 24) & 255;
        u[48] = u18[9] & 0xff;
        u[49] = u18[18] & 0xff;
        u[51] = 11;
        u[52] = u19[10] & 0xff;
        u[53] = u19[19] & 0xff;
        u[55] = u19[4] & 0xff;
        u[56] = u21[11] & 0xff;
        u[57] = u21[21] & 0xff;
        u[59] = 12;
        u[60] = (int) (ink & 255);
        u[61] = (int) ((ink >> 8) & 255);
        u[62] = (int) ((ink >> 16) & 255);
        u[63] = (int) ((ink >> 24) & 255);
        u[64] = (int) ((ink >> 32) & 255);
        u[65] = (int) ((ink >> 40) & 255);
        u[66] = u[12];
        u[67] = PAGE_ID & 255;
        u[68] = (PAGE_ID >> 8) & 255;
        u[69] = (PAGE_ID >> 16) & 255;
        u[70] = (PAGE_ID >> 24) & 255;
        u[71] = AID & 255;
        u[72] = (AID >> 8) & 255;
        u[73] = (AID >> 16) & 255;
        u[74] = (AID >> 24) & 255;

        int[] envCodes = new int[env.length()];
        for (int i = 0; i < env.length(); i++) envCodes[i] = env.charAt(i) & 0xff;
        u[78] = envCodes.length;
        u[79] = u[78] & 255;
        u[80] = (u[78] >> 8) & 255;
        String tail = ((now + 3) & 255) + ",";
        int[] tailCodes = new int[tail.length()];
        for (int i = 0; i < tail.length(); i++) tailCodes[i] = tail.charAt(i) & 0xff;
        u[83] = tailCodes.length;
        u[84] = u[83] & 255;
        u[85] = (u[83] >> 8) & 255;

        int x = u86[0];
        for (int i = 1; i < 8; i++) x ^= u86[i];
        x ^= u[24] ^ u[26] ^ u[27] ^ u[28] ^ u[29] ^ u[30] ^ u[31] ^ u[32] ^ u[33] ^ u[34];
        x ^= u[35] ^ u[36] ^ u[38] ^ u[39] ^ u[40] ^ u[41] ^ u[42] ^ u[43] ^ u[44] ^ u[45];
        x ^= u[46] ^ u[47] ^ u[48] ^ u[49] ^ u[51] ^ u[52] ^ u[53] ^ u[55] ^ u[56] ^ u[57];
        x ^= u[59] ^ u[60] ^ u[61] ^ u[62] ^ u[63] ^ u[64] ^ u[65] ^ u[66] ^ u[67] ^ u[68];
        x ^= u[69] ^ u[70] ^ u[71] ^ u[72] ^ u[73] ^ u[74] ^ u[79] ^ u[80] ^ u[84] ^ u[85];
        u[87] = x & 255;

        int[] out = new int[96];
        int oi = 0;
        for (int idx : TLIST) out[oi++] = u[idx];
        for (int i = 0; i < envCodes.length; i++) out[oi++] = envCodes[i];
        for (int i = 0; i < tailCodes.length; i++) out[oi++] = tailCodes[i];
        out[oi] = u[87];
        return out;
    }

    /** Full a_bogus. head is the 4 framing bytes, x the 32 transform randoms. */
    private static String generate(String query, String form, String userAgent, long now,
                                   long enterPageTs, String env, int[] u86, int[] head, int[] x) {
        int[] u88 = buildU88(query, form, userAgent, now, enterPageTs, env, u86);
        byte[] body = u88Transform(u88, x);
        byte[] plain = new byte[8 + body.length];
        for (int i = 0; i < 8; i++) plain[i] = (byte) u86[i];
        System.arraycopy(body, 0, plain, 8, body.length);
        byte[] crypt = rc4(new byte[]{(byte) 211}, plain);
        byte[] framed = new byte[4 + crypt.length];
        for (int i = 0; i < 4; i++) framed[i] = (byte) head[i];
        System.arraycopy(crypt, 0, framed, 4, crypt.length);
        return base64(framed, S4);
    }

    public static final String ENV = "1536|747|1536|864|1536|834|1536|864|Win32";
    private static final java.security.SecureRandom RNG = new java.security.SecureRandom();

    /** Signs a raw query string (without a_bogus) with fresh randoms. */
    public static String sign(String query) {
        return sign(query, Http.UA);
    }

    public static String sign(String query, String ua) {
        return sign(query, "", ua);
    }

    static String signForm(String query, String form) {
        return sign(query, form, Http.UA);
    }

    private static String sign(String query, String form, String ua) {
        int[] u86 = new int[8];
        int[] head = new int[4];
        int[] x = new int[32];
        for (int i = 0; i < 8; i++) u86[i] = RNG.nextInt(256);
        head[0] = 0x01 | (RNG.nextInt(256) & 0xaa);
        head[1] = 0x02 | (RNG.nextInt(256) & 0x55);
        head[2] = 0x50 | (RNG.nextInt(256) & 0x2a);
        head[3] = 0x02 | (RNG.nextInt(256) & 0x15);
        for (int i = 0; i < 32; i++) x[i] = RNG.nextInt(256);
        long now = System.currentTimeMillis();
        return generate(query, form, ua, now, now, ENV, u86, head, x);
    }

    private static int parseIntSafe(String s) {
        int v = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') break;
            v = v * 10 + (c - '0');
        }
        return v;
    }
}
