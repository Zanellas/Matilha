<!-- RASCUNHO: reescrever com as suas palavras antes de commitar -->
# ADR-0003: Concorrência no saldo com lock pessimista em ordem fixa

- **Status:** Aceito
- **Data:** 2026-11

## Contexto

Duas operações ao mesmo tempo na mesma conta podem causar dois problemas:

- **Atualização perdida ou saldo negativo:** as duas leem o saldo de 100 e as duas debitam 80.
- **Deadlock:** a transferência A→B trava A e espera B, enquanto a B→A trava B e espera A.

## Decisão

- As contas envolvidas em depósitos e transferências são lidas com `SELECT ... FOR UPDATE` (`findByAccountNumberForUpdate`). A segunda operação espera a primeira terminar.
- Numa transferência, as duas contas são **sempre travadas na mesma ordem**, pelo menor número de conta primeiro. Assim duas transferências em sentidos opostos nunca esperam uma pela outra em círculo.
- A regra de negócio fica na entidade: `withdraw()` recusa o débito se o saldo não for suficiente.
- O `@Version` (lock otimista) protege as alterações que não passam por esse fluxo, como `PATCH` e bloqueio. O conflito vira `409`.
- O banco tem `CHECK (balance >= 0)` como última linha de defesa, caso um bug passe pela entidade.
- O `MoneyMovementConcurrencyTest` prova o comportamento contra um PostgreSQL real, com várias threads: o saldo nunca fica negativo, não há deadlock e nenhuma atualização se perde.

## Alternativas consideradas

- **Só lock otimista.** Com muitas operações disputando a mesma conta, boa parte falharia e precisaria ser repetida. Para saldo, que é um ponto de disputa, o lock pessimista é mais previsível.
- **`UPDATE` atômico** (`SET balance = balance - x WHERE balance >= x`). É eficiente, mas tira a regra da entidade e dificulta registrar o saldo resultante na movimentação.

## Consequências

- O saldo fica correto mesmo sob concorrência.
- As transações seguram locks, então precisam ser curtas. Nenhuma chamada externa pode acontecer dentro delas.
- Operações na mesma conta são processadas uma de cada vez. Para o volume do projeto, isso é aceitável.
