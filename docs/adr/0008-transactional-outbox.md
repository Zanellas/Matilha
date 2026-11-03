<!-- RASCUNHO: reescrever com as suas palavras antes de commitar -->
# ADR-0008: Transactional Outbox para publicar eventos

- **Status:** Aceito
- **Data:** 2026-11

## Contexto

Quando uma transferência termina, o transfer precisa publicar `TransferCompleted` ou `TransferFailed` no Kafka. Gravar no banco e publicar no Kafka são duas operações em sistemas diferentes, e não existe transação que abranja os dois (o problema do *dual write*):

- Publicar **depois** do commit: se o Kafka estiver fora do ar, o evento se perde.
- Publicar **antes** do commit: se a gravação falhar, sai um evento de algo que não aconteceu.

## Decisão

- **Gravação:** o `TransferFinisher` grava o novo status da transferência e uma linha em `outbox_events` **na mesma transação**. Ele é um bean separado porque o `@Transactional` não funciona quando o método é chamado de dentro da própria classe.
- **Publicação:** o `OutboxPublisher` roda a cada segundo. Ele reserva lotes de até 50 eventos com `SKIP LOCKED` e `claimed_until` (1 minuto), como o reconciliador (ADR-0006), e envia ao tópico `matilha.transfer.events`.
- **`published_at` só é gravado depois da confirmação do Kafka** (`acks=all`, espera de até 15 segundos). Na primeira falha, o lote para, para não inverter a ordem dos eventos. O publicador registra as tentativas e o último erro.
- A chave da mensagem é a conta de origem, o que mantém em ordem os eventos da mesma conta. O tópico tem 3 partições.
- `UNIQUE (aggregate_id, event_type)`: existe no máximo um evento de cada tipo por transferência.
- O payload é JSON versionado: `eventId`, `eventType`, `eventVersion`, `occurredAt` e o valor como texto, para não virar número de ponto flutuante no consumidor.

## Alternativas consideradas

- **Publicar direto, antes ou depois do commit:** os dois casos do contexto.
- **Transação distribuída entre banco e Kafka:** complexa e sem garantia real.
- **CDC (Debezium) lendo o log do banco:** resolve o mesmo problema, mas exige mais infraestrutura.

## Consequências

- Nenhum evento se perde.
- A entrega é **pelo menos uma vez**. Se o Kafka confirmar e a gravação do `published_at` falhar, o evento é reenviado. Por isso o consumidor precisa descartar duplicatas pelo `eventId`.
- Há uma latência de até cerca de 1 segundo entre o commit e a publicação.
- A tabela cresce com o tempo. A limpeza dos eventos publicados fica para o futuro.
