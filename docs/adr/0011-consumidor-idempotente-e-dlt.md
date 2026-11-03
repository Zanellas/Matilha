# ADR-0011: Consumidor idempotente e Dead Letter Topic

- **Status:** Aceito
- **Data:** 2026-11

## Contexto

O notification consome `TransferCompleted` e `TransferFailed` do tópico `matilha.transfer.events`. O outbox do transfer entrega **pelo menos uma vez** (ADR-0008), então o mesmo evento pode chegar mais de uma vez. Além disso, pode chegar uma mensagem que o consumidor não sabe processar: JSON quebrado, versão nova, tipo ou código desconhecido. Essa mensagem não pode travar a partição nem sumir sem registro.

## Decisão

- **Contrato próprio:** `TransferEventMessage` é uma cópia do evento do transfer, sem importar a classe dele (ADR-0009). Campos desconhecidos são ignorados (`@JsonIgnoreProperties`), para o transfer poder acrescentar campos. O `failureCode` é um enum próprio: um código desconhecido **falha** na leitura, em vez de virar `null` ou texto que ninguém entende.
- **Mensagem como texto:** o listener recebe `String` e converte com o `JsonMapper`, sem depender da configuração de serializer do produtor.
- **Idempotência:** a tabela `processed_events` (`event_id` como chave primária) é gravada **na mesma transação** que as notificações. Se o `eventId` já existe, o evento é ignorado. Se dois consumidores processarem o mesmo evento ao mesmo tempo, a chave primária rejeita o segundo e a transação dele inteira volta atrás.
- **Tabela separada** em vez de `UNIQUE (event_id)` em `notifications`: um evento gera de 0 a 2 notificações, e a tabela registra o fato "evento tratado", independentemente de quantas foram geradas.
- **Offset depois do commit:** o auto-commit do Kafka fica desligado (padrão do Spring Kafka). O offset é confirmado quando o listener retorna, e a essa altura a transação já foi gravada.
- **Erros:** `DefaultErrorHandler` com 3 tentativas e 1 segundo de intervalo, e depois `DeadLetterPublishingRecoverer`. Erros que não adianta repetir vão direto para a DLT: `JacksonException` (JSON inválido, enum desconhecido), `UnsupportedEventException` (versão maior que a suportada, `eventType` desconhecido) e `BusinessException` (evento malformado, por exemplo falha sem código).
- **DLT com nome explícito:** `matilha.transfer.events.DLT`, criada pelo notification com 3 partições. A mensagem vai para a mesma partição da original, por isso a DLT tem pelo menos tantas partições quanto o tópico. Os cabeçalhos guardam a exceção e a origem (tópico, partição, offset).
- A violação de chave primária na corrida entre consumidores **continua retentável** de propósito: na nova tentativa, o evento é reconhecido como já processado.

## Alternativas consideradas

- **`UNIQUE (event_id)` em `notifications`:** quebra com 2 notificações por evento e não registra eventos que geram zero.
- **Comportamento padrão (10 tentativas e descarte com log):** a mensagem se perde.
- **`@RetryableTopic` (tópicos de retry com atraso):** não bloqueia a partição, mas perde a ordem e exige mais tópicos. Fica para o futuro.
- **`failureCode` como `String`:** não quebraria a leitura, mas o código novo passaria sem ser entendido e seria barrado no CHECK do banco.
- **Schema Registry com Avro:** valida o contrato na publicação, mas exige mais infraestrutura.

## Consequências

- Eventos repetidos não geram notificações repetidas.
- Mensagens envenenadas saem do caminho na primeira falha e ficam guardadas na DLT, com o motivo nos cabeçalhos.
- Quando o transfer criar um tipo, uma versão ou um código novo, o notification precisa ser atualizado antes, ou junto. Até lá, essas mensagens ficam na DLT. Ainda não existe ferramenta para reprocessar a DLT.
- **Falha de infraestrutura vs. mensagem envenenada:** na mensagem envenenada, o defeito está na mensagem, e ela falha sempre. Na falha de infraestrutura, o defeito está no ambiente: todas falham, e todas passariam se o banco voltasse. Com 3 tentativas, um banco fora do ar por alguns segundos manda mensagens **boas** para a DLT. Isso está aceito por enquanto, porque elas podem ser reprocessadas. A melhoria é retentar falhas de infraestrutura sem limite, com backoff exponencial. A partição fica parada enquanto o banco estiver fora, e isso é correto, porque as mensagens seguintes falhariam do mesmo jeito.
- `processed_events` cresce para sempre. A limpeza dos registros antigos fica para o futuro.
