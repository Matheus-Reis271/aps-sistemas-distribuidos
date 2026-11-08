package com.aps.ambiental;

// APS - Sistema distribuido para monitoramento ambiental urbano
// Autores: NOME1, NOME2, NOME3

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

    // sem coercao: 4.9 nao vira 4, "-23.5" (texto) nao vira numero e numero ou
    // booleano nao viram texto. Este ultimo caso precisa da CoercionConfig: sem
    // ela, "descricao": 42 passava como "42". O que foi assinado tem que ser
    // exatamente o que segue para o banco.
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
            // sem esta checagem o erro so apareceria no subscribe, depois de
            // conectar e numa thread separada: o worker ficaria conectado e surdo
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

        Runtime.getRuntime().addShutdownHook(new Thread(() -> encerrar(cliente), "encerramento"));

        log("worker %s iniciando (broker %s)", WORKER_ID, BROKER);
        conectar(cliente, opcoes);
        Thread.currentThread().join();
    }

    // o reconnect automatico do Paho so entra em acao depois da primeira
    // conexao bem-sucedida; a primeira fica por nossa conta. Ele tambem nao
    // distingue senha recusada de broker fora do ar: se a senha mudar com o
    // worker rodando, ele continua tentando
    private static void conectar(MqttClient cliente, MqttConnectOptions opcoes) throws InterruptedException {
        long espera = 1_000;
        while (true) {
            try {
                cliente.connect(opcoes);
                return;
            } catch (MqttSecurityException e) {
                // usuario/senha errados ou cliente sem permissao (CONNACK 4 ou 5):
                // o broker vai dar a mesma resposta em toda tentativa
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

    // erro de configuracao: tentar de novo nao muda a resposta, e seguir
    // rodando sem receber nada pareceria funcionamento normal. O shutdown
    // hook, se ja registrado, ainda desconecta e imprime o resumo.
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
                if (e.getReasonCode() == MqttException.REASON_CODE_SUBSCRIBE_FAILED) {
                    // SUBACK 0x80: o broker negou a assinatura (ACL). Ficar
                    // conectado sem receber nada e pior que parar
                    sair("broker negou a assinatura de %s; confira a ACL", TOPICO);
                }
                // conexao caiu no meio do subscribe: na reconexao o
                // connectComplete roda de novo e refaz a assinatura
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
            // quando o INSERT entrar, a falha dele passa por ErroBanco.classificar():
            // REPETIR espera e tenta de novo antes deste ponto (sem confirmar),
            // REJEITAR segue para a confirmacao abaixo e ENCERRAR para o worker
            // sem confirmar. Nenhuma das tres cai no catch acima.
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
        // proximo passo: INSERT no PostGIS aqui (falha: ver ErroBanco)
    }

    private static void rejeitar(String id, String motivo) {
        rejeitados.incrementAndGet();
        log("REJEITADO %s  %s", curto(id), limpo(motivo, 160));
    }

    private static void log(String formato, Object... args) {
        System.out.println(LocalTime.now().format(HORA) + "  " + String.format(Locale.ROOT, formato, args));
    }

    // tudo que vem da rede passa por aqui antes de ir para o log: quebra de
    // linha ou separador Unicode permitiria forjar uma linha "OK" que nao existiu.
    // \p{Cc} e a categoria Unicode de todos os controles: alem do ASCII
    // (\x00-\x1F e \x7F, o unico que \p{Cntrl} cobre em Java) pega o C1
    // (U+0080 a U+009F), onde estao o NEL (U+0085) e o CSI (U+009B), que em
    // alguns terminais vale por ESC [ e abre sequencias ANSI
    static String limpo(String texto, int max) {
        if (texto == null) {
            return "-";
        }
        String t = texto.replaceAll("[\\p{Cc}\\p{Cf}\\u2028\\u2029]", "?");
        return t.length() > max ? t.substring(0, max) + "..." : t;
    }

    // texto livre da rede no meio de uma linha do log: entre aspas, com aspas
    // e barras invertidas escapadas. Sem isso, uma descricao como
    // `21:00:00  OK falso` se misturaria com o resto da linha
    static String entreAspas(String texto, int max) {
        if (texto == null) {
            return "-";
        }
        return "\"" + limpo(texto, max).replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    // As mensagens do Jackson sao longas (o corte do log as partia no meio) e
    // algumas trazem trechos do payload. Para o log basta saber qual campo
    // veio errado ou onde a sintaxe quebrou.
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
        int coluna = e.getLocation() == null ? -1 : e.getLocation().getColumnNr();
        String onde = coluna > 0 ? " na coluna " + coluna : "";
        return e instanceof JsonEOFException ? "JSON incompleto" + onde : "sintaxe invalida" + onde;
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
