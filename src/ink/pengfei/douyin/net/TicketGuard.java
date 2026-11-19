package ink.pengfei.douyin.net;

import android.util.Base64;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Map;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Binds server-issued tickets to a device key and to the session that received them. */
final class TicketGuard {
    private static final String CLIENT_COOKIE = "bd_ticket_guard_client_data";
    private static final String SERVER_COOKIE = "bd_ticket_guard_server_data";
    private final CookieStore cookies;
    private JSONObject pending;
    private String serverCert;
    private String clientCert;

    TicketGuard(CookieStore cookies) {
        this.cookies = cookies;
    }

    synchronized void prepareLogin() throws Exception {
        pending = null;
        serverCert = null;
        clientCert = null;
        // Preserve any existing ticket_guard / server_data until a new QR login succeeds.
        JSONObject data = new JSONObject()
                .put("bd-ticket-guard-version", 2)
                .put("bd-ticket-guard-iteration-version", 1)
                .put("bd-ticket-guard-ree-public-key", publicPoint())
                .put("bd-ticket-guard-web-version", 2);
        cookies.put(CLIENT_COOKIE, Http.enc(base64(data.toString().getBytes(StandardCharsets.UTF_8))));
        cookies.put("bd_ticket_guard_client_web_domain", "2");
    }

    synchronized void capture(Http.Resp response) {
        String raw = response == null ? null : response.header("bd-ticket-guard-server-data");
        if (raw == null || raw.isEmpty()) raw = cookies.get(SERVER_COOKIE);
        JSONObject data = parseServerData(raw);
        if (data != null) {
            pending = data;
        }
    }

    private static JSONObject parseServerData(String raw) {
        if (raw == null || raw.isEmpty()) return null;
        try {
            String encoded = URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8");
            byte[] decoded = decodeBase64(encoded);
            if (decoded == null) return null;
            JSONObject data = new JSONObject(new String(decoded, StandardCharsets.UTF_8));
            if (!data.optString("ticket").isEmpty() && !data.optString("ts_sign").isEmpty()) {
                return data;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static byte[] decodeBase64(String encoded) {
        if (encoded == null || encoded.isEmpty()) return null;
        try {
            return Base64.decode(encoded, Base64.DEFAULT);
        } catch (Exception ignored) {
            try {
                String norm = encoded.replace('-', '+').replace('_', '/');
                return Base64.decode(norm, Base64.DEFAULT);
            } catch (Exception ignored2) {
                return null;
            }
        }
    }

    synchronized boolean hasTicket() {
        if (pending != null) return true;
        String saved = cookies.readTicket();
        if (saved != null && !saved.isEmpty()) return true;
        return parseServerData(cookies.get(SERVER_COOKIE)) != null;
    }

    synchronized boolean hasPendingTicket() {
        return pending != null;
    }

    synchronized boolean finishLogin() {
        if (pending == null) {
            pending = parseServerData(cookies.get(SERVER_COOKIE));
        }
        if (pending == null) return false;
        try {
            String session = sessionFingerprint();
            if (session.isEmpty()) return false;
            pending.put("session", session);
            pending.put("private_key", ecKey().optString("priv"));
            if (serverCert != null && !serverCert.isEmpty()) pending.put("server_cert", serverCert);
            if (clientCert != null && !clientCert.isEmpty()) pending.put("client_cert", clientCert);
            cookies.saveTicket(pending.toString());
            pending = null;
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    synchronized void clear() {
        pending = null;
        serverCert = null;
        clientCert = null;
        cookies.saveTicket(null);
    }

    synchronized void setCertBundle(String serverPem, String clientPem) {
        if (serverPem != null && !serverPem.isEmpty()) serverCert = serverPem;
        if (clientPem != null && !clientPem.isEmpty()) clientCert = clientPem;
        try {
            String saved = cookies.readTicket();
            if (saved != null) {
                JSONObject data = new JSONObject(saved);
                if (serverCert != null && !serverCert.isEmpty()) data.put("server_cert", serverCert);
                if (clientCert != null && !clientCert.isEmpty()) data.put("client_cert", clientCert);
                cookies.saveTicket(data.toString());
            }
        } catch (Exception ignored) {}
    }

    synchronized boolean needsCert() {
        boolean hasServer = serverCert != null && !serverCert.isEmpty();
        boolean hasClient = clientCert != null && !clientCert.isEmpty();
        if (hasServer && hasClient) return false;
        try {
            String saved = cookies.readTicket();
            if (saved == null) return true;
            JSONObject o = new JSONObject(saved);
            return o.optString("server_cert").isEmpty() || o.optString("client_cert").isEmpty();
        } catch (Exception e) {
            return true;
        }
    }

    /** Generates a PKCS#10 ECDSA-SHA256 CSR wrapped in PEM and Base64-encoded for get_client_cert. */
    synchronized String csrBase64() throws Exception {
        JSONObject key = ecKey();
        byte[] spki = Base64.decode(key.optString("spki"), Base64.DEFAULT);
        if (spki == null || spki.length == 0) {
            // Backfill SPKI from saved public point or regenerate EC keypair.
            ecKey();
            spki = Base64.decode(ecKey().getString("spki"), Base64.DEFAULT);
        }
        byte[] cnVal = "douyin_tv".getBytes(StandardCharsets.UTF_8);
        byte[] cnOid = new byte[]{0x06, 0x03, 0x55, 0x04, 0x03};
        byte[] atv = derTag(0x30, concatBytes(cnOid, derTag(0x0c, cnVal)));
        byte[] subject = derTag(0x30, derTag(0x31, atv));
        byte[] version = new byte[]{0x02, 0x01, 0x00};
        byte[] attrs = new byte[]{(byte) 0xa0, 0x00};
        byte[] cri = derTag(0x30, concatBytes(version, subject, spki, attrs));

        Signature sig = Signature.getInstance("SHA256withECDSA");
        sig.initSign(privateKey());
        sig.update(cri);
        byte[] derSig = sig.sign();

        byte[] sigAlg = derTag(0x30, new byte[]{
                0x06, 0x08, 0x2a, (byte) 0x86, 0x48, (byte) 0xce, 0x3d, 0x04, 0x03, 0x02});
        byte[] bitStr = derTag(0x03, concatBytes(new byte[]{0x00}, derSig));
        byte[] csrDer = derTag(0x30, concatBytes(cri, sigAlg, bitStr));

        String rawB64 = base64(csrDer);
        StringBuilder pem = new StringBuilder("-----BEGIN CERTIFICATE REQUEST-----\n");
        for (int i = 0; i < rawB64.length(); i += 64) {
            pem.append(rawB64, i, Math.min(rawB64.length(), i + 64)).append('\n');
        }
        pem.append("-----END CERTIFICATE REQUEST-----\n");
        return base64(pem.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] derTag(int tag, byte[] body) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(tag);
        int len = body.length;
        if (len < 128) {
            out.write(len);
        } else if (len < 256) {
            out.write(0x81);
            out.write(len);
        } else {
            out.write(0x82);
            out.write((len >> 8) & 0xff);
            out.write(len & 0xff);
        }
        out.write(body, 0, len);
        return out.toByteArray();
    }

    private static byte[] concatBytes(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) out.write(p, 0, p.length);
        return out.toByteArray();
    }

    synchronized Map<String, String> headers(String path) throws Exception {
        String saved = cookies.readTicket();
        JSONObject data = saved == null ? null : new JSONObject(saved);
        if (data == null) {
            JSONObject fromCookie = parseServerData(cookies.get(SERVER_COOKIE));
            if (fromCookie != null) {
                pending = fromCookie;
                finishLogin();
                saved = cookies.readTicket();
                if (saved != null) data = new JSONObject(saved);
            }
        }
        if (data == null) throw new IOException("当前登录会话缺少抖音凭证");
        String session = sessionFingerprint();
        if (session.isEmpty()) {
            throw new IOException("当前会话未登录，请重新登录");
        }
        if (!session.equals(data.optString("session"))) {
            // Session cookie rotated after login: re-bind stored ticket if cookie server_data exists.
            JSONObject refreshed = parseServerData(cookies.get(SERVER_COOKIE));
            if (refreshed != null) {
                pending = refreshed;
                finishLogin();
                data = new JSONObject(cookies.readTicket());
            } else {
                data.put("session", session);
                cookies.saveTicket(data.toString());
            }
        }
        String tsSign = data.getString("ts_sign");
        long now = System.currentTimeMillis() / 1000L;
        String payload = "ticket=" + data.getString("ticket") + "&path=" + path + "&timestamp=" + now;

        // 使用登录时通过 bd_ticket_guard_client_data 绑定的 ECDSA P-256 密钥签名 (sign_type=0)。
        // 抖音服务端校验 ASN.1 DER 格式（Base64 以 MEQC/MEUC 开头），Java SHA256withECDSA 原生输出即为 DER，不可转 raw。
        Signature signature = Signature.getInstance("SHA256withECDSA");
        signature.initSign(privateKey());
        signature.update(payload.getBytes(StandardCharsets.UTF_8));
        String reqSign = base64(signature.sign());

        JSONObject client = new JSONObject().put("ts_sign", tsSign)
                .put("req_content", "ticket,path,timestamp")
                .put("req_sign", reqSign).put("timestamp", now);
        Map<String, String> headers = publicHeaders(tsSign, "0");
        headers.put("bd-ticket-guard-client-data", base64(client.toString().getBytes(StandardCharsets.UTF_8)));
        return headers;
    }

    private static byte[] derToRaw(byte[] der) {
        try {
            int pos = 0;
            if ((der[pos++] & 0xff) != 0x30) return der;
            int len = der[pos++] & 0xff;
            if ((len & 0x80) != 0) pos += (len & 0x7f);
            byte[] out = new byte[64];
            for (int part = 0; part < 2; part++) {
                if ((der[pos++] & 0xff) != 0x02) return der;
                int ilen = der[pos++] & 0xff;
                int start = 0;
                while (start < ilen - 1 && der[pos + start] == 0) start++;
                int vlen = ilen - start;
                if (vlen > 32) return der;
                System.arraycopy(der, pos + start, out, part * 32 + 32 - vlen, vlen);
                pos += ilen;
            }
            return out;
        } catch (Exception e) {
            return der;
        }
    }

    synchronized Map<String, String> publicHeaders() {
        String saved = cookies.readTicket();
        String tsSign = "";
        try {
            if (saved != null) tsSign = new JSONObject(saved).optString("ts_sign");
        } catch (Exception ignored) {}
        return publicHeaders(tsSign, "0");
    }

    private Map<String, String> publicHeaders(String tsSign, String signType) {
        return Http.map("bd-ticket-guard-ree-public-key", publicPoint(),
                "bd-ticket-guard-version", "2",
                "bd-ticket-guard-iteration-version", "1",
                "bd-ticket-guard-web-version", tsSign.startsWith("ts.1") ? "1" : "2",
                "bd-ticket-guard-web-sign-type", signType);
    }

    /** ECDH shared secret -> HKDF-SHA256(empty salt, empty info, 32B), matching web SDK deriveEcdhKey. */
    private byte[] ecdhKey(JSONObject data) {
        try {
            String cert = serverCert;
            if ((cert == null || cert.isEmpty())) {
                cert = data.optString("server_cert");
            }
            if (cert == null || cert.isEmpty()) return null;
            byte[] raw = Base64.decode(cert.replaceAll("-----[^-]+-----|\\s", ""), Base64.DEFAULT);
            PublicKey serverPub;
            try {
                serverPub = CertificateFactory.getInstance("X.509")
                        .generateCertificate(new ByteArrayInputStream(raw))
                        .getPublicKey();
            } catch (Exception ignored) {
                serverPub = KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(raw));
            }
            KeyAgreement ka = KeyAgreement.getInstance("ECDH");
            ka.init(privateKey());
            ka.doPhase(serverPub, true);
            byte[] shared = ka.generateSecret();
            // HKDF-Extract(salt=zeros(32), ikm=shared) -> HKDF-Expand(prk, info=empty, len=32)
            Mac extract = Mac.getInstance("HmacSHA256");
            extract.init(new SecretKeySpec(new byte[32], "HmacSHA256"));
            byte[] prk = extract.doFinal(shared);
            Mac expand = Mac.getInstance("HmacSHA256");
            expand.init(new SecretKeySpec(prk, "HmacSHA256"));
            return expand.doFinal(new byte[]{1});
        } catch (Exception e) {
            return null;
        }
    }

    private String sessionFingerprint() throws Exception {
        String sid = cookies.get("sessionid");
        if (sid == null || sid.isEmpty()) sid = cookies.get("sessionid_ss");
        if (sid == null || sid.isEmpty()) return "";
        return base64(MessageDigest.getInstance("SHA-256").digest(sid.getBytes(StandardCharsets.UTF_8)));
    }

    /** Software EC P-256 keypair persisted in prefs (AndroidKeyStore lacks ECDH). */
    private synchronized JSONObject ecKey() throws Exception {
        String raw = cookies.readEcKey();
        if (raw != null) {
            try {
                JSONObject existing = new JSONObject(raw);
                String priv = existing.optString("priv");
                String pub = existing.optString("pub");
                if (!priv.isEmpty() && !pub.isEmpty()) {
                    if (existing.optString("spki").isEmpty()) {
                        byte[] point = Base64.decode(pub, Base64.DEFAULT);
                        if (point.length == 65) {
                            byte[] prefix = {
                                    0x30, 0x59, 0x30, 0x13, 0x06, 0x07, 0x2a, (byte) 0x86,
                                    0x48, (byte) 0xce, 0x3d, 0x02, 0x01, 0x06, 0x08, 0x2a,
                                    (byte) 0x86, 0x48, (byte) 0xce, 0x3d, 0x03, 0x01, 0x07,
                                    0x03, 0x42, 0x00
                            };
                            existing.put("spki", base64(concatBytes(prefix, point)));
                            cookies.saveEcKey(existing.toString());
                        }
                    }
                    if (!existing.optString("spki").isEmpty()) return existing;
                }
            } catch (Exception ignored) {}
        }
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair pair = generator.generateKeyPair();
        JSONObject key = new JSONObject()
                .put("priv", base64(pair.getPrivate().getEncoded()))
                .put("pub", base64(uncompressed(pair.getPublic())))
                .put("spki", base64(pair.getPublic().getEncoded()));
        cookies.saveEcKey(key.toString());
        return key;
    }

    private PrivateKey privateKey() throws Exception {
        JSONObject key = ecKey();
        byte[] pkcs8 = Base64.decode(key.getString("priv"), Base64.DEFAULT);
        return KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
    }

    private String publicPoint() {
        try {
            return ecKey().optString("pub");
        } catch (Exception e) {
            return "";
        }
    }

    private static byte[] uncompressed(PublicKey pub) {
        java.security.spec.ECPoint w = ((ECPublicKey) pub).getW();
        byte[] point = new byte[65];
        point[0] = 4;
        coordinates(w.getAffineX().toByteArray(), point, 1);
        coordinates(w.getAffineY().toByteArray(), point, 33);
        return point;
    }

    private static void coordinates(byte[] signed, byte[] point, int start) {
        int length = Math.min(32, signed.length);
        System.arraycopy(signed, signed.length - length, point, start + 32 - length, length);
    }

    private static String base64(byte[] bytes) {
        return Base64.encodeToString(bytes, Base64.NO_WRAP);
    }
}
