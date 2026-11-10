package com.drift.backend.account.admin;

import java.util.List;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.drift.backend.account.authentication.AuthenticatedUser;
import com.drift.backend.account.exception.SessionEndedException;
import com.drift.backend.config.OpenApiConfig;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;

@RestController
public class AdminInvitationController {

	private final AdminInvitationService invitations;

	public AdminInvitationController(AdminInvitationService invitations) {
		this.invitations = invitations;
	}

	@GetMapping("/api/admin/invitation-organisations")
	@Operation(summary = "List organisations an administrator may invite into", description = "Returns the administrator's own active "
			+ "organisation and every active organisation that administrator created that still has no users. "
			+ "An organisation with users that is not the administrator's own company is omitted. "
			+ "This list is for invitations only. Assignment still uses GET /api/admin/organisations.",
			security = @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH_SCHEME))
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Organisations the administrator may invite into", content = @Content(
					mediaType = "application/json", array = @ArraySchema(schema = @Schema(implementation = AdminOrganisationResponse.class)))),
			@ApiResponse(responseCode = "401", description = "Missing, expired, or invalid bearer token", content = @Content(
					mediaType = "application/json", examples = @ExampleObject(value = """
							{"message":"Your session has ended. Log in again."}
							"""))),
			@ApiResponse(responseCode = "403", description = "The caller is not allowed to send an invitation for an organisation", content = @Content(
					mediaType = "application/json", examples = @ExampleObject(value = """
							{"message":"You cannot manage invitations for this organisation."}
							"""))) })
	public ResponseEntity<List<AdminOrganisationResponse>> invitationOrganisations(
			@io.swagger.v3.oas.annotations.Parameter(hidden = true) @AuthenticationPrincipal AuthenticatedUser user) {
		if (user == null) {
			throw new SessionEndedException();
		}
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(invitations.invitationOrganisations(user));
	}

	@GetMapping("/api/admin/invitations")
	@Operation(summary = "List pending invitations", description = "Lists pending invitations for the administrator's own active organisation "
			+ "and for active organisations that still have no users, including invitations whose expiry has passed. "
			+ "Consumed invitations and organisations the administrator may not invite into are omitted. "
			+ "The invitation token is not included.",
			security = @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH_SCHEME))
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Pending invitations, possibly empty", content = @Content(
					mediaType = "application/json", array = @ArraySchema(schema = @Schema(implementation = ManagedInvitationResponse.class)))),
			@ApiResponse(responseCode = "401", description = "Missing, expired, or invalid bearer token", content = @Content(
					mediaType = "application/json", examples = @ExampleObject(value = """
							{"message":"Your session has ended. Log in again."}
							"""))),
			@ApiResponse(responseCode = "403", description = "The caller is not allowed to send an invitation for an organisation", content = @Content(
					mediaType = "application/json", examples = @ExampleObject(value = """
							{"message":"You cannot manage invitations for this organisation."}
							"""))) })
	public ResponseEntity<List<ManagedInvitationResponse>> pending(
			@io.swagger.v3.oas.annotations.Parameter(hidden = true) @AuthenticationPrincipal AuthenticatedUser user) {
		if (user == null) {
			throw new SessionEndedException();
		}
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(invitations.pending(user));
	}

	@PostMapping("/api/admin/invitations/{invitationId}/withdraw")
	@Operation(summary = "Withdraw a pending invitation", description = "Withdraws one pending invitation in the administrator's own organisation "
			+ "or in an active organisation that still has no users. "
			+ "The invitation becomes revoked and one revocation record stores who withdrew it and when. "
			+ "A missing invitation and an invitation for an organisation the administrator may not invite into are refused without revealing it. "
			+ "An accepted, expired, or already withdrawn invitation is refused and left unchanged. "
			+ "The invitation token is not included.",
			security = @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH_SCHEME))
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Invitation withdrawn", content = @Content(
					mediaType = "application/json", schema = @Schema(implementation = WithdrawnInvitationResponse.class))),
			@ApiResponse(responseCode = "401", description = "Missing, expired, or invalid bearer token", content = @Content(
					mediaType = "application/json", examples = @ExampleObject(value = """
							{"message":"Your session has ended. Log in again."}
							"""))),
			@ApiResponse(responseCode = "403", description = "The caller cannot manage this invitation", content = @Content(
					mediaType = "application/json", examples = @ExampleObject(value = """
							{"message":"You cannot manage this invitation."}
							"""))),
			@ApiResponse(responseCode = "409", description = "The invitation is already withdrawn, already accepted, or expired", content = @Content(
					mediaType = "application/json", examples = @ExampleObject(value = """
							{"message":"This invitation is already withdrawn."}
							"""))) })
	public ResponseEntity<WithdrawnInvitationResponse> withdraw(
			@io.swagger.v3.oas.annotations.Parameter(hidden = true) @AuthenticationPrincipal AuthenticatedUser user,
			@PathVariable Long invitationId) {
		if (user == null) {
			throw new SessionEndedException();
		}
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(invitations.withdraw(user, invitationId));
	}

	@PostMapping("/api/admin/invitations")
	public ResponseEntity<InvitationIssuedResponse> invite(
			@AuthenticationPrincipal AuthenticatedUser user, @Valid @RequestBody InviteColleagueRequest request) {
		if (user == null) {
			throw new SessionEndedException();
		}
		return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
				.body(invitations.invite(user, request));
	}
}
