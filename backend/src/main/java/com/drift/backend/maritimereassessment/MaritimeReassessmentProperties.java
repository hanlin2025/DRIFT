package com.drift.backend.maritimereassessment;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.maritime-reassessment")
public class MaritimeReassessmentProperties {

	private boolean externalProcessingEnabled;
	private List<String> approvedDatasets = new ArrayList<>();
	private List<String> approvedSourceTypes = new ArrayList<>();
	private int maxConcurrentRequests = 1;
	private int maxCallsPerMinute = 2;
	private int maxEstimatedCostUnits = 1;
	private Duration callTimeout = Duration.ofSeconds(2);
	private int maxAttempts = 2;

	public boolean isExternalProcessingEnabled() { return externalProcessingEnabled; }
	public void setExternalProcessingEnabled(boolean externalProcessingEnabled) { this.externalProcessingEnabled = externalProcessingEnabled; }
	public List<String> getApprovedDatasets() { return approvedDatasets; }
	public void setApprovedDatasets(List<String> approvedDatasets) { this.approvedDatasets = approvedDatasets; }
	public List<String> getApprovedSourceTypes() { return approvedSourceTypes; }
	public void setApprovedSourceTypes(List<String> approvedSourceTypes) { this.approvedSourceTypes = approvedSourceTypes; }
	public int getMaxConcurrentRequests() { return maxConcurrentRequests; }
	public void setMaxConcurrentRequests(int maxConcurrentRequests) { this.maxConcurrentRequests = maxConcurrentRequests; }
	public int getMaxCallsPerMinute() { return maxCallsPerMinute; }
	public void setMaxCallsPerMinute(int maxCallsPerMinute) { this.maxCallsPerMinute = maxCallsPerMinute; }
	public int getMaxEstimatedCostUnits() { return maxEstimatedCostUnits; }
	public void setMaxEstimatedCostUnits(int maxEstimatedCostUnits) { this.maxEstimatedCostUnits = maxEstimatedCostUnits; }
	public Duration getCallTimeout() { return callTimeout; }
	public void setCallTimeout(Duration callTimeout) { this.callTimeout = callTimeout; }
	public int getMaxAttempts() { return maxAttempts; }
	public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }

	MaritimeReassessmentLimits toLimits() {
		return new MaritimeReassessmentLimits(externalProcessingEnabled,
				approvedDatasets == null ? Set.of() : Set.copyOf(approvedDatasets),
				approvedSourceTypes == null ? Set.of() : Set.copyOf(approvedSourceTypes),
				maxConcurrentRequests, maxCallsPerMinute, maxEstimatedCostUnits,
				callTimeout == null ? Duration.ofSeconds(2) : callTimeout, maxAttempts);
	}
}
