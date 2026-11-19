package ink.pengfei.douyin.net;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.RSAPublicKeySpec;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Builds the x-tt-session-dtrait header: d0_<b64(RSA(key||iv))>_<b64(AES-CBC(payload))>. */
final class Dtrait {
    private static final String MODULUS =
            "e3e76fbd8eee33ea2973e9cb1bf0ee992785a3b6957089b52cefa6508ad6a6b4733f46b51a9110e2ab4ef4c45ffe5c04231dfcfb8059e17386cdebd5b6e52f7c839413b013b505bb14a25fe4f9d22c223afe4d2f9751422d44959843faa8f26b223678cd7a2a8b7cae359fb5a1d6d103ffc61e07204b2cb268bab3c239295865e8b8c626bd8b12a6993f7efd8458006cb4f37159ae0438109a75350f917350efa34110ff71cdf252db4c220efd684bfbcabeb494a567099dace4901bea41b6c0a956d015317f0cf8a4a69bba653ea51885ef24a0ab79e1621c0daa2bab52d7c4965475bd011cf5fa98c1d5ea88218b68856a53d81e34382e0ef2d55e0409db89";
    private static final String EXPONENT = "010001";

    private static final String PAYLOAD =
            "[[\"str_14\",3419490229],[\"str_15\",2525084665],[\"str_16\",785994595],[\"str_19\",2910269993],"
            + "[\"str_29\",3870149837],[\"str_27\",2802661099],[\"str_28\",1068634537],[\"str_30\",140181531],"
            + "[\"str_31\",4255047500],[\"str_32\",3927047830],[\"str_3\",3757789876],[\"str_2\",2861649864],"
            + "[\"str_11\",2990008912],[\"str_12\",4273337803],[\"str_33\",4255047500],[\"str_34\",3530670207]]";

    private static final char[] B64 =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();
    private static final SecureRandom RNG = new SecureRandom();

    private Dtrait() {}

    static String build() {
        try {
            byte[] key = new byte[16];
            byte[] iv = new byte[16];
            RNG.nextBytes(key);
            RNG.nextBytes(iv);

            PublicKey publicKey = KeyFactory.getInstance("RSA").generatePublic(
                    new RSAPublicKeySpec(new BigInteger(MODULUS, 16), new BigInteger(EXPONENT, 16)));
            Cipher rsa = Cipher.getInstance("RSA/ECB/PKCS1Padding");
            rsa.init(Cipher.ENCRYPT_MODE, publicKey);
            byte[] material = new byte[32];
            System.arraycopy(key, 0, material, 0, 16);
            System.arraycopy(iv, 0, material, 16, 16);
            byte[] wrapped = rsa.doFinal(material);

            Cipher aes = Cipher.getInstance("AES/CBC/PKCS5Padding");
            aes.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
            byte[] body = aes.doFinal(PAYLOAD.getBytes(StandardCharsets.UTF_8));
            return "d0_" + b64(wrapped) + "_" + b64(body);
        } catch (Exception e) {
            return "";
        }
    }

    private static String b64(byte[] data) {
        StringBuilder sb = new StringBuilder((data.length + 2) / 3 * 4);
        int i = 0;
        while (i + 3 <= data.length) {
            int n = ((data[i] & 0xff) << 16) | ((data[i + 1] & 0xff) << 8) | (data[i + 2] & 0xff);
            sb.append(B64[(n >> 18) & 63]).append(B64[(n >> 12) & 63])
                    .append(B64[(n >> 6) & 63]).append(B64[n & 63]);
            i += 3;
        }
        int rest = data.length - i;
        if (rest == 1) {
            int n = (data[i] & 0xff) << 16;
            sb.append(B64[(n >> 18) & 63]).append(B64[(n >> 12) & 63]).append("==");
        } else if (rest == 2) {
            int n = ((data[i] & 0xff) << 16) | ((data[i + 1] & 0xff) << 8);
            sb.append(B64[(n >> 18) & 63]).append(B64[(n >> 12) & 63])
                    .append(B64[(n >> 6) & 63]).append('=');
        }
        return sb.toString();
    }
}
