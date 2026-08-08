package com.llm.gateway.observability;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.web.filter.OncePerRequestFilter;

/** Counts API requests at the HTTP boundary, before authentication can reject them. */
public class InboundMetricsFilter extends OncePerRequestFilter {

    private final MetricsRecorder metrics;

    public InboundMetricsFilter(MetricsRecorder metrics) {
        this.metrics = metrics;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return "OPTIONS".equalsIgnoreCase(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        metrics.incInbound();
        filterChain.doFilter(request, response);
    }
}
