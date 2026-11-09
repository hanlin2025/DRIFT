# CDG-331 shipment link and CSV import authorisation

This note compares the rules on `main` at `b5cf849`. It does not change application behaviour, schema, or permissions. It is the evidence for CDG-332. It does not decide who may grant or end a forwarder-importer authorisation.

## Shipment link

`PATCH /api/shipments/{id}/link-importer` links the shipment to another company, or clears the link when `importerCompanyId` is null.

A caller may use it only when all of the following are true:

- The caller is a freight forwarder. An importer, logistics manager, or administrator is refused with "Only a freight forwarder in the shipment's company can link it to an importer".
- The caller belongs to an active company. Otherwise the response is "Your account must belong to an active company to view shipments".
- That company owns the shipment. A freight forwarder from another company receives the same link refusal.

The selected company may be any other active company. The link does not read `forwarder_importer_relationships`. A missing, inactive, or same-company id is refused with "Invalid or inactive importer organisation selected." Null clears an existing link and does not require a relationship.

The shipment form shows the importer search only to a freight forwarder. The search itself returns every other active company. It is not limited to an authorised importer.

After a link is saved, the linked importer can view that shipment. Clearing the link removes that access. Deactivating a relationship row does not remove access that a link already gave. `ShipmentImporterAccessIntegrationTests` deactivates the row and the importer can still list, open, and track the shipment.

## CSV import

`POST /api/shipments/import` accepts a file only from a freight forwarder with an active company.

- An importer or logistics manager is refused with "Your role is not authorized to import shipments in bulk".
- An administrator is refused with "ADMIN bulk-import access is deferred until company type is defined". CDG-26 keeps that refusal.
- A caller without an active company is refused with "Bulk import requires an active company".

A blank Importer Organisation column imports the row with no importer link. A nonblank value must be the exact stored company code.

`ImporterOrganisationAuthorizer` then refuses the row when:

- the code is unknown, including a different case, as `UNKNOWN_IMPORTER_ORGANISATION`;
- the company is inactive, as `INACTIVE_IMPORTER_ORGANISATION`;
- the company is the importing company, or there is no active `forwarder_importer_relationships` row for that pair, as `UNAUTHORIZED_IMPORTER_ORGANISATION`.

An active row and an exact code allow the link. Deactivating that row makes the same code unauthorised for a later import. The test that records this is named `activeRelationshipsAuthorizeExactCompanyCodesAndDeactivationOnlyBlocksNewLinks`.

## Nothing creates the relationship

`V15__create_forwarder_importer_relationships.sql` creates the table and does not insert a row. `V2__companies_and_invitations.sql` seeds Harbourline Logistics (Demo) and Straits Fresh Imports (Demo) and does not relate them.

The entity can be constructed and deactivated. No production service calls either method. The only inserts are in tests. A freight forwarder therefore cannot name either seeded company, or any later company, on an import unless a row is written outside the product. CDG-298 creates a company and does not create this relationship.

## Not decided here

CDG-25 covers linking one shipment. CDG-26 and CDG-129 cover rejecting an importer the forwarder is not authorised to import for. Neither story provides a way to grant that authorisation, and the link path does not use the same check as import. Who may create, see, or end a relationship, whether linking must start using it, and whether ending it changes shipments already linked, belong to CDG-332.
