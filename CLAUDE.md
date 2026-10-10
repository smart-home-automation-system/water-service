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
  (`/home/water/**`) — today the web dashboard, which reads `status/temperature` and, from
  HAS-201 on, `temperature/history`.
- Calls: the Shelly Uni sensor on the LAN over HTTP, and PostgreSQL. No RabbitMQ.
- Owns its own database, `home-automation-water` — one append-only table, `temperature`,
  indexed on `updated_at` (`V2`).
- Uses libraries: `cholewa-commons`, `smart-home-sdk` (`SystemActiveReply`),
  `shelly-client` (the sensor models).
- It is the scaffold for **new** services (the `new-service` skill copies its pom,
  `Dockerfile` and workflows), so what is changed here spreads.

## Build & run

- Build + tests: `mvn verify` — needs a running Docker since HAS-200
  (`WaterTemperatureRepositoryTest`, Testcontainers); without one that test fails, it is not
  skipped.
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
  rolling services at once stay at 21. One poll every three minutes needs no more, but
  there is no headroom: scrapes saw at most 2 connections in use, which is now the whole pool.
  The poll is `fixedRate` and its write has no timeout, so two writes that hang (2026-10-03:
  single writes took minutes) hold both connections; every later poll then fails after
  `max-acquire-time` and the heating flag freezes, as does `GET status/temperature`. With 4
  that took four hung writes. A timeout on the write is the fix if it ever shows.
  `WaterServiceApplicationTest` pins the size.
- **The temperature history (HAS-200) is the contract of the dashboard's charts** — the shape
  and the rules of the room history of `heating-service` (HAS-199), with `HistoryRange` copied
  from there; only the widths differ. What is easy to break:
  - **The bucket widths follow the poll.** 5 min / 30 min / 2 h, each dividing a day (that is
    what puts a bucket on the clock of the house) and none below the 3 minutes of
    `WaterSensorCron` — a narrower one would be empty by design and the dashboard would draw a
    gap. Change the poll interval and `HistoryRangeTest` has to be read again.
  - **`updated_at` is wall-clock time of the house without a zone**, written by the JVM of the
    pod. The query buckets it as it is: the hour skipped in spring has no points, the hour
    repeated in autumn is averaged twice into the same buckets.
  - **The SQL is PostgreSQL's own**, and `WaterTemperatureRepositoryTest` is the only test that
    runs it (Testcontainers, the migrations applied by hand, the JVM on the zone of the house).
    It also pins the index of `V2`.
  - **It shares the pool of 2 with the poll and has no timeout either.** A month is one
    statement through the index (15 000 rows, 30 ms on a local PostgreSQL); a read that hangs
    holds a connection like a hung write does, and two of them freeze the heating flag
    `boiler-service` reads. One range at a time from a client; the timeout, if it ever shows,
    belongs on both.
  - **The refusals of a range carry no `code`** — a `ResponseStatusException`, answered as a
    400 with the reason as the message.
- **`measuredAt` of `status/temperature` is the `updated_at` of the row, cut to the second**
  (the column keeps microseconds). It must stay the time of the row: the service repeats its
  last row while the sensor is silent, and the dashboard judges the age of the reading by it.
- **A migration is rehearsed on a throwaway PostgreSQL, never by a local run** — `home,local`
  with the real `database-*` values is the production database, and a local instance also
  polls the sensor and stores rows. The recipe of `V2`: `postgres:16` in Docker with
  `-c ssl=on` and the snakeoil certificate of the image (`cholewa-commons` connects with
  `sslMode` `REQUIRE`; in Git Bash with `MSYS_NO_PATHCONV=1`, or the certificate paths are
  rewritten), the image of the current release against it, then the new jar with the five
  `--database-*` arguments while the old one keeps running.
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
