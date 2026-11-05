package com.aps.ambiental;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Payload do relato, conforme o contrato de mensagem.
 *
 * Os numeros sao Double/Integer e nao double/int: com primitivo, campo
 * ausente vira 0 e "lat":0 passaria como coordenada valida. Campo
 * desconhecido e ignorado sem risco, porque a assinatura cobre os bytes
 * crus da mensagem inteira.
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
