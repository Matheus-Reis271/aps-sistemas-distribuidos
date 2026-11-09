package com.aps.ambiental;

// APS - Sistema distribuido para monitoramento ambiental urbano
// Autores: NOME1, NOME2, NOME3

import com.fasterxml.jackson.core.JsonLocation;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.io.JsonEOFException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.type.LogicalType;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.MqttSecurityException;
import org.eclipse.paho.client.mqttv3.MqttTopic;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Worker: consome os relatos, confere a assinatura Ed25519, valida o
 * conteudo e descarta duplicatas por UUID. Ainda sem gravacao em banco.
 *
 * Variaveis de ambiente:
 *   MQTT_BROKER   (padrao tcp://localhost:1883)
 *   MQTT_USUARIO  e MQTT_SENHA (sem usuario, conecta como anonimo)
 *   WORKER_ID     (padrao worker-1; um por instancia)
 *   MQTT_TOPICO   (padrao ambiental/relato/+)
 */
public class Worker {

    private static final String BROKER = env("MQTT_BROKER", "tcp://localhost:1883");
    private static final String USUARIO = env("MQTT_USUARIO", null);
    private static final String WORKER_ID = env("WORKER_ID", "worker-1");

    // Assinatura comum: a sessao persistente guarda os relatos enquanto o
    // worker esta fora. A compartilhada ($share/workers/...) divide a carga,
    // mas o Mosquitto nao a persiste se o broker reiniciar (ver README).
    private static final String TOPICO = env("MQTT_TOPICO", "ambiental/relato/+");

    // sem limite de proposito: numa janela limitada, um id antigo sairia e o
    // replay passaria. Vira UNIQUE(id) quando o banco entrar.
    private static final Set<String> IDS_PROCESSADOS = ConcurrentHashMap.newKeySet();

    // sem coercao em nenhuma direcao: o que foi assinado e o que segue adiante
    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .withCoercionConfig(LogicalType.Textual, cfg -> cfg
                    .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                    .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                    .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail))
            .build();

    private static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("HH:mm:ss");

    private static final AtomicLong aceitos = new AtomicLong();
    private static final AtomicLong rejeitados = new AtomicLong();
    private static final AtomicLong duplicatas = new AtomicLong();

    public static void main(String[] args) throws Exception {
        try {
            MqttTopic.validate(TOPICO, true);
        } catch (IllegalArgumentException e) {
            sair("MQTT_TOPICO invalido (%s): %s", TOPICO, e.getMessage());
        }

        MqttClient cliente = new MqttClient(BROKER, WORKER_ID, new MemoryPersistence());
        cliente.setManualAcks(true);
        cliente.setCallback(new Callback(cliente));

        MqttConnectOptions opcoes = new MqttConnectOptions();
        opcoes.setCleanSession(false);
        opcoes.setAutomaticReconnect(true);
        opcoes.setMaxReconnectDelay(30_000);
        opcoes.setKeepAliveInterval(30);
        if (USUARIO != null) {
            opcoes.setUserName(USUARIO);
            opcoes.setPassword(env("MQTT_SENHA", "").toCharArray());
        }

        Runtime.getRuntime().addShutdownHook(new Thread(() -> encerrar(cliente), "encerramento"));

        log("worker %s iniciando (broker %s, usuario %s)", WORKER_ID, BROKER,
                USUARIO == null ? "anonimo" : limpo(USUARIO, 40));
        conectar(cliente, opcoes);
        Thread.currentThread().join();
    }

    // o reconnect automatico do Paho so vale depois da primeira conexao
    private static void conectar(MqttClient cliente, MqttConnectOptions opcoes) throws InterruptedException {
        long espera = 1_000;
        while (true) {
            try {
                cliente.connect(opcoes);
                return;
            } catch (MqttSecurityException e) {
                // CONNACK 4 ou 5: repetir nao muda a resposta do broker
                sair("broker recusou a conexao (%s); confira usuario, senha e ACL", e.getMessage());
            } catch (MqttException e) {
                log("broker indisponivel (%s), nova tentativa em %ds", e.getMessage(), espera / 1_000);
                Thread.sleep(espera);
                espera = Math.min(espera * 2, 30_000);
            }
        }
    }

    private static void encerrar(MqttClient cliente) {
        try {
            if (cliente.isConnected()) {
                cliente.disconnect(2_000);
            }
            cliente.close();
        } catch (MqttException e) {
            // ja estamos saindo; a sessao fica no broker de qualquer forma
        }
        log("encerrado (aceitos: %d, rejeitados: %d, duplicatas: %d)",
                aceitos.get(), rejeitados.get(), duplicatas.get());
    }

    // para erros de configuracao, em que tentar de novo nao adianta
    private static void sair(String formato, Object... args) {
        log("ERRO      " + formato, args);
        System.exit(1);
    }

    private static final class Callback implements MqttCallbackExtended {

        private final MqttClient cliente;

        Callback(MqttClient cliente) {
            this.cliente = cliente;
        }

        @Override
        public void connectComplete(boolean reconexao, String uri) {
            log(reconexao ? "reconectado a %s" : "conectado a %s", uri);
            // subscribe sincrono na thread de callback trava se houver fila
            // acumulada: o SUBACK fica atras das mensagens
            new Thread(this::assinar, "subscribe").start();
        }

        private void assinar() {
            try {
                cliente.subscribe(TOPICO, 1);
                log("inscrito em %s", TOPICO);
            } catch (MqttException e) {
                if (e.getReasonCode() == MqttException.REASON_CODE_SUBSCRIBE_FAILED) {
                    sair("broker negou a assinatura de %s; confira a ACL", TOPICO);
                }
                // conexao caiu no meio: a reconexao refaz a assinatura
                log("falha ao se inscrever em %s: %s", TOPICO, e.getMessage());
            }
        }

        @Override
        public void connectionLost(Throwable causa) {
            log("conexao perdida (%s), reconectando...", causa.getMessage());
        }

        @Override
        public void messageArrived(String topico, MqttMessage mensagem) {
            try {
                processar(topico, mensagem.getPayload());
            } catch (RuntimeException e) {
                // erro do programa: confirma mesmo assim, senao a mensagem
                // volta a cada reconexao e trava a fila
                rejeitados.incrementAndGet();
                log("ERRO      %s", limpo(e.toString(), 200));
            }
            try {
                cliente.messageArrivedComplete(mensagem.getId(), mensagem.getQos());
            } catch (MqttException e) {
                log("falha ao confirmar mensagem: %s", e.getMessage());
            }
        }

        @Override
        public void deliveryComplete(IMqttDeliveryToken token) {
        }
    }

    private static void processar(String topico, byte[] payload) {
        String bruto = new String(payload, StandardCharsets.UTF_8);

        AssinaturaExtratora.Resultado partes;
        try {
            partes = AssinaturaExtratora.extrair(bruto);
        } catch (IllegalArgumentException e) {
            rejeitar(null, "fora do formato (assinatura precisa ser a primeira chave)");
            return;
        }

        Relato relato;
        try {
            relato = MAPPER.readValue(bruto, Relato.class);
        } catch (JsonProcessingException e) {
            rejeitar(null, "JSON invalido: " + resumo(e));
            return;
        }

        String problema = VerificadorAssinatura.verificar(
                relato.chavePublica, partes.mensagemAssinada(), partes.assinaturaBase64());
        if (problema == null) {
            problema = ValidadorRelato.verificar(relato, topico);
        }
        if (problema != null) {
            rejeitar(relato.id, problema);
            return;
        }

        if (!IDS_PROCESSADOS.add(relato.id)) {
            duplicatas.incrementAndGet();
            log("DUPLICATA %s", curto(relato.id));
            return;
        }

        aceitos.incrementAndGet();
        log("OK        %s  %-10s  sev %d  (%.4f, %.4f)  %s", curto(relato.id), relato.tipo,
                relato.severidade, relato.lat, relato.lon, entreAspas(relato.descricao, 60));
        // proximo passo: INSERT no PostGIS; as falhas dele passam pela ErroBanco
    }

    private static void rejeitar(String id, String motivo) {
        rejeitados.incrementAndGet();
        log("REJEITADO %s  %s", curto(id), limpo(motivo, 160));
    }

    private static void log(String formato, Object... args) {
        System.out.println(LocalTime.now().format(HORA) + "  " + String.format(Locale.ROOT, formato, args));
    }

    // texto da rede antes do log: \p{Cc} pega tambem o C1 (NEL, CSI), que
    // quebra linha ou abre sequencia ANSI em alguns terminais
    static String limpo(String texto, int max) {
        if (texto == null) {
            return "-";
        }
        String t = texto.replaceAll("[\\p{Cc}\\p{Cf}\\u2028\\u2029]", "?");
        return t.length() > max ? t.substring(0, max) + "..." : t;
    }

    static String entreAspas(String texto, int max) {
        if (texto == null) {
            return "-";
        }
        return "\"" + limpo(texto, max).replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    // resumo curto no lugar da mensagem do Jackson, que e longa e as vezes
    // traz trechos do payload
    static String resumo(JsonProcessingException e) {
        if (e instanceof JsonMappingException m && !m.getPath().isEmpty()) {
            String campo = m.getPath().stream()
                    .map(ref -> ref.getFieldName() != null ? ref.getFieldName() : "[" + ref.getIndex() + "]")
                    .collect(Collectors.joining("."));
            if (m instanceof MismatchedInputException mi && mi.getTargetType() != null) {
                return "campo " + campo + ": tipo errado (esperado " + nomeDoTipo(mi.getTargetType()) + ")";
            }
            return "campo " + campo + ": valor invalido";
        }
        String tipo = e instanceof JsonEOFException ? "JSON incompleto" : "sintaxe invalida";
        JsonLocation local = e.getLocation();
        if (local == null || local.getLineNr() < 1) {
            return tipo;
        }
        return tipo + " na linha " + local.getLineNr() + ", coluna " + local.getColumnNr();
    }

    private static String nomeDoTipo(Class<?> tipo) {
        if (tipo == Integer.class) {
            return "inteiro";
        }
        if (tipo == Double.class) {
            return "numero";
        }
        if (tipo == String.class) {
            return "texto";
        }
        return tipo.getSimpleName();
    }

    private static String curto(String id) {
        if (id == null) {
            return "--------";
        }
        String t = limpo(id, id.length());
        return t.length() > 8 ? t.substring(0, 8) : t;
    }

    private static String env(String nome, String padrao) {
        String valor = System.getenv(nome);
        return valor == null || valor.isBlank() ? padrao : valor;
    }
}
