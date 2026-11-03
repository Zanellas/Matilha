# ADR-0007: Códigos de erro estáveis entre serviços

- **Status:** Aceito
- **Data:** 2026-11

## Contexto

No começo, o transfer guardava em `failureReason` o texto que o account devolvia, como `"Insufficient balance"`. Esse texto pode mudar a qualquer momento, e com os eventos ele passaria a chegar também ao notification. Três serviços dependeriam de uma frase em inglês.

## Decisão

- O account devolve erros no formato Problem Details (RFC 9457) com uma propriedade `code`, vinda do enum `ErrorCode`: `ACCOUNT_NOT_FOUND`, `ACCOUNT_INACTIVE`, `INSUFFICIENT_BALANCE` e `BUSINESS_RULE_VIOLATION`.
- O transfer traduz esse código para o **próprio** enum, `FailureCode`: `INSUFFICIENT_BALANCE`, `ACCOUNT_NOT_FOUND`, `ACCOUNT_INACTIVE`, `REJECTED_BY_ACCOUNT` e `INVALID_REQUEST`. Um código que ele não conhece vira `REJECTED_BY_ACCOUNT`.
- O código fica gravado em `failure_code`, com `CHECK` no banco. Ele é obrigatório quando a transferência está `FAILED` e proibido nos outros status.
- O texto continua existindo, mas só como detalhe legível para pessoas.

## Alternativas consideradas

- **Compartilhar o enum numa biblioteca comum.** Acopla os deploys dos serviços (ADR-0009).
- **Repassar o texto.** É um contrato frágil, que quebra com uma simples mudança de mensagem.

## Consequências

- Clientes e consumidores de eventos têm um contrato estável e podem tratar cada caso com segurança.
- O account pode criar códigos novos sem quebrar o transfer, que cai no código genérico.
- O mapeamento entre os dois enums precisa ser mantido.
