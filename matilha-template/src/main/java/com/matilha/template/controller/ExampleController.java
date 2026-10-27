package com.matilha.template.controller;

import java.net.URI;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.matilha.template.dto.CreateExampleRequestDto;
import com.matilha.template.dto.ExampleResponseDto;
import com.matilha.template.service.ExampleService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
@RequestMapping("/examples")
public class ExampleController {

    private final ExampleService exampleService;

    @PostMapping
    public ResponseEntity<ExampleResponseDto> create(@RequestBody @Valid CreateExampleRequestDto request) {
        ExampleResponseDto response = exampleService.create(request);
        return ResponseEntity.created(URI.create("/examples/" + response.id())).body(response);
    }

    @GetMapping("/{id}")
    public ResponseEntity<ExampleResponseDto> findById(@PathVariable UUID id) {
        return ResponseEntity.ok(exampleService.findById(id));
    }

    @GetMapping
    public ResponseEntity<Page<ExampleResponseDto>> findAll(Pageable pageable) {
        return ResponseEntity.ok(exampleService.findAll(pageable));
    }
}
