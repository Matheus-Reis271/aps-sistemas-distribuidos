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
`python publicador.py --help` lista as opcoes.

Saida do worker:
```
21:39:44  worker worker-1 iniciando (broker tcp://localhost:1883)
21:39:44  conectado a tcp://localhost:1883
21:39:44  inscrito em ambiental/relato/+
21:39:48  OK        f6dc2366  alagamento  sev 3  (-23.5505, -46.6333)  agua na altura do meio-fio
21:40:02  REJEITADO 802d7c90  assinatura nao confere
21:40:05  DUPLICATA f6dc2366
21:41:15  encerrado (aceitos: 1, rejeitados: 1, duplicatas: 1)
```
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
- `severidade`: inteiro de 1 a 5; `4.9` e rejeitado, nao arredondado
- `ts_dispositivo`: ISO 8601 com fuso (`...+00:00` ou `...Z`). No Dart:
  `DateTime.now().toUtc().toIso8601String()`
- `descricao`: opcional, ate 500 caracteres
- campos a mais sao ignorados (estao cobertos pela assinatura do mesmo jeito)

Se o app Flutter for o publicador real, o `jsonEncode` do Dart nao ordena
chaves sozinho: precisa montar o mapa em ordem alfabetica manualmente antes
de serializar, senao a verificacao falha. E nenhum campo pode vir antes de
`assinatura` em ordem alfabetica.

## O que a assinatura garante (e o que nao garante)
Integridade da mensagem e continuidade de autoria (mesma chave que assinou
relatos anteriores). Nao autentica quem e a pessoa, porque a chave publica
vai dentro da propria mensagem - qualquer um gera um par de chaves novo e
assina um relato "valido". Autenticacao de verdade exige registrar
dispositivo/chave em uma tabela quando o banco entrar.

## Limitacoes atuais
- Dedup por UUID em memoria, sem limite, zera a cada reinicio do worker. Vira
  `UNIQUE(id)` no Postgres quando a gravacao entrar.
- Sem gravacao em banco ainda - so valida e loga. Quando o INSERT entrar:
  falha de conexao com o banco deve ser repetida (com espera crescente) antes
  de confirmar a mensagem; relato invalido continua rejeitado e confirmado,
  senao ele volta a cada reconexao e trava a fila.
- `allow_anonymous true`: qualquer cliente na rede le e publica em qualquer
  topico, inclusive nos de alerta. Proximo passo: usuario/senha e ACL com os
  plugins `mosquitto_password_file` e `mosquitto_acl_file`, que ja vem na imagem.
- Faixa de severidade assumida como 1 a 5; confirmar com o grupo se o
  enunciado ou a proposta definem outra escala.
