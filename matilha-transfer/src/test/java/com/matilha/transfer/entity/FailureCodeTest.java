package com.matilha.transfer.entity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * O transfer decide quais códigos do account ele entende. Qualquer outro vira REJECTED_BY_ACCOUNT,
 * para um código novo no account não quebrar o transfer.
 */
class FailureCodeTest {

    @ParameterizedTest
    @CsvSource({
            "INSUFFICIENT_BALANCE, INSUFFICIENT_BALANCE",
            "ACCOUNT_NOT_FOUND, ACCOUNT_NOT_FOUND",
            "ACCOUNT_INACTIVE, ACCOUNT_INACTIVE" })
    void shouldMapKnownAccountCodes(String accountCode, FailureCode expected) {
        assertThat(FailureCode.fromAccountCode(accountCode)).isEqualTo(expected);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = { "BUSINESS_RULE_VIOLATION", "SOMETHING_NEW", "insufficient_balance", " " })
    void shouldUseGenericCodeForMissingOrUnknownCodes(String accountCode) {
        assertThat(FailureCode.fromAccountCode(accountCode)).isEqualTo(FailureCode.REJECTED_BY_ACCOUNT);
    }

    /**
     * INVALID_REQUEST é do transfer (o account recusou o pedido como inválido, ou seja, bug do transfer).
     * O account não pode impor esse código só por mandar o mesmo nome.
     */
    @Test
    void shouldNotAcceptTransferOwnCodesFromAccount() {
        assertThat(FailureCode.fromAccountCode("INVALID_REQUEST")).isEqualTo(FailureCode.REJECTED_BY_ACCOUNT);
    }
}
