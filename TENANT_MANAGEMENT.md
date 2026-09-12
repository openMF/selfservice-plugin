# Tenant Management

API-first lifecycle management for the tenants of a Mifos X / Apache Fineract installation:
list, create, update, activate, deactivate, suspend and remove.

Tracked as [MX-406](https://mifosforge.jira.com/browse/MX-406). The administrative UI that
consumes these endpoints is tracked separately as WEB-1242 in the
[web-app](https://github.com/openMF/web-app) repository, and the user guide for that UI belongs
with it.

## Contents

- [Why it lives here](#why-it-lives-here)
- [Security model](#security-model)
- [Configuration](#configuration)
- [Database changes](#database-changes)
- [API reference](#api-reference)
- [What creating a tenant actually does](#what-creating-a-tenant-actually-does)
- [What removing a tenant does not do](#what-removing-a-tenant-does-not-do)
- [Audit trail](#audit-trail)
- [Operational notes](#operational-notes)

## Why it lives here

Apache Fineract is multi-tenant by design, but has never exposed tenant administration: the
registry is edited with SQL scripts or an external tool. This plugin adds the API without
requiring a fork of Fineract.

Two things make that possible:

- Fineract's `JerseyConfig` registers **any** Spring bean annotated `@Path`, wherever it lives, so
  a plugin can contribute REST endpoints.
- The registry (`tenants`, `tenant_server_connections`) lives in the **tenant store** database,
  reachable through the `hikariTenantDataSource` bean. This feature talks to it with JDBC — a
  Spring Data repository would be bound to the per-tenant datasource and would not find these
  tables at all.

## Security model

Tenant management runs in a **master context**, above every tenant.

Fineract resolves every user inside the tenant named by `Fineract-Platform-TenantId`, so any
permission granted there — even `ALL_FUNCTIONS` — belongs to that tenant. Administering the
tenants themselves cannot belong to any one of them. This plugin therefore adds its own security
chain for `/v1/admin/tenants`, ordered ahead of Fineract's `/api/**` chain (the same approach the
self-service chain uses for `/v1/self/**`), so no change to core is needed:

- Requests authenticate with HTTP Basic against **master users** stored in the tenant store
  (`tenant_master_user`), not against any tenant's users.
- A master user must hold the **`SUPER_MASTER`** role.
- No tenant header is needed. A tenant's own users, however privileged in their tenant, get `401`.
- Each endpoint also checks the role itself, so a mistake in the chain's path matching fails closed.

Master passwords are stored only as Spring Security delegating hashes (`{bcrypt}…`).

### The first master user

No API can create the first master user without already requiring one, so it comes from
configuration at startup:

```bash
FINERACT_TENANT_MANAGEMENT_BOOTSTRAP_MASTER_USERNAME=master
FINERACT_TENANT_MANAGEMENT_BOOTSTRAP_MASTER_PASSWORD='a long, unique password'
```

- Created once. If the user already exists nothing changes — its password is **not** reset — so a
  lingering or edited variable can never silently overwrite a master credential.
- Passwords shorter than 12 characters are refused and no user is created.
- Safe when several nodes start at once: if another node creates the user first, the others
  confirm it exists and start normally.
- With no configuration and no master user, startup logs a warning and `/v1/admin/tenants` refuses every
  request.

Managing further master users through the API is not part of this change.

Database credentials are **write-only** throughout: they are encrypted with core's
`DatabasePasswordEncryptor`, are never selected into any projection, and are never returned by any
endpoint.

## Configuration

| Property | Default | Meaning |
|---|---|---|
| `fineract.tenant-management.bootstrap-master-username` | *(empty)* | Master user created at startup if it does not exist. |
| `fineract.tenant-management.bootstrap-master-password` | *(empty)* | Its password, at least 12 characters. Applied only when the user is first created. |
| `fineract.tenant-management.migrate-on-create` | `true` | Migrate a new tenant's schema immediately. Set `false` to leave it to the next platform startup. |
| `fineract.tenant-management.status-cache-seconds` | `30` | How long a tenant's status is cached by the enforcement filter. Also the worst-case delay before a suspension takes effect on **other** nodes in a cluster. |
| `fineract.tenant-management.status-stale-grace-seconds` | `300` | How long past its cache expiry an `ACTIVE` status is still trusted while the tenant store cannot be read. After that the tenant is refused until the store answers. |
| `fineract.tenant-management.plugin-changelogs` | self-service, then savings | Comma-separated plugin changelogs applied to a new tenant after core's, in order. Must use the exact `classpath:/...` strings each plugin's startup migration uses. Entries not on the classpath are skipped. Add a plugin here when you install one that owns tables. |

## Database changes

Applied to the **tenant store** database by `TenantManagementConfig`, after core's own upgrade.
Core's tenant-store changelog is a flat, hard-coded list with no module extension point — unlike
the per-tenant master — so the plugin runs its own changelog rather than appending to core's.

- `tenants.status` — `ACTIVE` / `INACTIVE` / `SUSPENDED`. **Existing rows default to `ACTIVE`**, so
  an existing installation behaves exactly as before.
- `tenants.description`, `tenants.contact_email` — optional metadata.
- `tenant_administration_audit` — the audit trail (below).
- `tenant_master_user` — master users for the master context.
- `tenant_retained_schema` — databases kept after a tenant is removed, and the identifier that owns them.

Master users are stored in `tenant_master_user` in the tenant store.

## API reference

Base path `/v1/admin/tenants`, served by the master security chain described above: authenticate as a
master user, and send no tenant header.

| Method | Path | Role | Purpose |
|---|---|---|---|
| `GET` | `/v1/admin/tenants` | `SUPER_MASTER` | List, with `search`, `status`, `offset`, `limit` |
| `GET` | `/v1/admin/tenants/template` | `SUPER_MASTER` | Selectable timezones and statuses |
| `GET` | `/v1/admin/tenants/{id}` | `SUPER_MASTER` | One tenant |
| `POST` | `/v1/admin/tenants` | `SUPER_MASTER` | Register and provision |
| `PUT` | `/v1/admin/tenants/{id}` | `SUPER_MASTER` | Partial update |
| `POST` | `/v1/admin/tenants/{id}?command=activate\|deactivate\|suspend` | `SUPER_MASTER` | Change status |
| `DELETE` | `/v1/admin/tenants/{id}` | `SUPER_MASTER` | Remove the registry entry |
| `POST` | `/v1/admin/tenants/test-connection` | `SUPER_MASTER` | Probe a database before committing |

`search` matches identifier and name case-insensitively, and matches **literally** — a term
containing `%` or `_` finds those characters rather than acting as a wildcard. `limit` is capped so
one request cannot pull an entire large registry into memory.

Ready-to-run requests are in
[`api-reference/bruno`](api-reference/bruno) under **TENANT MANAGEMENT**. The requests read
`{{master_username}}`, `{{master_password}}` and `{{tenant_db_password}}` from your Bruno
environment instead of embedding credentials.

The namespace is `/v1/admin/tenants` rather than `/v1/tenants` because core Fineract already serves
`/v1/tenants/{tenantId}/oidc-config`; a master chain claiming `/v1/tenants/**` would capture that core
endpoint.

### OpenAPI

The OpenAPI 3 description of these endpoints is
[`api-reference/openapi/tenant-management.yaml`](api-reference/openapi/tenant-management.yaml). It is
generated from the resource's annotations, and `TenantManagementOpenApiSpecTest` fails the build if
the committed file drifts from them. After changing the API, regenerate it with:

```bash
./mvnw test -Dtest=TenantManagementOpenApiSpecTest -Dopenapi.update=true
```

It is published as a file rather than through Fineract's Swagger UI: that UI loads a static
`fineract.json` produced when core itself is built, which plugin endpoints are never part of.

### Create

A local-development example. Supply the tenant's database password through `TENANT_DB_PASSWORD`
rather than typing it inline; production tenants need a unique database secret.

```bash
curl -X POST http://localhost:8080/fineract-provider/api/v1/admin/tenants \
  -H 'Content-Type: application/json' \
  -u "$MASTER_USERNAME:$MASTER_PASSWORD" \
  -d '{
    "identifier": "acme",
    "name": "Acme Microfinance",
    "timezoneId": "Asia/Kolkata",
    "schemaName": "mifostenant_acme",
    "schemaServer": "localhost",
    "schemaServerPort": "5432",
    "schemaUsername": "postgres",
    "schemaPassword": "'"$TENANT_DB_PASSWORD"'"
  }'
```

`identifier` is restricted to `[a-z0-9][a-z0-9_-]*` and `schemaName` to `[A-Za-z_][A-Za-z0-9_]{0,62}`.
These are narrow on purpose: the identifier travels in the `Fineract-Platform-TenantId` header and
is compared on every request, and the schema name is concatenated into `CREATE DATABASE` DDL,
which no JDBC driver allows to be bound as a parameter. The pattern — not escaping — is what makes
that safe. It must start with a letter or underscore, because PostgreSQL rejects an unquoted
database name that starts with a digit. It is stored in lower case: PostgreSQL folds an unquoted
name to lower case, and the connection must use the name the database was created with. 63 is PostgreSQL's identifier limit and the shortest across supported engines.

`identifier` **cannot be changed** afterwards: it is how every request selects a tenant and is
embedded in that tenant's existing sessions and integrations. Sending one to `PUT` is rejected
rather than silently ignored.

On `PUT`, a field sent blank is refused for `name`, `timezoneId`, `schemaServer`, `schemaServerPort`
and `schemaUsername`. For `description`, `contactEmail` and `schemaConnectionParameters`, an empty
string (or `null`) clears the value; omitting a field leaves it unchanged.

### Status

Status is enforced, not merely recorded. `TenantStatusEnforcementFilter` runs ahead of the security
chain and refuses any request addressed to a tenant that is not `ACTIVE` with **503 Service
Unavailable** — before any credential is read.

This is enforced in a filter because Fineract resolves a tenant with `where t.identifier = ?` and
no status predicate, in both `JdbcTenantDetailsService` and the authentication path
`AuthTenantDetailsServiceJdbc`. Without the filter the column would be decorative and a suspended
tenant would keep serving requests.

**Tenant administration itself is never blocked** by this filter: `/v1/admin/tenants` is not addressed to
a tenant, so a UI that always sends a tenant header cannot be locked out by that tenant's suspension.
No tenant — including `default` — is exempt from suspension.

A tenant whose stored status is not one of the three (a hand edit or corruption) is also refused
with 503, reported as `UNRECOGNISED`. The raw stored value is never echoed. The administration API returns such a tenant with `status: null`
rather than guessing, and setting a status through the API corrects it.

Status changes are idempotent: activating an already-active tenant succeeds and changes nothing, so
a retried request does not look like a failure.

## What creating a tenant actually does

1. Checks the identifier is free, and that the database may be bound to it: not the tenant store
   itself or a system database (`postgres`, `template0`, …), not already used by another registered
   tenant (same server, port and name — servers compared as written), and not retained from a
   removed tenant under a different identifier.
2. Creates the schema if absent — an existing schema is **reused, never emptied**.
3. Opens a connection to prove the credentials work.
4. Writes the connection and tenant rows in one transaction, with the password encrypted and the
   master password hash stamped. (Without that hash core's `TenantDataSourceFactory` refuses to
   open the tenant, failing later at startup with a bare "Invalid master password".)
5. Migrates the schema — core's changelog, then each plugin changelog the platform applies at
   startup (self-service, then savings, by default) — so the new tenant ends up with the same schema
   as one migrated at startup, and is usable immediately. A plugin changelog that isn't installed is
   skipped. Classpath discovery isn't used: at least one module (`fineract-branch`) ships a changelog
   that startup never applies, and discovery would give API-created tenants tables other tenants
   lack.

Steps 2 and 3 happen **before** anything is written, so a tenant that could never have worked
leaves no row behind. If step 5 fails, the registry entry is removed again and the error is
returned; the schema is left in place, and a retry resumes the migration rather than restarting it.

## What removing a tenant does not do

`DELETE` removes the **registry entry only**. It never drops a schema and never deletes tenant
data: the database is left intact for retention, audit or reinstatement. Dropping a live financial
database from an HTTP endpoint is not a capability this API has.

The retained database stays bound to the removed tenant's identifier (`tenant_retained_schema`):
only a tenant created again under that same identifier can reuse it, so a later tenant with a
different identifier can never be routed to a removed tenant's data.

The status check is part of the delete statement itself, so a tenant activated by a concurrent
request after the check is not removed.

An `ACTIVE` tenant is refused — deactivate it first, so removal is a deliberate two-step action
rather than something one mistaken request can do to a tenant currently serving users.

## Audit trail

Every mutation writes a row to `tenant_administration_audit` in the tenant store, recording the
action, outcome, tenant identifier, acting user, and the tenant they acted from.

It lives in the tenant store rather than per-tenant so it **outlives the tenants it describes** — a
trail that vanished along with the tenant whose deletion it recorded would be worthless — and so an
auditor has one place to look.

**No credentials are recorded.** The `detail` column holds the *names* of the fields a request
changed, never their values, so a password rotation is recorded as having happened while the
password itself never reaches the table.

A failure to write the trail never fails the action being recorded: losing an audit row is bad, but
rolling back a completed tenant change because its bookkeeping failed — leaving the registry
inconsistent with what the caller was told — is worse.

## Operational notes

- **Clustering.** The status cache is per-node. A suspension applied on one node takes effect on
  the others within `status-cache-seconds`. Lower it if you need suspensions to bite faster;
  raise it to reduce reads against the tenant store.
- **Cache eviction.** Writes evict both this plugin's status cache and core's `tenantsById` cache,
  which holds the connection details the platform routes on. Without that, a changed database host
  would keep routing to the old server until restart.
- **Registry unavailable.** If the tenant store cannot be read, the filter uses the last status it
  saw for that tenant. One last seen `ACTIVE` keeps working for up to `status-stale-grace-seconds`
  (5 minutes) past its cache expiry, and is refused after that, since another node may have
  suspended it meanwhile. One last seen suspended or unrecognised stays refused. A tenant with no
  earlier status is refused with 503 (`UNAVAILABLE`).
- **Unrecognised status.** Fails **closed** — the tenant is refused until its status is corrected.
- **Cache bound.** Only tenants that exist are cached, so invented identifiers in request headers
  cannot grow memory.
- **Both engines.** MariaDB/MySQL and PostgreSQL are supported. The integration test runs against
  real PostgreSQL; MariaDB has not yet been exercised end to end.
