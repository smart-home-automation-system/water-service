# water-service

Watches the domestic hot-water circuit. Every three minutes it reads two temperatures (hot
water, circulation) from a Shelly Uni sensor, stores the reading and recomputes one flag:
does the water need heating? `boiler-service` polls that flag to drive the pumps and the
furnace. Since HAS-316 it is also the water meter of the house: the Neo Smart Water Valve in the
garage, from the broker and its RPC, served on `GET /home/water/valve` (epic HAS-310), and since HAS-317
the consumption by day, week, month and year on `GET /home/water/consumption`; since HAS-318 the bills of
the waterworks compared with what the house measured, and the daily history imported from the Shelly cloud; since HAS-319
the alarms of the mains, the valve and frost, and since HAS-320 those of the flow - high, continuous, in an empty house.

Part of the smart-home-automation-system organization — org-wide conventions, the
repository map and working rules come from the workspace-level context
(`organization-repository/claude/organization.md`). The user writes the code in this
repository themselves; Claude's default role here is analysis, code review and security
review - the owner had Claude implement the readings of the water valve (HAS-316), the consumption
(HAS-317), the bills (HAS-318) and the alarms (HAS-319, HAS-320) and reviews them in IntelliJ.

## Role in the system

- Is called by: `boiler-service` (`GET /home/water/status/active`, directly over the cluster
  network) and, through `api-gateway-service`, by anything outside the cluster
  (`/home/water/**`) — today the web dashboard, which reads `status/temperature` and, from
  HAS-201 on, `temperature/history`.
- Calls: the Shelly Uni sensor on the LAN over HTTP, the water valve over its RPC
  (`Shelly.GetComponents`, HAS-316), `database-service` for the valve of the device registry
  (`GET /home/device/registry?kind=water valve&enabled=true`), `presence-service` for whether anybody is
  at home (`GET /home/presence/residents/presence` over k8s DNS, every minute - HAS-320), and PostgreSQL.
- Consumes: `device.prod.water` on `/device` (user `device`, `rabbitmq-password`) - every device
  event, of which only the valve is read (HAS-316).
- Publishes: the alarms of the valve to the `notification` exchange of `/notification` (user `notification`,
  `notification-rabbitmq-password`), categories `alert` and `info` (HAS-319).
- Owns its own database, `home-automation-water` — the append-only `temperature`, indexed on
  `updated_at` (`V2`), and the tables of the valve (`V3`).
- Uses libraries: `cholewa-commons`, `smart-home-sdk` (`SystemActiveReply`, `SmartDevice`),
  `shelly-client` (the sensor models; the valve with `ShellyRpcClient` and `ShellyMqttDecoder`).
- It is the scaffold for **new** services (the `new-service` skill copies its pom,
  `Dockerfile` and workflows), so what is changed here spreads.

## Build & run

- Build + tests: `mvn verify` — needs a running Docker since HAS-200
  (`WaterTemperatureRepositoryTest`, Testcontainers); without one that test fails, it is not
  skipped.
- Local run: `home,local` Spring profiles, port `6006` (Actuator `8006`); in-cluster port
  `6200`, Actuator `8200`. Needs PostgreSQL (`database.*`, Flyway runs at startup).
- Spring Boot **4.1.2** with logbook 4.2.0; `cholewa-commons` 1.13.0, `smart-home-sdk` 1.16.0,
  `shelly-client` 2.5.0 **with its beans on** since HAS-316 (`ShellyRpcClient`, `ShellyGen1Client`,
  `ShellyMqttDecoder` - pinned by `WaterServiceApplicationTest`); own libraries on the latest releases, as
  the org rule asks of every service task.
- A local run needs `rabbitmq-password` (any value without a broker - the listener then retries in the
  log) and a port-forward of `database-service` to 6005 for the registry.

## Specifics

- **The heating flag lives in memory and moves only after a successful database write.**
  `WaterService.handleWaterUpdate()` reads the sensor, saves the row and only then applies
  the hysteresis (on below 38 °C, off above 42 °C). A poll that fails anywhere — sensor or
  database — is logged and swallowed (`onErrorComplete`), and the flag keeps its last value.
  So a database outage freezes the flag: if it was `true`, `boiler-service` keeps heating
  until a write succeeds again. After a restart the flag starts as `false`.
- **`database.pool.max-size` is 2**, this service's share of the 47 connections of the
  managed database (20 allotted to the project; the paragraph below is the history of the budget of 22).
  The valve (HAS-316) adds two statements a minute (the snapshot) and one a night - the share stays 2. It was 4 up to and
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
  rewritten), the image of the current release against it, then the new jar while the old one keeps
  running. **The environment of the shell wins over a `--database-*` argument** (HAS-319: an IntelliJ env
  file with `database-*` / `flyway-url` sent a rehearsal jar to production and applied V5 there early): pass
  the throwaway database by the bound names and Flyway's own, e.g. `--database.host=localhost
  --database.port=15432 --database.name=water --database.username=water --database.password=w
  --spring.flyway.url=jdbc:postgresql://localhost:15432/water --spring.flyway.user=water
  --spring.flyway.password=w`, and check the first log lines name the throwaway host.
- **The pooled `ConnectionFactory` comes from `cholewa-commons`** via the `database.*` group;
  there is no `DbConfig` here. Since `cholewa-commons` 1.5 the pool validates every
  connection on acquire (`SELECT 1`, 2 s) and caps its lifetime at 30 minutes. Before that
  nothing checked a connection before use; during the connectivity trouble of 2026-10-03
  single writes took 200–370 s.
- **The poll is `fixedRate`, not `fixedDelay`**: for a method returning a `Mono` Spring does
  not wait for the previous run, so polls could overlap when one took longer than three
  minutes. **Since HAS-307 a poll is bounded** (`ScheduledPass.bounded` of `cholewa-commons` 1.11.0,
  `WaterSensorCron.LIMIT` 2 min, below the rate): the client of the Shelly has a connect timeout but
  none for the answer, so a sensor that took the connection and never answered held a poll for good,
  and a write that hangs did the same - now the poll ends at its bound with a WARN and they no longer
  pile up. A cancelled write may still run on the server (the cancel only stops the waiting).
  `WaterSensorCronTest` hangs a poll. The scheduler has the pool of 10 of the library (one task today).
  **The Shelly client has a `responseTimeout` of 5 s** since then too (`AppConfig.SHELLY_RESPONSE_TIMEOUT`,
  the value of boiler-service - netty's, between reads): a sensor that answers nothing fails the poll
  after 5 s, logged like any device error, and the bound is left for what no timeout covers.
  `AppConfigTest` pins it against a socket that never answers.
- **Scheduling is off under `test`** (HAS-307, `SchedulingConfig`, `@Profile("!test")` - it was on the
  application class, and every context test read the Shelly Uni of the house ten seconds after its start);
  the `test` document points the sensor at `localhost:1` as the second line of defence, both pinned by
  `WaterServiceApplicationTest`.
- **The calls to the Shelly are logged** since HAS-307: `cholewa-commons` >= 1.10.0 puts Logbook in the
  reactive chain of every `WebClient` of the injected builder, with full bodies (the answer of the Uni is
  small) and the `traceId`; there was no Logbook handler on that client before.
- **Surefire activates the `test` profile for every class** (`spring.profiles.active` in the
  pom), so a test without `@ActiveProfiles` does not switch the log output to JSON for the
  classes that follow. The `includes` match only `**/*Test.java` and `**/*IT.java` — a class
  named `...Tests` silently stops running.
- **Tests use `mockwebserver3`** since HAS-316: the legacy `com.squareup.okhttp3:mockwebserver` brought
  JUnit 4 and an older okhttp, which broke `mockwebserver3` next to it - `WaterSensorClientTest` was
  converted and the legacy dependency removed.
- **The water valve** (HAS-316, packages `registry`, `valve`, `rabbit`) - what is easy to break:
  - **One instance, never scaled**: the state of the valve lives in memory, so the Deployment is
    `Recreate` (it was RollingUpdate before - two pods would have split the events of the queue).
  - **Which component is which** is the key of the virtual component (`ValveTracker`): `boolean:200` the
    valve (`true` open), `number:200` the flow m³/min, `number:201` the pressure kPa, `number:202` the
    water temperature, `object:200` the counter - `value.counter.total` m³ read from the open map. A
    status of a virtual component has **no id**; shelly-client 2.5.0 judges it by `last_update_ts`.
  - `ValveRegistry` reads `?kind=water valve&enabled=true` at the start and every 5 min (bounded at 1 min);
    a failed read keeps the valve, before the first one `GET /valve` answers `503 REGISTRY_UNAVAILABLE`,
    none registered `404 NOT_FOUND_VALVE` (one INFO when it becomes so). Of more valves the lowest id
    (a WARN). **Another device id forgets the state** (`ValveTracker.reset`).
  - `DeviceEventConsumer` hands every event to `ValveEventRouter`, which picks the valve **by the routing
    key before a body is read** (about 130 messages a minute), case-insensitive on the device id; a body
    that is not its topic is a WARN without the body; nothing errors out of the listener
    (`.contextCapture()` last). The `local` profile reads `device.dev.water`.
  - `ValveReconciliation` reads `Shelly.GetComponents` 10 s after the start and a minute after every read
    (bounded at 45 s) - **alone it keeps the state current when MQTT is silent** (checked 2026-11-04: the
    jar without a broker answered the real valve in `/valve`). A failure is one WARN when it starts and an
    INFO on the return, DEBUG between; a component whose status is not its model the same, per component. The later
    arrival wins, MQTT or RPC. **The last will (`online false`) forgets every value** (the /code-review of HAS-316) - it
    would otherwise be served as fresh for minutes after the valve left; the next read brings them back while the RPC
    answers.
  - **The time of a value is the time of its report** (house time, cut to microseconds), not
    `last_update_ts` of the device (the time it last changed - 12 days old for the state of the valve).
    `water.valve.stale-after` (PT5M, 2 - 60 min, a bare number is minutes) leaves a value out of `/valve`
    and out of the snapshot. Units of the house in the answer only - l/min, bar, m³ - by `BigDecimal`
    shifts, the device units in memory and in `water_valve_status`.
  - `ValveSnapshot` (every minute, bounded at 50 s): `water_meter_reading` **compared with the table, not
    memory** (one statement - restart-safe; the latest row is the highest id, never the latest `measured_at`, a
    house time that goes back an hour in October), the total **rounded to the nine decimals of the column first**:
    the valve reports eleven, and a total the column rounds never equals the stored row - found on the
    rehearsal, it would have written a row a minute. `water_valve_status` is one row (`CHECK id = 1`), its
    `measured_at` moves only with a value (the pressure moves with almost every reading).
  - `MeterRetention` (03:25, bounded at 5 min): `water.meter.retention` (730 days, 366 - 3650, a bare number
    is days), never the latest row.
  - **The consumption** (HAS-317, `GET /home/water/consumption?range=day|week|month|year&at=`) is read from
    `water_meter_reading` by one statement per range (`WaterMeterRepository.CONSUMPTION`), the range and the one before
    it one after the other (`zipWhen`, never two connections of the pool at once). What is easy to break:
    - **A row is the moment a total was first seen**, so the water of a row is its total less the row before *by id*,
      counted in the bucket of its `measured_at`; the row before the range is read too (by the index on `measured_at`).
      **A negative delta is a reset and counts as 0** (`GREATEST`) - pinned with a reset inside a day.
    - **A bucket has a point only when the counter was watched in it**: a row measured in it, or the row live at its start
      checked after it began. An hour without water is `0`, an hour the service was down has no point; the water of a gap
      lands where the counter is seen again. Totals left out when nothing was watched - missing is never 0.
    - **Cut by `date_trunc`** with the unit of `ConsumptionRange` (`hour`, `day`, `month`): the deltas are summed once and
      the watch is one index read per bucket. Measured on 438 000 rows (two years at 600 changes a day): a year 0.15 s, a
      month 13 ms, a day 1 ms - the first form, a join of every bucket with every row, took 0.6 s for a year.
    - Weeks are Monday to Sunday, the period before is one calendar period earlier; today is the day of the house, read
      at every call. DST follows the rule of the temperature history (documented, not fixed).
    - Both parameters are read as text, so a missing or wrong one is `400 INVALID_RANGE` with the code - a typed
      parameter would be refused by WebFlux without one. The texts for the dashboard come with its water page (HAS-322).
  - **The bills and the imported history** (HAS-318, V4) - what is easy to break:
    - **The valve keeps no history** (no archive in its RPC, an empty KVS); the Shelly cloud has the daily consumption
      since about 2026-02 (`GET https://shelly-1-eu.shelly.cloud/v2/statistics/generic/accumulation?id=&channel=3200&date_range=custom&date_from=&date_to=`,
      undocumented, seen in the Shelly web app; 29.09 - 20.10.2026 is 11 019.7 L against the billed 11 m³). **No
      integration with the cloud** (owner 2026-11-05): it is imported once by hand through
      `PUT /home/water/consumption/imported`, which replaces the whole set.
    - **The import lives in its own table**, `water_consumption_import`, never in `water_meter_reading`: the latest row
      of the counter and its deltas go by `id`, and rows inserted afterwards with older dates would break both - the
      snapshot would compare the counter with an old row. **An imported day must not be after the first day of the
      readings** (`BillRules.checkImport`). That first day is watched only from its first row (2026-11-05 08:37), so its
      imported value **wins** over the measured part in a bucket of a day or a month (a join with the imported days in
      `CONSUMPTION` - a `NOT EXISTS` per row took a year to 0.55 s, the join keeps 0.15 s); its hours stay measured. No
      day is counted twice, none only in part once it is imported.
    - `GET /consumption` counts an imported day as watched (a bucket of a day, its share of a month); a day in hours has
      no imported points.
    - **The measured volume of a bill** is the sum of its days (`consumption(..., "day")`), left out when a day of the
      period has no bucket - a period before the history, or a day nobody watched. `difference` = measured - billed,
      `differencePercent` of the billed volume (left out for 0). The bills are measured one after the other
      (`concatMap`), never two statements of the pool of 2 at once.
    - **The rules are in `BillRules`, not Bean Validation**, so every refusal carries `INVALID_BILL` / `INVALID_IMPORT`
      with an English reason (the dashboard copies them); a body that is not JSON or a date that is not one is refused by
      WebFlux without a code. Overlap is a shared day, both ends included (`WaterBillRepository.findOverlapping`).
    - The import of a whole set is one transaction (`@Transactional` on `ConsumptionImportRepository.replaceAll`, a
      delete and one multi-row insert).
  - **The alarms** (HAS-319, package `alarm`, V5) - what is easy to break:
    - **A second connection to the broker**, `/notification` as the user `notification`
      (`notification-rabbitmq-password`, required): `NotificationRabbitConfig` is the **seventh copy** (heating, blinds,
      weather, light, amx, energy, water - kept by the owner's choice, HAS-319 left it to the owner). It must never be a
      `ConnectionFactory` bean - `RabbitAutoConfiguration` would back off from the listener of the device events;
      `WaterServiceApplicationTest` pins one connection factory and one publisher. `local` publishes with `env: dev`.
    - **Decided by `AlarmRules` without I/O** (pinned by `AlarmRulesTest`), held in memory by `AlarmMonitor` (every
      minute, bounded at 50 s): the decision at once, then the notices (each cut off after 10 s, a failure logged and
      tried again with the next pass - never applied before it was published), then the rows of `water_alert`.
      Nothing is decided before the open episodes are read, nothing while no valve is registered.
    - No mains / frost: below the bound in **every** pass for its time opens at the first of them; at or above it in
      every pass for the **same time** returns, closed at the first of them - a time hysteresis, or a temperature
      wavering about 3 °C would alert and return every few minutes. A value not fresh (`water.valve.stale-after`) is
      never low: it breaks both counts and closes nothing - so a valve that left raises only the offline alarm.
    - Offline: `lastHeard` is kept by the monitor, not the tracker - the last will forgets the values, and with them
      `newestReport`; the start of the pod counts as heard. The device and the broker go down together, so an alert
      that never got through is said by the return ("the alert did not get through").
    - Pressures in kPa (the unit of the valve) in the properties and the rules, bar in the words only.
  - **The flow alarms** (HAS-320, `FlowTrack`, `FlowRun`, `FlowRules`, `FlowMessages`, `House`, package `presence`,
    V6 - the kinds of `water_alert`) - what is easy to break:
    - **Judged from every report of the rate, not the minute pass alone**: `ValveTracker` hands each `number:200` to its
      `FlowListener` (`FlowTrack`, l/min) as it arrives, so a stop of 50 s is the same flow and a flush between two
      passes is still seen. One run per alarm, each by its own bound (high above `water.flow.high-above`, continuous
      above 0, the empty house above `empty-house-above`); a fall no longer than `continuous-pause` is the same run, a
      longer one ends it and the next starts a run that knows where the one before ended (`previousEnd`).
    - **The rate is published on a change only** (checked on the valve 2026-11-05: `last_update_ts` 20 min old with no
      water), so it is fresh for `stale-after` thanks to the RPC read of every minute; while it is not, the pass hands
      the litres of the last whole minute of the counter (`by_minute[0]`, `ValveState.lastMinute`) to the track. Neither
      fresh: the flow is not known - nothing opens or closes, reminders go on.
    - **A silence and the last will stop every run at the last report** (`FlowTrack.stopped`): a flow before and after a
      gap is never one flow, and an open episode closes where the valve was last heard. Reports further apart than
      `stale-after` plus the minute of the pass count as a gap.
    - The high and the continuous flow open only while the water still runs (`run.above()`); the empty house also in the
      pause after a fall - a short flush. Each opens at the start of its run and closes where it fell; an episode read
      after a restart goes on with the first run the track sees (its `previousEnd` is null).
    - **The empty house** from presence-service every minute (`PresenceClient` - the third copy, after light-service and
      appliance-service - and `HouseOccupancy.judge`, the rule of light-service; `House` keeps `emptySince` through an
      unknown spell). Unknown is never empty: presence-service not read (cut off after 10 s), a member not seen for 5 min.
      The episode ends with the flow, not with the return of a member.
    - **The flow is decided after the alarms of the valve and apart from them**: a defect in it is logged and decides
      nothing that pass, it never takes the mains, the silence and frost with it (an unboxing NPE did, before the review).
    - Flows in l/min in the properties, the track and the words (`ValveTracker.litres`); a pass has six notices at most,
      each cut off after 10 s - should they all hang, the pass ends at its bound and the rest is due with the next.
  - **No command to the valve yet** (`Boolean.Set` of shelly-client is there for the alarms of the epic);
    `lastCommandConfirmed` of the task comes with the first command.
  - **Rehearsed** (2026-11-04): the released 0.6.1 image applied V1-V2 on a throwaway PostgreSQL with SSL (a user
    with `USAGE, CREATE` on `public` only), the new jar V3 with the real valve and the registry through a
    port-forward, no broker: `/valve` answered the valve within 10 s, a row of the counter a change, the same
    total moving `last_checked_at` across a restart.
