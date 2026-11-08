# APS - Sistema distribuido para monitoramento ambiental urbano
# Autores: NOME1, NOME2, NOME3

"""Publica relatos assinados (Ed25519) no broker MQTT.

Exemplos:
    python publicador.py
    python publicador.py --tipo poluicao --severidade 4 --descricao "fumaca preta"
    python publicador.py --quantidade 10 --intervalo 0.5
"""

import argparse
import base64
import json
import math
import os
import sys
import threading
import time
import uuid
from datetime import datetime, timezone

import paho.mqtt.client as mqtt
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey

CAMINHO_CHAVE_PRIVADA = "chave_privada.bin"
TIPOS = ("alagamento", "poluicao", "transito")
DESCRICAO_MAX = 500  # o mesmo limite do ValidadorRelato do worker


def obter_chave_privada() -> Ed25519PrivateKey:
    if os.path.exists(CAMINHO_CHAVE_PRIVADA):
        with open(CAMINHO_CHAVE_PRIVADA, "rb") as f:
            dados = f.read()
        try:
            return Ed25519PrivateKey.from_private_bytes(dados)
        except ValueError:
            # nao gera outra sozinho: chave nova e outra chave publica, e os
            # relatos deixariam de ter a mesma autoria dos anteriores
            sys.exit(
                f"{CAMINHO_CHAVE_PRIVADA} esta corrompido ({len(dados)} bytes; uma chave "
                "Ed25519 tem 32). Apague o arquivo para gerar uma chave nova; os "
                "relatos seguintes sairao com outra chave publica."
            )

    chave = Ed25519PrivateKey.generate()
    dados = chave.private_bytes(
        encoding=serialization.Encoding.Raw,
        format=serialization.PrivateFormat.Raw,
        encryption_algorithm=serialization.NoEncryption(),
    )
    # 0o600: so o dono le/escreve
    fd = os.open(CAMINHO_CHAVE_PRIVADA, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "wb") as f:
        f.write(dados)
    print(f"chave nova gerada em {CAMINHO_CHAVE_PRIVADA}")
    return chave


def chave_publica_base64(chave: Ed25519PrivateKey) -> str:
    dados = chave.public_key().public_bytes(
        encoding=serialization.Encoding.Raw,
        format=serialization.PublicFormat.Raw,
    )
    return base64.b64encode(dados).decode("ascii")


def json_canonico(obj: dict) -> bytes:
    return json.dumps(
        obj, sort_keys=True, separators=(",", ":"), ensure_ascii=False
    ).encode("utf-8")


def montar_relato(chave, tipo, lat, lon, severidade, descricao) -> dict:
    relato = {
        "id": str(uuid.uuid4()),
        "tipo": tipo,
        "lat": float(lat),
        "lon": float(lon),
        "severidade": int(severidade),
        "descricao": descricao,
        "ts_dispositivo": datetime.now(timezone.utc).isoformat(),
        "chave_publica": chave_publica_base64(chave),
    }
    relato["assinatura"] = base64.b64encode(chave.sign(json_canonico(relato))).decode("ascii")
    return relato


def conectar(host: str, porta: int, timeout: float = 5.0) -> mqtt.Client:
    """So devolve o cliente depois que o broker aceitou a conexao (CONNACK)."""
    pronto = threading.Event()
    resposta = {}

    def on_connect(cliente, userdata, flags, reason_code, properties):
        resposta["rc"] = reason_code
        pronto.set()

    cliente = mqtt.Client(
        mqtt.CallbackAPIVersion.VERSION2,
        client_id=f"publicador-{uuid.uuid4().hex[:8]}",
    )
    cliente.on_connect = on_connect
    cliente.connect(host, porta, keepalive=30)
    cliente.loop_start()

    if not pronto.wait(timeout):
        cliente.loop_stop()
        raise TimeoutError(f"sem resposta do broker em {timeout:.0f}s")
    if resposta["rc"].is_failure:
        cliente.loop_stop()
        raise ConnectionError(f"broker recusou a conexao ({resposta['rc']})")
    return cliente


def publicar(cliente: mqtt.Client, relato: dict, timeout: float = 5.0) -> str:
    # payload no fio = a mesma serializacao canonica usada para assinar, com o
    # campo assinatura incluido (primeira chave em ordem alfabetica). O worker
    # corta esse campo do texto bruto e verifica o resto.
    topico = f"ambiental/relato/{relato['tipo']}"
    info = cliente.publish(topico, json_canonico(relato), qos=1)
    # wait_for_publish devolve None no paho 2.x; quem diz se o broker
    # confirmou (PUBACK) e o is_published(). O PUBACK cobre so o trecho
    # publicador -> broker: nada no MQTT avisa se o worker aceitou o relato.
    info.wait_for_publish(timeout=timeout)
    if not info.is_published():
        raise RuntimeError(f"broker nao confirmou o relato {relato['id'][:8]} em {timeout:.0f}s")
    return topico


# Validacao das entradas. Confere o mesmo que o worker confere depois da
# assinatura, para que um relato que ele vai rejeitar nem saia daqui. Cada
# funcao e usada como type= do argparse, que mostra a mensagem e sai com
# codigo 2 em vez de um traceback.

def _numero(texto: str, minimo: float, maximo: float) -> float:
    try:
        valor = float(texto)
    except ValueError:
        raise argparse.ArgumentTypeError(f"'{texto}' nao e um numero") from None
    # float() aceita "nan", "inf" e "1e999" (que vira inf); nenhum serve aqui
    if not math.isfinite(valor) or not minimo <= valor <= maximo:
        raise argparse.ArgumentTypeError(f"precisa ser um numero entre {minimo:g} e {maximo:g}")
    return valor


def latitude(texto: str) -> float:
    return _numero(texto, -90, 90)


def longitude(texto: str) -> float:
    return _numero(texto, -180, 180)


def intervalo(texto: str) -> float:
    return _numero(texto, 0, 86400)


def quantidade(texto: str) -> int:
    try:
        valor = int(texto)
    except ValueError:
        raise argparse.ArgumentTypeError(f"'{texto}' nao e um inteiro") from None
    if valor < 1:
        raise argparse.ArgumentTypeError("precisa ser pelo menos 1")
    return valor


def porta(texto: str) -> int:
    try:
        valor = int(texto)
    except ValueError:
        raise argparse.ArgumentTypeError(f"'{texto}' nao e um inteiro (veja --porta ou MQTT_PORTA)") from None
    if not 1 <= valor <= 65535:
        raise argparse.ArgumentTypeError("precisa estar entre 1 e 65535")
    return valor


def descricao(texto: str) -> str:
    # len() conta caracteres (code points), como o worker e o PostgreSQL.
    # No Dart, String.length conta unidades UTF-16 (um emoji vale 2); la o
    # equivalente e texto.runes.length
    if len(texto) > DESCRICAO_MAX:
        raise argparse.ArgumentTypeError(f"tem {len(texto)} caracteres; o maximo e {DESCRICAO_MAX}")
    try:
        texto.encode("utf-8")
    except UnicodeEncodeError:
        # no Linux, bytes invalidos no argv chegam como "surrogates", que nao
        # tem codificacao UTF-8
        raise argparse.ArgumentTypeError("tem bytes que nao sao UTF-8 valido") from None
    return texto


def main() -> None:
    p = argparse.ArgumentParser(description="Publica relatos assinados no broker MQTT.")
    p.add_argument("--host", default=os.environ.get("MQTT_HOST") or "localhost")
    # default em texto: assim o argparse valida o MQTT_PORTA do mesmo jeito que o --porta
    p.add_argument("--porta", type=porta, default=os.environ.get("MQTT_PORTA") or "1883")
    p.add_argument("--tipo", choices=TIPOS, default="alagamento")
    p.add_argument("--severidade", type=int, choices=range(1, 6), default=3, metavar="1-5")
    p.add_argument("--lat", type=latitude, default=-23.5505)
    p.add_argument("--lon", type=longitude, default=-46.6333)
    p.add_argument("--descricao", type=descricao, default="agua na altura do meio-fio",
                   help=f"ate {DESCRICAO_MAX} caracteres")
    p.add_argument("--quantidade", type=quantidade, default=1)
    p.add_argument("--intervalo", type=intervalo, default=1.0, help="segundos entre relatos")
    args = p.parse_args()

    try:
        chave = obter_chave_privada()
    except OSError as e:
        sys.exit(f"nao foi possivel ler ou criar {CAMINHO_CHAVE_PRIVADA}: {e}")
    try:
        cliente = conectar(args.host, args.porta)
    except (OSError, ValueError) as e:
        sys.exit(f"nao foi possivel conectar em {args.host}:{args.porta}: {e}")
    print(f"conectado a {args.host}:{args.porta}")

    enviados = 0
    try:
        for i in range(args.quantidade):
            relato = montar_relato(chave, args.tipo, args.lat, args.lon, args.severidade, args.descricao)
            topico = publicar(cliente, relato)
            enviados += 1
            print(f"  {relato['id'][:8]}  {topico}  sev {relato['severidade']}  confirmado pelo broker (PUBACK)")
            if i + 1 < args.quantidade:
                time.sleep(args.intervalo)
    except KeyboardInterrupt:
        print("interrompido")
    except RuntimeError as e:
        print(f"erro: {e}", file=sys.stderr)
    finally:
        cliente.disconnect()
        cliente.loop_stop()

    print(f"{enviados} de {args.quantidade} relato(s) confirmado(s) pelo broker; se o worker "
          "aceitou, quem diz e o log dele")
    if enviados < args.quantidade:
        sys.exit(1)


if __name__ == "__main__":
    main()
