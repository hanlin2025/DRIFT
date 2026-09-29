package com.drift.backend.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.drift.backend.account.exception.SessionEndedException;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OpenApiIntegrationTests {

	@Autowired MockMvc mvc;

	@Test
	void swaggerUiAndOpenApiDocumentTheProtectedShipmentEndpoints() throws Exception {
		mvc.perform(get("/swagger-ui/index.html"))
				.andExpect(status().isOk());

		mvc.perform(get("/v3/api-docs"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.paths['/api/shipments'].post.summary").value("Create a shipment"))
				.andExpect(jsonPath("$.paths['/api/shipments'].post.security[0].bearerAuth").exists())
				.andExpect(jsonPath("$.paths['/api/shipments'].get.summary").value("List shipments"))
				.andExpect(jsonPath("$.paths['/api/shipments'].get.security[0].bearerAuth").exists())
				.andExpect(jsonPath("$.paths['/api/shipments/{shipmentId}'].get.summary").value("Get a shipment"))
				.andExpect(jsonPath("$.paths['/api/shipments/{shipmentId}'].get.description")
						.value("Returns one shipment of the authenticated user's active company, including the planned connection window calculated from the stored mother-vessel arrival and feeder-vessel departure. A shipment of another company is reported as not found."))
				.andExpect(jsonPath("$.paths['/api/shipments/{shipmentId}'].get.responses['404']").exists())
				.andExpect(jsonPath("$.components.securitySchemes.bearerAuth.type").value("http"))
				.andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme").value("bearer"))
				.andExpect(jsonPath("$.components.schemas.CreateShipmentRequest.required").isArray())
				.andExpect(jsonPath("$.components.schemas.CreateShipmentRequest.properties.shipmentReference.example")
						.value("HBL-2026-001"))
				.andExpect(jsonPath("$.components.schemas.ShipmentResponse.properties.connectionWindow.$ref")
						.value("#/components/schemas/ConnectionWindow"))
				.andExpect(jsonPath("$.components.schemas.ConnectionWindow.properties.duration.example")
						.value("1 day 4 hours"))
				.andExpect(jsonPath("$.components.schemas.ConnectionWindow.properties.totalSeconds.example")
						.value("100800"))
				.andExpect(jsonPath("$.paths['/api/shipments'].get.responses['401'].content['application/json'].example.message")
						.value(SessionEndedException.MESSAGE))
				.andExpect(jsonPath("$.paths['/api/shipments'].post.responses['401'].content['application/json'].example.message")
						.value(SessionEndedException.MESSAGE))
				.andExpect(jsonPath("$.paths['/api/shipments/{shipmentId}'].get.responses['401'].content['application/json'].example.message")
						.value(SessionEndedException.MESSAGE));
	}
}
