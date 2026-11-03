# ADR-0001: Monorepo com serviços independentes e um banco por serviço

- **Status:** Aceito
- **Data:** 2026-11

## Contexto

O Matilha tem quatro serviços (account, transfer, notification e gateway) e é mantido por uma pessoa. O objetivo é praticar microsserviços de verdade: cada serviço com build, deploy e dados próprios. Ao mesmo tempo, o projeto precisa ser fácil de clonar, rodar e avaliar.

## Decisão

- **Um repositório, uma pasta por serviço.** Cada serviço tem o próprio `pom.xml`, `Dockerfile`, `compose.yaml`, README e workflow de CI. O CI de cada serviço só roda quando a pasta dele muda.
- **Um banco por serviço.** O account usa o `account-db` e o transfer usa o `transfer-db`, cada um no seu container PostgreSQL. Nenhum serviço acessa o banco de outro.
- **Comunicação só por API ou evento:** HTTP para pedidos que precisam de resposta, Kafka para avisar que algo aconteceu.
- **Infraestrutura compartilhada em `infra/`** (o Kafka), separada dos serviços.

## Alternativas consideradas

- **Um repositório por serviço.** É o mais fiel a times separados, mas para uma pessoa multiplica o trabalho de versionamento, CI e documentação sem trazer aprendizado novo.
- **Um banco só, com um schema por serviço.** É mais simples de operar, mas permite que um serviço leia ou altere dados de outro. O acoplamento passa a acontecer pelo dado, e a fronteira entre os serviços deixa de existir na prática.

## Consequências

- Cada serviço compila, testa e sobe sozinho, e as fronteiras ficam explícitas.
- Não existe transação entre serviços. Isso obrigou a resolver a consistência de outro jeito: idempotência (ADR-0004), reconciliador (ADR-0006) e outbox (ADR-0008).
- Rodar o sistema inteiro localmente exige vários containers.
