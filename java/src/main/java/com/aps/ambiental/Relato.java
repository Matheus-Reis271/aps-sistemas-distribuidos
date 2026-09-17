package com.aps.ambiental;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Payload do relato, conforme o contrato de mensagem (docs/contrato-mensagem.md). */
public class Relato {

    @JsonProperty("id")
    public String id;

    @JsonProperty("tipo")
    public String tipo;

    @JsonProperty("lat")
    public double lat;

    @JsonProperty("lon")
    public double lon;

    @JsonProperty("severidade")
    public int severidade;

    @JsonProperty("descricao")
    public String descricao;

    @JsonProperty("ts_dispositivo")
    public String tsDispositivo;

    @JsonProperty("chave_publica")
    public String chavePublica;

    @JsonProperty("assinatura")
    public String assinatura;

    @Override
    public String toString() {
        return "Relato{id=%s, tipo=%s, lat=%s, lon=%s, severidade=%d, ts=%s}"
                .formatted(id, tipo, lat, lon, severidade, tsDispositivo);
    }
}
