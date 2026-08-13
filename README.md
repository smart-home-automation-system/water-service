# water-service

[![CI](https://github.com/smart-home-automation-system/water-service/actions/workflows/CI.yml/badge.svg)](https://github.com/smart-home-automation-system/water-service/actions/workflows/CI.yml)
[![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=smart-home-automation-system_water-service&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=smart-home-automation-system_water-service)
[![Vulnerabilities](https://sonarcloud.io/api/project_badges/measure?project=smart-home-automation-system_water-service&metric=vulnerabilities)](https://sonarcloud.io/summary/new_code?id=smart-home-automation-system_water-service)

![GitHub Release Date - Published_At](https://img.shields.io/github/release-date/smart-home-automation-system/water-service?style=plastic)
![GitHub Release](https://img.shields.io/github/v/release/smart-home-automation-system/water-service?style=plastic)

---

![GitHub top language](https://img.shields.io/github/languages/top/smart-home-automation-system/water-service?style=plastic)
![Java](https://img.shields.io/badge/java-21-yellow?style=plastic)
![SpringBoot](https://img.shields.io/badge/SpringBoot-4.1.0-blue?style=plastic)
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

- Build: `mvn verify` (JDK 21).
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
  starts without them and fails on the first query instead. Only `database.pool.max-size: 4`
  is pinned here, as this service's share of the 22 backend connections the managed database
  allows; the rest of the pool settings come from the library defaults.
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

Base path `/home/water` (`spring.webflux.base-path`). Both endpoints are read-only.

| Method | Path | Description |
|---|---|---|
| `GET` | `/home/water/status/active` | Whether the hot water currently needs heating — `SystemActiveReply` (`{"active": true\|false}`) from `smart-home-sdk`. Recomputed on every successful sensor poll with the 38/42 °C hysteresis described above. |
| `GET` | `/home/water/status/temperature` | The most recent stored reading — `TemperatureReply` with `water.temperature` and `circulation.temperature` in °C. `circulation.pumpActive` is part of the model but is not populated yet, so it is always `false`. Before the first reading is stored the response is empty (`200` with no body). |

`boiler-service` is the consumer: it polls `http://water-service:6200/home/water/status/active`
directly over the cluster network (`internal.service.water-service` in its configuration) and
falls back to `false` when the call fails.

The service is **not** reachable from outside the cluster. `api-gateway-service` does define
water routes, but they target `/home/water/hot` and `/home/water/management` — paths this
service does not expose.

Failures of the Shelly call are wrapped in `WaterException`, which `cholewa-commons`'
`GlobalErrorExceptionHandler` renders as `400` in the shared `Errors` JSON contract. In
practice only the scheduled poll calls the sensor, and it logs and swallows the error, so the
endpoints above do not surface it.

# Database

- **Access:** reactive, via `r2dbc-postgresql` and a Spring Data R2DBC repository.
- **Migrations:** Flyway (JDBC driver) from `src/main/resources/db/migration`.
- **Schema:** a single table `temperature` (`V1`) — `id` (identity), `updated_at`, `water` and
  `circulation` as `NUMERIC(5,2)`.
- **Writes:** one row per successful poll (every `PT3m`, the first `PT10s` after startup). The
  table is append-only — there is no retention or cleanup job.
- **Reads:** `status/temperature` returns the newest row by `updated_at`.
