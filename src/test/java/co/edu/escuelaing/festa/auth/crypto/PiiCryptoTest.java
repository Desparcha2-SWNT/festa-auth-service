package co.edu.escuelaing.festa.auth.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import co.edu.escuelaing.festa.auth.TestSupport;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class PiiCryptoTest {

    private final PiiCrypto crypto = new PiiCrypto(TestSupport.PII_KEY);

    @Test
    void cifraYDescifra() {
        String enc = crypto.encrypt("Ana Pérez");
        assertThat(enc).startsWith("v1:").doesNotContain("Ana");
        assertThat(crypto.decrypt(enc)).isEqualTo("Ana Pérez");
    }

    @Test
    void elMismoTextoProduceCifradosDistintos() {
        assertThat(crypto.encrypt("x")).isNotEqualTo(crypto.encrypt("x"));
    }

    @Test
    void indiceCiegoEsDeterministaEIgnoraMayusculasYEspacios() {
        assertThat(crypto.blindIndex(" Ana@Escuelaing.edu.co ")).isEqualTo(crypto.blindIndex("ana@escuelaing.edu.co"));
        assertThat(crypto.blindIndex("a@x.co")).isNotEqualTo(crypto.blindIndex("b@x.co"));
    }

    @Test
    void detectaManipulacion() {
        String enc = crypto.encrypt("dato");
        byte[] raw = Base64.getDecoder().decode(enc.substring(3));
        raw[raw.length - 1] ^= 1;
        String tampered = "v1:" + Base64.getEncoder().encodeToString(raw);
        assertThatThrownBy(() -> crypto.decrypt(tampered)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void exigeLlaveDe32Bytes() {
        assertThatThrownBy(() -> new PiiCrypto("")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new PiiCrypto(Base64.getEncoder().encodeToString(new byte[16])))
                .isInstanceOf(IllegalStateException.class);
    }
}
