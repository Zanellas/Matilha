# ADR-0011: Consumidor idempotente e Dead Letter Topic

- **Status:** Aceito
- **Data:** 2026-11

## Contexto

O notification consome `TransferCompleted` e `TransferFailed` do tópico `matilha.transfer.events`. O outbox do transfer entrega **pelo menos uma vez** (ADR-0008), então o mesmo evento pode chegar mais de uma vez.

Além disso, existem dois tipos de falha muito diferentes:

- **Mensagem envenenada:** o defeito está na mensagem (JSON quebrado, versão nova, tipo desconhecido, campo obrigatório faltando). Ela falha sempre, não importa quantas vezes seja repetida.
- **Falha de infraestrutura:** o defeito está no ambiente (banco fora do ar, por exemplo). Todas as mensagens falham, e todas passariam se o ambiente voltasse.

Uma mensagem envenenada não pode travar a partição nem sumir sem registro. Uma mensagem boa não pode ser descartada só porque o banco caiu por alguns minutos.

## Decisão

- **Contrato próprio:** `TransferEventMessageDto` é uma cópia do evento do transfer, sem importar a classe dele (ADR-0009). Campos desconhecidos são ignorados (`@JsonIgnoreProperties`), para o transfer poder acrescentar campos sem quebrar o consumidor.
- **Código de falha tolerante:** o `failureCode` chega como `String` e é convertido por `FailureCode.fromCode()`. Um código desconhecido vira `REJECTED_BY_ACCOUNT`, a mesma regra que o transfer usa com os códigos do account (ADR-0007). O cliente continua recebendo a notificação de falha, mesmo que o transfer crie um código novo.
- **Mensagem como texto:** o listener recebe `String` e converte com o `JsonMapper`, sem depender da configuração de serializer do produtor.
- **Idempotência:** a tabela `processed_events` (`event_id` como chave primária) é gravada **na mesma transação** que as notificações. Se o `eventId` já existe, o evento é ignorado. O `ProcessedEvent` implementa `Persistable`, então o Hibernate faz um `INSERT` direto, e não um merge. Assim, se dois consumidores processarem o mesmo evento ao mesmo tempo, a chave primária rejeita o segundo e a transação dele inteira volta atrás.
- **Tabela separada** em vez de `UNIQUE (event_id)` em `notifications`: um evento gera de 1 a 2 notificações, e a tabela registra o fato "evento tratado", independentemente de quantas foram geradas.
- **Offset depois do commit:** o auto-commit do Kafka fica desligado (padrão do Spring Kafka). O offset é confirmado quando o listener retorna, e a essa altura a transação já foi gravada.
- **Mensagens envenenadas vão direto para a DLT**, sem nova tentativa: `JacksonException` (JSON inválido), `UnsupportedEventException` (versão maior que a suportada, `eventType` desconhecido) e `BusinessException` (evento malformado, por exemplo sem conta de origem).
- **Falhas de infraestrutura são repetidas sem limite**, com backoff exponencial: começa em 1 segundo, dobra a cada tentativa e para de crescer em 30 segundos. A partição fica parada enquanto o problema durar, e isso é correto: as mensagens seguintes falhariam do mesmo jeito, e esperar preserva a ordem.
- A violação de chave primária na corrida entre consumidores também é repetida de propósito: na nova tentativa, o evento é reconhecido como já processado e ignorado.
- **DLT com nome explícito:** `matilha.transfer.events.DLT`, criada pelo notification com 3 partições. A mensagem vai para a mesma partição da original, por isso a DLT tem pelo menos tantas partições quanto o tópico. Os cabeçalhos guardam a exceção e a origem (tópico, partição e offset).

## Alternativas consideradas

- **`UNIQUE (event_id)` em `notifications`:** quebra quando um evento gera 2 notificações.
- **Comportamento padrão do Spring Kafka** (algumas tentativas e descarte com log): a mensagem se perde.
- **Número fixo de tentativas para tudo** (por exemplo, 3 com 1 segundo): um banco fora do ar por alguns segundos mandaria mensagens **boas** para a DLT, e ninguém as reprocessa hoje.
- **`failureCode` como enum direto no JSON:** um código novo no transfer faria a leitura falhar, e a notificação de falha iria para a DLT em vez de chegar ao cliente.
- **`@RetryableTopic`** (tópicos de retry com atraso): não bloqueia a partição, mas perde a ordem e exige mais tópicos. Fica para o futuro.
- **Schema Registry com Avro:** valida o contrato na publicação, mas exige mais infraestrutura.

## Consequências

- Eventos repetidos não geram notificações repetidas.
- Mensagens envenenadas saem do caminho na primeira falha e ficam guardadas na DLT, com o motivo nos cabeçalhos.
- Mensagens boas nunca vão para a DLT por causa de uma falha temporária do ambiente.
- Enquanto o banco estiver fora do ar, o consumo para e as notificações atrasam. Elas não se perdem.
- Um código de falha novo no transfer não quebra o notification. Já um tipo de evento novo ou uma versão nova exigem atualizar o notification antes, ou junto. Até lá, essas mensagens ficam na DLT.
- Ainda não existe ferramenta para reprocessar a DLT.
- `processed_events` cresce para sempre. A limpeza dos registros antigos fica para o futuro.