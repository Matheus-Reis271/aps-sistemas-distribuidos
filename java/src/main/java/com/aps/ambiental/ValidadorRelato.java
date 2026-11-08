package com.aps.ambiental;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Set;
import java.util.regex.Pattern;

/** Regras de conteudo do relato, aplicadas depois de conferir a assinatura. */
final class ValidadorRelato {

    static final Set<String> TIPOS = Set.of("alagamento", "poluicao", "transito");

    // so a forma canonica em minusculas (a que uuid4() do Python e o pacote
    // uuid do Dart geram): "ABC..." e "abc..." seriam ids diferentes no dedup
    private static final Pattern UUID_CANONICO =
            Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    private static final int DESCRICAO_MAX = 500;

    // Janela aceita para ts_dispositivo, contada na hora em que o worker
    // processa. 7 dias para tras cobrem a fila offline do app; 1 dia para a
    // frente comporta relogio adiantado. Fora dela a data so atrapalha: um
    // relato "de 2099" apareceria primeiro em toda consulta por mais recentes,
    // e o ano 999999999, que o Java le sem reclamar, nao cabe no timestamptz
    // do PostgreSQL (vai ate o ano 294276).
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
            // o topico vem da rede e aceita qualquer texto, ate espaco: fica
            // fora do log. O tipo ja foi conferido contra a lista
            return "tipo " + r.tipo + " fora do topico ambiental/relato/" + r.tipo;
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
        // conta caracteres (code points), como o len() do publicador e o
        // varchar do PostgreSQL; length() contaria cada emoji como 2
        if (r.descricao != null && r.descricao.codePointCount(0, r.descricao.length()) > DESCRICAO_MAX) {
            return "descricao com mais de " + DESCRICAO_MAX + " caracteres";
        }
        if (r.descricao != null && r.descricao.indexOf('\0') >= 0) {
            // o text do PostgreSQL nao guarda o caractere nulo: o INSERT
            // falharia com erro de dado
            return "descricao com caractere nulo";
        }
        return null;
    }
}
