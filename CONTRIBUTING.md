# Contributing to the Self Service Plugin

Thank you for your interest in contributing! This guide will get you set up and pointed to the right resources.

---

## Development Setup

### Prerequisites

| Requirement | Version |
|---|---|
| Java (JDK) | **25** |
| Maven | 3.6+ (or use the included `./mvnw` wrapper) |
| Docker | Required for integration tests ([Testcontainers](https://www.testcontainers.org/)) |
| Apache Fineract | **1.16.0-SNAPSHOT** (`develop` branch) |

### Build

```bash
git clone https://github.com/openMF/selfservice-plugin.git
cd selfservice-plugin
./mvnw clean package -Dmaven.test.skip=true
```

### Add as a Maven/Gradle Dependency

If you're building a project that depends on this plugin:

**Repository:** `https://mifos.jfrog.io/artifactory/libs-snapshot-local/`

**Maven:**
```xml
<dependency>
    <groupId>community.mifos</groupId>
    <artifactId>selfservice-plugin</artifactId>
    <version>1.16.0-SNAPSHOT</version>
</dependency>
```

**Gradle:**
```groovy
implementation 'community.mifos:selfservice-plugin:1.16.0-SNAPSHOT'
```

---

## Testing

The project has a multi-tier test pyramid (unit → integration) with JaCoCo coverage enforcement.

For the full guide — including how to run tests, add new integration tests, naming conventions, and CI structure — see **[TESTING.md](TESTING.md)**.

Quick start:

```bash
# Unit tests only (~60s, no Docker)
./mvnw clean test

# Full pipeline including integration tests (Docker required)
./mvnw clean verify
```

---

## Code Style

The project uses [Google Java Format](https://github.com/google/google-java-format) enforced by the [Spotless](https://github.com/diffplug/spotless) Maven plugin. Non-conforming code will fail the build.

```bash
# Auto-fix formatting before committing
./mvnw spotless:apply
```

For more on coding standards, common patterns, and API design conventions, see **[AGENTS.md](AGENTS.md)**.

---

## How to Contribute

1. **Fork** the repository and create a feature branch from `develop`.
2. **Write tests** — new features must include test coverage. See [TESTING.md](TESTING.md) for conventions.
3. **Follow the code style** — run `./mvnw spotless:apply` before committing.
4. **Open a Pull Request** against `develop` with a clear description of your changes.

You can also contribute by:
- Reviewing PRs from other contributors
- Helping triage and respond to issues
- Improving documentation

Active contributors are promoted to committer status on this project.

We recommend that you **Watch** and **Star** this project on GitHub to stay up to date.
