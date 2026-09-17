package com.aps.ambiental;

import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * O publicador serializa o relato com chaves em ordem alfabetica e sem
 * espacos (json.dumps sort_keys=True, separators=(',',':')), incluindo o
 * campo "assinatura" nessa mesma serializacao. Como "assinatura" e a
 * primeira chave em ordem alfabetica, ela sempre aparece logo apos o "{".
 *
 * Por isso a verificacao aqui nao reconstroi o objeto (reserializar em
 * Java pode formatar numero, null ou caractere de controle diferente do
 * Python e quebrar a assinatura por motivo errado). Em vez disso corta o
 * campo assinatura do texto bruto recebido e verifica os bytes restantes
 * exatamente como chegaram.
 */
public final class AssinaturaExtratora {

    private static final Pattern PADRAO = Pattern.compile(
            "^\\{\"assinatura\":\"([^\"]*)\",(.*)$", Pattern.DOTALL);

    private AssinaturaExtratora() {
    }

    public record Resultado(String assinaturaBase64, byte[] mensagemAssinada) {
    }

    /**
     * @param payloadBruto texto exato recebido no MQTT (UTF-8), sem qualquer parse prévio
     * @throws IllegalArgumentException se o campo assinatura não estiver no formato esperado
     */
    public static Resultado extrair(String payloadBruto) {
        String texto = payloadBruto.strip();
        Matcher m = PADRAO.matcher(texto);
        if (!m.matches()) {
            throw new IllegalArgumentException(
                    "payload nao comeca com \"assinatura\" como primeira chave");
        }
        String assinatura = m.group(1);
        byte[] mensagemAssinada = ("{" + m.group(2)).getBytes(StandardCharsets.UTF_8);
        return new Resultado(assinatura, mensagemAssinada);
    }
}
