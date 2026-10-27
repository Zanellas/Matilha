package com.matilha.template.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import com.matilha.template.exception.BusinessException;

class ExampleTest {

    @Test
    void shouldCreateExample() {
        Example example = new Example("Primeiro", "Um exemplo");

        assertThat(example.getName()).isEqualTo("Primeiro");
        assertThat(example.getDescription()).isEqualTo("Um exemplo");
    }

    @Test
    void shouldAllowMissingDescription() {
        Example example = new Example("Primeiro", null);

        assertThat(example.getDescription()).isNull();
    }

    @Test
    void shouldLeaveIdAndVersionNullUntilPersisted() {
        Example example = new Example("Primeiro", null);

        assertThat(example.getId()).isNull();
        assertThat(example.getVersion()).isNull();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = { " ", "\t" })
    void shouldRejectBlankName(String name) {
        assertThatThrownBy(() -> new Example(name, null))
                .isInstanceOf(BusinessException.class)
                .hasMessage("name must not be blank");
    }
}
