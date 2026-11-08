---
name: selfservice-docker-compose
description: Install, verify and remove the Mifos Self Service Plugin running inside Apache Fineract with Docker Compose, using the docker-compose.yml at this repository's root (PostgreSQL + a plugin build from this checkout + the apache/fineract:develop image). Use when the user asks to install, deploy, run, start, stop, uninstall or troubleshoot the self service plugin, or Fineract with the plugin, locally or with Docker.
license: MPL-2.0
compatibility: Needs Docker with the Compose plugin (docker compose) 2.23.1 or later, curl, about 4 GB of free memory for Docker, internet access, and a clone of https://github.com/openMF/selfservice-plugin. No local Java or Maven needed. Not for production.
---

# Install the self service plugin with Docker Compose

`docker-compose.yml` in the repository root starts three services:

| Service | What it does |
|---|---|
| `db` | PostgreSQL 18 with the `fineract_tenants` database |
| `plugin-build` | Builds the plugin JAR from this checkout with Maven and JDK 25, copies it and the Mifos plugins it depends on (`savings-plugin`) to a shared volume, then exits |
| `fineract` | `apache/fineract:develop` with those JARs in `/app/plugins`, on `https://localhost:8443` |

Run every command yourself, from the repository root; don't hand the steps to the user as
instructions. Do not report success until every check in step 4 passes.

## 1. Preflight

Run these and stop with the error if any fails:

```bash
docker info --format '{{.ServerVersion}} {{.MemTotal}}'   # daemon running; memory in bytes
docker compose version                                    # Compose v2 plugin
docker compose ps -a                                      # is the stack already there?
```

- Docker needs about 4 GB of memory for the stack. If `MemTotal` is below 4000000000, tell the user to
  raise Docker's memory limit before continuing.
- If `docker compose ps -a` lists a `fineract` container, the stack is already installed. Don't
  reinstall; go to step 4 and verify it. If it is stopped, `docker compose up -d` starts it again.
- Port 8443 on localhost must be free. Run `curl -s -m 2 -o /dev/null http://127.0.0.1:8443/`. The
  port is free only if curl exits with code 7 (connection refused); any other exit code, including a
  TLS, empty-reply or timeout error, means something listens there. If the stack isn't running,
  another program has the port. Tell the user; don't stop it yourself.

## 2. Start

```bash
docker compose up -d
```

This returns once the build has finished and Fineract's container has started. On the first run the
build took about 7.5 minutes on a laptop, mostly Maven downloads; later runs reuse the `maven-repo`
volume and the build takes under a minute. Give the command a timeout of at least 20 minutes, or
run it in the background and check `docker compose ps -a` until `plugin-build` has exited. Fineract
then needs about a minute for its database migrations; step 4 waits for that.

## 3. Watch for early failures

```bash
docker compose ps -a
```

| Symptom | Cause and fix |
|---|---|
| `plugin-build` exited with a non-zero code | Build failed. Show `docker compose logs --tail=80 plugin-build`. Network errors mean Maven couldn't reach its repositories |
| `fineract` stays `Created` | It waits for `plugin-build` to finish and `db` to be healthy. Not a failure while the build runs |
| `fineract` restarting or `Exited` | Show `docker compose logs --tail=100 fineract`. `Connection refused` to `db` means the database wasn't ready: `docker compose restart fineract` |
| `fineract` exits with code 137 | Out of memory. Docker needs about 4 GB; ask the user to raise the limit |
| `ClassNotFoundException: org.apache.fineract...` naming a Mifos class | A Mifos plugin JAR is missing from `/app/plugins`. Check `docker compose logs plugin-build`: its last lines list the copied JARs. Rebuild with `docker compose up -d --force-recreate plugin-build fineract` |
| `database "fineract_default" does not exist` | The database volume was created by an older version of `docker-compose.yml`. Ask the user before `docker compose down -v` (it deletes the data), then `docker compose up -d` |
| `UnsupportedClassVersionError` in the fineract log | The Fineract image runs an older Java than the plugin needs (25). Run `docker compose pull fineract` and `docker compose up -d` |
| `port is already allocated` | Something else uses port 8443. Tell the user |

## 4. Verify (all must pass)

```bash
sh .agents/skills/selfservice-docker-compose/scripts/verify-install.sh
```

The script waits up to 10 minutes for Fineract's health to report `UP`, then checks that:

1. Fineract's overall health is `UP` (HTTP 200 from `actuator/health`).
2. The plugin JAR is mounted at `/app/plugins/selfservice-plugin.jar`.
3. The admin login `mifos` / `password` works on the `default` tenant.
4. `POST /fineract-provider/api/v1/self/authentication` with the body `{}` returns `400` with the
   plugin validator's `username` and `password` errors. A Fineract without the plugin answers `401`.

It exits 0 only if all four pass, and prints the last 50 lines of the Fineract log if one fails.
Exit code 2 means `fineract` isn't running (start it with `docker compose up -d`) or curl is missing.
Report its PASS/FAIL lines to the user as a table. If it fails, give the failure and the log
instead of a success message.

## 5. Tell the user how to use it

- API base: `https://localhost:8443/fineract-provider/api/v1`. The certificate is self-signed, so use
  `curl -k`, and accept the certificate once in a browser before a web app can call the API.
- Every request needs the header `Fineract-Platform-TenantId: default`.
- Self service endpoints are under `/api/v1/self/` (the API table in README.md).
- Admin login: `mifos` / `password`.
- To rebuild after changing the plugin source: `docker compose up -d --force-recreate plugin-build fineract`.

## 6. Stop or uninstall

```bash
docker compose stop        # stop, keep the data
docker compose down        # remove containers, keep the database and Maven volumes
docker compose down -v     # remove everything, including the database
```

Ask before running `down -v`: it deletes all Fineract data. Confirm with `docker compose ps -a`,
which should list nothing after `down`.

## Rules

- Change the system only through the commands above. Don't edit `docker-compose.yml` or the source
  unless the user asks.
- Don't run `docker system prune`, `docker volume prune`, or commands that stop or remove containers
  outside this Compose project.
- The database password `postgres` and the login `mifos` / `password` are fixed values for a local
  demo. The database port isn't published, and Fineract listens on 127.0.0.1 only. Tell the user to
  change both before exposing the install to anyone else.
