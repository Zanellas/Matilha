package com.matilha.template.exception;

import java.util.UUID;

public class ExampleNotFoundException extends RuntimeException {
    public ExampleNotFoundException(UUID id) {
        super("Example not found: " + id);
    }
}
