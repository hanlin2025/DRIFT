package com.drift.backend.shipment;

import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;

import com.drift.backend.BackendApplication;

/**
 * Test launcher for the recommendation-decision browser run. It is compiled with
 * the test classes and is not packaged in the application jar. The production
 * impact source stays on the assessment path.
 */
public final class RecommendationDecisionBrowserApplication {

	private RecommendationDecisionBrowserApplication() {
	}

	public static void main(String[] args) {
		SpringApplication application = new SpringApplication(BackendApplication.class);
		application.addInitializers(RecommendationDecisionBrowserApplication::registerImpact);
		application.run(args);
	}

	static void registerImpact(ConfigurableApplicationContext context) {
		if (!(context instanceof BeanDefinitionRegistry registry)) {
			throw new IllegalStateException("The browser launcher needs a bean definition registry");
		}
		RootBeanDefinition definition = new RootBeanDefinition(BrowserImpact.class);
		definition.setPrimary(true);
		registry.registerBeanDefinition("browserRecommendationImpact", definition);
	}

	static final class BrowserImpact extends ShipmentRecommendationImpactSource {

		private final ShipmentRecommendationImpact impact;

		BrowserImpact(Environment environment) {
			this.impact = outcome(environment.getProperty("DRIFT_RECOMMENDATION_IMPACT_OUTCOME", "NOT_RETURNED"));
		}

		@Override
		ShipmentRecommendationImpact current() {
			return impact;
		}

		private static ShipmentRecommendationImpact outcome(String configured) {
			String value = configured == null || configured.isBlank() ? "NOT_RETURNED" : configured;
			return switch (value) {
				case "NOT_RETURNED" -> ShipmentRecommendationImpact.notReturned();
				case "NO_PREDICTED_IMPACT" -> ShipmentRecommendationImpact.noPredictedImpact();
				case "AFFECTS_CONSTRAINTS" -> ShipmentRecommendationImpact.affectsConstraints();
				default -> throw new IllegalStateException("Unknown recommendation impact outcome");
			};
		}
	}
}
