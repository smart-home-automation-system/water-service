# water-service

[![CI](https://github.com/smart-home-automation-system/water-service/actions/workflows/CI.yml/badge.svg)](https://github.com/smart-home-automation-system/water-service/actions/workflows/CI.yml)
[![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=smart-home-automation-system_water-service&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=smart-home-automation-system_water-service)
[![Vulnerabilities](https://sonarcloud.io/api/project_badges/measure?project=smart-home-automation-system_water-service&metric=vulnerabilities)](https://sonarcloud.io/summary/new_code?id=smart-home-automation-system_water-service)

![GitHub Release Date - Published_At](https://img.shields.io/github/release-date/smart-home-automation-system/water-service?style=plastic)
![GitHub Release](https://img.shields.io/github/v/release/smart-home-automation-system/water-service?style=plastic)

---

![GitHub top language](https://img.shields.io/github/languages/top/smart-home-automation-system/water-service?style=plastic)
![Java](https://img.shields.io/badge/java-21-yellow?style=plastic)
![SpringBoot](https://img.shields.io/badge/SpringBoot-4.1.2-blue?style=plastic)
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

It is also the **water meter of the house** (HAS-316): the Neo Smart Water Valve (a Shelly X XT1) in
the garage reports its state, the flow rate, the water pressure, the water temperature and its
consumption counter as five virtual components. The service takes them from the device events of
the broker (`device.prod.water` on `/device`) and reads them over the RPC of the valve every
minute, keeps them in memory, stores the counter whenever it changes and serves the valve on
`GET /home/water/valve`. The valve itself comes from the device registry of `database-service`
(the kind `water valve`). From the stored counter it answers the consumption by day, week, month
and year on `GET /home/water/consumption` (HAS-317).

It tells the household on Discord (the `alert` and `info` categories of `notification-service`)
when the valve shows trouble (HAS-319), judged every minute from what is known of the valve:

| Alarm | When | Return |
|---|---|---|
| No mains water | the pressure below `water.alarm.pressure-low-below` (100 kPa = 1 bar) in every pass for `pressure-low-after` (5 min) | at or above it for the same time |
| Not available | the valve not heard - no message and no answer of its RPC - for `offline-after` (5 min) | the first report after it |
| Frost risk | the water temperature below `frost-below` (3 °C) for `frost-after` (5 min) | at or above it for the same time |

Each is one alert at its bound, a reminder every `reminder-every` (1 h) while it lasts and one
info on the return naming how long it lasted; a value not known (not fresh) never raises an alarm.
The open alarms are stored (`water_alert`, `V5`), so a restart neither repeats an alert nor forgets
a return; an alert that never got through (the broker down with the valve) is said by the return.

The flow of the water raises three more (HAS-320), judged from every report of the flow rate as
it arrives (MQTT at every change, the RPC read of every minute) - so a pause shorter than a minute
is seen - and in the same minute pass:

| Alarm | When | Return |
|---|---|---|
| High water flow | above `water.flow.high-above` (25 l/min) for `high-after` (3 min) | the flow at or below it for longer than `continuous-pause` |
| Continuous water flow | above zero for `continuous-after` (30 min), a pause no longer than `continuous-pause` (1 min) being the same flow | a pause longer than that |
| Water while nobody is at home | above `empty-house-above` (0.5 l/min, the noise of the sensor) while the house is empty for `empty-house-after` (2 min) | the flow stops |

Each is judged by its own run, so a high flow that is also continuous raises the high flow at its
bound and the continuous one at its own. Whether the house is empty is read every minute from
`presence-service` (`GET /home/presence/residents/presence` over k8s DNS, the rule of
`light-service`): unknown - presence-service not read, a member not seen for a while - is never
empty. While the rate is not fresh (published on a change only, the RPC read failing) the litres of
the last whole minute of the counter (`by_minute`) are the flow; while neither is known nothing
opens or closes - the offline alarm speaks for the valve. The reminders and the stored episodes are
those of the alarms above (`water_alert`, kinds added by `V6`).

**The regeneration of the softener is no leak** (HAS-399): it takes water from the mains, about
0.5 l/min for two hours at 02:00 every few days, which had raised the continuous flow on the night of
2026-11-09. A continuous flow or one of the empty house that started with a regeneration (seen from
`regeneration_status` of the iQua cloud - `none` and `scheduled` are none, from the time the cloud changed
`regen_status_enum`; without the words `regen_status_enum` above 1 on the day of a regeneration - 1 is scheduled)
is judged from where the regeneration ended - raised only when the water runs on after it, the
continuous flow for `continuous-after` from the end, the empty house for longer than the pause; a
flow that starts near the time of the regeneration (`regen_time_secs`, 5 min before to 20 min after)
waits up to 20 min for a reading of the cloud to say whether the softener regenerates. A high flow is
never excused. The words of a regeneration going on are not seen yet - every start and end is logged
with them.

It also closes and opens the valve (HAS-321, `POST /home/water/valve`) - the one command that cuts
the water of the whole house. **Closing on a leak is the owner's choice and off by default**
(`water.flow.close-on-alarm: false`): when on, the first alert of a continuous flow or of water
running in an empty house closes the valve first (`source=rule`) and says so, and the end of the
episode says the valve stays closed; a high flow never closes it (a bath). The valve keeps its
`on_power_restore` at `none` (the owner's setting): after a power cut its state is what its first
report says.

It also drives the **circulation pump of the hot water** (HAS-404, epic HAS-403) - relay 1
"CIRCULATION" of the same Shelly Uni: the state of the pump and a manual start, a run stopped once
the return reaches its target or after a time limit; and since HAS-405 the automation - a run on a
new motion or a light switched on in a bathroom (read from `light-service`) and in the comfort
windows, while somebody is at home, the tank is warm and the return cold. It takes over from the
scene "CONTROL WATER CIRCULATION" of the Shelly cloud, disabled at its deploy.

It reads the **water softener** too (HAS-397, epic HAS-396) - a Viessmann AquaHome 20 Smart, an EcoWater / iQua
device with no local API: every five minutes from the cloud of the iQua application (`api.myiquaapp.com`), served on
`GET /home/water/softener` - the water it treated, the salt, the regenerations, the hardness and its own alerts.

Everything is non-blocking (Spring WebFlux / Reactor): the sensor is read with `WebClient`
using the models from the shared `shelly-client` (the valve with its RPC client and its MQTT
decoder), PostgreSQL is accessed over **R2DBC**
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
  is pinned here, as this service's share of the 47 backend connections the managed database
  allows (20 allotted to the project), sized so that a rollout — two pods, two pools and the Flyway connection of the new
  one — still fits; the rest of the pool settings come from the library defaults. Since
  `cholewa-commons` 1.5 those include validating every connection on acquire (`SELECT 1`,
  2 s) and a 30-minute connection lifetime, so a connection that stopped answering is
  replaced instead of being kept until the pod restarts.
- Flyway derives its JDBC URL from those same properties
  (`jdbc:postgresql://<database-host>:<database-port>/<database-name>`), so a local run needs
  no extra flag. Override with `--flyway-url=...` only when migrations have to target a
  different URL than the connection properties describe. Flyway is enabled in `home`/`local`
  and disabled in the `test` profile.
- RabbitMQ: the device events on `/device` as the user `device`; `rabbitmq-password` has no
  default and the service does not start without it (`cholewa.required-properties`). The `local`
  profile connects to `localhost` and reads `device.dev.water`; without a broker the listener
  retries in the log and the read of every minute still keeps the valve current.
- Notifications (HAS-319): a second connection to the same broker, virtual host `/notification`,
  user `notification`; `notification-rabbitmq-password` has no default either. The `local`
  profile publishes with `env: dev`, so a local run never reaches the channel of the house.
- The device registry: `internal.service.database` (`http://localhost:6005` in `local` - a
  port-forward to `database-service`); whether anybody is at home: `internal.service.presence`
  (`http://localhost:6009` in `local`, a port-forward to `presence-service` - not reached, the house
  is not known and no alarm of the empty house is raised); the motion and the lights of the bathrooms:
  `internal.service.light` (`http://localhost:6012` in `local`, a port-forward to `light-service` -
  not reached, the automation of the circulation triggers nothing but its comfort windows, and those
  start nothing while the house is not known).
- The water softener (HAS-397): the iQua account in `iqua-email` / `iqua-password` - no default, the service does
  not start without them (`cholewa.required-properties`); with a real account a local run logs in to the cloud and reads
  the softener every five minutes like the pod (one more login a run - mind the about 1000 calls a day of the account).
- The sensor address comes from `shelly.sensor.uni.hot-water.host` / `.port`. When the sensor
  is unreachable the scheduled poll logs the error and skips the cycle — the service keeps
  running, it simply stores no new reading, so `status/temperature` keeps returning the last
  one and `status/active` keeps its current value.

# API

Base path `/home/water` (`spring.webflux.base-path`). Everything is read-only but the bills and the
imported consumption (HAS-318) and the command of the valve (HAS-321); like every service behind
the gateway, the writes are not authenticated - access is LAN / VPN only.

| Method | Path | Description |
|---|---|---|
| `GET` | `/home/water/status/active` | Whether the hot water currently needs heating — `SystemActiveReply` (`{"active": true\|false}`) from `smart-home-sdk`. Recomputed on every successful sensor poll with the 38/42 °C hysteresis described above. |
| `GET` | `/home/water/status/temperature` | The most recent stored reading — `TemperatureReply` with `measuredAt`, `water.temperature` and `circulation.temperature` in °C. `measuredAt` is when the sensor was read — a local date-time of the house, to the second, without an offset. The service repeats its last reading for as long as the sensor is silent, so `measuredAt` is what tells a current reading from an old one. `circulation.pumpActive` is part of the model but is not populated yet, so it is always `false`. Before the first reading is stored the response is empty (`200` with no body). |
| `GET` | `/home/water/temperature/history?from=&to=` | The stored temperatures over a range, averaged into buckets — see below. |
| `GET` | `/home/water/valve` | The water valve now — see below. |
| `POST` | `/home/water/valve?state=open|close&source=` | Close or open the valve — see below. |
| `GET` | `/home/water/consumption?range=&at=` | The water used by day, week, month or year, with the period before — see below. |
| `GET`, `PUT` | `/home/water/consumption/imported` | The daily consumption imported from the Shelly cloud — see below. |
| `GET`, `POST`, `PUT`, `DELETE` | `/home/water/bills`, `/home/water/bills/{id}` | The bills of the waterworks with what the house measured — see below. |
| `GET` | `/home/water/circulation` | The circulation pump now — see below. |
| `POST` | `/home/water/circulation?source=` | Start a run of the circulation pump — see below. |
| `GET` | `/home/water/circulation/runs?from=&to=` | The runs the service started — see below. |
| `GET` | `/home/water/softener` | The water softener now, from the iQua cloud — see below. |
| `GET` | `/home/water/softener/history?metric=&from=&to=` | The stored history of one metric of the softener — see below. |
| `GET` | `/home/water/softener/days?from=&to=` | The litres through the softener per day — see below. |
| `GET` | `/home/water/softener/regenerations?from=&to=` | The regenerations of the softener — see below. |
| `GET` | `/home/water/softener/alerts` | The alerts the iQua cloud keeps of the softener — see below. |
| `POST` | `/home/water/softener/regeneration?when=now|tonight` | Start a regeneration now (default) or tonight at 02:00 — see below. |
| `DELETE` | `/home/water/softener/regeneration` | Cancel the regeneration scheduled for tonight — see below. |
| `PUT` | `/home/water/softener/live` | Open the live stream of the softener (or extend the one open) — see below. |
| `DELETE` | `/home/water/softener/live` | Close the live stream. |

## The water valve

`GET /home/water/valve` answers what is in memory, never a call to the valve:

```json
{
  "state": "open",
  "flowRate": 0.0,
  "pressure": 4.7,
  "temperature": 11.7,
  "counter": {"total": 61.722, "measuredAt": "2026-11-04T21:15:57"},
  "measuredAt": "2026-11-04T21:15:57",
  "lastCommandConfirmed": true
}
```

- `state` `open` / `closed` (`boolean:200`), `flowRate` l/min (`number:200`, reported in m³/min),
  `pressure` bar with two decimals (`number:201`, reported in kPa), `temperature` °C
  (`number:202`), `counter.total` m³ with three decimals (`object:200`).
- **A value not reported within `water.valve.stale-after` (5 min) is left out** - never a default,
  missing is never off; with nothing fresh the answer is `{}`. A value is reported by a message of
  the broker or by the read of every minute, and `measuredAt` is the time of the report (house
  time, to the second, without an offset), not the time the value last changed on the device -
  the newest of what is shown at the top, the counter's under `counter`.
- `503` `REGISTRY_UNAVAILABLE` until the device registry was read once since the start,
  `404` `NOT_FOUND_VALVE` while it has no water valve.
- `lastCommandConfirmed` - whether the last command was answered by the report of the valve:
  `false` from its sending until the report arrives and when none did within 60 s; left out until a
  command was sent since the start.

`POST /home/water/valve?state=close&source=dashboard` sends `Boolean.Set` of `boolean:200` once -
never repeated - and answers `202` with what was taken:

```json
{"state": "close", "source": "dashboard", "commandedAt": "2026-11-05T18:05:01"}
```

- Taken is not done: the command is stored (`water_valve_command`, `V7`) and waits for the report
  of `boolean:200` in the asked state - by MQTT, or by a read of the valve asked 15 s after the
  command - within 60 s; `lastCommandConfirmed` tells. The third unconfirmed command in a row is an
  alert on Discord (once a run, none while the valve is not heard - the offline alarm speaks).
- `state` is `open` or `close`, `source` a lowercase word of at most 20 letters or dashes
  (`dashboard`, `rule`); anything else is `400` `INVALID_COMMAND`.
- `502` `DEVICE_FAILED` (message "Device failed" - shared with the circulation pump since HAS-404;
  it read "Water valve failed" before) when the valve refused the command (its status alone in `details`) or did
  not answer within 15 s - a command not answered may still have been carried out, so it is stored
  and waits for its report too; `503` `REGISTRY_UNAVAILABLE` / `404` `NOT_FOUND_VALVE` as above.

## Consumption

`GET /home/water/consumption?range=day&at=2026-11-05` (HAS-317) - the water used, from the
stored readings of the counter:

```json
{
  "range": "day",
  "from": "2026-11-05T00:00:00",
  "to": "2026-11-06T00:00:00",
  "unit": "m3",
  "total": 0.124,
  "previousTotal": 0.2,
  "buckets": [{"at": "2026-11-05T07:00:00", "volume": 0.123}]
}
```

- `range` `day` (buckets of an hour), `week` (Monday to Sunday) or `month` (buckets of a day),
  `year` (buckets of a month); `at` any day of the range as `YYYY-MM-DD`, today of the house
  when left out. `from` / `to` the range in house time, its start included and its end not.
- `total` the water of the range, `previousTotal` of the same range one period earlier (the
  day, week, month or year before) - each left out when the counter was not watched in its range.
- `buckets` one per bucket in which the counter was watched, oldest first: a bucket without
  water has `0`, a bucket the service did not watch (it was down) has **no point**. Volumes in
  m³ with three decimals; the total is the sum before rounding.
- The water of a bucket is the sum of the rises of the counter first seen in it. **A drop of the
  counter is a reset** (`Service.ResetCounters`, a replaced device) and counts as nothing - never
  a negative volume. The water of a gap in the readings counts where the counter is seen again.
- Every date-time is wall-clock time of the house: the hour skipped in spring is a bucket of `0`
  (the counter was watched across it), the hour repeated in autumn is one bucket with the water of
  both (the rule of the temperature history).
- `400` `INVALID_RANGE` for a range that is not one of the four (a missing one included) or a date
  that is not `YYYY-MM-DD` within the years 2000 - 2999.
- **Imported days** (HAS-318, below) count as watched days: a bucket of a day of a week or a
  month, their share of a month of a year; a day in hours has no imported points.

## Imported consumption

The valve keeps no history itself; the Shelly cloud has the daily consumption since about
February 2026. It is imported once by hand (no integration with the cloud), so the bills and the
statistics reach back before the first reading of the house.

- `GET /home/water/consumption/imported` - `[{"day": "2026-10-01", "volume": 0.512}]`, oldest first.
- `PUT /home/water/consumption/imported` with the same list **replaces the whole import** (`204`)
  - sent again, it fixes what was sent before. A day must not be after the first day of the
  readings of the house (today while there are none); that first day is watched only from its
  first reading, so its imported value wins over the measured part (not in hours); volumes in m³, at
  most three decimals, not negative; at most 3660 days, each once. `400` `INVALID_IMPORT` with the
  reason in `details`.

## Bills

The bills of the waterworks, entered by the owner (HAS-318), each answered with what the house
measured over its period:

```json
{
  "id": 7,
  "periodFrom": "2026-09-29",
  "periodTo": "2026-10-20",
  "billedVolume": 11.000,
  "meterReading": 1234.500,
  "amount": 98.76,
  "note": "Waterworks",
  "measuredVolume": 11.020,
  "difference": 0.020,
  "differencePercent": 0.2,
  "createdAt": "2026-11-05T10:00:00",
  "updatedAt": "2026-11-05T10:00:00"
}
```

| Method | Path | |
|---|---|---|
| `GET` | `/home/water/bills` | Every bill, newest period first. |
| `GET` | `/home/water/bills/{id}` | One bill. |
| `POST` | `/home/water/bills` | Enter a bill - `201` with it. |
| `PUT` | `/home/water/bills/{id}` | Replace a bill whole (no partial update) - its id and `createdAt` stay. |
| `DELETE` | `/home/water/bills/{id}` | `204`. |

- The body: `periodFrom`, `periodTo` (whole days, both included) and `billedVolume` (m³, three
  decimals) required; `meterReading` (m³), `amount` (PLN, two decimals) and `note` (500
  characters) optional.
- `measuredVolume` is the water from `periodFrom` 00:00 to `periodTo` 24:00 - the daily
  consumption above, imported days included; `difference` is measured less billed, and
  `differencePercent` its share of the billed volume. **All three are left out** (missing is never
  0) when a day of the period has neither readings nor an imported value; the percent also when
  nothing was billed.
- `400` `INVALID_BILL` with the reason in `details` - a missing field, `periodTo` before
  `periodFrom` or in the future, a negative volume or amount, too many decimals, a period that
  shares a day with another bill; `404` `NOT_FOUND_BILL`.

## The circulation pump

Relay 1 "CIRCULATION" of the Shelly Uni of the hot water (HAS-404), switched through
`shelly-client` 2.6.0. The relay has `auto_off` of 60 s on the device; a start sends
`/relay/1?turn=on&timer=<max-run>`, whose timer replaces it for that switch and flips the relay back
should the service never stop it.

`GET /home/water/circulation` - the newest read of the Uni (every 15 s, every 5 s while a run goes
on), a field left out when it is not known - never off, never 0; the read is left out when older
than a minute:

```json
{
  "running": true,
  "since": "2026-11-05T19:00:00",
  "trigger": "manual",
  "source": "dashboard",
  "returnTemperature": 28.5,
  "tankTemperature": 45.0,
  "measuredAt": "2026-11-05T19:00:05",
  "lastRun": {"startedAt": "2026-11-05T19:00:00", "trigger": "manual", "source": "dashboard", "returnAtStart": 24.50}
}
```

- `running` - the relay is on; `since` - since when; `trigger` and `source` - of the run the service
  started, left out for a pump switched on by something else (the scene of the Shelly cloud).
- `returnTemperature` - sensor 1 of the Uni, `tankTemperature` - sensor 0, in °C.
- `lastRun` - the newest run the service started, the one going on included (`endedAt` and
  `stoppedBy` left out while it goes on).

`POST /home/water/circulation?source=dashboard` - `202` with the run (as `lastRun`): started is not
warm, the read tells when the return got there.

- The Uni is read first: the tank below `water.circulation.tank-min` (38 °C) is `409` `TANK_COLD`;
  a Uni not read, or read without the tank, `502` `DEVICE_FAILED`.
- A run already going on is answered as it is - no second command; two starts at once share one.
- The command is sent once: refused by the Uni, `502` `DEVICE_FAILED` and nothing held; not
  answered, `502` all the same, but the run is held and watched - it may have been carried out.
- `source` is a lowercase word of at most 20 letters or dashes; anything else is `400`
  `INVALID_COMMAND`.

A run ends when the return reaches `water.circulation.return-target` (35 °C, with
`stop-on-return`) or after `max-run` (3 min) - the relay is switched off; when the relay is found
off before that (`device` - a hand in the Shelly app, a power cut; `limit` near max-run - the timer
of the device; `failure` for a start the Uni did not answer); and as `failure` when the Uni is not
heard for a minute past max-run, or when the off command fails (the timer stops the pump). A run an
earlier pod left open (a deploy during a run) is ended as `failure` at the end of its timer at the
first pass of the next.

`GET /home/water/circulation/runs?from=&to=` - the runs started in `[from, to)`, newest first, each as
`lastRun`: `startedAt`, `endedAt`, `trigger`, `source`, `stoppedBy` (`temperature`, `limit`,
`device`, `failure`), `returnAtStart`, `returnAtEnd`. The range follows the rules of the
temperature history (local date-times, at most 31 days, `400` without a code otherwise).

| Property | Default | Meaning |
|---|---|---|
| `water.circulation.return-target` | `35` | °C of the return that stops a run (20 - 60) |
| `water.circulation.max-run` | `PT3M` | the longest run, also the timer of the device (30 s - 10 min, a bare number is minutes) |
| `water.circulation.tank-min` | `38` | °C of the tank below which a start is refused (20 - 80) |
| `water.circulation.stop-on-return` | `true` | whether the return stops a run - `false` leaves the limit alone, for a sensor that is not on the return |

### The automation

Since HAS-405 the pump is also started by itself, **before the tap is opened** - never on the flow
of the valve, the water then runs already. Triggers:

- **a new motion** in a room of `rooms` - `occupancy.lastMotionAt` of `GET /home/light/rooms/{room}` of
  `light-service` newer than the last one seen (read every 10 s, over k8s DNS; the bathroom down has no
  motion sensor) - trigger `motion`, source the room;
- **a light switched on** in such a room (`anyOn` from `false` to `true`) - trigger `light`;
- **every minute of a comfort window** - trigger `window`, source the window (`19:00-23:30`).

The first read of a room after a start is what was there already and triggers nothing. A trigger
starts a run only when all of these hold - the cheap ones first, presence-service last:

- outside the quiet hours (23:30 - 05:30) and with no run going on and the pump off (the Uni read
  within the minute - unknown never starts);
- the tank at least `tank-min` and the return below `start-below` (30 °C);
- `pause-after` (20 min) since the end of the last run stopped by the temperature or the limit;
- somebody at home (`presence-service`; not known is never at home).

The run is the start of the manual one (the Uni read again, the tank checked again) with its
trigger and source in `circulation_run`. Three runs in a row whose return rose by less than
`min-rise` (1.5 °C) - stopped by the temperature or the limit, both temperatures known - are an
**alert** on the technical channel (a pump or a sensor to look at), the next run that rises an
info; judged by the rise, not by the limit (owner, 2026-11-06: with the tank at about 43 °C the
return rarely reaches 35 °C within a run). The count is in memory.

| Property | Default | Meaning |
|---|---|---|
| `water.circulation.automation.enabled` | `true` | `false` switches the automation off without a release; the manual start stays |
| `water.circulation.automation.rooms` | `bathroom up`, `bathroom down` | the rooms of `light-service` whose motion and lights start a run (1 - 10) |
| `water.circulation.automation.start-below` | `30` | °C of the return below which an automatic run starts (15 - 60) |
| `water.circulation.automation.pause-after` | `PT20M` | no automatic run within it after a run stopped by the temperature or the limit (0 - 2 h, a bare number is minutes) |
| `water.circulation.automation.quiet-from` / `quiet-to` | `23:30` / `05:30` | no automatic run between them, across midnight |
| `water.circulation.automation.comfort-windows` | `06:00-08:00`, `19:00-23:30` | a run every minute of a window while the conditions hold (at most 6) |
| `water.circulation.automation.min-rise` / `no-rise-runs` | `1.5` / `3` | the alert of a return that does not warm (0.5 - 10 °C, 2 - 10 runs) |

## The water softener

`GET /home/water/softener` answers the last read of the iQua cloud (HAS-397). The softener has no local API (scanned
2026-11-05), so the service logs in to the cloud of the iQua application with the account of the house
(`POST /auth/login`, a token of about 24 h held in memory, refreshed an hour before its end and after a `401`), lists
the devices of the account at its start and once a day, and reads `GET /devices/{id}/detail-or-summary` every
`water.softener.cloud.interval` (5 min) - one call of data a read. After three failed logins within 30 minutes it tries
none for 30 minutes: the cloud blocks an account above about 1000 calls a day.

```json
{
  "reachable": true,
  "online": true,
  "measuredAt": "2026-11-09T10:00:00",
  "stale": false,
  "water": {"usedTodayLitres": 312.4, "averageDailyLitres": 410.0, "treatedAvailableLitres": 1890.5,
            "totalLitres": 152003.2, "flowLitresPerMinute": 0.0},
  "salt": {"levelPercent": 45.0, "daysLeft": 38, "outOfSaltDate": "2026-12-17"},
  "regeneration": {"daysSinceLast": 3, "lastAt": "2026-11-06", "total": 412},
  "hardnessGpg": 14.0,
  "hardnessMgCaco3": 239.7,
  "daysInOperation": 950,
  "signalDbm": -58,
  "alerts": {"lowSalt": false, "leak": false, "excessiveWaterUse": false, "shutoffValve": false, "resin": false,
             "error": false}
}
```

- `reachable` - `false` after two missed reads in a row, at once when the account has no softener; left out until the
  first read ended. A missed read keeps the last reading, `stale` says when it is older than two intervals.
- `online` - the softener connected to the cloud, as the cloud says.
- `water` - litres and l/min, as the cloud converts them. **The flow is the value the cloud last refreshed**: it moves
  only while an application keeps a live session with the softener (the live flow is HAS-401).
- `salt.levelPercent` - the per cent the iQua application shows, counted down by the softener from what was entered on its
  panel, not measured; `outOfSaltDate`
  and `regeneration.lastAt` are the days of the reading moved by `daysLeft` / `daysSinceLast`.
- `hardnessGpg` - the hardness set on the softener (grains per gallon); `hardnessMgCaco3` the same x 17.12.
- `alerts` - the flags of the softener; `leak` is its own watch of a continuous flow.
- A value the cloud leaves out is left out - never a default. Before the first reading: `503 SOFTENER_UNAVAILABLE`.

| Property | Default | Meaning |
|---|---|---|
| `water.softener.cloud.interval` | `PT5M` | the read of the softener (1 - 60 min, a bare number is minutes) |
| `water.softener.serial-number` | none | one softener of several on the account; without it the first one |
| `water.softener.cloud.base-url` | `https://api.myiquaapp.com/v1` | the API of the iQua application |

### The history of the softener

The iQua application shows only today and the last regeneration; the service keeps its own history (HAS-398). Every read
is stored in `softener_reading` - a row only when a value changed, otherwise the latest row is checked again - and the
regenerations come from the regeneration events of the cloud once an hour into `softener_regeneration`. Both are kept
730 days (`water.meter.retention`, the same nightly pass as the counter of the valve).

- `GET /home/water/softener/history?metric=&from=&to=` - one metric over a range: `saltLevel` (%), `waterUsedToday`,
  `totalWater`, `treatedWaterAvailable` (litres), `flow` (l/min), `hardness` (gpg). The shape of the weather history:
  `{"metric", "from", "to", "bucketSeconds", "points": [{"at", "avg", "min", "max"}]}`. At most 366 days; buckets of
  5 min up to 2 days, 30 min up to 8, 2 h up to 31, a day beyond. A row is stored on a change only, so a bucket without a
  change has no point. A metric not in the list is `400 UNKNOWN_METRIC`, a range that breaks a rule `400 INVALID_RANGE`.
- `GET /home/water/softener/days?from=&to=` - `[{"day": "2026-11-08", "litres": 310.0}]`, the litres through the softener
  per day of the house from the deltas of its counter (a drop is a reset, never a negative volume; a day not read has no
  entry). Beside the day of the water meter (`GET /home/water/consumption`) the difference is the water that did not go
  through the softener - the bypass, the garden.
- `GET /home/water/softener/regenerations?from=&to=` - `[{"startedAt", "endedAt", "deviceStartedAt", "running"}]`, newest
  first; `endedAt` left out while a regeneration goes on.
- `from` / `to` of the days and the regenerations are days (`YYYY-MM-DD`), both included, the last 30 days up to today by
  default, at most 366; those of the history are date-times (`YYYY-MM-DDTHH:MM`), `to` not included.

### The alerts of the softener

The house tells the owner on Discord of the softener as of every device (HAS-399), from the flags of each reading and
whether the cloud hears it:

| Alarm | Level | When | Reminder |
|---|---|---|---|
| Low salt | warn | `low_salt_alert` - the text names the per cent left and the days to run out | once a day |
| Leak | error | `flow_monitor_alert`, the continuous flow the softener watches itself | hourly |
| Excessive use of water | warn | `excessive_water_use_alert` | hourly |
| Shutoff valve, resin, error | error | `water_shutoff_valve_alert`, `resin_alert`, `error_code_alert` | hourly |
| Not heard | warn | no reading of the softener online - `online: false` or the cloud not answering - for `water.softener.offline-after` (30 min; it drops off Wi-Fi for minutes on its own) | hourly |

A flag raised opens the alarm at that reading, the flag down in a reading ends it with one info naming how long it lasted.
A flag the reading leaves out, no reading, or the flags of a softener the cloud says is offline decide nothing - unknown is
never an alarm. The open episodes are rows of `water_alert` (kinds added by `V10`), so a restart repeats nothing; a
notification that fails is logged and published with the next pass, it never stalls the pass.

`GET /home/water/softener/alerts` answers the alerts the iQua cloud keeps - the list of the application:
`[{"id", "type", "title", "message", "level", "at", "read"}]`, newest first, `at` in the time of the house, a field the
cloud left out left out. The first page of 20, read when asked and kept for one read interval (the cloud blocks an account
above about 1000 calls a day). `503 SOFTENER_UNAVAILABLE` before the softener was found on the account,
`502 DEVICE_FAILED` when the cloud did not answer - kept for the interval as well.

| Property | Default | Meaning |
|---|---|---|
| `water.softener.offline-after` | `PT30M` | no reading of the softener online for this long is the alarm (5 min - 24 h, a bare number is minutes) |

### The regeneration on demand

The only write of the house on the softener (HAS-400) - before guests, after a refill:

- `POST /home/water/softener/regeneration?when=now` (the default) - a regeneration now; `when=tonight` - at the time of the
  softener, 02:00. `DELETE /home/water/softener/regeneration` - cancel the one scheduled for tonight.
- Sent once to the iQua cloud (`PUT /devices/{id}/command`) as the pairs the device offers in the actions of its detail:
  `regenerate` / `regenerate`, `schedule`, `cancel` (read from the real device in both states, pinned by a test).
- `202` - `{"command", "status", "message", "sentAt"}`, the status and message as the cloud words them. Refused before
  anything is sent: `503 SOFTENER_UNAVAILABLE` without a reading, `409 REGENERATION_IN_PROGRESS` while it regenerates (the
  application offers no cancel of a running one), `409 COMMAND_TOO_SOON` within a minute of the last command;
  `400 INVALID_COMMAND` for another `when`. A command the cloud refused or did not answer is `502 SOFTENER_COMMAND_FAILED`
  with its status alone - it counts for the minute, it may have been carried out.
- No row of its own: the regeneration it starts is seen in `GET /softener/regenerations`, a scheduled one as
  `regeneration.status: "scheduled"` of `GET /softener` after the next read (5 min).

### The live flow

The cloud refreshes the flow of the softener only while a client keeps its live stream open (HAS-401), so the flow of
`GET /softener` is otherwise what the cloud last had:

- `PUT /home/water/softener/live` - `{"live": true, "liveUntil"}`: the service opens the stream (`GET /devices/{id}/live`, then
  the WebSocket at its address on the host of the cloud) and holds it for `water.softener.live-for` (5 min); a second `PUT`
  extends it from then, one stream at most. `DELETE /home/water/softener/live` (204) closes it; so do the end of its time and
  the shutdown of the pod.
- Every frame of the flow (`current_water_flow_gpm`, its converted litres a minute) updates the flow and `measuredAt` of
  `GET /softener`, which says `live: true` and `liveUntil` while the stream is open. Nothing is stored - the reads of every
  five minutes keep the history.
- The address carries a token of its own and is never logged. The cloud throttles the stream ("not for continuous data
  retrieval"): its 429 is `429 LIVE_THROTTLED`, never retried; `503 SOFTENER_UNAVAILABLE` before the softener was found,
  `502 DEVICE_FAILED` when the stream did not open. A stream the cloud closes is one WARN and is not opened again by itself.

| Property | Default | Meaning |
|---|---|---|
| `water.softener.live-for` | `PT5M` | how long a `PUT` holds the stream (1 - 10 min, a bare number is minutes) |

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
- **Schema:** the table `temperature` (`V1`) — `id` (identity), `updated_at`, `water` and
  `circulation` as `NUMERIC(5,2)` — with an index on `updated_at` (`V2`); the tables of the valve
  (`V3`): `water_meter_reading` (`total_m3 NUMERIC(18,9)`, `measured_at`, `last_checked_at`) and
  `water_valve_status` (one row: `open`, `flow_rate` m³/min, `pressure` kPa, `temperature`,
  `measured_at`, `last_checked_at`); the bills (`V4`): `water_bill` (the period, `billed_volume_m3`,
  `meter_reading_m3`, `amount`, `note`, `created_at`, `updated_at`) and `water_consumption_import`
  (`day`, `volume_m3` - the history of the Shelly cloud, kept apart from `water_meter_reading`,
  whose latest row and deltas go by `id`); the commands of the valve (`V7`): `water_valve_command`
  (`commanded_at`, `state`, `source`, `confirmed_at`); the runs of the circulation pump (`V8`):
  `circulation_run` (`started_at`, `ended_at`, `trigger`, `source`, `stopped_by`, `return_at_start`,
  `return_at_end`), kept 365 days - deleted every night at 03:35; the alarms (`V5`): `water_alert` (`kind`, `opened_at`,
  `last_told_at`, `closed_at`; at most one open a kind), its kinds of the flow alarms added by `V6`.
- **The valve:** a snapshot every minute of what is fresh - a row of `water_meter_reading` only when
  the counter changed (otherwise `last_checked_at` of the latest row moves), the one row of
  `water_valve_status` updated (its `measured_at` only with a change of a value). The readings older
  than `water.meter.retention` (730 days) are deleted every night at 03:25, never the latest row.
- **Writes:** one row per successful poll (every `PT3m`, the first `PT10s` after startup). The
  table is append-only — there is no retention or cleanup job.
- **Reads:** `status/temperature` returns the newest row by `updated_at`; the history averages
  the rows of a range in the database (a month is some 15 000 rows, answered as 372). Both go
  through the index on `updated_at`.
