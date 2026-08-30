# Capstan · Phase 01 — Foundation

**Goal:** monorepo scaffold, infra via Compose, Spring Boot skeleton that boots
clean with Flyway applied and the JVM pinned to UTC. Nothing domain-specific yet.

**Done when:** `docker compose up -d` then `./mvnw spring-boot:run` gives a green
`/actuator/health` with `db`, `redis`, and `rabbit` all UP, and
`flyway_schema_history` contains V1.

---

## 1. Repo layout

```
capstan/
├── docker-compose.yml
├── .env.example
├── backend/
│   ├── pom.xml
│   └── src/main/java/dev/capstan/CapstanApplication.java
│   └── src/main/resources/
│       ├── application.yml
│       └── db/migration/V1__baseline.sql
├── frontend/          # empty for now, Phase 08
├── simulator/         # empty for now, Phase 02
└── docs/
```

---

## 2. docker-compose.yml — verbatim

```yaml
services:
  postgres:
    image: postgres:16-alpine
    container_name: capstan-postgres
    environment:
      POSTGRES_DB: capstan
      POSTGRES_USER: capstan
      POSTGRES_PASSWORD: capstan_dev_only
      TZ: UTC
      PGTZ: UTC
    ports: ["5432:5432"]
    volumes:
      - capstan_pg:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U capstan -d capstan"]
      interval: 5s
      timeout: 3s
      retries: 20

  redis:
    image: redis:7-alpine
    container_name: capstan-redis
    command: ["redis-server", "--appendonly", "yes"]
    ports: ["6379:6379"]
    volumes:
      - capstan_redis:/data
    healthcheck:
      test: ["CMD", "redis-cli", "ping"]
      interval: 5s
      timeout: 3s
      retries: 20

  rabbitmq:
    image: rabbitmq:3.13-management-alpine
    container_name: capstan-rabbit
    environment:
      RABBITMQ_DEFAULT_USER: capstan
      RABBITMQ_DEFAULT_PASS: capstan_dev_only
    ports: ["5672:5672", "15672:15672"]
    volumes:
      - capstan_rabbit:/var/lib/rabbitmq
    healthcheck:
      test: ["CMD", "rabbitmq-diagnostics", "-q", "ping"]
      interval: 10s
      timeout: 5s
      retries: 20

volumes:
  capstan_pg:
  capstan_redis:
  capstan_rabbit:
```

---

## 3. pom.xml dependencies — verbatim, version-sensitive

Flyway 10 split out the Postgres support into its own artifact. Omitting
`flyway-database-postgresql` produces `Unsupported Database: PostgreSQL 16.x` at
startup and is the single most common time-waster on this stack.

```xml
<parent>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-parent</artifactId>
  <version>3.5.4</version>
  <relativePath/>
</parent>

<properties>
  <java.version>21</java.version>
  <spring-cloud.version>2025.0.0</spring-cloud.version>
</properties>

<dependencies>
  <dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-web</artifactId>
  </dependency>
  <dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-jpa</artifactId>
  </dependency>
  <dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-validation</artifactId>
  </dependency>
  <dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
  </dependency>
  <dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-amqp</artifactId>
  </dependency>
  <dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-redis</artifactId>
  </dependency>

  <!-- Flyway 10: BOTH artifacts are required on PostgreSQL -->
  <dependency>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-core</artifactId>
  </dependency>
  <dependency>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-database-postgresql</artifactId>
  </dependency>

  <dependency>
    <groupId>org.postgresql</groupId>
    <artifactId>postgresql</artifactId>
    <scope>runtime</scope>
  </dependency>
  <dependency>
    <groupId>org.projectlombok</groupId>
    <artifactId>lombok</artifactId>
    <optional>true</optional>
  </dependency>
  <dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-test</artifactId>
    <scope>test</scope>
  </dependency>
  <dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>postgresql</artifactId>
    <scope>test</scope>
  </dependency>
</dependencies>
```

---

## 4. UTC enforcement — verbatim

Every money decision in Capstan is time-conditional: quiet hours, payday windows,
cooling-off periods, mandate validity. A JVM running in IST while Postgres runs
in UTC produces off-by-5.5-hour guardrail bugs that look like policy errors and
cost hours to find. Pin it at startup, before any bean initializes.

```java
package dev.capstan;

import jakarta.annotation.PostConstruct;
import java.util.TimeZone;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class CapstanApplication {

    public static void main(String[] args) {
        // Must run before the Spring context starts.
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        System.setProperty("user.timezone", "UTC");
        SpringApplication.run(CapstanApplication.class, args);
    }

    @PostConstruct
    void assertUtc() {
        if (!"UTC".equals(TimeZone.getDefault().getID())) {
            throw new IllegalStateException(
                "JVM timezone is " + TimeZone.getDefault().getID() + ", expected UTC");
        }
    }
}
```

**Display rule:** all persisted timestamps are `TIMESTAMPTZ` in UTC. Conversion to
`Asia/Kolkata` happens only at the presentation boundary (Phase 08) and inside the
quiet-hours evaluator (Phase 04), which converts explicitly and is unit-tested
against IST boundary cases.

---

## 5. application.yml — verbatim

```yaml
spring:
  application.name: capstan
  datasource:
    url: jdbc:postgresql://localhost:5432/capstan
    username: capstan
    password: ${DB_PASSWORD:capstan_dev_only}
    hikari:
      maximum-pool-size: 12
      pool-name: capstan-pool
  jpa:
    hibernate.ddl-auto: validate      # Flyway owns the schema. Never 'update'.
    open-in-view: false
    properties:
      hibernate.jdbc.time_zone: UTC
      hibernate.jdbc.batch_size: 50
      hibernate.order_inserts: true
  flyway:
    enabled: true
    baseline-on-migrate: false
    locations: classpath:db/migration
  rabbitmq:
    host: localhost
    port: 5672
    username: capstan
    password: ${RABBIT_PASSWORD:capstan_dev_only}
    listener.simple:
      acknowledge-mode: manual
      prefetch: 8
      default-requeue-rejected: false   # rejected messages go to the DLX, not back
  data.redis:
    host: localhost
    port: 6379

management:
  endpoints.web.exposure.include: health,info,metrics,prometheus
  endpoint.health.show-details: always

capstan:
  clock-zone: UTC
  display-zone: Asia/Kolkata
  llm:
    api-key: ${ANTHROPIC_API_KEY:}
    model: claude-sonnet-4-6
    timeout-ms: 12000

logging.level:
  dev.capstan: DEBUG
```

---

## 6. V1__baseline.sql — verbatim

```sql
CREATE EXTENSION IF NOT EXISTS btree_gist;
CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- Sanity: fail the migration loudly if the DB is not UTC.
DO $$
BEGIN
  IF current_setting('TimeZone') <> 'UTC' THEN
    RAISE EXCEPTION 'Database timezone is %, expected UTC', current_setting('TimeZone');
  END IF;
END $$;
```

---

## 7. Acceptance checks

```bash
docker compose up -d
docker compose ps                      # all three healthy

cd backend && ./mvnw clean spring-boot:run

curl -s localhost:8080/actuator/health | jq
# expect: status UP, components db/redis/rabbit all UP

docker compose exec postgres psql -U capstan -d capstan \
  -c "select version, description, success from flyway_schema_history;"
# expect: 1 | baseline | t
```

## 8. Gotchas already paid for

- `flyway-database-postgresql` is mandatory on Flyway 10 (§3).
- `ddl-auto: validate`, never `update` — a silent Hibernate schema change will
  desync from Flyway and break Phase 05's constraints.
- `default-requeue-rejected: false` — the default `true` causes a rejected
  message to loop hot instead of reaching the DLQ. Phase 05 depends on this.
- `open-in-view: false` — leaving it on hides lazy-loading bugs until demo day.
