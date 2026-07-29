# Keycloak realm seed — canonical identity topology (gap T1-2)

`realm-gmepay.json` is imported by the compose Keycloak (`--import-realm`) on first
boot. It is the **single source of truth** for realm name, client ids and the claim
mappers the backend requires. Every other file that names a realm/client/issuer must
agree with it; see the table below.

## 1. One topology, four files

| | value |
|---|---|
| realm | `gmepay` (**only** realm — `gmepay-partners` never existed in any seed file and is gone from every config) |
| admin-ui client | `admin-ui` — **public**, auth-code + PKCE `S256` |
| partner-portal-ui client | `partner-portal-ui` — **public**, auth-code + PKCE `S256` |
| browser-facing issuer (local docker) | `http://localhost:8097/realms/gmepay` |
| in-cluster JWKS (local docker) | `http://keycloak:8080/realms/gmepay/protocol/openid-connect/certs` |
| host port | **8097** → container 8080 (8090 is `scheme-adapter-zeropay`, see `docs/COMPOSE.md`) |

Both clients are **public**. A browser SPA cannot keep a `client_secret`, so the
previous confidential clients (`admin-ui-dev-secret` / `partner-portal-ui-dev-secret`)
could never complete a token exchange from the browser — Keycloak answered
`invalid_client`. PKCE `S256` replaces the secret.

Files that must match, and the key in each:

| file | keys |
|---|---|
| `docker-compose.yml` (`keycloak`) | `KC_HOSTNAME_URL` ← `${KC_PUBLIC_URL:-http://localhost:8097}` |
| `docker-compose.yml` (`ops-partner-bff`, `api-gateway`) | `OIDC_ISSUER_URI`, `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWK_SET_URI` |
| `apps/*/src/api/oidc.js` | `DEFAULT_KEYCLOAK_URL`, `DEFAULT_CLIENT_ID` (overridable by `NEXT_PUBLIC_KEYCLOAK_URL` / `NEXT_PUBLIC_KEYCLOAK_CLIENT_ID`) |
| `apps/*/.env.example`, `apps/*/.env.local` | the two `NEXT_PUBLIC_KEYCLOAK_*` vars |
| `deploy/helm/gmepay/values*.yaml` | `abi.OIDC_ISSUER_URI`, `abi.SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_{ISSUER,JWK_SET}_URI`, per-UI `NEXT_PUBLIC_KEYCLOAK_*` |

`docker/keycloak/check-topology.mjs` asserts the whole table programmatically (99
checks, no servers, no dependencies) — run it after touching any of those files:

```bash
node docker/keycloak/check-topology.mjs   # exit 0 = every file agrees
```

### Why issuer and JWKS are two different URLs

Keycloak stamps `iss` with the URL **the browser used** (pinned here by
`KC_HOSTNAME_URL`), so the resource server must expect
`http://localhost:8097/realms/gmepay`. A container cannot resolve `localhost:8097`,
so OIDC discovery against the issuer would fail — the JWKS URI is therefore pinned
separately to the in-network address. Spring Boot builds the decoder from
`jwk-set-uri` and still validates `iss` against `issuer-uri`, which is exactly the
split we want. Override both with one knob:

```
KC_PUBLIC_URL=https://auth.example.com docker compose --profile core up -d
```

## 2. Claim mappers (without these every caller is authenticated-but-403)

`ops-partner-bff` derives authorization from token claims only
(`security/TokenClaims.java`), never from request headers:

| claim | shape | source | consumed by |
|---|---|---|---|
| `permissions` | JSON array of `resource.action` codes (a CSV string is also accepted) | user attribute `permissions`, mapper `gmepay-permissions` (multivalued) | `OpsRbacGuard.requireOps/requireTxnView/requireAdminRead/requireAdminWrite`, `AdminSurfaceRbacInterceptor` over `/v1/admin/**` |
| `partner_id` | single string, must equal a **seeded partner code** | user attribute `partner_id`, mapper `gmepay-partner-id` | `OpsRbacGuard.requirePartnerScope` for `/v1/portal/{partnerId}/**` |
| `realm_access.roles` | JSON array | built-in `roles` client scope (already in `defaultClientScopes`) | `ROLE_*` authorities (parity with api-gateway) |

Both mappers are declared **inline on each client** (not on a shared client scope)
so a realm import cannot half-apply them.

Seeded users:

| user | password | realm role | `partner_id` | `permissions` |
|---|---|---|---|---|
| `admin` | `demo` | OPERATOR | — (hub) | full hub set incl. `ops:operate`, `partner.activate`, `rbac.manage` |
| `operator-readonly` | `demo` | OPERATOR | — (hub) | `partner.view`, `txn.view`, `report.generate` (read-only: admin **writes** 403) |
| `partner-demo` | `demo` | PARTNER_USER | `GMEREMIT` | none (a partner needs no permission for its own portal) |
| `partner-sendmn` | `demo` | PARTNER_USER | `SENDMN` | none |

`GMEREMIT` and `SENDMN` are the codes `config-registry`'s `PartnerSeeder` actually
creates. The old seed gave `partner-demo` **no** `partner_id` at all, so every portal
page 404'd/403'd even after a successful login.

## 3. Known Keycloak 25 caveat — unmanaged user attributes

Realm import writes `permissions` / `partner_id` straight onto the user model, so the
seeded users work. But KC 24+ ships the declarative **User Profile** with unmanaged
attributes *disabled*, so those two attributes are invisible in the Admin Console and
an admin-REST update of a user can drop them. To manage them by hand, enable
unmanaged attributes once per realm:

*Admin Console →* Realm settings → General → **Unmanaged attributes = Enabled**

or

```bash
docker compose exec keycloak /opt/keycloak/bin/kcadm.sh config credentials \
  --server http://localhost:8080 --realm master --user admin --password admin
docker compose exec keycloak /opt/keycloak/bin/kcadm.sh update users/profile -r gmepay \
  -s 'unmanagedAttributePolicy=ENABLED'
```

This is deliberately **not** in the seed JSON: a malformed
`components["org.keycloak.userprofile.UserProfileProvider"]` block fails the whole
realm import, and nothing in the seeded flow needs it.

## 4. Verifying a login without a browser

`directAccessGrantsEnabled` is left on for these dev clients, so a token can be
fetched with curl (dev realm only — never enable it in production):

```bash
TOKEN=$(curl -s -X POST \
  http://localhost:8097/realms/gmepay/protocol/openid-connect/token \
  -d grant_type=password -d client_id=partner-portal-ui \
  -d username=partner-demo -d password=demo -d scope=openid | jq -r .access_token)

# claims must show iss=http://localhost:8097/realms/gmepay and partner_id=GMEREMIT
echo "$TOKEN" | cut -d. -f2 | base64 -d 2>/dev/null | jq .

curl -i -H "Authorization: Bearer $TOKEN" http://localhost:8095/v1/portal/GMEREMIT/overview   # 200
curl -i -H "Authorization: Bearer $TOKEN" http://localhost:8095/v1/portal/SENDMN/overview     # 403
curl -i -H "Authorization: Bearer $TOKEN" http://localhost:8095/v1/admin/partners             # 403
curl -i http://localhost:8095/v1/portal/GMEREMIT/overview                                     # 401
```
