package co.edu.escuelaing.festa.auth.crypto;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Locale;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Cifrado en reposo de datos personales (RNF4).
 * <ul>
 *   <li>{@link #encrypt}/{@link #decrypt}: AES-256-GCM con IV aleatorio, formato {@code v1:base64(iv || ciphertext)}.</li>
 *   <li>{@link #blindIndex}: HMAC-SHA256 determinístico para buscar por email sin descifrar.</li>
 * </ul>
 * La llave de índice se deriva de la llave maestra, así una no revela la otra.
 */
public class PiiCrypto {

    private static final String PREFIX = "v1:";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec encryptionKey;
    private final SecretKeySpec indexKey;
    private final SecureRandom random = new SecureRandom();

    public PiiCrypto(String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            throw new IllegalStateException("PII_ENCRYPTION_KEY es obligatoria (32 bytes en base64)");
        }
        byte[] master = Base64.getDecoder().decode(base64Key.trim());
        if (master.length != 32) {
            throw new IllegalStateException("PII_ENCRYPTION_KEY debe tener 32 bytes, tiene " + master.length);
        }
        this.encryptionKey = new SecretKeySpec(hmac(master, "festa-pii-encryption"), "AES");
        this.indexKey = new SecretKeySpec(hmac(master, "festa-pii-blind-index"), "HmacSHA256");
    }

    public String encrypt(String plaintext) {
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, encryptionKey, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return PREFIX + Base64.getEncoder().encodeToString(out);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("No se pudo cifrar", e);
        }
    }

    public String decrypt(String stored) {
        if (!stored.startsWith(PREFIX)) {
            throw new IllegalArgumentException("Formato de dato cifrado desconocido");
        }
        try {
            byte[] in = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, encryptionKey, new GCMParameterSpec(TAG_BITS, in, 0, IV_BYTES));
            byte[] pt = cipher.doFinal(in, IV_BYTES, in.length - IV_BYTES);
            return new String(pt, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("No se pudo descifrar", e);
        }
    }

    /** Índice ciego de un email (normalizado: trim + minúsculas). */
    public byte[] blindIndex(String email) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(indexKey);
            return mac.doFinal(normalizeEmail(email).getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("No se pudo calcular el índice", e);
        }
    }

    public static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private static byte[] hmac(byte[] key, String info) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(info.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
