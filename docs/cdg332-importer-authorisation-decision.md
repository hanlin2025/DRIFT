# CDG-332 importer authorisation decision

This note records the rule for CDG-329. It uses the comparison in `docs/cdg331-link-and-import-authorisation.md` and does not change application behaviour, schema, or permissions.

An authorisation is one `forwarder_importer_relationships` row for a forwarder company and a different importer company. Granting makes that row active. Ending deactivates it.

## Who may grant, see, and end an authorisation

Only an administrator whose active company is the forwarder company may grant or end that company's authorisations.

A freight forwarder whose active company is the forwarder company may see that company's active authorisations and may use them when linking a shipment or importing a CSV. A freight forwarder cannot grant or end an authorisation.

Grant, end, and seeing another company's authorisations are refused for:

- a freight forwarder of a different company
- an importer
- a logistics manager
- an administrator whose active company is not that forwarder company
- an administrator with no active company

## Linking and CSV import use the same authorisation

A freight forwarder may set a shipment they own to an importer only when an active authorisation exists for that forwarder company and that importer. CSV import requires the same active authorisation before it links a row.

A blank Importer Organisation column still imports the row with no importer link. Clearing an existing link does not require an authorisation. Changing a shipment from one importer to a different importer requires an active authorisation for the new importer.

## Shipments already linked

Ending an authorisation blocks new links and new imports for that pair. Shipments already linked to that importer stay linked. That importer keeps the visibility those links already give. Removing that existing access is a separate action and is outside this decision.

## Unknown, inactive, and same-company importers

Those three stay refused.

On a link, a missing, inactive, or same-company selection stays "Invalid or inactive importer organisation selected." An active other company with no active authorisation is refused and the shipment is left unchanged.

On a CSV import, an unknown code stays `UNKNOWN_IMPORTER_ORGANISATION`, an inactive company stays `INACTIVE_IMPORTER_ORGANISATION`, and the forwarder's own company or a company with no active authorisation stays `UNAUTHORIZED_IMPORTER_ORGANISATION`.

An importer or a logistics manager cannot call the link or the import, so they receive the role refusal and do not reach the importer checks. An administrator cannot call the link. Administrator bulk import stays deferred under CDG-26, including an administrator of the forwarder company, with "ADMIN bulk-import access is deferred until company type is defined".

## Left in place

CDG-25 remains the shipment link. CDG-26 remains the import role refusal. CDG-129 remains the CSV importer-code rejection. CDG-298 remains company creation and still does not create an authorisation. This note does not add a company-type field, create a company, send an invitation, or change the visibility of a shipment that is already linked. The follow-on boundary is CDG-333.
