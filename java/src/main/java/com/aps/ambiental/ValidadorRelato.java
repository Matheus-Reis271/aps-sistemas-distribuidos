package com.aps.ambiental;

import java.time.OffsetDateTime;
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
            return "tipo nao bate com o topico " + topico;
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
        try {
            OffsetDateTime.parse(r.tsDispositivo);
        } catch (DateTimeParseException e) {
            return "ts_dispositivo fora do ISO 8601 com fuso";
        }
        if (r.descricao != null && r.descricao.length() > DESCRICAO_MAX) {
            return "descricao com mais de " + DESCRICAO_MAX + " caracteres";
        }
        return null;
    }
}
