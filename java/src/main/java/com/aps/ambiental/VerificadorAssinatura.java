package com.aps.ambiental;

import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Set;

/** Verificacao Ed25519 usando apenas java.security (JDK 17+, sem lib externa). */
public final class VerificadorAssinatura {

    // cabecalho DER fixo de SubjectPublicKeyInfo para Ed25519 (RFC 8410).
    // a chave publica raw da lib cryptography do Python (32 bytes) precisa
    // desse envelope antes do KeyFactory aceitar.
    private static final byte[] CABECALHO_DER_ED25519 = {
            0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00
    };

    // A curva tem 8 pontos de ordem pequena, e o JDK aceita todos como chave
    // publica. Nenhum deles tem chave privada: com o ponto identidade, por
    // exemplo, a assinatura fixa R = identidade, S = 0 confere para qualquer
    // mensagem, e qualquer pessoa assina "como" essa chave. Os 8 pontos tem so
    // estes 5 valores de y (o x muda apenas o bit de sinal).
    private static final BigInteger P = BigInteger.TWO.pow(255).subtract(BigInteger.valueOf(19));
    private static final Set<BigInteger> Y_ORDEM_PEQUENA = Set.of(
            BigInteger.ZERO,                // ordem 4
            BigInteger.ONE,                 // ordem 1 (identidade)
            P.subtract(BigInteger.ONE),     // ordem 2
            new BigInteger("05fc536d880238b13933c6d305acdfd5f098eff289f4c345b027b2c28f95e826", 16),  // ordem 8
            new BigInteger("7a03ac9277fdc74ec6cc392cfa53202a0f67100d760b3cba4fd84d3d706a17c7", 16)); // ordem 8

    private VerificadorAssinatura() {
    }

    /** @return null se a assinatura confere; caso contrario, o motivo da recusa */
    public static String verificar(String chavePublicaBase64, byte[] mensagem, String assinaturaBase64) {
        if (chavePublicaBase64 == null) {
            return "sem chave_publica";
        }

        byte[] chaveRaw;
        byte[] assinatura;
        try {
            chaveRaw = Base64.getDecoder().decode(chavePublicaBase64);
            assinatura = Base64.getDecoder().decode(assinaturaBase64);
        } catch (IllegalArgumentException e) {
            return "base64 invalido na chave ou na assinatura";
        }
        if (chaveRaw.length != 32) {
            return "chave publica com " + chaveRaw.length + " bytes (esperado 32)";
        }
        if (assinatura.length != 64) {
            return "assinatura com " + assinatura.length + " bytes (esperado 64)";
        }
        if (ordemPequena(chaveRaw)) {
            return "chave publica de ordem pequena (nao tem chave privada)";
        }

        byte[] chaveDer = new byte[CABECALHO_DER_ED25519.length + chaveRaw.length];
        System.arraycopy(CABECALHO_DER_ED25519, 0, chaveDer, 0, CABECALHO_DER_ED25519.length);
        System.arraycopy(chaveRaw, 0, chaveDer, CABECALHO_DER_ED25519.length, chaveRaw.length);

        try {
            PublicKey chavePublica = KeyFactory.getInstance("Ed25519")
                    .generatePublic(new X509EncodedKeySpec(chaveDer));
            Signature verificador = Signature.getInstance("Ed25519");
            verificador.initVerify(chavePublica);
            verificador.update(mensagem);
            return verificador.verify(assinatura) ? null : "assinatura nao confere";
        } catch (Exception e) {
            return "chave publica invalida";
        }
    }

    // a chave chega como 32 bytes little-endian: o y nos 255 bits de baixo e o
    // sinal de x no bit mais alto. O mod P tambem pega as codificacoes nao
    // canonicas (y >= P), que o JDK ja recusa de qualquer forma.
    static boolean ordemPequena(byte[] chaveRaw) {
        byte[] be = new byte[32];
        for (int i = 0; i < 32; i++) {
            be[i] = chaveRaw[31 - i];   // little-endian para big-endian
        }
        be[0] &= 0x7f;                  // descarta o bit de sinal de x
        return Y_ORDEM_PEQUENA.contains(new BigInteger(1, be).mod(P));
    }
}
