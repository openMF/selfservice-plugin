# Mifos® Self Service Plugin for Apache Fineract®

[![Java CI](https://github.com/openMF/selfservice-plugin/actions/workflows/maven-build.yml/badge.svg)](https://github.com/openMF/selfservice-plugin/actions/workflows/maven-build.yml)
[![License: MPL 2.0](https://img.shields.io/badge/License-MPL_2.0-brightgreen.svg)](https://opensource.org/licenses/MPL-2.0)
![Java 21](https://img.shields.io/badge/Java-21-blue)
![Spring Boot 3](https://img.shields.io/badge/Spring_Boot-3-6DB33F)
![Fineract 1.15.0-SNAPSHOT](https://img.shields.io/badge/Fineract-1.15.0--SNAPSHOT-orange)

A Spring Boot plugin that extends [Apache Fineract](https://fineract.apache.org/) to provide **self-service banking capabilities** to end users. It lets customers register, authenticate, view their accounts, transfer funds, and manage their financial products — all without staff intervention.

---

## Table of Contents

- [What Is This?](#what-is-this)
- [What Does It Do?](#what-does-it-do)
- [Architecture Overview](#architecture-overview)
- [Prerequisites](#prerequisites)
- [Getting Started](#getting-started)
  - [Build from Source](#build-from-source)
  - [Deploy with Fineract (Docker)](#deploy-with-fineract-docker)
  - [Deploy with Fineract (Tomcat)](#deploy-with-fineract-tomcat)
- [API Reference](#api-reference)
- [Tenant Management](#tenant-management)
- [Contributing](#contributing)
- [History](#history)
- [Important Notices](#important-notices)
- [License](#license)

---

## What Is This?

Apache Fineract is an open-source core banking platform. Out of the box, its APIs are designed for **back-office staff** — loan officers, tellers, and administrators. There is no built-in mechanism for a bank's actual *customers* to log in, check their balances, or initiate transfers themselves.

**This plugin bridges that gap.** It adds a complete set of customer-facing REST APIs (under `/v1/self/...`) that sit on top of Fineract's core. Think of it as the backend for a mobile banking app or a customer web portal.

> **Key point:** This is a *plugin*, not a standalone application. It ships as a JAR that you drop into your existing Fineract deployment. It extends Fineract at runtime — no forking, no patching of Fineract source code required.

## What Does It Do?

| Capability | Description |
|---|---|
| **Self-Registration & Enrollment** | Customers can sign up, receive a verification token (via email/SMS), and activate their account. |
| **Authentication** | Dedicated login endpoint with HTTP Basic Auth support, 2FA compatibility, and login attempt notifications. |
| **Password Recovery** | Request a password reset token and renew the password — all self-service. |
| **Client Profile** | View personal details, profile images, charges, and transaction history. |
| **Savings Accounts** | List savings accounts, view balances, transactions, charges, and apply for new savings products. |
| **Loan Accounts** | Browse loan details, repayment schedules, charges, guarantors, and apply for new loans. |
| **Share Accounts** | View share account holdings and apply for share products. |
| **Fund Transfers** | Transfer between own accounts or to third-party beneficiaries (with configurable daily/per-beneficiary limits). |
| **Beneficiary Management** | Add, update, and delete third-party transfer beneficiaries. |
| **Product Discovery** | Browse available savings, loan, and share products. |
| **Reports** | Run pre-configured financial reports scoped to the authenticated user. |
| **Surveys & Scorecards** | Participate in surveys and view scorecards. |
| **Notifications** | Event-driven email/SMS notifications for login activity and account activation (with cooldown and SMTP fallback). |

## Architecture Overview

```
┌─────────────────────────────────────────────────────────┐
│                   Client Applications                   │
│          (Mobile App / Web Portal / Chatbot)            │
└────────────────────────┬────────────────────────────────┘
                         │  HTTPS  /v1/self/*
                         ▼
┌─────────────────────────────────────────────────────────┐
│              Self Service Plugin (this repo)            │
│  ┌─────────────┐ ┌──────────┐ ┌──────────────────────┐  │
│  │ Registration│ │ Security │ │ Notifications        │  │
│  │ & Enrollment│ │ & Auth   │ │ (Email / SMS)        │  │
│  └─────────────┘ └──────────┘ └──────────────────────┘  │
│  ┌─────────────┐ ┌──────────┐ ┌──────────────────────┐  │
│  │ Clients     │ │ Savings  │ │ Loans                │  │
│  └─────────────┘ └──────────┘ └──────────────────────┘  │
│  ┌─────────────┐ ┌──────────┐ ┌──────────────────────┐  │
│  │ Shares      │ │ Transfers│ │ Products / Reports   │  │
│  └─────────────┘ └──────────┘ └──────────────────────┘  │
└────────────────────────┬────────────────────────────────┘
                         │  delegates to
                         ▼
┌─────────────────────────────────────────────────────────┐
│               Apache Fineract Core                      │
│        (fineract-provider, fineract-core, etc.)         │
│                   Multi-Tenant DB                       │
└─────────────────────────────────────────────────────────┘
```

**Tech stack:** Java 21 · Spring Boot 3 · Spring Security (Basic Auth + OAuth2) · JAX-RS · JPA/EclipseLink · Liquibase · Lombok · Swagger/OpenAPI

## Prerequisites

| Requirement | Version |
|---|---|
| Java (JDK) | **21** |
| Maven | 3.6+ (or use the included `./mvnw` wrapper) |
| Apache Fineract | **1.15.0-SNAPSHOT** (`develop` branch) |
| Database | PostgreSQL or MySQL (managed by Fineract) |

> **Note:** You do *not* need to install Fineract from source to use the plugin. You just need a running Fineract instance (JAR or Docker) to deploy the plugin into.

## Getting Started

### Build from Source

```bash
git clone https://github.com/openMF/selfservice-plugin.git
cd selfservice-plugin
./mvnw clean package -Dmaven.test.skip=true
```

This produces a JAR at `target/selfservice-plugin-1.15.0-SNAPSHOT.jar`.

### Deploy with Fineract (Docker)

```bash
# Create a directory for plugin JARs
mkdir -p /opt/fineract/plugins

# Copy the built plugin
cp target/selfservice-plugin-*.jar /opt/fineract/plugins/

# Start Fineract with the plugin on the classpath
java -Dloader.path=/opt/fineract/plugins/ -jar fineract-provider.jar
```

### Deploy with Fineract (Tomcat)

```bash
# Copy the JAR into Fineract's library directory
cp target/selfservice-plugin-*.jar $TOMCAT_HOME/webapps/fineract-provider/WEB-INF/lib/

# Restart Tomcat
$TOMCAT_HOME/bin/shutdown.sh && $TOMCAT_HOME/bin/startup.sh
```

The plugin auto-registers its endpoints, runs its own Liquibase migrations against every configured tenant, and is ready to use — **no additional configuration needed**.

### Pre-Built JARs

Pre-built snapshots are published to JFrog Artifactory:

👉 [Download latest JAR](https://mifos.jfrog.io/ui/native/libs-snapshot-local/community/mifos/selfservice-plugin/1.15.0-SNAPSHOT/)

## API Reference

Most endpoints live under `/v1/self/`. Here's a summary of the available resources:

| Base Path | Resource | Methods |
|---|---|---|
| `/v1/self/registration` | Self-Registration & Enrollment | `POST` |
| `/v1/self/authentication` | Login (HTTP Basic) | `POST` |
| `/v1/self/password` | Forgot Password / Reset | `POST` |
| `/v1/self/user` | Current User Profile | `GET`, `PUT` |
| `/v1/self/userdetails` | Current User Details | `GET` |
| `/v1/self/clients` | Client Profile, Images, Charges, Transactions | `GET`, `POST`, `PUT`, `DELETE` |
| `/v1/self/savingsaccounts` | Savings Accounts & Transactions | `GET`, `POST` |
| `/v1/self/loans` | Loan Accounts, Charges, Guarantors | `GET`, `POST`, `PUT` |
| `/v1/self/shareaccounts` | Share Accounts | `GET`, `POST` |
| `/v1/self/accounttransfers` | Fund Transfers (Own + TPT) | `GET`, `POST` |
| `/v1/self/beneficiaries/tpt` | Third-Party Transfer Beneficiaries | `GET`, `POST`, `PUT`, `DELETE` |
| `/v1/self/loanproducts` | Loan Products Catalog | `GET` |
| `/v1/self/savingsproducts` | Savings Products Catalog | `GET` |
| `/v1/self/products/share` | Share Products Catalog | `GET` |
| `/v1/self/runreports` | Self-Service Reports | `GET` |
| `/v1/self/surveys` | Surveys (SPM) | `GET` |
| `/v1/self/surveys/scorecards` | Survey Scorecards | `GET`, `POST` |

### Administrative Endpoints

These are consumed by staff-facing clients such as the web app rather than by self-service users,
so they sit **outside** `/v1/self/`: that prefix is matched by the self-service security chain and
authenticates self-service users only. Outside it, ordinary platform credentials apply.

| Base Path | Resource | Methods |
|---|---|---|
| `/v1/branding` | Tenant Branding | `GET`, `PUT` |
| `/v1/admin/tenants` | Tenant Management (master users only) — see [TENANT_MANAGEMENT.md](TENANT_MANAGEMENT.md) | `GET`, `POST`, `PUT`, `DELETE` |

Full OpenAPI/Swagger documentation is available at runtime via the Fineract Swagger UI when the plugin is loaded.

A [Postman collection](postman/) is also included for hands-on API exploration.

## Tenant Management

The plugin can manage the tenants of the installation itself — create, update, activate, deactivate,
suspend and remove — with schema provisioning, an audit trail, and enforcement so a suspended tenant
actually stops being served.

Because this administers the platform itself rather than any one tenant, it runs in a separate
master context — master users with the `SUPER_MASTER` role — and is documented separately:

**→ [TENANT_MANAGEMENT.md](TENANT_MANAGEMENT.md)**

## Contributing

Interested in contributing? We'd love your help. See the **[Contributing Guide](CONTRIBUTING.md)** for everything you need — development setup, running tests, code style, and project structure.

We recommend that you **Watch** and **Star** this project on GitHub to stay up to date.

## History

This plugin was extracted from the [Apache Fineract](https://github.com/apache/fineract) codebase. The self-service functionality was originally part of Fineract core and was [removed in this commit](https://github.com/apache/fineract/commit/5364ddbe121ae6d7f95e06cce20450e0fb479f42) to keep Fineract modular. This repository continues that work as a standalone, community-maintained plugin under the [Mifos Initiative](https://mifos.org/).

## Important Notices

- Mifos® is not affiliated with, endorsed by, or otherwise associated with the Apache Software Foundation® (ASF) or any of its projects.
- Apache Software Foundation® is a vendor-neutral organization; its projects are governed independently.
- Apache Fineract®, Fineract, Apache, the Apache® feather, and the Apache Fineract® project logo are either registered trademarks or trademarks of the Apache Software Foundation®.

## License

This project is licensed under the [Mozilla Public License 2.0](LICENSE).
