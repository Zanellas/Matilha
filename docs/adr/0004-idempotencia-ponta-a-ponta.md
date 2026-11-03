<!-- RASCUNHO: reescrever com as suas palavras antes de commitar -->
# ADR-0004: Idempotência ponta a ponta

- **Status:** Aceito
- **Data:** 2026-11

## Contexto

Redes falham. O cliente pode não receber a resposta e tentar de novo, e o transfer pode dar timeout no account e reenviar. Em um sistema financeiro, **repetir um pedido nunca pode mover o dinheiro duas vezes**.

## Decisão

A idempotência existe nas duas pontas da transferência.

**Cliente → transfer**

- O `POST /transfers` exige o header `Idempotency-Key`, um UUID gerado pelo cliente. A chave é `UNIQUE` no banco.
- Mesma chave com os mesmos dados: o transfer devolve a transferência existente, sem chamar o account de novo.
- Mesma chave com dados diferentes: `422`.
- Duas requisições com a mesma chave ao mesmo tempo: o `UNIQUE` deixa só uma criar a transferência, e a outra relê e devolve a vencedora.

**Transfer → account**

- O `id` da transferência vai para o account como `transferId`. O id é gravado antes da chamada, então todo reenvio usa o mesmo.
- O account grava o id da operação em `account_movements`, com `UNIQUE (operation_id, type)`. Um id repetido devolve o resultado original. Com dados diferentes, devolve `422`.

## Alternativas consideradas

- **O servidor gerar o id sem chave do cliente.** Se a resposta se perder, o reenvio do cliente cria uma segunda transferência.
- **Detectar duplicidade por janela de tempo ou por hash dos dados.** É frágil: duas transferências legítimas e iguais seriam bloqueadas.

## Consequências

- Qualquer passo do fluxo pode ser repetido com segurança. É isso que permite o reconciliador (ADR-0006) e o outbox (ADR-0008).
- O cliente precisa gerar e guardar a chave. Para tentar de novo uma transferência `FAILED`, ele usa uma chave nova.
