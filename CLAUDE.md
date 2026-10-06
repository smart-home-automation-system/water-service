# water-service

Watches the domestic hot-water circuit. Every three minutes it reads two temperatures (hot
water, circulation) from a Shelly Uni sensor, stores the reading and recomputes one flag:
does the water need heating? `boiler-service` polls that flag to drive the pumps and the
furnace.

Part of the smart-home-automation-system organization — org-wide conventions, the
repository map and working rules come from the workspace-level context
(`organization-repository/claude/organization.md`). The user writes the code in this
repository themselves; Claude's default role here is analysis, code review and security
review.

## Role in the system

- Is called by: `boiler-service` (`GET /home/water/status/active`, directly over the cluster
  network) and, through `api-gateway-service`, by anything outside the cluster
  (`/home/water/**`).
- Calls: the Shelly Uni sensor on the LAN over HTTP, and PostgreSQL. No RabbitMQ.
- Owns its own database, `home-automation-water` — one append-only table, `temperature`.
- Uses libraries: `cholewa-commons`, `smart-home-sdk` (`SystemActiveReply`),
  `shelly-client` (the sensor models).
- It is the scaffold for **new** services (the `new-service` skill copies its pom,
  `Dockerfile` and workflows), so what is changed here spreads.

## Build & run

- Build + tests: `mvn verify`
- Local run: `home,local` Spring profiles, port `6006` (Actuator `8006`); in-cluster port
  `6200`, Actuator `8200`. Needs PostgreSQL (`database.*`, Flyway runs at startup).
- Spring Boot **4.1.1** with logbook 4.2.0 since HAS-178; own libraries on the latest
  releases, as the org rule asks of every service task.

## Specifics

- **The heating flag lives in memory and moves only after a successful database write.**
  `WaterService.handleWaterUpdate()` reads the sensor, saves the row and only then applies
  the hysteresis (on below 38 °C, off above 42 °C). A poll that fails anywhere — sensor or
  database — is logged and swallowed (`onErrorComplete`), and the flag keeps its last value.
  So a database outage freezes the flag: if it was `true`, `boiler-service` keeps heating
  until a write succeeds again. After a restart the flag starts as `false`.
- **`database.pool.max-size` is 2**, this service's share of the 22 connections of the
  managed database (heating 2 / database 4 / water 2 / presence 2 = 10). It was 4 up to and
  including 0.5.0 (HAS-169): the Deployment rolls, so during a rollout the old and the new pod
  each hold a pool and Flyway adds one JDBC connection; with this split even the three
  rolling services at once stay at 21. One poll every three minutes needs no more — the
  metrics never showed more than 2 connections in use.
- **The pooled `ConnectionFactory` comes from `cholewa-commons`** via the `database.*` group;
  there is no `DbConfig` here. Since `cholewa-commons` 1.5 the pool validates every
  connection on acquire (`SELECT 1`, 2 s) and caps its lifetime at 30 minutes. Before that
  nothing checked a connection before use; during the connectivity trouble of 2026-10-03
  single writes took 200–370 s.
- **The poll is `fixedRate`, not `fixedDelay`**: for a method returning a `Mono` Spring does
  not wait for the previous run, so polls can overlap when one takes longer than three
  minutes. `presence-service` switched to `fixedDelay` for that reason; here it is unchanged.
- **Surefire activates the `test` profile for every class** (`spring.profiles.active` in the
  pom), so a test without `@ActiveProfiles` does not switch the log output to JSON for the
  classes that follow. The `includes` match only `**/*Test.java` and `**/*IT.java` — a class
  named `...Tests` silently stops running.
- **Tests still use the legacy `com.squareup.okhttp3:mockwebserver`**, which brings JUnit 4
  onto the classpath — a JUnit 4 test compiles and never runs. `mockwebserver3` is the
  replacement (see `presence-service`); not migrated yet.
