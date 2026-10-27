package com.matilha.template.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.matilha.template.dto.CreateExampleRequestDto;
import com.matilha.template.dto.ExampleResponseDto;
import com.matilha.template.exception.BusinessException;
import com.matilha.template.exception.ExampleNotFoundException;
import com.matilha.template.service.ExampleService;

@WebMvcTest(ExampleController.class)
class ExampleControllerTest {

    private static final UUID EXAMPLE_ID = UUID.fromString("8a2b4c6d-1e3f-4a5b-8c7d-9e0f1a2b3c4d");

    private static final String VALID_REQUEST = """
            {
              "name": "Primeiro",
              "description": "Um exemplo"
            }
            """;

    private static final ExampleResponseDto RESPONSE = new ExampleResponseDto(EXAMPLE_ID, "Primeiro", "Um exemplo",
            Instant.parse("2026-10-26T12:00:00Z"));

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ExampleService exampleService;

    private static MockHttpServletRequestBuilder postExample(String body) {
        return post("/examples")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    @Nested
    class Create {

        @Test
        void shouldReturnCreated() throws Exception {
            when(exampleService.create(any())).thenReturn(RESPONSE);

            mockMvc.perform(postExample(VALID_REQUEST))
                    .andExpect(status().isCreated())
                    .andExpect(header().string("Location", "/examples/" + EXAMPLE_ID))
                    .andExpect(jsonPath("$.id").value(EXAMPLE_ID.toString()))
                    .andExpect(jsonPath("$.name").value("Primeiro"))
                    .andExpect(jsonPath("$.description").value("Um exemplo"))
                    .andExpect(jsonPath("$.createdAt").exists());

            verify(exampleService).create(new CreateExampleRequestDto("Primeiro", "Um exemplo"));
        }

        @Test
        void shouldReturnUnprocessableOnBusinessError() throws Exception {
            when(exampleService.create(any())).thenThrow(new BusinessException("Example name already in use"));

            mockMvc.perform(postExample(VALID_REQUEST))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.detail").value("Example name already in use"));
        }

        @Test
        void shouldReturnConflictOnDataIntegrityViolation() throws Exception {
            when(exampleService.create(any())).thenThrow(new DataIntegrityViolationException("boom"));

            mockMvc.perform(postExample(VALID_REQUEST))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.detail").value("The request conflicts with existing data"));
        }

        @Test
        void shouldHideUnexpectedErrorDetails() throws Exception {
            when(exampleService.create(any())).thenThrow(new IllegalStateException("secret internals"));

            mockMvc.perform(postExample(VALID_REQUEST))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.detail").value("An unexpected error occurred"));
        }
    }

    @Nested
    class Validation {

        @ParameterizedTest
        @ValueSource(strings = { "{}", "{ \"name\": \"\" }", "{ \"name\": \"   \" }" })
        void shouldRejectMissingOrBlankName(String body) throws Exception {
            mockMvc.perform(postExample(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors.name").exists());

            verify(exampleService, never()).create(any());
        }

        @Test
        void shouldRejectNameTooLong() throws Exception {
            mockMvc.perform(postExample("{ \"name\": \"" + "a".repeat(101) + "\" }"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors.name").exists());
        }

        @Test
        void shouldRejectDescriptionTooLong() throws Exception {
            mockMvc.perform(postExample("{ \"name\": \"Primeiro\", \"description\": \"" + "a".repeat(256) + "\" }"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors.description").exists());
        }

        @Test
        void shouldRejectMalformedJson() throws Exception {
            mockMvc.perform(postExample("{ not json"))
                    .andExpect(status().isBadRequest());

            verify(exampleService, never()).create(any());
        }
    }

    @Nested
    class FindById {

        @Test
        void shouldReturnExample() throws Exception {
            when(exampleService.findById(EXAMPLE_ID)).thenReturn(RESPONSE);

            mockMvc.perform(get("/examples/{id}", EXAMPLE_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(EXAMPLE_ID.toString()))
                    .andExpect(jsonPath("$.name").value("Primeiro"));
        }

        @Test
        void shouldReturnNotFound() throws Exception {
            when(exampleService.findById(EXAMPLE_ID)).thenThrow(new ExampleNotFoundException(EXAMPLE_ID));

            mockMvc.perform(get("/examples/{id}", EXAMPLE_ID))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.detail").value("Example not found: " + EXAMPLE_ID));
        }

        @Test
        void shouldRejectIdThatIsNotUuid() throws Exception {
            mockMvc.perform(get("/examples/{id}", "abc"))
                    .andExpect(status().isBadRequest());

            verify(exampleService, never()).findById(any());
        }
    }

    @Nested
    class FindAll {

        @Test
        void shouldReturnPage() throws Exception {
            when(exampleService.findAll(any()))
                    .thenReturn(new PageImpl<>(List.of(RESPONSE), PageRequest.of(1, 5), 6));

            mockMvc.perform(get("/examples").param("page", "1").param("size", "5"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content[0].id").value(EXAMPLE_ID.toString()))
                    .andExpect(jsonPath("$.page.number").value(1))
                    .andExpect(jsonPath("$.page.size").value(5))
                    .andExpect(jsonPath("$.page.totalElements").value(6));

            ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
            verify(exampleService).findAll(pageable.capture());
            assertThat(pageable.getValue().getPageNumber()).isEqualTo(1);
            assertThat(pageable.getValue().getPageSize()).isEqualTo(5);
        }
    }
}
