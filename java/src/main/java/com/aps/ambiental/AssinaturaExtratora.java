package com.aps.ambiental;

import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Corta o campo "assinatura" do texto bruto e devolve os bytes que foram
 * assinados. O publicador serializa com chaves em ordem alfabetica, entao
 * "assinatura" e sempre a primeira. Reconstruir o objeto em Java poderia
 * formatar numeros diferente do Python e quebrar a assinatura.
 */
public final class AssinaturaExtratora {

    private static final Pattern PADRAO = Pattern.compile(
            "^\\{\"assinatura\":\"([^\"]*)\",(.*)$", Pattern.DOTALL);

    private AssinaturaExtratora() {
    }

    public record Resultado(String assinaturaBase64, byte[] mensagemAssinada) {
    }

    /** @throws IllegalArgumentException se "assinatura" nao for a primeira chave */
    public static Resultado extrair(String payloadBruto) {
        Matcher m = PADRAO.matcher(payloadBruto);
        if (!m.matches()) {
            throw new IllegalArgumentException(
                    "payload nao comeca com \"assinatura\" como primeira chave");
        }
        String assinatura = m.group(1);
        byte[] mensagemAssinada = ("{" + m.group(2)).getBytes(StandardCharsets.UTF_8);
        return new Resultado(assinatura, mensagemAssinada);
    }
}
