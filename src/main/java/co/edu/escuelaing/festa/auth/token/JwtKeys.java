package co.edu.escuelaing.festa.auth.token;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.RSAKey;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/** Par de llaves RS256 con el que se firman los JWT. El kid es el thumbprint (RFC 7638) de la llave pública. */
public record JwtKeys(RSAPublicKey publicKey, RSAPrivateKey privateKey, String keyId) {

    public static JwtKeys of(RSAPublicKey publicKey, RSAPrivateKey privateKey) {
        try {
            String kid = new RSAKey.Builder(publicKey).build().computeThumbprint().toString();
            return new JwtKeys(publicKey, privateKey, kid);
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    public static JwtKeys fromPemFiles(String publicPath, String privatePath) {
        try {
            KeyFactory kf = KeyFactory.getInstance("RSA");
            var pub = (RSAPublicKey) kf.generatePublic(new X509EncodedKeySpec(readPem(publicPath)));
            var priv = (RSAPrivateKey) kf.generatePrivate(new PKCS8EncodedKeySpec(readPem(privatePath)));
            return of(pub, priv);
        } catch (GeneralSecurityException | IOException e) {
            throw new IllegalStateException("No se pudieron leer las llaves JWT (" + publicPath + ", " + privatePath + ")", e);
        }
    }

    public RSAKey toJwk() {
        return new RSAKey.Builder(publicKey).privateKey(privateKey).keyID(keyId).build();
    }

    private static byte[] readPem(String path) throws IOException {
        String pem = Files.readString(Path.of(path))
                .replaceAll("-----(BEGIN|END) [A-Z ]+-----", "")
                .replaceAll("\\s", "");
        return Base64.getDecoder().decode(pem);
    }
}
