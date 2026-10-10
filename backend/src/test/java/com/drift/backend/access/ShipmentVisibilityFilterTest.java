package com.drift.backend.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import com.drift.backend.account.Role;
import com.drift.backend.account.UserAccount;
import com.drift.backend.account.UserAccountRepository;
import com.drift.backend.account.authentication.AuthenticatedUser;
import com.drift.backend.account.exception.SessionEndedException;
import com.drift.backend.company.Company;
import com.drift.backend.shipment.ShipmentAccessPolicy;
import com.drift.backend.shipment.ShipmentVisibility;
import com.drift.backend.shipment.ShipmentVisibilityLookup;
import com.drift.backend.shipment.exception.ShipmentDetailForbiddenException;
import com.drift.backend.shipment.exception.ShipmentNotFoundException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class ShipmentVisibilityFilterTest {

	private static final long COMPANY_ID = 20L;

	@Mock private UserAccountRepository users;
	@Mock private ShipmentVisibilityLookup shipments;
	@Mock private FilterChain chain;

	private final ObjectMapper json = new ObjectMapper();
	private ShipmentVisibilityFilter filter;

	@BeforeEach
	void setUp() {
		filter = new ShipmentVisibilityFilter(users, shipments, new ShipmentAccessPolicy(), json);
	}

	@AfterEach
	void clearSecurity() {
		SecurityContextHolder.clearContext();
	}

	@Test
	void leavesImportJobsUpdatesAndAnonymousRequestsAlone() throws Exception {
		filter.doFilter(request("GET", "/api/shipments/import/9"), new MockHttpServletResponse(), chain);
		filter.doFilter(request("PUT", "/api/shipments/9"), new MockHttpServletResponse(), chain);
		filter.doFilter(request("GET", "/api/shipments/abc"), new MockHttpServletResponse(), chain);
		filter.doFilter(request("GET", "/api/shipments/9/risk"), new MockHttpServletResponse(), chain);

		verify(chain, org.mockito.Mockito.times(4)).doFilter(any(), any());
		verifyNoInteractions(users, shipments);
	}

	@Test
	void passesAnIdThatDoesNotFitInALongThroughToTheHandler() throws Exception {
		signIn();

		filter.doFilter(request("GET", "/api/shipments/9999999999999999999"), new MockHttpServletResponse(), chain);
		filter.doFilter(request("GET", "/api/shipments/9999999999999999999/tracking"), new MockHttpServletResponse(), chain);

		verify(chain, org.mockito.Mockito.times(2)).doFilter(any(), any());
		verifyNoInteractions(shipments);
	}

	@Test
	void refusesAHiddenDetailBeforeTheHandlerCanWriteRisk() throws Exception {
		signIn();
		when(shipments.find(8L)).thenReturn(Optional.of(new ShipmentVisibility(8L, 10L, 30L)));
		MockHttpServletResponse response = new MockHttpServletResponse();

		filter.doFilter(request("GET", "/api/shipments/8", "/app"), response, chain);

		verify(chain, never()).doFilter(any(), any());
		assertThat(response.getStatus()).isEqualTo(403);
		assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
		assertThat(response.getContentAsString()).isEqualTo(
				"{\"message\":\"" + ShipmentDetailForbiddenException.MESSAGE + "\"}");
		assertThat(response.getContentAsString()).doesNotContain("SECRET").doesNotContain("risk");
	}

	@Test
	void letsTheManagingCompanyAndTheLinkedImporterReadTheDetail() throws Exception {
		signIn();
		when(shipments.find(8L)).thenReturn(Optional.of(new ShipmentVisibility(8L, COMPANY_ID, null)));
		filter.doFilter(request("GET", "/api/shipments/8"), new MockHttpServletResponse(), chain);

		when(shipments.find(8L)).thenReturn(Optional.of(new ShipmentVisibility(8L, 10L, COMPANY_ID)));
		filter.doFilter(request("GET", "/api/shipments/8;jsessionid=abc"), new MockHttpServletResponse(), chain);

		verify(chain, org.mockito.Mockito.times(2)).doFilter(any(), any());
	}

	@Test
	void passesAMissingDetailThroughToTheNotFoundHandler() throws Exception {
		signIn();
		when(shipments.find(8L)).thenReturn(Optional.empty());

		filter.doFilter(request("GET", "/api/shipments/8"), new MockHttpServletResponse(), chain);

		verify(chain).doFilter(any(), any());
	}

	@Test
	void hidesTrackingForAMissingOrUnrelatedShipment() throws Exception {
		signIn();
		when(shipments.find(8L)).thenReturn(Optional.of(new ShipmentVisibility(8L, 10L, 30L)));
		MockHttpServletResponse hidden = new MockHttpServletResponse();
		filter.doFilter(request("GET", "/api/shipments/8/tracking"), hidden, chain);

		when(shipments.find(9L)).thenReturn(Optional.empty());
		MockHttpServletResponse missing = new MockHttpServletResponse();
		filter.doFilter(request("GET", "/api/shipments/9/tracking"), missing, chain);

		verify(chain, never()).doFilter(any(), any());
		assertThat(hidden.getStatus()).isEqualTo(404);
		assertThat(missing.getStatus()).isEqualTo(404);
		assertThat(hidden.getContentAsString()).isEqualTo(
				"{\"message\":\"" + ShipmentNotFoundException.MESSAGE + "\"}");
		assertThat(missing.getContentAsString()).isEqualTo(hidden.getContentAsString());
	}

	@Test
	void letsAVisibleShipmentThroughToTracking() throws Exception {
		signIn();
		when(shipments.find(8L)).thenReturn(Optional.of(new ShipmentVisibility(8L, 10L, COMPANY_ID)));

		filter.doFilter(request("GET", "/api/shipments/8/tracking/"), new MockHttpServletResponse(), chain);

		verify(chain).doFilter(any(), any());
	}

	@Test
	void stripsHiddenRowsFromTheListAndKeepsExtraFieldsOnVisibleRows() throws Exception {
		signIn();
		byte[] original = """
				[{"id":9,"shipmentReference":"THEIRS","motherVessel":"SECRET","risk":{"level":"HIGH"}},{"id":8,"shipmentReference":"MINE","motherVessel":"MV Mother","risk":{"level":"LOW","explanation":"kept"}}]
				""".getBytes(StandardCharsets.UTF_8);
		when(shipments.visibleIds(eq(COMPANY_ID), any())).thenReturn(Set.of(8L));
		doAnswer(invocation -> {
			HttpServletResponse response = invocation.getArgument(1);
			response.setStatus(200);
			response.setContentType("application/json");
			response.getOutputStream().write(original);
			return null;
		}).when(chain).doFilter(any(), any());
		MockHttpServletResponse response = new MockHttpServletResponse();

		filter.doFilter(request("GET", "/api/shipments"), response, chain);

		assertThat(response.getStatus()).isEqualTo(200);
		JsonNode rows = json.readTree(response.getContentAsByteArray());
		assertThat(rows).hasSize(1);
		assertThat(rows.get(0).get("shipmentReference").asString()).isEqualTo("MINE");
		assertThat(rows.get(0).get("risk").get("level").asString()).isEqualTo("LOW");
		assertThat(rows.get(0).get("risk").get("explanation").asString()).isEqualTo("kept");
		assertThat(response.getContentAsString()).doesNotContain("SECRET").doesNotContain("THEIRS");
	}

	@Test
	void passesAnAlreadyVisibleListThroughUnchanged() throws Exception {
		signIn();
		byte[] original = "[{\"id\":8,\"risk\":null,\"motherVessel\":\"MV Mother\"}]".getBytes(StandardCharsets.UTF_8);
		when(shipments.visibleIds(eq(COMPANY_ID), any())).thenReturn(Set.of(8L));
		doAnswer(invocation -> {
			HttpServletResponse response = invocation.getArgument(1);
			response.setStatus(200);
			response.getOutputStream().write(original);
			return null;
		}).when(chain).doFilter(any(), any());
		MockHttpServletResponse response = new MockHttpServletResponse();

		filter.doFilter(request("GET", "/api/shipments"), response, chain);

		assertThat(response.getContentAsByteArray()).containsExactly(original);
	}

	@Test
	void leavesNonOkAndUnreadableListBodiesAlone() throws Exception {
		signIn();
		doAnswer(invocation -> {
			HttpServletResponse response = invocation.getArgument(1);
			response.setStatus(403);
			response.getOutputStream().write("{\"message\":\"kept\"}".getBytes(StandardCharsets.UTF_8));
			return null;
		}).when(chain).doFilter(any(), any());
		MockHttpServletResponse denied = new MockHttpServletResponse();
		filter.doFilter(request("GET", "/api/shipments"), denied, chain);

		doAnswer(invocation -> {
			HttpServletResponse response = invocation.getArgument(1);
			response.setStatus(200);
			response.getOutputStream().write("not-json".getBytes(StandardCharsets.UTF_8));
			return null;
		}).when(chain).doFilter(any(), any());
		MockHttpServletResponse unreadable = new MockHttpServletResponse();
		filter.doFilter(request("GET", "/api/shipments"), unreadable, chain);

		verifyNoInteractions(shipments);
		assertThat(denied.getStatus()).isEqualTo(403);
		assertThat(denied.getContentAsString()).isEqualTo("{\"message\":\"kept\"}");
		assertThat(unreadable.getContentAsString()).isEqualTo("not-json");
	}

	@Test
	void endsTheSessionWhenTheAccountHasGone() throws Exception {
		authenticate();
		when(users.findForPermissionCheck(5L)).thenReturn(Optional.empty());
		MockHttpServletResponse response = new MockHttpServletResponse();

		filter.doFilter(request("GET", "/api/shipments"), response, chain);

		verify(chain, never()).doFilter(any(), any());
		assertThat(response.getStatus()).isEqualTo(401);
		assertThat(response.getContentAsString()).isEqualTo(
				"{\"message\":\"" + SessionEndedException.MESSAGE + "\"}");
	}

	@Test
	void passesThroughWhenTheCompanyIsInactive() throws Exception {
		authenticate();
		Company company = mock(Company.class);
		when(company.isActive()).thenReturn(false);
		UserAccount account = mock(UserAccount.class);
		when(account.getCompany()).thenReturn(company);
		when(users.findForPermissionCheck(5L)).thenReturn(Optional.of(account));

		filter.doFilter(request("GET", "/api/shipments/8"), new MockHttpServletResponse(), chain);

		verify(chain).doFilter(any(), any());
		verifyNoInteractions(shipments);
	}

	private void signIn() {
		authenticate();
		UserAccount account = mock(UserAccount.class);
		Company company = mock(Company.class);
		when(company.getId()).thenReturn(COMPANY_ID);
		when(company.isActive()).thenReturn(true);
		when(account.getCompany()).thenReturn(company);
		when(users.findForPermissionCheck(5L)).thenReturn(Optional.of(account));
	}

	private static void authenticate() {
		SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
				new AuthenticatedUser(5L, "importer@example.com", Role.IMPORTER, Instant.parse("2026-10-10T00:00:00Z"), 0),
				null, List.of(new SimpleGrantedAuthority("ROLE_IMPORTER"))));
	}

	private static MockHttpServletRequest request(String method, String path) {
		return request(method, path, "");
	}

	private static MockHttpServletRequest request(String method, String path, String contextPath) {
		MockHttpServletRequest request = new MockHttpServletRequest(method, path);
		request.setContextPath(contextPath);
		request.setRequestURI(contextPath + path);
		return request;
	}
}
