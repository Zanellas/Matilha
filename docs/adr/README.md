# Registros de decisão de arquitetura (ADRs)

Cada arquivo registra uma decisão importante do Matilha: o problema, o que foi decidido, as alternativas descartadas e o preço da escolha.

| ADR | Decisão |
|---|---|
| [0001](0001-monorepo-e-banco-por-servico.md) | Monorepo com serviços independentes e um banco por serviço |
| [0002](0002-account-dono-do-saldo.md) | O account é o único dono do saldo; o transfer orquestra |
| [0003](0003-concorrencia-no-saldo.md) | Concorrência no saldo com lock pessimista em ordem fixa |
| [0004](0004-idempotencia-ponta-a-ponta.md) | Idempotência ponta a ponta |
| [0005](0005-chamada-sincrona-e-pending.md) | Chamada síncrona fora de transação e `PENDING` quando o resultado é desconhecido |
| [0006](0006-reconciliador-de-pendentes.md) | Reconciliador de pendentes com `SKIP LOCKED` e lease |
| [0007](0007-codigos-de-erro-entre-servicos.md) | Códigos de erro estáveis entre serviços |
| [0008](0008-transactional-outbox.md) | Transactional Outbox para publicar eventos |
| [0009](0009-codigo-duplicado-sem-biblioteca-comum.md) | Código transversal duplicado em vez de biblioteca compartilhada |
| [0010](0010-flyway-e-testes-com-postgres-real.md) | Schema com Flyway e testes contra PostgreSQL real |
| [0011](0011-consumidor-idempotente-e-dlt.md) | Consumidor idempotente e Dead Letter Topic |


## Formato

- **Contexto**: o problema e as restrições
- **Decisão**: o que foi feito
- **Alternativas consideradas**: o que foi descartado e por quê
- **Consequências**: o que se ganha e o que se paga

Uma decisão aceita não é editada. Se ela mudar, um ADR novo a substitui, e o antigo recebe o status "Substituído por ADR-XXXX".
