package com.aps.ambiental;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Set;
import java.util.regex.Pattern;

/** Regras de conteudo do relato, aplicadas depois de conferir a assinatura. */
final class ValidadorRelato {

    static final Set<String> TIPOS = Set.of("alagamento", "poluicao", "transito");

    // so minusculas: "ABC..." e "abc..." seriam ids diferentes no dedup
    private static final Pattern UUID_CANONICO =
            Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    private static final int DESCRICAO_MAX = 500;

    // 7 dias cobrem a fila offline do app; 1 dia comporta relogio adiantado
    private static final Duration JANELA_PASSADO = Duration.ofDays(7);
    private static final Duration JANELA_FUTURO = Duration.ofDays(1);

    private ValidadorRelato() {
    }

    /** @return null se o relato e aceitavel; caso contrario, o motivo da recusa */
    static String verificar(Relato r, String topico) {
        if (r.id == null || !UUID_CANONICO.matcher(r.id).matches()) {
            return "id ausente ou fora do formato UUID";
        }
        if (r.tipo == null || !TIPOS.contains(r.tipo)) {
            return "tipo desconhecido";
        }
        if (!topico.equals("ambiental/relato/" + r.tipo)) {
            // o topico vem da rede e aceita qualquer texto: fica fora do log
            return "tipo " + r.tipo + " recebido em outro topico";
        }
        if (r.lat == null || r.lon == null) {
            return "sem lat/lon";
        }
        if (r.lat < -90 || r.lat > 90 || r.lon < -180 || r.lon > 180) {
            return "coordenada fora da faixa";
        }
        if (r.severidade == null || r.severidade < 1 || r.severidade > 5) {
            return "severidade ausente ou fora de 1..5";
        }
        if (r.tsDispositivo == null) {
            return "sem ts_dispositivo";
        }
        OffsetDateTime ts;
        try {
            ts = OffsetDateTime.parse(r.tsDispositivo);
        } catch (DateTimeParseException e) {
            return "ts_dispositivo fora do ISO 8601 com fuso";
        }
        OffsetDateTime agora = OffsetDateTime.now(ZoneOffset.UTC);
        if (ts.isBefore(agora.minus(JANELA_PASSADO)) || ts.isAfter(agora.plus(JANELA_FUTURO))) {
            return "ts_dispositivo fora da janela aceita (ate " + JANELA_PASSADO.toDays()
                    + " dias atras e " + JANELA_FUTURO.toDays() + " dia a frente)";
        }
        if (r.descricao != null) {
            // code points, como o len() do Python e o varchar do PostgreSQL
            if (r.descricao.codePointCount(0, r.descricao.length()) > DESCRICAO_MAX) {
                return "descricao com mais de " + DESCRICAO_MAX + " caracteres";
            }
            // o PostgreSQL nao guarda o caractere nulo, e um surrogate sem par
            // viraria "?" no UTF-8: o texto gravado deixaria de ser o assinado
            if (r.descricao.indexOf('\0') >= 0) {
                return "descricao com caractere nulo";
            }
            if (!StandardCharsets.UTF_8.newEncoder().canEncode(r.descricao)) {
                return "descricao com caractere invalido";
            }
        }
        return null;
    }
}
