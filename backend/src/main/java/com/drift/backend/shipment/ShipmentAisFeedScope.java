package com.drift.backend.shipment;

import java.io.IOException;

import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Makes the AIS feed read available while a shipment response is assembled.
 * The read lives in {@link ShipmentDetailResponse#from} so detail and the importer link stay together.
 */
@Component
class ShipmentAisFeedScope extends OncePerRequestFilter {

	private final ShipmentAisFeed feed;

	ShipmentAisFeedScope(ShipmentAisFeed feed) {
		this.feed = feed;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		ShipmentAisFeed.use(feed);
		try {
			chain.doFilter(request, response);
		} finally {
			ShipmentAisFeed.clear();
		}
	}
}
