<p style="text-align: center;">
  <a href="https://mariadb.com/">
    <img alt="mariadb logo" src="https://mariadb.com/kb/static/images/logo-2018-black.png">
  </a>
</p>

# Benchmark

How to run :

```script
mvn clean package -P bench -DskipTests

# run all benchmarks
nohup java -Duser.country=US -Duser.language=en -jar target/benchmarks.jar > log.txt

# run a specific benchmark
java -Duser.country=US -Duser.language=en -jar target/benchmarks.jar "Select_100_cols"
```

Configuration by system properties :

* TEST_HOST: Hostname. default "localhost"
* TEST_PORT: port. default 3306
* TEST_USERNAME: user name. default "root"
* TEST_PASSWORD: password. default ""
* TEST_DATABASE: database. default "testj"
* TEST_OTHER: permit adding connection string options. default ""

example:

```script
mvn clean package -P bench -Dmaven.test.skip
java -DTEST_PORT=3307 -Duser.country=US -Duser.language=en -jar target/benchmarks.jar "Select_100_cols"
```

## Connection-local frequency tracker

`PreparedStatementFrequencyTrackerBenchmark` needs no database. It uses 300 query identities and a selected capacity of 100.
The window sizes are 5,000 and 30,000 execution units. SQL sizes are 2,048 and 3,072 Latin-1 characters.
These values are experiments, not connector defaults.

```sh
mvn -Pbench -DskipTests package
java -jar target/benchmarks.jar 'PreparedStatementFrequencyTrackerBenchmark.repeated' -prof gc -rf json -rff repeated.json
java -jar target/benchmarks.jar 'PreparedStatementFrequencyTrackerBenchmark.oneUseTail' -prof gc -rf json -rff tail.json
java -jar target/benchmarks.jar 'PreparedStatementFrequencyTrackerBenchmark.tailInputBaseline' -prof gc -rf json -rff input.json
java -jar target/benchmarks.jar 'PreparedStatementFrequencyTrackerBenchmark.workloadShift' -prof gc -rf json -rff shift.json
java -jar target/benchmarks.jar 'PreparedStatementFrequencyTrackerBenchmark.batch64' -prof gc -rf json -rff batch.json
```

Each fork uses one benchmark thread and its own tracker. Steady measurements run without network or database work.
Batch results report cost per submission of 64 execution units.
The one-use tail includes fresh SQL construction. Compare its input-generation baseline before attributing cost to the tracker.

## Server-preparation strategy

`PreparedStatementStrategyBenchmark` compares the `all` and `frequency` strategies on an integrated driver against a database.
It reads `TEST_HOST`, `TEST_PORT`, `TEST_USERNAME`, `TEST_PASSWORD`, and `TEST_DATABASE` from system properties, like the benchmarks above.

It compares `all` and `frequency` with an already-warm server handle.
It measures statement reuse and creation/closure separately, including result consumption.
It uses cache capacity 100, window 5,000, TLS disabled, and one benchmark thread.
These are experiment settings, not recommended defaults or production throughput measurements.

```sh
mvn -Pbench -DskipTests package
java -DTEST_HOST=$HOST -DTEST_PORT=$PORT -DTEST_USERNAME=$USER -DTEST_PASSWORD=$PASS \
  -jar target/benchmarks.jar 'PreparedStatementStrategyBenchmark' -prof gc -rf json -rff strategy.json
```

Report allocation and timing intervals separately; this warm-cache workload does not measure cold-query preparation savings.
Run against a dedicated local database only.
