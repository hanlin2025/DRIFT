# Organisation creation (CDG-313)

An administrator whose own company is active may add another organisation from User
management. Creating the organisation does not assign that administrator to it, does not
invite anyone, does not create a shipment, and does not create an importer relationship.

## Eligible administrators

Any `ADMIN` account that belongs to an active company may create an organisation. The new
row is active immediately. The creator stays in their original company.

## Assignment versus invitation

`GET /api/admin/organisations` remains the assignment list and still returns only the
administrator's own active company.

`GET /api/admin/invitation-organisations` returns the administrator's own active company
plus active companies that administrator created that still have no users. The creating
administrator may invite an importer, freight forwarder, or logistics manager into those
empty organisations. An invitation still cannot grant `ADMIN`. Inviting the first
administrator of another organisation stays a later story.

Once an organisation has users, invitation into it follows the usual own-organisation rule.
Creating administrators for a fresh installation remains [first administrator setup](first-administrator.md).

## Importer search and local invitations

`GET /api/organisations?type=importer` returns other active companies for roles that search
broadly. A freight forwarder sees only authorised importers. After an administrator grants
an authorisation, the new organisation can appear in that freight forwarder's importer
search. `scripts/create-invitation.py` accepts any active organisation code, including one
created through this flow.
