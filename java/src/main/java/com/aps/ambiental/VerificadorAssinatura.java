package com.aps.ambiental;

import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/** Verificacao Ed25519 usando apenas java.security (JDK 17+, sem lib externa). */
public final class VerificadorAssinatura {

    // cabecalho DER fixo de SubjectPublicKeyInfo para Ed25519 (RFC 8410).
    // a chave publica raw da lib cryptography do Python (32 bytes) precisa
    // desse envelope antes do KeyFactory aceitar.
    private static final byte[] CABECALHO_DER_ED25519 = {
            0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00
    };

    private VerificadorAssinatura() {
    }

    public static boolean verificar(String chavePublicaBase64Raw, byte[] mensagem, String assinaturaBase64) {
        try {
            byte[] chaveRaw = Base64.getDecoder().decode(chavePublicaBase64Raw);
            if (chaveRaw.length != 32) {
                return false;
            }

            byte[] chaveDer = new byte[CABECALHO_DER_ED25519.length + chaveRaw.length];
            System.arraycopy(CABECALHO_DER_ED25519, 0, chaveDer, 0, CABECALHO_DER_ED25519.length);
            System.arraycopy(chaveRaw, 0, chaveDer, CABECALHO_DER_ED25519.length, chaveRaw.length);

            PublicKey chavePublica = KeyFactory.getInstance("Ed25519")
                    .generatePublic(new X509EncodedKeySpec(chaveDer));

            Signature verificador = Signature.getInstance("Ed25519");
            verificador.initVerify(chavePublica);
            verificador.update(mensagem);
            return verificador.verify(Base64.getDecoder().decode(assinaturaBase64));

        } catch (Exception e) {
            return false;
        }
    }
}
