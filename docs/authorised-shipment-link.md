# Authorised shipment link

Linking a shipment to an importer requires an active authorisation for that forwarder–importer pair. CSV import already used the same row. Only an administrator of the forwarder company can grant or end that authorisation.

## Merge order

Merge #254 before #266, #267, and #268. Merge #269 with that group when it is ready. #254 is the only product path that creates an administrator. Until it is on `main`, nobody can grant an authorisation, so nobody can link a shipment once #267 is there.

## Which company can grant

Setup creates one administrator for one existing company, then refuses any further administrator. An invitation cannot create an administrator and cannot invite someone into a different company. Until a later story lets an administrator invite the first administrator of another organisation, only the company chosen during setup can grant authorisations.

For the demo data, setup must choose Harbourline Logistics (`HARBOURLINE_DEMO`). If setup chooses Straits Fresh, Harbourline never gets an administrator and cannot authorise an importer.
