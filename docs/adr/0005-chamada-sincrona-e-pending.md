# ADR-0005: Chamada síncrona fora de transação e `PENDING` quando o resultado é desconhecido

- **Status:** Aceito
- **Data:** 2026-11

## Contexto

O transfer chama o account por HTTP. Essa chamada pode demorar, falhar ou dar timeout **depois** de o account já ter movido o dinheiro. Duas perguntas precisavam de resposta: como não prender recursos durante a espera, e qual status registrar quando não se sabe o resultado.

## Decisão

O processamento acontece em três passos:

1. Grava a transferência como `PENDING` (transação curta).
2. Chama o account **fora de qualquer transação**, com timeouts explícitos: 2 segundos para conectar e 5 para ler.
3. Grava o resultado (transação curta, no `TransferFinisher`).

| Resposta do account | Resultado |
|---|---|
| `200` | `COMPLETED` |
| `404` ou `422` | `FAILED`, com o código do erro (ADR-0007) |
| `400` | `FAILED` com `INVALID_REQUEST` e log de erro: o transfer mandou um pedido inválido, o que é um bug |
| `409`, `5xx`, timeout ou conexão recusada | Continua `PENDING` |

Para o cliente: `201` quando `COMPLETED`, `202` quando `PENDING` e `422` (Problem Details com o `transferId`) quando `FAILED`.

## Alternativas consideradas

- **Manter a transação aberta durante a chamada HTTP.** Cada chamada lenta prende uma conexão do pool. Com o account lento, o pool esgota e o transfer inteiro para.
- **Tratar timeout como `FAILED`.** O dinheiro pode ter saído e o sistema registraria que não saiu.
- **Processamento totalmente assíncrono por fila.** O cliente nunca receberia o resultado na hora. Fica como evolução possível.

## Consequências

- O pool de conexões fica protegido, e o status registrado é sempre verdadeiro.
- Passa a existir um estado intermediário, `PENDING`, que alguém precisa resolver. Isso é o ADR-0006.
