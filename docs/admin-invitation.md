# Administrator invitations (CDG-310)

An administrator invites a colleague from User management. The invitation is stored for the administrator's own active organisation and one of the roles that person may join: importer, freight forwarder, or logistics manager. It cannot grant a second administrator. The email, role and organisation are taken from the invitation when the colleague registers. The signup form cannot change them.

The message is sent through the local Mailpit mailbox (`localhost:1025`, web view http://localhost:8025). It contains a signup link and the same invitation code. The code is not returned by the API and is not written to the application log. A failed send rolls the new invitation back, so the page does not say the invitation was sent and a pending invitation for that email is left as it was. Sending again after a failure can create one new pending invitation.

An invitation lasts seven days unless `INVITATION_TTL` is set. `INVITATION_LINK_BASE_URL` defaults to `http://localhost:5173/signup`, and `INVITATION_FROM` defaults to `noreply@localhost`.

Someone who is not an administrator, or an administrator choosing another organisation, cannot send the invitation. An expired, revoked, or already used code cannot register an account.
