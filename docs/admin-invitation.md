# Administrator invitations (CDG-310)

An administrator invites a colleague from User management. The invitation is stored for an organisation the administrator may invite into: their own active organisation, or an active organisation that administrator created that still has no users (see [organisation creation](cdg313-organisation-creation.md)). The role is one of the roles that person may join: importer, freight forwarder, or logistics manager. It cannot grant a second administrator. The email, role and organisation are taken from the invitation when the colleague registers. The signup form cannot change them.

The message is sent through the local Mailpit mailbox (`localhost:1025`, web view http://localhost:8025). It contains a signup link and the same invitation code. The code is not returned by the API and is not written to the application log. A failed send rolls the new invitation back, so the page does not say the invitation was sent and a pending invitation for that email is left as it was. Sending again after a failure can create one new pending invitation.

An invitation lasts seven days unless `INVITATION_TTL` is set. Its link defaults to `${FRONTEND_BASE_URL}/signup`; locally, `FRONTEND_BASE_URL` defaults to `http://localhost:5173`. `MAIL_FROM_ADDRESS` is shared with password-reset and risk-alert messages and defaults locally to `noreply@localhost`. `INVITATION_LINK_BASE_URL` can still override the invitation link when needed.

Someone who is not an administrator, or an administrator choosing an organisation they may not invite into, cannot send the invitation. An organisation with users that is not the administrator's own company is refused. An expired, revoked, or already used code cannot register an account.

The same administrator can list the pending invitations for organisations they may invite into. The list shows the invited email, role, organisation and expiry. An invitation that is still pending after its expiry is shown as expired. Consumed invitations, invitations for organisations they may not invite into, and the invitation code are omitted. An administrator who cannot send, someone who is not an administrator, and a signed-out caller cannot load the list. An authenticated refusal is 403. An organisation with nothing pending is an empty list. Retrying a failed load does not change an invitation.

Withdrawing is a separate confirmation. It uses the same permission as sending and does not send a replacement. The rules are:

- A pending invitation that has not expired becomes `REVOKED`. One revocation record stores the administrator and the time.
- An accepted invitation cannot be withdrawn, and the account that used it is unchanged.
- An expired invitation stays expired and pending. Withdraw does not make it valid, and it still cannot register.
- An invitation that is already revoked stays as it is. A second withdrawal does not add another revocation record and says that it is already withdrawn. Reissuing an invitation still revokes the previous pending row and does not write a withdrawal record.
- A missing invitation and an invitation in another organisation get the same 403. The response does not include that invitation. A signed-out caller gets 401.
- A withdrawal that fails before it is saved leaves the invitation pending. The page shows an error that can be tried again.
- Opening or submitting a revoked invitation does not create an account. The signup page says the invitation is no longer valid, without saying whether an account exists.
