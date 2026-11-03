<!-- RASCUNHO: reescrever com as suas palavras antes de commitar -->
# ADR-0002: O account é o único dono do saldo; o transfer orquestra

- **Status:** Aceito
- **Data:** 2026-11

## Contexto

Uma transferência envolve duas coisas diferentes: o **pedido** do cliente (quem pediu, quando, qual foi o resultado) e o **movimento do dinheiro** entre duas contas. Era preciso decidir qual serviço faz o quê, e como garantir que o débito e o crédito aconteçam juntos.

## Decisão

- **O account é o único serviço que altera saldo.** Ele guarda as contas, os saldos e as movimentações (`account_movements`, com o saldo resultante de cada operação).
- O account expõe `POST /internal/transfers`, que **debita e credita numa única transação local**. Como as duas contas estão no mesmo banco, o próprio PostgreSQL garante que acontece tudo ou nada.
- **O transfer é dono do pedido.** Ele registra a transferência (status, motivo da falha, chave de idempotência), chama o account e guarda o resultado. Ele nunca altera saldo.
- As rotas `/internal/**` são para comunicação entre serviços e não são expostas pelo gateway.
- O `POST /internal/deposits` representa a entrada de dinheiro externo no sistema, como um PIX recebido de outro banco.

## Alternativas consideradas

- **Saga com débito e crédito separados**, com compensação se o crédito falhar. É o padrão quando os dados ficam em bancos diferentes. Aqui as duas contas estão no mesmo banco, então a saga traria complexidade sem nenhum ganho.
- **O transfer alterar o saldo diretamente.** Exigiria acesso ao banco do account, o que quebra o ADR-0001.

## Consequências

- A atomicidade do dinheiro é garantida pelo banco, sem lógica de compensação.
- O extrato sempre bate com o saldo, porque toda operação gera movimentações.
- O transfer depende da disponibilidade do account. Isso é tratado nos ADR-0005 e ADR-0006.
