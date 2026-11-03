package com.matilha.notification.entity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class FailureCodeTest {

    /**
     * Cada código conhecido volta como ele mesmo. Se alguém acrescentar um valor ao enum e esquecer
     * o case no fromCode, ele cairia no genérico sem ninguém perceber: este teste pega isso.
     */
    @ParameterizedTest
    @EnumSource(FailureCode.class)
    void shouldMapEveryKnownCodeToItself(FailureCode code) {
        assertThat(FailureCode.fromCode(code.name())).isEqualTo(code);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = { "", "ACCOUNT_BLOCKED", "insufficient_balance", " INSUFFICIENT_BALANCE" })
    void shouldFallBackToGenericCode(String code) {
        assertThat(FailureCode.fromCode(code)).isEqualTo(FailureCode.REJECTED_BY_ACCOUNT);
    }
}
