package com.drift.backend.maritimeai;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import com.drift.backend.account.AccountRole;
import com.drift.backend.account.Role;
import com.drift.backend.account.UserAccount;
import com.drift.backend.account.authentication.AuthenticatedUser;
import com.drift.backend.company.Company;
import com.drift.backend.maritimecontext.MaritimeContextService;
import com.drift.backend.maritimecontext.MaritimeEvidence;
import com.drift.backend.shipment.Shipment;
import com.drift.backend.shipment.ShipmentDetailResponse;
import com.drift.backend.shipment.ShipmentRepository;
import com.drift.backend.shipment.ShipmentService;
import com.drift.backend.shipment.ShipmentStatus;

import tools.jackson.databind.ObjectMapper;

class MaritimeAiAssessmentServiceTest {
	@Test
	void mixedBundleWithUnapprovedAuthenticEvidenceAbstainsBeforeCallingTheProvider() throws Exception {
		AssessmentResult result = runAssessment(true, authenticEvidence("mpa-approved"), authenticEvidence("mpa-unapproved"));

		assertThat(result.response().state()).isEqualTo("ABSTAINED");
		assertThat(result.response().validationStatus()).isEqualTo("UNAPPROVED_AUTHENTIC_EVIDENCE");
		assertThat(result.providerCalled()).isFalse();
		assertThat(result.persisted()).isNotNull();
		assertThat(result.persisted().getGenerationState()).isEqualTo(MaritimeAiGenerationState.ABSTAINED);
		assertThat(result.persisted().getValidationStatus()).isEqualTo("UNAPPROVED_AUTHENTIC_EVIDENCE");
		assertThat(result.persisted().getOutputJson()).doesNotContain("mpa-approved", "mpa-unapproved");
	}

	@Test
	void disabledAuthenticApprovalAbstainsEvenForAnOtherwiseApprovedRecord() throws Exception {
		AssessmentResult result = runAssessment(false, authenticEvidence("mpa-approved"));

		assertThat(result.response().state()).isEqualTo("ABSTAINED");
		assertThat(result.response().validationStatus()).isEqualTo("UNAPPROVED_AUTHENTIC_EVIDENCE");
		assertThat(result.providerCalled()).isFalse();
		assertThat(result.persisted()).isNotNull();
		assertThat(result.persisted().getGenerationState()).isEqualTo(MaritimeAiGenerationState.ABSTAINED);
		assertThat(result.persisted().getValidationStatus()).isEqualTo("UNAPPROVED_AUTHENTIC_EVIDENCE");
		assertThat(result.persisted().getOutputJson()).doesNotContain("mpa-approved");
	}

	private static AssessmentResult runAssessment(boolean authenticApprovalEnabled, MaritimeEvidence... evidence) throws Exception {
		Company company = newCompany(44L);
		UserAccount creator = new UserAccount("Test Forwarder", "forwarder@example.com", "unused",
				new AccountRole(Role.FREIGHT_FORWARDER), company);
		Shipment shipment = new Shipment(company, creator, "CDG255-TEST", "Singapore", "Jakarta", "Singapore", "Mother",
				OffsetDateTime.parse("2026-10-29T02:00:00+08:00"), "Feeder",
				OffsetDateTime.parse("2026-10-29T10:00:00+08:00"));
		setField(shipment, "id", 7L);
		ShipmentDetailResponse detail = detail(7L, 44L);

		ShipmentRepository shipmentRepository = repository(ShipmentRepository.class, (proxy, method, args) -> {
			if (method.getName().equals("findById")) return Optional.of(shipment);
			return unsupported(method.getName());
		});
		AtomicReference<MaritimeAiAssessment> persisted = new AtomicReference<>();
		MaritimeAiAssessmentRepository assessments = repository(MaritimeAiAssessmentRepository.class, (proxy, method, args) -> {
			if (method.getName().equals("save")) {
				persisted.set((MaritimeAiAssessment) args[0]);
				return args[0];
			}
			return unsupported(method.getName());
		});
		ShipmentService shipments = new ShipmentService(null, null, null, null, null, null, new ObjectMapper(),
				new DefaultListableBeanFactory().getBeanProvider(Clock.class), null, null, null, null) {
			@Override public ShipmentDetailResponse get(AuthenticatedUser principal, Long shipmentId) { return detail; }
		};

		MaritimeContextService context = contextWith(evidence);
		AtomicBoolean providerCalled = new AtomicBoolean();
		MaritimeAiClient client = packageToInterpret -> {
			providerCalled.set(true);
			return MaritimeAiClient.MaritimeAiClientResult.unavailable("The provider must not be called");
		};
		var service = new MaritimeAiAssessmentService(shipments, shipmentRepository, context, client, assessments,
				new ObjectMapper(), new DefaultListableBeanFactory().getBeanProvider(Clock.class), true, authenticApprovalEnabled,
				"MPA_ETA_REVISION", "mpa-approved");

		var response = service.generate(new AuthenticatedUser(1L, "test@example.com", Role.FREIGHT_FORWARDER, Instant.EPOCH), 7L);
		return new AssessmentResult(response, providerCalled.get(), persisted.get());
	}

	private record AssessmentResult(MaritimeAiAssessmentResponse response, boolean providerCalled, MaritimeAiAssessment persisted) { }

	private static MaritimeEvidence authenticEvidence(String id) {
		return new MaritimeEvidence("MPA_ETA_REVISION", "RETROSPECTIVE_ANALYSIS", id, "Historical revision", "MPA", "", "citation",
				null, null, "2026-10-27T15:30:00Z", "HISTORICAL_REPLAY", "old", "new", "change", "COMPATIBLE", "COMPATIBLE",
				"UNKNOWN", "COMPATIBLE", true, true, "UNVERIFIED_IMO_ONLY", "Historical context only");
	}

	private static Company newCompany(Long id) throws Exception {
		var constructor = Company.class.getDeclaredConstructor();
		constructor.setAccessible(true);
		Company company = constructor.newInstance();
		setField(company, "id", id);
		return company;
	}

	private static MaritimeContextService contextWith(MaritimeEvidence... evidence) throws Exception {
		Class<?> adapterType = Class.forName("com.drift.backend.maritimecontext.MaritimeEvidenceSourceAdapter");
		Object adapter = Proxy.newProxyInstance(adapterType.getClassLoader(), new Class<?>[] { adapterType },
				(proxy, method, args) -> method.getName().equals("forShipment") ? List.of(evidence) : unsupported(method.getName()));
		var constructor = MaritimeContextService.class.getDeclaredConstructor(boolean.class, List.class, Clock.class);
		constructor.setAccessible(true);
		return (MaritimeContextService) constructor.newInstance(true, List.of(adapter),
				Clock.fixed(Instant.parse("2026-10-27T16:00:00Z"), java.time.ZoneOffset.UTC));
	}

	private static ShipmentDetailResponse detail(Long id, Long companyId) throws Exception {
		var components = ShipmentDetailResponse.class.getRecordComponents();
		Object[] values = new Object[components.length];
		values[0] = id;
		values[1] = companyId;
		values[values.length - 1] = ShipmentStatus.ACTIVE;
		Class<?>[] parameterTypes = java.util.Arrays.stream(components).map(component -> component.getType()).toArray(Class<?>[]::new);
		return ShipmentDetailResponse.class.getDeclaredConstructor(parameterTypes).newInstance(values);
	}

	private static void setField(Object target, String name, Object value) throws Exception {
		Field field = target.getClass().getDeclaredField(name);
		field.setAccessible(true);
		field.set(target, value);
	}

	private static Object unsupported(String method) {
		throw new UnsupportedOperationException("Unexpected repository method: " + method);
	}

	@SuppressWarnings("unchecked")
	private static <T> T repository(Class<T> type, InvocationHandler handler) {
		InvocationHandler withObjectMethods = (proxy, method, args) -> {
			if (method.getDeclaringClass() == Object.class) {
				return switch (method.getName()) {
					case "toString" -> type.getSimpleName() + " test proxy";
					case "hashCode" -> System.identityHashCode(proxy);
					case "equals" -> proxy == args[0];
					default -> unsupported(method.getName());
				};
			}
			return handler.invoke(proxy, method, args);
		};
		return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type }, withObjectMethods);
	}
}
