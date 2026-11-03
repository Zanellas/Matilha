# ADR-0006: Reconciliador de pendentes com `SKIP LOCKED` e lease

- **Status:** Aceito
- **Data:** 2026-11

## Contexto

Uma transferência pode ficar `PENDING` por dois motivos: o account estava fora do ar, ou o account moveu o dinheiro e a gravação do resultado falhou. Se o cliente reenviar, o transfer devolve a `PENDING` sem chamar o account (ADR-0004), então ela nunca se resolveria sozinha. Além disso, o serviço pode rodar com várias réplicas.

## Decisão

- O `TransferReconciler` roda a cada 60 segundos e pega as transferências `PENDING` com mais de 30 segundos, em lotes de 20.
- A busca usa `SELECT ... FOR UPDATE SKIP LOCKED` numa transação curta, que grava uma reserva em `claimed_until` (agora + 5 minutos). Cada pendente é pega por uma réplica só.
- Depois de reservar, ele reenvia ao account **com o mesmo `transferId`**. Como o account é idempotente (ADR-0004), ele devolve o resultado original ou processa agora. Em seguida, finaliza a transferência como na requisição normal.
- Se a réplica cair no meio, a reserva expira e outra réplica assume.
- Se uma requisição e o reconciliador finalizarem a mesma transferência, o `@Version` deixa só um gravar.
- Um índice parcial (`WHERE status = 'PENDING'`) mantém a busca rápida mesmo com a tabela grande.

## Alternativas consideradas

- **Depender do cliente para reenviar.** O reenvio devolve a `PENDING` sem chamar o account, então ela ficaria parada para sempre.
- **Lock distribuído** (ShedLock, Redis). Seria uma dependência nova para um problema que o PostgreSQL já resolve.
- **Manter o lock da linha durante a chamada HTTP.** Volta ao problema de prender conexões descrito no ADR-0005.

## Consequências

- O sistema se corrige sozinho, e o mecanismo funciona com várias réplicas.
- Os parâmetros dependem uns dos outros: a idade mínima precisa ser maior que os timeouts somados, e a reserva precisa cobrir um lote inteiro no pior caso.
- Uma transferência pendente pode levar cerca de um minuto e meio para ser resolvida.
