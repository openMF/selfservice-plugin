# AGENTS.md

This file provides context and guidance for AI coding assistants working with the Mifos Self Service Plugin repository.

## Repository Overview

The **Mifos Self Service Plugin** is a Spring Boot plugin that extends Apache Fineract to provide self-service banking capabilities to end users. It enables customers to manage their own accounts, view transactions, and perform banking operations without requiring staff intervention.

### Architecture

- **Framework**: Spring Boot 3 with Java 21
- **Integration**: Apache Fineract 1.15.0-SNAPSHOT
- **Security**: Spring Security with Basic Auth and OAuth2 support
- **Database**: PostgreSQL/MySQL with JPA/EclipseLink
- **API**: RESTful endpoints under `/v1/self`

### Key Components

- **Authentication & Security**: User authentication, permission enforcement, multi-tenant support
- **User Management**: Self-service user registration, profile management
- **Account Management**: Savings accounts, loan accounts, share accounts
- **Product Discovery**: Browse available banking products
- **Reporting**: Financial statements and transaction reports

## Development Environment Setup

### Prerequisites
- Java 21
- Maven 3.6+
- PostgreSQL or MySQL database
- Apache Fineract instance

### Build Commands
```bash
# Build the plugin
./mvnw clean package -Dmaven.test.skip=true

# Run tests
./mvnw test

# Run integration tests
./mvnw verify
```

### Database Setup
The plugin uses Liquibase for database migrations. Scripts are located in `src/main/resources/db/changelog/`.

### Running the Plugin
This is a library/plugin that extends Apache Fineract. It runs as part of the Fineract application, not as a standalone service.

To install, verify, troubleshoot or remove the plugin with Docker Compose, load the
`selfservice-docker-compose` skill in `.agents/skills/selfservice-docker-compose/SKILL.md` and follow it.

#### Deployment Options:
```bash
# With Apache Fineract (Docker)
java -Dloader.path=$PLUGIN_HOME/libs/ -jar fineract-provider.jar

# With Apache Fineract (Tomcat)
Copy JAR to $TOMCAT_HOME/webapps/fineract-provider/WEB-INF/lib/
```

## Coding Standards

### File Structure
```text
src/main/java/org/apache/fineract/selfservice/
  - security/          # Authentication and authorization
  - useradministration/ # User management
  - client/           # Client operations
  - savings/          # Savings account operations
  - loanaccount/      # Loan account operations
  - products/         # Product browsing
  - registration/     # User registration
  - config/           # Configuration classes
```

### Code Style
- Follow Google Java Format (enforced by Spotless Maven plugin)
- Use Lombok for boilerplate reduction
- RequiredArgsConstructor for dependency injection
- Proper Javadoc for public APIs

### Security Guidelines
- All endpoints must be secured with appropriate permissions
- Use `@PreAuthorize` annotations for method-level security
- Validate all input data using DataValidators
- Never expose sensitive information in API responses

### API Design
- Use JAX-RS annotations (`@Path`, `@GET`, `@POST`, etc.)
- Return proper HTTP status codes
- Use OpenAPI tags for documentation
- Follow RESTful conventions

### Testing
- Unit tests for all service classes
- Integration tests for API endpoints
- Use TestContainers for database tests
- Mock external dependencies

## Common Patterns

### Service Layer
```java
@Service
@RequiredArgsConstructor
public class ExampleServiceImpl implements ExampleService {
    private final ExampleRepository repository;
    
    @Override
    @Transactional
    public Result performOperation(Command command) {
        // Implementation
    }
}
```

### API Resource
```java
@Path("/v1/self/example")
@Component
@Tag(name = "Self Example", description = "Example operations")
@RequiredArgsConstructor
public class SelfExampleApiResource {
    private final ExampleService service;
    
    @GET
    @Path("/{id}")
    public Response getExample(@PathParam("id") Long id) {
        // Implementation
    }
}
```

### Data Validation
```java
@Component
public class ExampleDataValidator {
    private final FromJsonHelper fromJsonHelper;
    
    public void validate(String json) {
        // Validation logic
    }
}
```

## Important Notes

- This is a plugin, not a standalone application
- Depends on Apache Fineract core modules
- Uses multi-tenant architecture
- Security is critical - handle with care
- Follow Fineract coding conventions

## Debugging Tips

- Enable debug logging: `logging.level.org.apache.fineract.selfservice=DEBUG`
- Use Spring Boot Actuator endpoints for monitoring
- Check application logs for security-related issues
- Verify database migrations in development

## References

- [Apache Fineract Documentation](https://fineract.apache.org/)
- [Spring Boot Documentation](https://spring.io/projects/spring-boot)
- [JAX-RS Specification](https://jakarta.ee/specifications/restful-ws/)
- [Spring Security Reference](https://spring.io/projects/spring-security)

## Contact

For questions about this repository:
- Create an issue on GitHub
- Check the Mifos community forums
- Review existing documentation and code comments
