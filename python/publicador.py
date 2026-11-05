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


def obter_chave_privada() -> Ed25519PrivateKey:
    if os.path.exists(CAMINHO_CHAVE_PRIVADA):
        with open(CAMINHO_CHAVE_PRIVADA, "rb") as f:
            return Ed25519PrivateKey.from_private_bytes(f.read())

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
    # confirmou (PUBACK) e o is_published()
    info.wait_for_publish(timeout=timeout)
    if not info.is_published():
        raise RuntimeError(f"broker nao confirmou o relato {relato['id'][:8]} em {timeout:.0f}s")
    return topico


def main() -> None:
    p = argparse.ArgumentParser(description="Publica relatos assinados no broker MQTT.")
    p.add_argument("--host", default=os.environ.get("MQTT_HOST", "localhost"))
    p.add_argument("--porta", type=int, default=int(os.environ.get("MQTT_PORTA", "1883")))
    p.add_argument("--tipo", choices=TIPOS, default="alagamento")
    p.add_argument("--severidade", type=int, choices=range(1, 6), default=3, metavar="1-5")
    p.add_argument("--lat", type=float, default=-23.5505)
    p.add_argument("--lon", type=float, default=-46.6333)
    p.add_argument("--descricao", default="agua na altura do meio-fio")
    p.add_argument("--quantidade", type=int, default=1)
    p.add_argument("--intervalo", type=float, default=1.0, help="segundos entre relatos")
    args = p.parse_args()

    chave = obter_chave_privada()
    try:
        cliente = conectar(args.host, args.porta)
    except (OSError, ConnectionError) as e:
        sys.exit(f"nao foi possivel conectar em {args.host}:{args.porta}: {e}")
    print(f"conectado a {args.host}:{args.porta}")

    enviados = 0
    try:
        for i in range(args.quantidade):
            relato = montar_relato(chave, args.tipo, args.lat, args.lon, args.severidade, args.descricao)
            topico = publicar(cliente, relato)
            enviados += 1
            print(f"  {relato['id'][:8]}  {topico}  sev {relato['severidade']}  confirmado pelo broker")
            if i + 1 < args.quantidade:
                time.sleep(args.intervalo)
    except KeyboardInterrupt:
        print("interrompido")
    except RuntimeError as e:
        print(f"erro: {e}", file=sys.stderr)
    finally:
        cliente.disconnect()
        cliente.loop_stop()

    print(f"{enviados} de {args.quantidade} relato(s) publicado(s)")
    if enviados < args.quantidade:
        sys.exit(1)


if __name__ == "__main__":
    main()
