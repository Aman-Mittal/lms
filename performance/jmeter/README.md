# Load tests (Apache JMeter)

`lms-api.jmx` signs in once as the demo administrator, sets up a partner and a
custom field to read and write, then drives a mixed workload: reads picked at
random across the platform's list endpoints and the custom-field endpoints, a
readiness probe, and a write in one iteration of ten (registering partners, and
concurrent writes to one record's custom values, where losing a race must be a
clean 409 and never a 500).

| Script | What it does |
|---|---|
| `run.sh` | One JMeter JVM against `BASE_URL`; fails on the error-rate or 95th-percentile limit |
| `run-distributed.sh` | A controller and several `jmeter-server` workers over RMI; the load is workers x users, and the merged result is judged by the same limits |
| `summarise.py` | Reads the result file and decides pass or fail |

```
BASE_URL=http://localhost:8080 DEMO_ADMIN_PASSWORD=... bash performance/jmeter/run.sh
BASE_URL=http://localhost:8080 DEMO_ADMIN_PASSWORD=... WORKERS=3 THREADS=4 \
    bash performance/jmeter/run-distributed.sh
```

By default the workers start on loopback, which exercises the real protocol on
one machine. To use real infrastructure, start `jmeter-server` on each machine
and pass `REMOTE_WORKERS=host1:1099,host2:1099`.

In CI, `.github/workflows/load.yml` runs both against a throwaway stack (on
demand, weekly, and on a pull request labelled `load`); `jmeter.yml` runs the
single-node plan against a deployment.

## Assertions that allow a 409

A sampler that accepts a conflict must set the assertion's "Ignore Status":
otherwise JMeter marks any 4xx as a failure before the assertion is consulted,
and a perfectly good 409 counts against the error rate.
