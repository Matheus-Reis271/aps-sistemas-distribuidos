# aps-sistemas distribuidos — publicador (Python) + worker (Java)

## Como rodar

**1. Broker** (Mosquitto 2.1.2, com persistencia em `mosquitto/data/`)
```
docker compose up -d
```

**2. Worker** (JDK 17+)
```
cd java
mvn clean package
java -jar target/ambiental-worker-1.0.0.jar
```

**3. Publicador**
```
cd python
pip install cryptography paho-mqtt
python publicador.py
python publicador.py --tipo poluicao --severidade 4 --descricao "fumaca preta"
python publicador.py --quantidade 10 --intervalo 0.5
```
Na primeira execucao ele gera `chave_privada.bin` (permissao 600, fora do git).
`python publicador.py --help` lista as opcoes. O publicador confere as
entradas antes de conectar: coordenada finita e dentro da faixa e descricao de
ate 500 caracteres (as mesmas regras do worker), quantidade de pelo menos 1,
intervalo e porta validos. Um valor invalido encerra o programa com uma
mensagem, sem traceback. Se o `chave_privada.bin` estiver corrompido, o
publicador avisa e encerra, em vez de gerar outra chave sozinho.

Saida do worker:
```
21:39:44  worker worker-1 iniciando (broker tcp://localhost:1883)
21:39:44  conectado a tcp://localhost:1883
21:39:44  inscrito em ambiental/relato/+
21:39:48  OK        f6dc2366  alagamento  sev 3  (-23.5505, -46.6333)  "agua na altura do meio-fio"
21:40:02  REJEITADO 802d7c90  assinatura nao confere
21:40:05  DUPLICATA f6dc2366
21:40:09  REJEITADO --------  JSON invalido: campo severidade: tipo errado (esperado inteiro)
21:41:15  encerrado (aceitos: 1, rejeitados: 2, duplicatas: 1)
```
A descricao, que e texto livre vindo da rede, aparece entre aspas (aspas
internas escapadas) e qualquer caractere de controle vira `?`: assim nenhum
relato consegue imitar uma linha do log.
Se o broker estiver fora do ar, o worker tenta de novo com espera crescente
(1s, 2s, 4s... ate 30s) e reconecta sozinho se a conexao cair. Ctrl+C encerra
desconectando do broker; a sessao continua la e os relatos que chegarem
enquanto o worker estiver parado sao entregues quando ele voltar.

Para ver todo o trafego do broker: `docker exec broker mosquitto_sub -v -t '#'`

## Configuracao do worker

Por variavel de ambiente:

| variavel      | padrao                  |
|---------------|-------------------------|
| `MQTT_BROKER` | `tcp://localhost:1883`  |
| `WORKER_ID`   | `worker-1`              |
| `MQTT_TOPICO` | `ambiental/relato/+`    |

No PowerShell: `$env:WORKER_ID="worker-2"`; no cmd: `set WORKER_ID=worker-2`.
Um `MQTT_TOPICO` invalido encerra o worker na partida, antes de conectar.

**Dois ou mais workers.** Cada instancia precisa de um `WORKER_ID` proprio: dois
workers com o mesmo id tomam a sessao um do outro em loop e nenhum trabalha.
- Com o topico padrao, todos os workers recebem todos os relatos. Sem banco,
  cada um aceita o mesmo relato; com `UNIQUE(id)` e `ON CONFLICT DO NOTHING`, a
  segunda gravacao nao faz nada.
- Para dividir a carga, `MQTT_TOPICO='$share/workers/ambiental/relato/+'` (cada
  relato vai para um worker so). Ressalva: o Mosquitto nao persiste assinatura
  compartilhada. Se o broker reiniciar, os relatos publicados antes de o worker
  reconectar se perdem, mesmo com o broker tendo confirmado ao publicador.

## Contrato de mensagem
O publicador serializa o relato de forma canonica (chaves em ordem alfabetica,
sem espacos, `json.dumps(sort_keys=True, separators=(',',':'))`) e publica
exatamente esses bytes, incluindo o campo `assinatura`. O worker verifica
cortando o campo `assinatura` do texto bruto recebido (ver `AssinaturaExtratora`)
em vez de reconstruir o objeto - reconstrucao muda formatacao de numero,
null e caractere de controle entre Python e Java e quebra a assinatura por
motivo errado.

Regras que o worker aplica depois de conferir a assinatura:
- `id`: UUID em minusculas (o `uuid4()` do Python e o pacote `uuid` do Dart ja geram assim)
- `tipo`: `alagamento`, `poluicao` ou `transito`, igual ao do topico `ambiental/relato/<tipo>`
- `lat`/`lon`: numeros dentro da faixa; numero escrito como texto e rejeitado
- campos de texto so aceitam texto: `"descricao": 42` e rejeitado em vez de
  virar `"42"`
- `severidade`: inteiro de 1 a 5; `4.9` e rejeitado, nao arredondado
- `ts_dispositivo`: ISO 8601 com fuso (`...+00:00` ou `...Z`), de no maximo 7
  dias atras ate 1 dia a frente do relogio do worker, contados na hora em que
  ele processa. No Dart: `DateTime.now().toUtc().toIso8601String()`
- `descricao`: opcional, ate 500 caracteres, sem caractere nulo (`\u0000`),
  que o PostgreSQL nao guarda. Caractere aqui e code point, como no `len()` do
  Python e no `varchar` do PostgreSQL: um emoji conta 1. No Dart,
  `String.length` conta unidades UTF-16 (emoji vale 2); use `descricao.runes.length`
- campos a mais sao ignorados (estao cobertos pela assinatura do mesmo jeito)

A janela de data tambem tem efeito sobre replay: depois que o worker reinicia
e esquece os ids vistos, um relato capturado so passa de novo se tiver menos
de 7 dias. Com `UNIQUE(id)` no banco, nem isso.

Se o app Flutter for o publicador real, o `jsonEncode` do Dart nao ordena
chaves sozinho: precisa montar o mapa em ordem alfabetica manualmente antes
de serializar, senao a verificacao falha. E nenhum campo pode vir antes de
`assinatura` em ordem alfabetica.

## O que o PUBACK garante (e o que nao garante)
Com QoS 1, o publicador so mostra "confirmado pelo broker" depois do PUBACK.
Isso cobre apenas o trecho publicador -> broker: o broker recebeu e guardou a
mensagem. Nada no MQTT avisa o publicador se o worker aceitou ou rejeitou o
relato depois. A confirmacao fim a fim tem de ser construida por cima, e o app
vai ter a mesma limitacao. Duas saidas, se o app precisar mostrar "relato
recebido":
- o worker publica o resultado em `ambiental/resposta/<id>`, e o app se
  inscreve nesse topico antes de enviar;
- o app consulta a API REST pelo `id` depois de enviar.

## O que a assinatura garante (e o que nao garante)
Integridade da mensagem e continuidade de autoria (mesma chave que assinou
relatos anteriores). Nao autentica quem e a pessoa, porque a chave publica
vai dentro da propria mensagem - qualquer um gera um par de chaves novo e
assina um relato "valido". Autenticacao de verdade exige registrar
dispositivo/chave em uma tabela quando o banco entrar.

O worker recusa as 8 chaves publicas de ordem pequena da curva Ed25519.
Nenhuma delas tem chave privada: com a chave identidade, por exemplo, a mesma
assinatura trivial vale para qualquer mensagem, e qualquer pessoa assinaria
"como" essa chave. Aceita-las quebraria a continuidade de autoria e, mais
tarde, o cadastro de dispositivos.

## Limitacoes atuais
- Dedup por UUID em memoria, sem limite, zera a cada reinicio do worker. Vira
  `UNIQUE(id)` no Postgres quando a gravacao entrar.
- Sem gravacao em banco ainda - so valida e loga. Quando o INSERT entrar, a
  falha dele passa por `ErroBanco.classificar()`, que decide pelo SQLState:
  - conexao (08), falta de recurso (53), banco reiniciando (57P01 a 57P03) ou
    conflito entre transacoes (40001, 40P01): espera, tenta de novo e so
    confirma a mensagem depois de gravar;
  - dado invalido (22) ou restricao violada (23): o banco vai recusar sempre,
    entao o relato e rejeitado e confirmado. Repetir travaria a fila como uma
    mensagem envenenada;
  - qualquer outro (senha errada, tabela que nao existe, permissao): para o
    worker sem confirmar, e a mensagem espera no broker ate a configuracao ser
    corrigida. Rejeitar nesse caso descartaria relatos validos.
- `allow_anonymous true`: qualquer cliente na rede le e publica em qualquer
  topico, inclusive nos de alerta. Proximo passo: usuario/senha e ACL com os
  plugins `mosquitto_password_file` e `mosquitto_acl_file`, que ja vem na imagem.
  O worker ja trata a recusa: senha errada na primeira conexao (CONNACK 4 ou 5)
  ou assinatura negada (SUBACK 0x80) encerram o worker com mensagem clara, em
  vez de tentar para sempre ou ficar conectado sem receber nada. Duas
  ressalvas, uma do broker e outra do Paho:
  - o `mosquitto_acl_file` nao confere assinaturas: aceita qualquer subscribe
    e so filtra na entrega. Um worker sem permissao de leitura fica inscrito e
    nao recebe nada, sem erro nenhum. O SUBACK 0x80 vem do plugin
    `dynamic-security`. Com `acl_file`, confira a leitura do usuario do worker
    com `mosquitto_sub -u <usuario> -P <senha> -t 'ambiental/relato/+'` antes
    de subir;
  - o reconnect automatico do Paho nao distingue senha recusada de broker fora
    do ar: se a senha mudar com o worker rodando, ele continua tentando.
- Faixa de severidade assumida como 1 a 5; confirmar com o grupo se o
  enunciado ou a proposta definem outra escala.
