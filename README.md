# aps-sistemas distribuidos — publicador (Python) + worker (Java)

## Contrato de mensagem
O publicador serializa o relato de forma canonica (chaves em ordem alfabetica,
sem espacos, `json.dumps(sort_keys=True, separators=(',',':'))`) e publica
exatamente esses bytes, incluindo o campo `assinatura`. O worker verifica
cortando o campo `assinatura` do texto bruto recebido (ver `AssinaturaExtratora`)
em vez de reconstruir o objeto - reconstrucao muda formatacao de numero,
null e caractere de controle entre Python e Java e quebra a assinatura por
motivo errado.

Se o app Flutter for o publicador real, o `jsonEncode` do Dart nao ordena
chaves sozinho: precisa montar o mapa em ordem alfabetica manualmente antes
de serializar, senao a verificacao falha.

## O que a assinatura garante (e o que nao garante)
Integridade da mensagem e continuidade de autoria (mesma chave que assinou
relatos anteriores). Nao autentica quem e a pessoa, porque a chave publica
vai dentro da propria mensagem - qualquer um gera um par de chaves novo e
assina um relato "valido". Autenticacao de verdade exige registrar
dispositivo/chave em uma tabela quando o banco entrar.

## Rodando o broker
```
docker run -d --name broker -p 1883:1883 \
  -v $(pwd)/mosquitto/mosquitto.conf:/mosquitto/config/mosquitto.conf \
  -v $(pwd)/mosquitto/data:/mosquitto/data \
  eclipse-mosquitto:2
```

## Publicador
```
cd python
pip install cryptography paho-mqtt
python publicador.py
```
Gera `chave_privada.bin` na primeira execucao (permissao 600).

## Worker
```
cd java
mvn clean package
java -jar target/ambiental-worker-1.0.0.jar
```

## Limitacoes atuais
- Dedup por UUID e em memoria (`Set`), zera a cada reinicio do worker. Vira
  `UNIQUE(id)` no Postgres quando a gravacao entrar.
- Sem gravacao em banco ainda - so valida e loga.
- Faixa de severidade assumida como 1 a 5; confirmar com o grupo se o
  enunciado ou a proposta definem outra escala.
