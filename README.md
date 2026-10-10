# water-service

[![CI](https://github.com/smart-home-automation-system/water-service/actions/workflows/CI.yml/badge.svg)](https://github.com/smart-home-automation-system/water-service/actions/workflows/CI.yml)
[![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=smart-home-automation-system_water-service&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=smart-home-automation-system_water-service)
[![Vulnerabilities](https://sonarcloud.io/api/project_badges/measure?project=smart-home-automation-system_water-service&metric=vulnerabilities)](https://sonarcloud.io/summary/new_code?id=smart-home-automation-system_water-service)

![GitHub Release Date - Published_At](https://img.shields.io/github/release-date/smart-home-automation-system/water-service?style=plastic)
![GitHub Release](https://img.shields.io/github/v/release/smart-home-automation-system/water-service?style=plastic)

---

![GitHub top language](https://img.shields.io/github/languages/top/smart-home-automation-system/water-service?style=plastic)
![Java](https://img.shields.io/badge/java-21-yellow?style=plastic)
![SpringBoot](https://img.shields.io/badge/SpringBoot-4.1.1-blue?style=plastic)
[![Coverage](https://sonarcloud.io/api/project_badges/measure?project=smart-home-automation-system_water-service&metric=coverage)](https://sonarcloud.io/summary/new_code?id=smart-home-automation-system_water-service)
[![Lines of Code](https://sonarcloud.io/api/project_badges/measure?project=smart-home-automation-system_water-service&metric=ncloc)](https://sonarcloud.io/summary/new_code?id=smart-home-automation-system_water-service)

![GitHub issues](https://img.shields.io/github/issues/smart-home-automation-system/water-service?style=plastic)
![GitHub contributors](https://img.shields.io/github/contributors/smart-home-automation-system/water-service?style=plastic)
![GitHub pull requests](https://img.shields.io/github/issues-pr-raw/smart-home-automation-system/water-service?style=plastic)

![GitHub last commit](https://img.shields.io/github/last-commit/smart-home-automation-system/water-service?style=plastic)
![GitHub commit activity](https://img.shields.io/github/commit-activity/m/smart-home-automation-system/water-service?style=plastic)

---

# Description

Watches the domestic hot-water circuit. Every three minutes the service polls a **Shelly Uni**
sensor for two temperatures — hot water and circulation — stores the reading in PostgreSQL and
recomputes a single flag: does the water need heating? The flag is published on
`status/active`, and `boiler-service` uses it to drive the pumps and the furnace.

The decision uses hysteresis so the furnace does not cycle around a single threshold: heating
is switched **on below 38 °C** and **off above 42 °C**, with no change in between. The flag
lives in memory only — after a restart it starts as `false` until the first successful poll.

Everything is non-blocking (Spring WebFlux / Reactor): the sensor is read with `WebClient`
using the models from the shared `shelly-client`, PostgreSQL is accessed over **R2DBC**
(Spring Data R2DBC) and the schema is managed by **Flyway**. `SystemActiveReply` comes from
the shared `smart-home-sdk`, and errors are rendered through `cholewa-commons`.

# Run locally

- Build: `mvn verify` (JDK 21). It needs a running **Docker**: the history query is
  PostgreSQL's own and its test runs against a PostgreSQL container (Testcontainers) — without
  Docker that test fails, it is not skipped.
- Ports: local profile `6006` (management `8006`); in the deployed `home` profile the service
  listens on `6200` and Actuator on `8200` like every service in the cluster. The ingress
  routes only 6200, so Actuator is reachable inside the cluster only — that is where the
  Kubernetes probes hit `/actuator/health/{readiness,liveness}` and Prometheus scrapes
  `/actuator/prometheus`.
- Requires a reachable PostgreSQL instance. The connection properties (`database-host`,
  `database-port`, `database-name`, `database-user`, `database-password`) are bound to the
  `database.*` prefix that `cholewa-commons` consumes — the library builds the pooled
  `ConnectionFactory`, this service declares no `DbConfig` of its own. They have placeholder
  defaults (`localhost:5432`) and the pool does not open connections eagerly, so the context
  starts without them and fails on the first query instead. Only `database.pool.max-size: 2`
  is pinned here, as this service's share of the 22 backend connections the managed database
  allows, sized so that a rollout — two pods, two pools and the Flyway connection of the new
  one — still fits; the rest of the pool settings come from the library defaults. Since
  `cholewa-commons` 1.5 those include validating every connection on acquire (`SELECT 1`,
  2 s) and a 30-minute connection lifetime, so a connection that stopped answering is
  replaced instead of being kept until the pod restarts.
- Flyway derives its JDBC URL from those same properties
  (`jdbc:postgresql://<database-host>:<database-port>/<database-name>`), so a local run needs
  no extra flag. Override with `--flyway-url=...` only when migrations have to target a
  different URL than the connection properties describe. Flyway is enabled in `home`/`local`
  and disabled in the `test` profile.
- The sensor address comes from `shelly.sensor.uni.hot-water.host` / `.port`. When the sensor
  is unreachable the scheduled poll logs the error and skips the cycle — the service keeps
  running, it simply stores no new reading, so `status/temperature` keeps returning the last
  one and `status/active` keeps its current value.

# API

Base path `/home/water` (`spring.webflux.base-path`). All three endpoints are read-only.

| Method | Path | Description |
|---|---|---|
| `GET` | `/home/water/status/active` | Whether the hot water currently needs heating — `SystemActiveReply` (`{"active": true\|false}`) from `smart-home-sdk`. Recomputed on every successful sensor poll with the 38/42 °C hysteresis described above. |
| `GET` | `/home/water/status/temperature` | The most recent stored reading — `TemperatureReply` with `measuredAt`, `water.temperature` and `circulation.temperature` in °C. `measuredAt` is when the sensor was read — a local date-time of the house, to the second, without an offset. The service repeats its last reading for as long as the sensor is silent, so `measuredAt` is what tells a current reading from an old one. `circulation.pumpActive` is part of the model but is not populated yet, so it is always `false`. Before the first reading is stored the response is empty (`200` with no body). |
| `GET` | `/home/water/temperature/history?from=&to=` | The stored temperatures over a range, averaged into buckets — see below. |

## Temperature history

`GET /home/water/temperature/history?from=2026-10-08T00:00:00&to=2026-10-09T00:00:00`

```json
{
  "from": "2026-10-08T00:00:00",
  "to": "2026-10-09T00:00:00",
  "bucketSeconds": 300,
  "points": [
    { "at": "2026-10-08T00:00:00", "water": 46.81, "circulation": 26.44 }
  ]
}
```

- `from` and `to` are both required: local date-times without an offset, read by the clock
  of the house. The range includes its start and not its end, spans at most **31 days** and
  lies within the years 2000 to 9999. A value with `Z` or an offset, `from` not before `to`
  or a longer range is a `400` — in the shared `Errors` JSON, without a `code`. A `to` in the
  future is fine: there are simply no points there.
- **The service chooses the bucket** from the length of the range and names it in
  `bucketSeconds`: 5 min up to 2 days (at most 576 points), 30 min up to 8 days (384), 2 h up
  to 31 days (372). A reading is stored every 3 minutes, so every bucket of a working sensor
  has one.
- A point is a bucket: `at` is its start, `water` and `circulation` the averages of the
  readings in it, in °C, rounded to 2 decimals. **A bucket without a reading has no point** —
  no nulls — so two points further apart than `bucketSeconds` are a gap in the readings. A
  range without readings is a `200` with an empty `points`.
- **Buckets are aligned to the clock of the house, not to `from`**: ask from a midnight or a
  full hour, or the first point starts before the range (and there is one point more).
- On the night the summer time begins the history has a gap of an hour no sensor caused; the
  hour repeated when it ends is averaged into the same buckets twice.
- Whether the circulation pump ran is not stored and is not part of the history.

`boiler-service` is the consumer of `status/active`: it polls `http://water-service:6200/home/water/status/active`
directly over the cluster network (`internal.service.water-service` in its configuration) and
falls back to `false` when the call fails.

The web dashboard reads `status/temperature` and the history. From outside the cluster all three endpoints are reachable through `api-gateway-service`, which
routes `/home/water/**` to this service over the cluster network (since its 0.2.0).

Failures of the Shelly call are wrapped in `WaterException`, which `cholewa-commons`'
`GlobalErrorExceptionHandler` renders as `400` in the shared `Errors` JSON contract. In
practice only the scheduled poll calls the sensor, and it logs and swallows the error, so the
endpoints above do not surface it.

# Database

- **Access:** reactive, via `r2dbc-postgresql` and a Spring Data R2DBC repository.
- **Migrations:** Flyway (JDBC driver) from `src/main/resources/db/migration`.
- **Schema:** a single table `temperature` (`V1`) — `id` (identity), `updated_at`, `water` and
  `circulation` as `NUMERIC(5,2)` — with an index on `updated_at` (`V2`).
- **Writes:** one row per successful poll (every `PT3m`, the first `PT10s` after startup). The
  table is append-only — there is no retention or cleanup job.
- **Reads:** `status/temperature` returns the newest row by `updated_at`; the history averages
  the rows of a range in the database (a month is some 15 000 rows, answered as 372). Both go
  through the index on `updated_at`.
