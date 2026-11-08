package com.aps.ambiental;

import java.sql.SQLException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTransientException;

/**
 * O que o worker deve fazer quando o INSERT no banco falhar. Ainda nao ha
 * gravacao; a regra fica pronta para quando ela entrar.
 *
 * A pergunta e sempre a mesma: o problema e do banco ou do relato? Repetir
 * um relato que o banco nunca vai aceitar trava a fila do mesmo jeito que uma
 * mensagem envenenada. Descartar um relato porque o banco estava fora do ar
 * perde um relato valido.
 */
final class ErroBanco {

    enum Acao {
        /** banco fora do ar ou sobrecarregado: espera, tenta de novo e so confirma depois de gravar */
        REPETIR,
        /** o banco recusou este relato e vai recusar sempre: rejeita e confirma */
        REJEITAR,
        /** erro de configuracao do worker: encerra sem confirmar a mensagem */
        ENCERRAR
    }

    private ErroBanco() {
    }

    static Acao classificar(SQLException e) {
        String estado = e.getSQLState() == null ? "" : e.getSQLState();

        if (e instanceof SQLTransientException || e instanceof SQLRecoverableException
                || estado.startsWith("08")        // conexao caiu ou nao abriu
                || estado.startsWith("53")        // banco sem recurso: conexoes, memoria, disco
                || estado.startsWith("57P0")      // banco desligando ou reiniciando
                || estado.equals("40001")         // conflito de serializacao entre transacoes
                || estado.equals("40P01")) {      // deadlock
            return Acao.REPETIR;
        }
        if (estado.startsWith("22")               // dado invalido: data, numero, texto
                || estado.startsWith("23")) {     // restricao violada: NOT NULL, CHECK...
            return Acao.REJEITAR;
        }
        // Senha errada (28), tabela, coluna ou permissao (42), banco que nao
        // existe (3D) e qualquer estado desconhecido. O relato nao tem culpa,
        // entao nao pode ser descartado, e repetir nao resolve. Parar sem
        // confirmar deixa a mensagem guardada no broker ate alguem corrigir a
        // configuracao e religar o worker.
        return Acao.ENCERRAR;
    }
}
