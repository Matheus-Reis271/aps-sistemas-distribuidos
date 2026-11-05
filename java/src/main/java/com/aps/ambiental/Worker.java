package com.aps.ambiental;

// APS - Sistema distribuido para monitoramento ambiental urbano
// Autores: NOME1, NOME2, NOME3

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Worker: consome os relatos, confere a assinatura Ed25519, valida o
 * conteudo e descarta duplicatas por UUID. Ainda sem gravacao em banco.
 *
 * Configuracao por variavel de ambiente:
 *   MQTT_BROKER  (padrao tcp://localhost:1883)
 *   WORKER_ID    (padrao worker-1; cada instancia precisa de um id proprio)
 *   MQTT_TOPICO  (padrao ambiental/relato/+)
 */
public class Worker {

    private static final String BROKER = env("MQTT_BROKER", "tcp://localhost:1883");
    private static final String WORKER_ID = env("WORKER_ID", "worker-1");

    // Assinatura comum por padrao: o broker a guarda na sessao persistente e
    // segura os relatos enquanto o worker esta fora, inclusive se o proprio
    // broker reiniciar. Para dividir a carga entre varios workers, usar
    // MQTT_TOPICO='$share/workers/ambiental/relato/+'. O custo: o Mosquitto nao
    // persiste assinatura compartilhada, entao relatos publicados entre a volta
    // do broker e a reconexao do worker se perdem (testado no 2.1.2).
    private static final String TOPICO = env("MQTT_TOPICO", "ambiental/relato/+");

    // dedup em memoria ate o banco entrar (ai vira UNIQUE(id) na tabela).
    // sem limite de tamanho de proposito: com janela limitada, basta mandar
    // relatos suficientes para um id antigo sair dela e o replay passar.
    // um milhao de ids ocupa ~124 MB.
    private static final Set<String> IDS_PROCESSADOS = ConcurrentHashMap.newKeySet();

    // sem coercao: 4.9 nao vira 4 e "-23.5" (texto) nao vira numero. O que foi
    // assinado tem que ser exatamente o que segue para o banco.
    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .build();

    private static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("HH:mm:ss");

    private static final AtomicLong aceitos = new AtomicLong();
    private static final AtomicLong rejeitados = new AtomicLong();
    private static final AtomicLong duplicatas = new AtomicLong();

    public static void main(String[] args) throws Exception {
        MqttClient cliente = new MqttClient(BROKER, WORKER_ID, new MemoryPersistence());
        cliente.setManualAcks(true);
        cliente.setCallback(new Callback(cliente));

        MqttConnectOptions opcoes = new MqttConnectOptions();
        opcoes.setCleanSession(false);
        opcoes.setAutomaticReconnect(true);
        opcoes.setMaxReconnectDelay(30_000);
        opcoes.setKeepAliveInterval(30);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> encerrar(cliente), "encerramento"));

        log("worker %s iniciando (broker %s)", WORKER_ID, BROKER);
        conectar(cliente, opcoes);
        Thread.currentThread().join();
    }

    // o reconnect automatico do Paho so entra em acao depois da primeira
    // conexao bem-sucedida; a primeira fica por nossa conta
    private static void conectar(MqttClient cliente, MqttConnectOptions opcoes) throws InterruptedException {
        long espera = 1_000;
        while (true) {
            try {
                cliente.connect(opcoes);
                return;
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

    private static final class Callback implements MqttCallbackExtended {

        private final MqttClient cliente;

        Callback(MqttClient cliente) {
            this.cliente = cliente;
        }

        @Override
        public void connectComplete(boolean reconexao, String uri) {
            log(reconexao ? "reconectado a %s" : "conectado a %s", uri);
            // este metodo roda na thread de callback do Paho. Um subscribe
            // sincrono aqui fica esperando o SUBACK, que nunca chega se houver
            // mensagens acumuladas enchendo a fila dessa mesma thread (deadlock).
            new Thread(this::assinar, "subscribe").start();
        }

        private void assinar() {
            try {
                cliente.subscribe(TOPICO, 1);
                log("inscrito em %s", TOPICO);
            } catch (MqttException e) {
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
                // erro de programa, nao do relato. Confirma mesmo assim: sem o ack
                // a mensagem volta a cada reconexao e trava a fila do worker.
                rejeitados.incrementAndGet();
                log("ERRO      %s", limpo(e.toString(), 200));
            }
            // quando o INSERT entrar, falha de conexao com o banco deve repetir
            // antes deste ponto (com espera crescente), nao cair no catch acima
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
            // getOriginalMessage nao traz o payload; getMessage traz, e com ele
            // daria para escrever linhas falsas no log
            rejeitar(null, "JSON invalido: " + e.getOriginalMessage());
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
                relato.severidade, relato.lat, relato.lon, limpo(relato.descricao, 60));
        // proximo passo: INSERT no PostGIS aqui
    }

    private static void rejeitar(String id, String motivo) {
        rejeitados.incrementAndGet();
        log("REJEITADO %s  %s", curto(id), limpo(motivo, 160));
    }

    private static void log(String formato, Object... args) {
        System.out.println(LocalTime.now().format(HORA) + "  " + String.format(Locale.ROOT, formato, args));
    }

    // tudo que vem da rede passa por aqui antes de ir para o log: quebra de
    // linha ou separador Unicode permitiria forjar uma linha "OK" que nao existiu
    static String limpo(String texto, int max) {
        if (texto == null) {
            return "-";
        }
        String t = texto.replaceAll("[\\p{Cntrl}\\p{Cf}\\u2028\\u2029]", "?");
        return t.length() > max ? t.substring(0, max) + "..." : t;
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
