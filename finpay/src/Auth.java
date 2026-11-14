import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;

/** Stateless HS256 JWT + PBKDF2 password hashing (JDK only). */
public final class Auth {
    private Auth() {}
    static final long TTL_SECONDS = 3600;
    private static final byte[] SECRET;
    private static final SecureRandom RNG = new SecureRandom();
    static {
        String env = System.getenv("FINPAY_JWT_SECRET");
        if (env != null && env.length() >= 32) SECRET = env.getBytes(StandardCharsets.UTF_8);
        else { SECRET = new byte[48]; RNG.nextBytes(SECRET); }
    }
    private static String b64(byte[] b) { return Base64.getUrlEncoder().withoutPadding().encodeToString(b); }

    public static String hashPassword(String pw) {
        try {
            byte[] salt = new byte[16]; RNG.nextBytes(salt);
            byte[] h = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(new PBEKeySpec(pw.toCharArray(), salt, 120_000, 256)).getEncoded();
            return b64(salt) + "$" + b64(h);
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
    public static boolean verifyPassword(String pw, String stored) {
        try {
            String[] p = stored.split("\\$");
            byte[] salt = Base64.getUrlDecoder().decode(p[0]);
            byte[] h = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(new PBEKeySpec(pw.toCharArray(), salt, 120_000, 256)).getEncoded();
            return MessageDigest.isEqual(h, Base64.getUrlDecoder().decode(p[1]));
        } catch (Exception e) { return false; }
    }
    private static byte[] sign(String data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(SECRET, "HmacSHA256"));
        return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
    }
    public static String issue(long studentId, String role) {
        try {
            String head = b64("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
            long exp = System.currentTimeMillis() / 1000 + TTL_SECONDS;
            String body = b64(Json.write(Json.m("sub", studentId, "role", role, "exp", exp)).getBytes(StandardCharsets.UTF_8));
            return head + "." + body + "." + b64(sign(head + "." + body));
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
    /** Returns {sub, role} or null when invalid/expired. */
    public static long[] verify(String token, String[] roleOut) {
        try {
            String[] p = token.split("\\.");
            if (p.length != 3) return null;
            if (!MessageDigest.isEqual(sign(p[0] + "." + p[1]), Base64.getUrlDecoder().decode(p[2]))) return null;
            Map<String, Object> c = Json.parseObject(new String(Base64.getUrlDecoder().decode(p[1]), StandardCharsets.UTF_8));
            long exp = ((Number) c.get("exp")).longValue();
            if (exp < System.currentTimeMillis() / 1000) return null;
            roleOut[0] = (String) c.get("role");
            return new long[]{((Number) c.get("sub")).longValue()};
        } catch (Exception e) { return null; }
    }
}
