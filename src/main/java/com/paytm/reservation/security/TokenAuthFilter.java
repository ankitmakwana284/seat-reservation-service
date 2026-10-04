package com.paytm.reservation.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@Order(1)
public class TokenAuthFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain filterChain
    ) throws ServletException, IOException {
        try {
            String authHeader = request.getHeader("Authorization");
            String userId = null;

            if (authHeader != null && authHeader.startsWith("Bearer ")) {
                userId = authHeader.substring(7).trim();
            } else {
                // Secondary fallback header for testing scripts
                userId = request.getHeader("X-User-Id");
            }

            if (userId != null && !userId.isBlank()) {
                UserContext.setUserId(userId);
            }

            filterChain.doFilter(request, response);
        } finally {
            UserContext.clear();
        }
    }
}