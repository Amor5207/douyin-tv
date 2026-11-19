package ink.pengfei.douyin.util;

/** SM3 256-bit hash, byte-compatible with the Douyin web signer. */
public final class Sm3 {
    private static final int[] IV = {
            0x7380166f, 0x4914b2b9, 0x172442d7, 0xda8a0600,
            0xa96f30bc, 0x163138aa, 0xe38dee4d, 0xb0fb0e4e
    };

    private Sm3() {}

    public static byte[] hash(byte[] data) {
        int[] reg = IV.clone();
        long bitLen = (long) data.length * 8L;
        int total = ((data.length + 8) / 64 + 1) * 64;
        byte[] buf = new byte[total];
        System.arraycopy(data, 0, buf, 0, data.length);
        buf[data.length] = (byte) 0x80;
        for (int i = 0; i < 8; i++) {
            buf[total - 8 + i] = (byte) (bitLen >>> (56 - 8 * i));
        }
        int[] w = new int[68];
        int[] w1 = new int[64];
        for (int off = 0; off < total; off += 64) {
            for (int i = 0; i < 16; i++) {
                int p = off + i * 4;
                w[i] = ((buf[p] & 0xff) << 24) | ((buf[p + 1] & 0xff) << 16)
                        | ((buf[p + 2] & 0xff) << 8) | (buf[p + 3] & 0xff);
            }
            for (int i = 16; i < 68; i++) {
                int a = w[i - 16] ^ w[i - 9] ^ Integer.rotateLeft(w[i - 3], 15);
                a = a ^ Integer.rotateLeft(a, 15) ^ Integer.rotateLeft(a, 23);
                w[i] = a ^ Integer.rotateLeft(w[i - 13], 7) ^ w[i - 6];
            }
            for (int i = 0; i < 64; i++) w1[i] = w[i] ^ w[i + 4];

            int a = reg[0], b = reg[1], c = reg[2], d = reg[3];
            int e = reg[4], f = reg[5], g = reg[6], h = reg[7];
            for (int j = 0; j < 64; j++) {
                int t = j < 16 ? 0x79cc4519 : 0x7a879d8a;
                int ss1 = Integer.rotateLeft(Integer.rotateLeft(a, 12) + e + Integer.rotateLeft(t, j), 7);
                int ss2 = ss1 ^ Integer.rotateLeft(a, 12);
                int ff = j < 16 ? (a ^ b ^ c) : ((a & b) | (a & c) | (b & c));
                int gg = j < 16 ? (e ^ f ^ g) : ((e & f) | (~e & g));
                int tt1 = ff + d + ss2 + w1[j];
                int tt2 = gg + h + ss1 + w[j];
                d = c;
                c = Integer.rotateLeft(b, 9);
                b = a;
                a = tt1;
                h = g;
                g = Integer.rotateLeft(f, 19);
                f = e;
                e = tt2 ^ Integer.rotateLeft(tt2, 9) ^ Integer.rotateLeft(tt2, 17);
            }
            reg[0] ^= a; reg[1] ^= b; reg[2] ^= c; reg[3] ^= d;
            reg[4] ^= e; reg[5] ^= f; reg[6] ^= g; reg[7] ^= h;
        }
        byte[] out = new byte[32];
        for (int i = 0; i < 8; i++) {
            int v = reg[i];
            out[i * 4] = (byte) (v >>> 24);
            out[i * 4 + 1] = (byte) (v >>> 16);
            out[i * 4 + 2] = (byte) (v >>> 8);
            out[i * 4 + 3] = (byte) v;
        }
        return out;
    }

    public static byte[] twice(byte[] data) {
        return hash(hash(data));
    }
}
