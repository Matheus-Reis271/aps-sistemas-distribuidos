package com.aps.ambiental;

import java.sql.SQLException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTransientException;
import java.util.Set;

/**
 * O que fazer quando o INSERT falhar (a gravacao ainda nao existe). A pergunta
 * e se o problema e do banco ou do relato: repetir um relato que o banco
 * nunca aceita trava a fila; descartar porque o banco caiu perde um relato bom.
 * Usada so pela thread de callback do Paho, por isso o contador nao e atomico.
 */
final class ErroBanco {

    enum Acao {
        /** banco fora do ar ou sobrecarregado: espera e tenta de novo, sem confirmar */
        REPETIR,
        /** o banco vai recusar este relato sempre: rejeita e confirma */
        REJEITAR,
        /** erro de configuracao: encerra o worker sem confirmar */
        ENCERRAR
    }

    // muitas recusas seguidas indicam schema errado, nao relatos ruins
    static final int LIMITE_REJEICOES_SEGUIDAS = 20;

    private static final Set<String> PASSAGEIROS = Set.of(
            "57P01", "57P02", "57P03", "57P05",  // banco desligando, reiniciando ou sessao expirada
            "57014",                             // consulta cancelada (statement_timeout)
            "40001", "40P01");                   // conflito entre transacoes

    private int rejeicoesSeguidas;

    /** Classifica a falha e aplica o disjuntor de rejeicoes seguidas. */
    Acao decidir(SQLException e) {
        Acao acao = classificar(e);
        if (acao == Acao.REJEITAR && ++rejeicoesSeguidas >= LIMITE_REJEICOES_SEGUIDAS) {
            return Acao.ENCERRAR;
        }
        return acao;
    }

    /** Chamar depois de cada INSERT bem-sucedido. */
    void gravou() {
        rejeicoesSeguidas = 0;
    }

    static Acao classificar(SQLException e) {
        String estado = e.getSQLState() == null ? "" : e.getSQLState();
        if (e instanceof SQLTransientException || e instanceof SQLRecoverableException
                || estado.startsWith("08")       // conexao
                || estado.startsWith("53")       // sem conexoes livres, memoria ou disco
                || PASSAGEIROS.contains(estado)) {
            return Acao.REPETIR;
        }
        if (estado.startsWith("22") || estado.startsWith("23")) {   // dado invalido ou restricao
            return Acao.REJEITAR;
        }
        // senha (28), tabela ou permissao (42), banco apagado ou inexistente
        // (57P04, 3D) e desconhecidos: a mensagem espera no broker
        return Acao.ENCERRAR;
    }
}
