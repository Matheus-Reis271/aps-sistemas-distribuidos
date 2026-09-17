# APS - Sistema distribuido para monitoramento ambiental urbano
# Autores: NOME1, NOME2, NOME3

import base64
import json
import os
import uuid
from datetime import datetime, timezone

import paho.mqtt.client as mqtt
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from cryptography.hazmat.primitives import serialization

BROKER_HOST = "localhost"
BROKER_PORT = 1883
CAMINHO_CHAVE_PRIVADA = "chave_privada.bin"


def obter_par_de_chaves() -> Ed25519PrivateKey:
    if os.path.exists(CAMINHO_CHAVE_PRIVADA):
        with open(CAMINHO_CHAVE_PRIVADA, "rb") as f:
            return Ed25519PrivateKey.from_private_bytes(f.read())

    chave_privada = Ed25519PrivateKey.generate()
    dados = chave_privada.private_bytes(
        encoding=serialization.Encoding.Raw,
        format=serialization.PrivateFormat.Raw,
        encryption_algorithm=serialization.NoEncryption(),
    )
    # 0o600: so o dono le/escreve, chave privada nao pode ficar com permissao padrao
    fd = os.open(CAMINHO_CHAVE_PRIVADA, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "wb") as f:
        f.write(dados)
    return chave_privada


def chave_publica_base64(chave_privada: Ed25519PrivateKey) -> str:
    dados = chave_privada.public_key().public_bytes(
        encoding=serialization.Encoding.Raw,
        format=serialization.PublicFormat.Raw,
    )
    return base64.b64encode(dados).decode("ascii")


def json_canonico(obj: dict) -> bytes:
    return json.dumps(
        obj, sort_keys=True, separators=(",", ":"), ensure_ascii=False
    ).encode("utf-8")


def montar_relato(chave_privada: Ed25519PrivateKey) -> dict:
    relato = {
        "id": str(uuid.uuid4()),
        "tipo": "alagamento",
        "lat": -23.5505,
        "lon": -46.6333,
        "severidade": 3,
        "descricao": "agua na altura do meio-fio",
        "ts_dispositivo": datetime.now(timezone.utc).isoformat(),
        "chave_publica": chave_publica_base64(chave_privada),
    }

    assinatura = chave_privada.sign(json_canonico(relato))
    relato["assinatura"] = base64.b64encode(assinatura).decode("ascii")
    return relato


def publicar(relato: dict) -> None:
    topico = f"ambiental/relato/{relato['tipo']}"

    # payload no fio = a mesma serializacao canonica usada para assinar,
    # incluindo agora o campo assinatura. isso permite o lado que verifica
    # remover o campo por corte de texto em vez de reconstruir o objeto.
    payload = json_canonico(relato)

    cliente = mqtt.Client(
        mqtt.CallbackAPIVersion.VERSION2,
        client_id=f"publicador-{relato['id'][:8]}",
    )
    cliente.connect(BROKER_HOST, BROKER_PORT)
    cliente.loop_start()
    info = cliente.publish(topico, payload, qos=1)
    entregue = info.wait_for_publish(timeout=5)
    if not entregue:
        raise RuntimeError(f"Publicacao nao confirmada em 5s (rc={info.rc})")
    cliente.loop_stop()
    cliente.disconnect()

    print(f"publicado em {topico}: {relato['id']}")


if __name__ == "__main__":
    chave_privada = obter_par_de_chaves()
    relato = montar_relato(chave_privada)
    publicar(relato)
