// @ts-check
import { defineConfig } from 'astro/config';
import starlight from '@astrojs/starlight';
import sitemap from '@astrojs/sitemap';

// https://astro.build/config
export default defineConfig({
  site: 'https://github.com/openMF/selfservice-plugin-docs',
  integrations: [
    starlight({
      title: 'Mifos Self Service Plugin',
      description:
        'Customer-facing self-service banking APIs for Apache Fineract — registration, authentication, accounts, transfers, step-up OTP, and activity history.',
      social: [
        {
          icon: 'github',
          label: 'GitHub',
          href: 'https://github.com/openMF/selfservice-plugin',
        },
        {
          icon: 'mastodon',
          label: 'Mastodon',
          href: 'https://fosstodon.org/@mifos',
        },
      ],
      head: [
        { tag: 'link', rel: 'stylesheet', href: '/styles/design-system.css' },
      ],
      sidebar: [
        {
          label: 'Getting Started',
          items: [
            { label: 'Introduction', slug: 'getting-started/introduction' },
            { label: 'What is the Self Service Plugin?', slug: 'getting-started/what-is' },
            { label: 'Architecture Overview', slug: 'getting-started/architecture' },
            { label: 'Prerequisites', slug: 'getting-started/prerequisites' },
          ],
        },
        {
          label: 'User Guide',
          items: [
            {
              label: 'Installation',
              items: [
                { label: 'Build from Source', slug: 'user-guide/installation/build-from-source' },
                { label: 'Deploy with Docker', slug: 'user-guide/installation/docker' },
                { label: 'Deploy with Tomcat', slug: 'user-guide/installation/tomcat' },
                { label: 'Pre-built JARs (Artifactory)', slug: 'user-guide/installation/prebuilt-jars' },
              ],
            },
            {
              label: 'Configuration',
              items: [
                { label: 'Basic Settings', slug: 'user-guide/configuration/basic-settings' },
                { label: 'Notifications (Email / SMS)', slug: 'user-guide/configuration/notifications' },
                { label: 'Step-up OTP Authentication', slug: 'user-guide/configuration/stepup-otp' },
              ],
            },
            {
              label: 'API Overview',
              items: [
                { label: 'Authentication & Registration', slug: 'user-guide/api-overview/authentication-registration' },
                { label: 'Client Profile', slug: 'user-guide/api-overview/client-profile' },
                { label: 'Savings Accounts', slug: 'user-guide/api-overview/savings-accounts' },
                { label: 'Loan Accounts', slug: 'user-guide/api-overview/loan-accounts' },
                { label: 'Share Accounts', slug: 'user-guide/api-overview/share-accounts' },
                { label: 'Transfers & Beneficiaries', slug: 'user-guide/api-overview/transfers-beneficiaries' },
                { label: 'Products Catalog', slug: 'user-guide/api-overview/products-catalog' },
                { label: 'Reports & Surveys', slug: 'user-guide/api-overview/reports-surveys' },
                { label: 'Activity History', slug: 'user-guide/api-overview/activity-history' },
              ],
            },
            {
              label: 'API Reference',
              items: [
                { label: 'Endpoints Summary', slug: 'user-guide/api-reference/endpoints-summary' },
                { label: 'Postman & Bruno Collections', slug: 'user-guide/api-reference/postman-bruno' },
                { label: 'Swagger / OpenAPI', slug: 'user-guide/api-reference/swagger-openapi' },
              ],
            },
            { label: 'Security & Best Practices', slug: 'user-guide/security-best-practices' },
          ],
        },
        {
          label: 'Developer Guide',
          items: [
            { label: 'Development Setup', slug: 'developer-guide/development-setup' },
            { label: 'Project Structure', slug: 'developer-guide/project-structure' },
            { label: 'Coding Standards & Patterns', slug: 'developer-guide/coding-standards' },
            { label: 'Adding New Endpoints', slug: 'developer-guide/adding-new-endpoints' },
            { label: 'Testing (Unit + Integration + Testcontainers)', slug: 'developer-guide/testing' },
            { label: 'Database Migrations (Liquibase)', slug: 'developer-guide/database-migrations' },
            { label: 'Contributing', slug: 'developer-guide/contributing' },
            { label: 'Troubleshooting', slug: 'developer-guide/troubleshooting' },
          ],
        },
        {
          label: 'Reference',
          items: [
            { label: 'Configuration Properties', slug: 'reference/configuration-properties' },
            { label: 'Permissions', slug: 'reference/permissions' },
            { label: 'Changelog / Version Compatibility', slug: 'reference/changelog-version-compatibility' },
            { label: 'License', slug: 'reference/license' },
          ],
        },
      ],
      favicon: '/favicon.svg',
      editLink: {
        baseUrl: 'https://github.com/openMF/selfservice-plugin-docs/edit/main/src/content/docs/',
      },
      lastUpdated: true,
      pagination: true,
      components: {
        // Custom components can be registered here if needed
      },
    }),
    sitemap(),
  ],
});
