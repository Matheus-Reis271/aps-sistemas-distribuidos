package com.aps.ambiental;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Payload do relato. Double/Integer em vez de double/int: com primitivo,
 * campo ausente viraria 0, e "lat":0 passaria como coordenada valida.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class Relato {

    @JsonProperty("id")
    public String id;

    @JsonProperty("tipo")
    public String tipo;

    @JsonProperty("lat")
    public Double lat;

    @JsonProperty("lon")
    public Double lon;

    @JsonProperty("severidade")
    public Integer severidade;

    @JsonProperty("descricao")
    public String descricao;

    @JsonProperty("ts_dispositivo")
    public String tsDispositivo;

    @JsonProperty("chave_publica")
    public String chavePublica;

    @JsonProperty("assinatura")
    public String assinatura;
}
