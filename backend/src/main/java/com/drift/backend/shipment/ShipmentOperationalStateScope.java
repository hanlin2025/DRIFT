package com.drift.backend.shipment;

import java.io.IOException;

import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Makes the operational-state lookup available while a shipment response is assembled.
 * The lookup lives in {@link ShipmentDetailResponse#from} so detail and the importer link stay together.
 */
@Component
class ShipmentOperationalStateScope extends OncePerRequestFilter {

	private final ShipmentOperationalStates states;

	ShipmentOperationalStateScope(ShipmentOperationalStates states) {
		this.states = states;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		ShipmentOperationalStates.use(states);
		try {
			chain.doFilter(request, response);
		} finally {
			ShipmentOperationalStates.clear();
		}
	}
}
