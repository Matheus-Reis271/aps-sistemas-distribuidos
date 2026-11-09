#!/usr/bin/env bash
# Cria no broker os usuarios worker e publicador, cada um so com o que precisa.
# Rodar uma vez, com o broker no ar, da pasta do projeto (no Windows, pelo Git Bash):
#   bash mosquitto/configurar-acl.sh
set -euo pipefail
cd "$(dirname "$0")/.."

if [ ! -f .env ]; then
    echo "crie o arquivo .env a partir do .env.exemplo antes de rodar" >&2
    exit 1
fi
# tr -d '\r': o .env salvo no Bloco de Notas termina as linhas com CRLF
ADMIN=$(grep '^MOSQUITTO_DYNSEC_PASSWORD=' .env | cut -d= -f2- | tr -d '\r')

pedir_senha() {
    local senha
    read -r -s -p "Senha para o usuario $1 (minimo 12 caracteres): " senha
    echo >&2
    if [ "${#senha}" -lt 12 ]; then
        echo "senha curta demais" >&2
        exit 1
    fi
    printf '%s' "$senha"
}
SENHA_WORKER=${SENHA_WORKER:-$(pedir_senha worker)}
SENHA_PUBLICADOR=${SENHA_PUBLICADOR:-$(pedir_senha publicador)}

# o mosquitto_ctrl sai com 0 mesmo quando falha: erro e qualquer saida que
# sobre depois de tirar o aviso de conexao sem TLS (dentro do container)
ctrl() {
    local saida
    saida=$(docker compose exec -T broker mosquitto_ctrl -u admin -P "$ADMIN" dynsec "$@" 2>&1 \
        | grep -v -e 'without encryption' -e 'visible on the network' -e '^[[:space:]]*$' || true)
    if [ -n "$saida" ]; then
        echo "$saida" >&2
        exit 1
    fi
}

# worker: so le relatos (assinatura comum ou compartilhada)
ctrl createRole worker
ctrl addRoleACL worker subscribePattern 'ambiental/relato/+' allow
ctrl addRoleACL worker subscribePattern '$share/+/ambiental/relato/+' allow
ctrl addRoleACL worker publishClientReceive 'ambiental/relato/+' allow
ctrl createClient worker -p "$SENHA_WORKER"
ctrl addClientRole worker worker

# publicador: so publica relatos
ctrl createRole publicador
ctrl addRoleACL publicador publishClientSend 'ambiental/relato/+' allow
ctrl createClient publicador -p "$SENHA_PUBLICADOR"
ctrl addClientRole publicador publicador

echo "pronto: usuarios worker e publicador criados"
