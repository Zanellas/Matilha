package com.matilha.template.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import com.matilha.template.dto.CreateExampleRequestDto;
import com.matilha.template.dto.ExampleResponseDto;
import com.matilha.template.entity.Example;
import com.matilha.template.exception.BusinessException;
import com.matilha.template.exception.ExampleNotFoundException;
import com.matilha.template.repository.ExampleRepository;

@ExtendWith(MockitoExtension.class)
class ExampleServiceTest {

    private static final UUID EXAMPLE_ID = UUID.fromString("8a2b4c6d-1e3f-4a5b-8c7d-9e0f1a2b3c4d");
    private static final CreateExampleRequestDto REQUEST = new CreateExampleRequestDto("Primeiro", "Um exemplo");

    @Mock
    private ExampleRepository exampleRepository;

    @InjectMocks
    private ExampleService exampleService;

    /** Simula o id que o Hibernate preenche ao persistir. */
    private static Example persisted(String name) {
        Example example = new Example(name, "Um exemplo");
        ReflectionTestUtils.setField(example, "id", EXAMPLE_ID);
        return example;
    }

    @Nested
    class Create {

        @Test
        void shouldSaveAndReturnExample() {
            when(exampleRepository.existsByName("Primeiro")).thenReturn(false);
            when(exampleRepository.save(any())).thenReturn(persisted("Primeiro"));

            ExampleResponseDto response = exampleService.create(REQUEST);

            assertThat(response.id()).isEqualTo(EXAMPLE_ID);
            assertThat(response.name()).isEqualTo("Primeiro");
            assertThat(response.description()).isEqualTo("Um exemplo");
        }

        @Test
        void shouldRejectNameAlreadyInUse() {
            when(exampleRepository.existsByName("Primeiro")).thenReturn(true);

            assertThatThrownBy(() -> exampleService.create(REQUEST))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("Example name already in use");
            verify(exampleRepository, never()).save(any());
        }
    }

    @Nested
    class FindById {

        @Test
        void shouldReturnExample() {
            when(exampleRepository.findById(EXAMPLE_ID)).thenReturn(Optional.of(persisted("Primeiro")));

            assertThat(exampleService.findById(EXAMPLE_ID).id()).isEqualTo(EXAMPLE_ID);
        }

        @Test
        void shouldThrowWhenNotFound() {
            when(exampleRepository.findById(EXAMPLE_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> exampleService.findById(EXAMPLE_ID))
                    .isInstanceOf(ExampleNotFoundException.class)
                    .hasMessage("Example not found: " + EXAMPLE_ID);
        }
    }

    @Nested
    class FindAll {

        @Test
        void shouldMapPage() {
            Pageable pageable = PageRequest.of(0, 20);
            when(exampleRepository.findAll(pageable))
                    .thenReturn(new PageImpl<>(List.of(persisted("Primeiro")), pageable, 1));

            Page<ExampleResponseDto> page = exampleService.findAll(pageable);

            assertThat(page.getTotalElements()).isEqualTo(1);
            assertThat(page.getContent()).extracting(ExampleResponseDto::name).containsExactly("Primeiro");
        }
    }
}
