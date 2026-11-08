# CDG-333 importer authorisation follow-on boundary

This note records the CDG-332 decision on CDG-329 and names the later implementation boundary. It does not change code, add a migration, or create a Jira story.

The decision is in `docs/cdg332-importer-authorisation-decision.md`. Only an administrator whose active company is the forwarder company may grant or end that company's authorisations. A freight forwarder in that company may see and use the active ones, and cannot grant or end them. An importer, a logistics manager, a freight forwarder of another company, and an administrator outside that company are refused. A shipment link and a CSV import both require the same active authorisation. Ending it blocks new links and imports and leaves shipments already linked, and their visibility, in place. Unknown, inactive, and same-company importers stay refused. Administrator bulk import stays deferred under CDG-26.

## Checks a later story would change

`ShipmentLinkService.importer` accepts any other active company. For a non-null importer it would also require `ForwarderImporterRelationshipRepository.existsByForwarderCompanyIdAndImporterCompanyIdAndActiveTrue` for the shipment's owning company. Without that row the link is not saved. A null importer still clears the link and does not require an authorisation. Replacing the current importer with a different company requires an active authorisation for the new company.

Nothing in production calls `ForwarderImporterRelationship`'s constructor or `deactivate`. A later story would let an administrator of the forwarder company grant through the constructor and end through `deactivate`. A freight forwarder, an importer, a logistics manager, an administrator of another company, and an administrator with no active company would be refused. Granting would not itself link a shipment. Ending would set `active` to false and would not clear `shipments.importer_company_id`.

`OrganisationService.search` returns every other active company for `GET /api/organisations?type=importer`, which is the list the shipment form offers. A later story would limit that list to the caller's active authorisations so the form offers only a company the link check would accept. `GET /api/admin/organisations` stays the administrator assignment list.

## Checks that stay

`RolePermissionMatrix` keeps `PATCH /api/shipments/{id}/link-importer` for a freight forwarder with an active company, and refuses every other role with "Only a freight forwarder in the shipment's company can link it to an importer". A caller with no active company still receives "Your account must belong to an active company to view shipments". `ShipmentLinkService` still requires the caller's company to own the shipment.

A missing, inactive, or same-company selection still raises `InvalidImporterOrganisationException` with "Invalid or inactive importer organisation selected."

`RolePermissionMatrix.importUpload` stays. A freight forwarder with an active company may upload. An importer or logistics manager receives "Your role is not authorized to import shipments in bulk". An administrator receives "ADMIN bulk-import access is deferred until company type is defined". A caller with no active company receives "Bulk import requires an active company".

`ImporterOrganisationAuthorizer` stays. A blank importer column links nobody. An unknown code stays `UNKNOWN_IMPORTER_ORGANISATION`. An inactive company stays `INACTIVE_IMPORTER_ORGANISATION`. The forwarder's own company, or a company with no active authorisation, stays `UNAUTHORIZED_IMPORTER_ORGANISATION`.

`ShipmentAccessPolicy.canView` stays the managing company or the company already stored as the linked importer. `canEdit` stays the managing company. `canDecide` stays whoever can view. Ending an authorisation does not remove that visibility.

V15 stays the relationship table and still inserts no row. No company-type column is added. CDG-298 still creates a company without creating an authorisation.

## Stories that remain

CDG-25 remains the shipment link. CDG-26 remains the import role refusal, including deferred administrator import. CDG-129 remains the CSV importer-code rejection. CDG-298 remains company creation. This note does not replace those stories and does not open another implementation story.
