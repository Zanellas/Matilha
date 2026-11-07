package com.matilha.account.dto;

import org.springframework.security.core.Authentication;

public record RequesterDto(String userId, boolean admin) {
    public static RequesterDto from(Authentication authentication) {
        boolean admin = authentication.getAuthorities().stream()
                .anyMatch(authority -> authority.getAuthority().equals("ROLE_admin"));
        return new RequesterDto(authentication.getName(), admin);
    }

}
