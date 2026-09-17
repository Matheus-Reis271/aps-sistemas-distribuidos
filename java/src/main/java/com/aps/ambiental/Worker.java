package com.aps.ambiental;

// APS - Sistema distribuido para monitoramento ambiental urbano
// Autores: NOME1, NOME2, NOME3

import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Worker: consome os topicos de relato, verifica assinatura Ed25519 e
 * deduplica por UUID. Ainda sem gravacao em banco - o dedup por UUID em
 * memoria e provisorio e some a cada reinicio; quando o PostGIS entrar,
 * vira constraint UNIQUE(id) na tabela.
 */
public class Worker {

    private static final String BROKER_URI = "tcp://localhost:1883";
    private static final String CLIENT_ID = "worker-java-1";
    private static final String TOPICO = "ambiental/relato/+";

    private static final double LAT_MIN = -90, LAT_MAX = 90;
    private static final double LON_MIN = -180, LON_MAX = 180;
    private static final int SEVERIDADE_MIN = 1, SEVERIDADE_MAX = 5;

    private static final Set<String> idsJaProcessados =
            Collections.synchronizedSet(new LinkedHashSet<>());

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static void main(String[] args) throws Exception {
        MqttClient cliente = new MqttClient(BROKER_URI, CLIENT_ID, new MemoryPersistence());

        MqttConnectOptions opcoes = new MqttConnectOptions();
        opcoes.setCleanSession(false);
        opcoes.setAutomaticReconnect(true);

        cliente.setManualAcks(true);
        cliente.setCallback(new WorkerCallback(cliente));
        cliente.connect(opcoes);

        System.out.println("worker conectado, aguardando conexao completa para assinar topico");
        Thread.currentThread().join();
    }

    private static class WorkerCallback implements MqttCallbackExtended {

        private final MqttClient cliente;

        WorkerCallback(MqttClient cliente) {
            this.cliente = cliente;
        }

        @Override
        public void connectComplete(boolean reconectando, String uriServidor) {
            try {
                // resubscribe aqui, nao no main: se a conexao cair e voltar,
                // e aqui que a assinatura do topico e refeita
                cliente.subscribe(TOPICO, 1);
                System.out.println((reconectando ? "reconectado" : "conectado")
                        + ", assinando " + TOPICO);
            } catch (MqttException e) {
                System.err.println("falha ao assinar topico: " + e.getMessage());
            }
        }

        @Override
        public void messageArrived(String topico, MqttMessage mensagemMqtt) {
            String payloadBruto = new String(mensagemMqtt.getPayload(), StandardCharsets.UTF_8);
            boolean processado = false;
            try {
                processado = processar(topico, payloadBruto);
            } catch (Exception e) {
                System.err.println("erro processando mensagem de " + topico + ": " + e.getMessage());
            } finally {
                // ack manual: so confirma ao broker depois que terminou de processar.
                // se cair uma excecao de banco aqui no futuro, NAO chega neste ack
                // e o broker reentrega a mensagem.
                if (processado) {
                    try {
                        cliente.messageArrivedComplete(mensagemMqtt.getId(), mensagemMqtt.getQos());
                    } catch (MqttException e) {
                        System.err.println("falha ao confirmar mensagem: " + e.getMessage());
                    }
                }
            }
        }

        @Override
        public void connectionLost(Throwable causa) {
            System.err.println("conexao perdida: " + causa.getMessage());
        }

        @Override
        public void deliveryComplete(IMqttDeliveryToken token) {
        }
    }

    /** @return true se a mensagem foi tratada (valida ou rejeitada de forma definitiva) e pode ser confirmada */
    private static boolean processar(String topico, String payloadBruto) {
        AssinaturaExtratora.Resultado extraido;
        try {
            extraido = AssinaturaExtratora.extrair(payloadBruto);
        } catch (IllegalArgumentException e) {
            System.out.println("[REJEITADO] payload fora do formato esperado: " + e.getMessage());
            return true; // formato invalido nao vai se corrigir sozinho, nao adianta reentregar
        }

        Relato relato;
        try {
            relato = MAPPER.readValue(payloadBruto, Relato.class);
        } catch (Exception e) {
            System.out.println("[REJEITADO] JSON invalido: " + e.getMessage());
            return true;
        }

        if (idsJaProcessados.contains(relato.id)) {
            System.out.println("[DUPLICATA] " + relato.id);
            return true;
        }

        boolean assinaturaValida = VerificadorAssinatura.verificar(
                relato.chavePublica, extraido.mensagemAssinada(), extraido.assinaturaBase64());
        if (!assinaturaValida) {
            System.out.println("[REJEITADO] " + relato.id + " assinatura invalida");
            return true;
        }

        // a assinatura garante integridade e que veio da mesma chave de sempre,
        // nao autentica quem e o autor - a chave publica viaja na propria mensagem.
        // autenticacao real exige registrar dispositivo/chave no banco.

        if (!topico.equals("ambiental/relato/" + relato.tipo)) {
            System.out.println("[REJEITADO] " + relato.id + " tipo=" + relato.tipo
                    + " nao bate com o topico " + topico);
            return true;
        }

        if (relato.lat < LAT_MIN || relato.lat > LAT_MAX
                || relato.lon < LON_MIN || relato.lon > LON_MAX
                || relato.severidade < SEVERIDADE_MIN || relato.severidade > SEVERIDADE_MAX) {
            System.out.println("[REJEITADO] " + relato.id + " valores fora de faixa");
            return true;
        }

        idsJaProcessados.add(relato.id);
        System.out.println("[OK] " + relato);
        // proximo passo: INSERT no PostGIS aqui
        return true;
    }
}
