# Cold start: time to first result

Query: `blood pressure medication` (AND), k=10, 12 forked JVMs per configuration after 3 discarded warm-up runs.

`java.version` 25.0.4.1 / OpenJDK 64-Bit Server VM 25.0.4.1+1-LTS

`os` Windows 11 10.0 amd64, 8 available processors, 7918 MB physical memory (2132 MB free at the start of this run)

`floor` 91.8 ms on the waypoint classpath, 117.4 ms on the lucene classpath -- JVM startup that both engines pay and neither can avoid.

| configuration | external p50 (ms) | p5 | p95 | above floor (ms) | in-process p50 (ms) |
|---|---:|---:|---:|---:|---:|
| (floor) jvm + waypoint classpath | 91.8 | 88.8 | 95.7 | - | - |
| (floor) jvm + lucene classpath | 117.4 | 114.5 | 126.4 | - | - |
| waypoint | 124.4 | 120.4 | 131.9 | 32.6 | 35.2 |
| waypoint + AppCDS | 111.5 | 108.6 | 115.2 | 19.7 | 21.2 |
| waypoint + AOT cache | 99.7 | 95.9 | 102.0 | 7.9 | 16.4 |
| lucene (scalar) | 404.4 | 400.0 | 425.7 | 287.1 | 297.1 |
| lucene (scalar) + AppCDS | 237.6 | 230.3 | 252.8 | 120.3 | 138.0 |
| lucene (scalar) + AOT cache | 197.8 | 193.8 | 208.0 | 80.4 | 93.9 |
| lucene + SIMD | 514.6 | 499.5 | 538.4 | 397.2 | 380.9 |
| lucene + SIMD + AppCDS | 305.4 | 300.5 | 327.4 | 188.1 | 177.8 |
| lucene + SIMD + AOT cache | 346.0 | 342.5 | 364.4 | 228.6 | 240.6 |

## Result

**Stock `java -cp`, which is what almost everyone runs: 124.4 ms vs 404.4 ms (lucene (scalar)) = **3.3x**.

**Both engines tuned as hard as JDK 25 allows: waypoint + AOT cache 99.7 ms vs lucene (scalar) + AOT cache 197.8 ms = 2.0x.**

Pre-registered kill criterion (design doc section 3): with the AOT cache enabled for **both** engines and Lucene given a `forceMerge(1)` single-segment index, the external median must be at least **3x** better or the headline claim is withdrawn. Measured: **2.0x** -- **not met**.

Discounting JVM startup, which is a floor neither engine controls, the work actually attributable to the engine is 7.9 ms vs 80.4 ms = **10.1x**.

## JVM diagnostics

Reported rather than suppressed. Where a configuration did not get the full benefit of what it was given, that favours the other engine and has to be visible.

- **lucene + SIMD + AppCDS**: [error][cds] An error has occurred while processing the shared archive file. Run with -Xlog:aot,cds for details.; [error][cds] Mismatched values for property jdk.module.addmods: jdk.incubator.vector specified during runtime but not during dump time; [error][cds] Disabling optimized module handling

## Exact commands

- **(floor) jvm + waypoint classpath**
  ```
  C:\Users\126ah\.jdks\jdk-25.0.4.1+1\bin\java.exe -cp C:\Users\126ah\Projects\waypoint-the-search-engine\cli\target\waypoint-cli.jar io.waypoint.cli.Main
  ```
- **(floor) jvm + lucene classpath**
  ```
  C:\Users\126ah\.jdks\jdk-25.0.4.1+1\bin\java.exe -cp C:\Users\126ah\Projects\waypoint-the-search-engine\bench\target\original-waypoint-bench.jar;C:\Users\126ah\.m2\repository\io\waypoint\waypoint-core\1.0.0-SNAPSHOT\waypoint-core-1.0.0-SNAPSHOT.jar;C:\Users\126ah\.m2\repository\org\apache\lucene\lucene-core\10.5.1\lucene-core-10.5.1.jar;C:\Users\126ah\.m2\repository\org\apache\lucene\lucene-analysis-common\10.5.1\lucene-analysis-common-10.5.1.jar;C:\Users\126ah\.m2\repository\org\openjdk\jmh\jmh-core\1.37\jmh-core-1.37.jar;C:\Users\126ah\.m2\repository\net\sf\jopt-simple\jopt-simple\5.0.4\jopt-simple-5.0.4.jar;C:\Users\126ah\.m2\repository\org\apache\commons\commons-math3\3.6.1\commons-math3-3.6.1.jar;C:\Users\126ah\.m2\repository\org\openjdk\jmh\jmh-generator-annprocess\1.37\jmh-generator-annprocess-1.37.jar;C:\Users\126ah\.m2\repository\org\junit\jupiter\junit-jupiter\5.11.3\junit-jupiter-5.11.3.jar;C:\Users\126ah\.m2\repository\org\junit\jupiter\junit-jupiter-api\5.11.3\junit-jupiter-api-5.11.3.jar;C:\Users\126ah\.m2\repository\org\opentest4j\opentest4j\1.3.0\opentest4j-1.3.0.jar;C:\Users\126ah\.m2\repository\org\junit\platform\junit-platform-commons\1.11.3\junit-platform-commons-1.11.3.jar;C:\Users\126ah\.m2\repository\org\apiguardian\apiguardian-api\1.1.2\apiguardian-api-1.1.2.jar;C:\Users\126ah\.m2\repository\org\junit\jupiter\junit-jupiter-params\5.11.3\junit-jupiter-params-5.11.3.jar;C:\Users\126ah\.m2\repository\org\junit\jupiter\junit-jupiter-engine\5.11.3\junit-jupiter-engine-5.11.3.jar;C:\Users\126ah\.m2\repository\org\junit\platform\junit-platform-engine\1.11.3\junit-platform-engine-1.11.3.jar io.waypoint.bench.LuceneSearchMain
  ```
- **waypoint**
  ```
  C:\Users\126ah\.jdks\jdk-25.0.4.1+1\bin\java.exe -cp C:\Users\126ah\Projects\waypoint-the-search-engine\cli\target\waypoint-cli.jar io.waypoint.cli.Main search indexes/msmarco-1m.wpt --and -k 10 -t blood pressure medication
  ```
- **waypoint + AppCDS**
  ```
  C:\Users\126ah\.jdks\jdk-25.0.4.1+1\bin\java.exe -XX:SharedArchiveFile=target\coldstart-and\waypoint.jsa -cp C:\Users\126ah\Projects\waypoint-the-search-engine\cli\target\waypoint-cli.jar io.waypoint.cli.Main search indexes/msmarco-1m.wpt --and -k 10 -t blood pressure medication
  ```
- **waypoint + AOT cache**
  ```
  C:\Users\126ah\.jdks\jdk-25.0.4.1+1\bin\java.exe -XX:AOTCache=target\coldstart-and\waypoint.aot -cp C:\Users\126ah\Projects\waypoint-the-search-engine\cli\target\waypoint-cli.jar io.waypoint.cli.Main search indexes/msmarco-1m.wpt --and -k 10 -t blood pressure medication
  ```
- **lucene (scalar)**
  ```
  C:\Users\126ah\.jdks\jdk-25.0.4.1+1\bin\java.exe -cp C:\Users\126ah\Projects\waypoint-the-search-engine\bench\target\original-waypoint-bench.jar;C:\Users\126ah\.m2\repository\io\waypoint\waypoint-core\1.0.0-SNAPSHOT\waypoint-core-1.0.0-SNAPSHOT.jar;C:\Users\126ah\.m2\repository\org\apache\lucene\lucene-core\10.5.1\lucene-core-10.5.1.jar;C:\Users\126ah\.m2\repository\org\apache\lucene\lucene-analysis-common\10.5.1\lucene-analysis-common-10.5.1.jar;C:\Users\126ah\.m2\repository\org\openjdk\jmh\jmh-core\1.37\jmh-core-1.37.jar;C:\Users\126ah\.m2\repository\net\sf\jopt-simple\jopt-simple\5.0.4\jopt-simple-5.0.4.jar;C:\Users\126ah\.m2\repository\org\apache\commons\commons-math3\3.6.1\commons-math3-3.6.1.jar;C:\Users\126ah\.m2\repository\org\openjdk\jmh\jmh-generator-annprocess\1.37\jmh-generator-annprocess-1.37.jar;C:\Users\126ah\.m2\repository\org\junit\jupiter\junit-jupiter\5.11.3\junit-jupiter-5.11.3.jar;C:\Users\126ah\.m2\repository\org\junit\jupiter\junit-jupiter-api\5.11.3\junit-jupiter-api-5.11.3.jar;C:\Users\126ah\.m2\repository\org\opentest4j\opentest4j\1.3.0\opentest4j-1.3.0.jar;C:\Users\126ah\.m2\repository\org\junit\platform\junit-platform-commons\1.11.3\junit-platform-commons-1.11.3.jar;C:\Users\126ah\.m2\repository\org\apiguardian\apiguardian-api\1.1.2\apiguardian-api-1.1.2.jar;C:\Users\126ah\.m2\repository\org\junit\jupiter\junit-jupiter-params\5.11.3\junit-jupiter-params-5.11.3.jar;C:\Users\126ah\.m2\repository\org\junit\jupiter\junit-jupiter-engine\5.11.3\junit-jupiter-engine-5.11.3.jar;C:\Users\126ah\.m2\repository\org\junit\platform\junit-platform-engine\1.11.3\junit-platform-engine-1.11.3.jar io.waypoint.bench.LuceneSearchMain indexes/msmarco-1m-lucene --and -k 10 -t blood pressure medication
  ```
- **lucene (scalar) + AppCDS**
  ```
  C:\Users\126ah\.jdks\jdk-25.0.4.1+1\bin\java.exe -XX:SharedArchiveFile=target\coldstart-and\lucene-scalar.jsa -cp C:\Users\126ah\Projects\waypoint-the-search-engine\bench\target\original-waypoint-bench.jar;C:\Users\126ah\.m2\repository\io\waypoint\waypoint-core\1.0.0-SNAPSHOT\waypoint-core-1.0.0-SNAPSHOT.jar;C:\Users\126ah\.m2\repository\org\apache\lucene\lucene-core\10.5.1\lucene-core-10.5.1.jar;C:\Users\126ah\.m2\repository\org\apache\lucene\lucene-analysis-common\10.5.1\lucene-analysis-common-10.5.1.jar;C:\Users\126ah\.m2\repository\org\openjdk\jmh\jmh-core\1.37\jmh-core-1.37.jar;C:\Users\126ah\.m2\repository\net\sf\jopt-simple\jopt-simple\5.0.4\jopt-simple-5.0.4.jar;C:\Users\126ah\.m2\repository\org\apache\commons\commons-math3\3.6.1\commons-math3-3.6.1.jar;C:\Users\126ah\.m2\repository\org\openjdk\jmh\jmh-generator-annprocess\1.37\jmh-generator-annprocess-1.37.jar;C:\Users\126ah\.m2\repository\org\junit\jupiter\junit-jupiter\5.11.3\junit-jupiter-5.11.3.jar;C:\Users\126ah\.m2\repository\org\junit\jupiter\junit-jupiter-api\5.11.3\junit-jupiter-api-5.11.3.jar;C:\Users\126ah\.m2\repository\org\opentest4j\opentest4j\1.3.0\opentest4j-1.3.0.jar;C:\Users\126ah\.m2\repository\org\junit\platform\junit-platform-commons\1.11.3\junit-platform-commons-1.11.3.jar;C:\Users\126ah\.m2\repository\org\apiguardian\apiguardian-api\1.1.2\apiguardian-api-1.1.2.jar;C:\Users\126ah\.m2\repository\org\junit\jupiter\junit-jupiter-params\5.11.3\junit-jupiter-params-5.11.3.jar;C:\Users\126ah\.m2\repository\org\junit\jupiter\junit-jupiter-engine\5.11.3\junit-jupiter-engine-5.11.3.jar;C:\Users\126ah\.m2\repository\org\junit\platform\junit-platform-engine\1.11.3\junit-platform-engine-1.11.3.jar io.waypoint.bench.LuceneSearchMain indexes/msmarco-1m-lucene --and -k 10 -t blood pressure medication
  ```
- **lucene (scalar) + AOT cache**
  ```
  C:\Users\126ah\.jdks\jdk-25.0.4.1+1\bin\java.exe -XX:AOTCache=target\coldstart-and\lucene-scalar.aot -cp C:\Users\126ah\Projects\waypoint-the-search-engine\bench\target\original-waypoint-bench.jar;C:\Users\126ah\.m2\repository\io\waypoint\waypoint-core\1.0.0-SNAPSHOT\waypoint-core-1.0.0-SNAPSHOT.jar;C:\Users\126ah\.m2\repository\org\apache\lucene\lucene-core\10.5.1\lucene-core-10.5.1.jar;C:\Users\126ah\.m2\repository\org\apache\lucene\lucene-analysis-common\10.5.1\lucene-analysis-common-10.5.1.jar;C:\Users\126ah\.m2\repository\org\openjdk\jmh\jmh-core\1.37\jmh-core-1.37.jar;C:\Users\126ah\.m2\repository\net\sf\jopt-simple\jopt-simple\5.0.4\jopt-simple-5.0.4.jar;C:\Users\126ah\.m2\repository\org\apache\commons\commons-math3\3.6.1\commons-math3-3.6.1.jar;C:\Users\126ah\.m2\repository\org\openjdk\jmh\jmh-generator-annprocess\1.37\jmh-generator-annprocess-1.37.jar;C:\Users\126ah\.m2\repository\org\junit\jupiter\junit-jupiter\5.11.3\junit-jupiter-5.11.3.jar;C:\Users\126ah\.m2\repository\org\junit\jupiter\junit-jupiter-api\5.11.3\junit-jupiter-api-5.11.3.jar;C:\Users\126ah\.m2\repository\org\opentest4j\opentest4j\1.3.0\opentest4j-1.3.0.jar;C:\Users\126ah\.m2\repository\org\junit\platform\junit-platform-commons\1.11.3\junit-platform-commons-1.11.3.jar;C:\Users\126ah\.m2\repository\org\apiguardian\apiguardian-api\1.1.2\apiguardian-api-1.1.2.jar;C:\Users\126ah\.m2\repository\org\junit\jupiter\junit-jupiter-params\5.11.3\junit-jupiter-params-5.11.3.jar;C:\Users\126ah\.m2\repository\org\junit\jupiter\junit-jupiter-engine\5.11.3\junit-jupiter-engine-5.11.3.jar;C:\Users\126ah\.m2\repository\org\junit\platform\junit-platform-engine\1.11.3\junit-platform-engine-1.11.3.jar io.waypoint.bench.LuceneSearchMain indexes/msmarco-1m-lucene --and -k 10 -t blood pressure medication
  ```
- **lucene + SIMD**
  ```
  C:\Users\126ah\.jdks\jdk-25.0.4.1+1\bin\java.exe --add-modules jdk.incubator.vector -cp C:\Users\126ah\Projects\waypoint-the-search-engine\bench\target\original-waypoint-bench.jar;C:\Users\126ah\.m2\repository\io\waypoint\waypoint-core\1.0.0-SNAPSHOT\waypoint-core-1.0.0-SNAPSHOT.jar;C:\Users\126ah\.m2\repository\org\apache\lucene\lucene-core\10.5.1\lucene-core-10.5.1.jar;C:\Users\126ah\.m2\repository\org\apache\lucene\lucene-analysis-common\10.5.1\lucene-analysis-common-10.5.1.jar;C:\Users\126ah\.m2\repository\org\openjdk\jmh\jmh-core\1.37\jmh-core-1.37.jar;C:\Users\126ah\.m2\repository\net\sf\jopt-simple\jopt-simple\5.0.4\jopt-simple-5.0.4.jar;C:\Users\126ah\.m2\repository\org\apache\commons\commons-math3\3.6.1\commons-math3-3.6.1.jar;C:\Users\126ah\.m2\repository\org\openjdk\jmh\jmh-generator-annprocess\1.37\jmh-generator-annprocess-1.37.jar;C:\Users\126ah\.m2\repository\org\junit\jupiter\junit-jupiter\5.11.3\junit-jupiter-5.11.3.jar;C:\Users\126ah\.m2\repository\org\junit\jupiter\junit-jupiter-api\5.11.3\junit-jupiter-api-5.11.3.jar;C:\Users\126ah\.m2\repository\org\opentest4j\opentest4j\1.3.0\opentest4j-1.3.0.jar;C:\Users\126ah\.m2\repository\org\junit\platform\junit-platform-commons\1.11.3\junit-platform-commons-1.11.3.jar;C:\Users\126ah\.m2\repository\org\apiguardian\apiguardian-api\1.1.2\apiguardian-api-1.1.2.jar;C:\Users\126ah\.m2\repository\org\junit\jupiter\junit-jupiter-params\5.11.3\junit-jupiter-params-5.11.3.jar;C:\Users\126ah\.m2\repository\org\junit\jupiter\junit-jupiter-engine\5.11.3\junit-jupiter-engine-5.11.3.jar;C:\Users\126ah\.m2\repository\org\junit\platform\junit-platform-engine\1.11.3\junit-platform-engine-1.11.3.jar io.waypoint.bench.LuceneSearchMain indexes/msmarco-1m-lucene --and -k 10 -t blood pressure medication
  ```
- **lucene + SIMD + AppCDS**
  ```
  C:\Users\126ah\.jdks\jdk-25.0.4.1+1\bin\java.exe --add-modules jdk.incubator.vector -XX:SharedArchiveFile=target\coldstart-and\lucene-simd.jsa -cp C:\Users\126ah\Projects\waypoint-the-search-engine\bench\target\original-waypoint-bench.jar;C:\Users\126ah\.m2\repository\io\waypoint\waypoint-core\1.0.0-SNAPSHOT\waypoint-core-1.0.0-SNAPSHOT.jar;C:\Users\126ah\.m2\repository\org\apache\lucene\lucene-core\10.5.1\lucene-core-10.5.1.jar;C:\Users\126ah\.m2\repository\org\apache\lucene\lucene-analysis-common\10.5.1\lucene-analysis-common-10.5.1.jar;C:\Users\126ah\.m2\repository\org\openjdk\jmh\jmh-core\1.37\jmh-core-1.37.jar;C:\Users\126ah\.m2\repository\net\sf\jopt-simple\jopt-simple\5.0.4\jopt-simple-5.0.4.jar;C:\Users\126ah\.m2\repository\org\apache\commons\commons-math3\3.6.1\commons-math3-3.6.1.jar;C:\Users\126ah\.m2\repository\org\openjdk\jmh\jmh-generator-annprocess\1.37\jmh-generator-annprocess-1.37.jar;C:\Users\126ah\.m2\repository\org\junit\jupiter\junit-jupiter\5.11.3\junit-jupiter-5.11.3.jar;C:\Users\126ah\.m2\repository\org\junit\jupiter\junit-jupiter-api\5.11.3\junit-jupiter-api-5.11.3.jar;C:\Users\126ah\.m2\repository\org\opentest4j\opentest4j\1.3.0\opentest4j-1.3.0.jar;C:\Users\126ah\.m2\repository\org\junit\platform\junit-platform-commons\1.11.3\junit-platform-commons-1.11.3.jar;C:\Users\126ah\.m2\repository\org\apiguardian\apiguardian-api\1.1.2\apiguardian-api-1.1.2.jar;C:\Users\126ah\.m2\repository\org\junit\jupiter\junit-jupiter-params\5.11.3\junit-jupiter-params-5.11.3.jar;C:\Users\126ah\.m2\repository\org\junit\jupiter\junit-jupiter-engine\5.11.3\junit-jupiter-engine-5.11.3.jar;C:\Users\126ah\.m2\repository\org\junit\platform\junit-platform-engine\1.11.3\junit-platform-engine-1.11.3.jar io.waypoint.bench.LuceneSearchMain indexes/msmarco-1m-lucene --and -k 10 -t blood pressure medication
  ```
- **lucene + SIMD + AOT cache**
  ```
  C:\Users\126ah\.jdks\jdk-25.0.4.1+1\bin\java.exe --add-modules jdk.incubator.vector -XX:AOTCache=target\coldstart-and\lucene-simd.aot -cp C:\Users\126ah\Projects\waypoint-the-search-engine\bench\target\original-waypoint-bench.jar;C:\Users\126ah\.m2\repository\io\waypoint\waypoint-core\1.0.0-SNAPSHOT\waypoint-core-1.0.0-SNAPSHOT.jar;C:\Users\126ah\.m2\repository\org\apache\lucene\lucene-core\10.5.1\lucene-core-10.5.1.jar;C:\Users\126ah\.m2\repository\org\apache\lucene\lucene-analysis-common\10.5.1\lucene-analysis-common-10.5.1.jar;C:\Users\126ah\.m2\repository\org\openjdk\jmh\jmh-core\1.37\jmh-core-1.37.jar;C:\Users\126ah\.m2\repository\net\sf\jopt-simple\jopt-simple\5.0.4\jopt-simple-5.0.4.jar;C:\Users\126ah\.m2\repository\org\apache\commons\commons-math3\3.6.1\commons-math3-3.6.1.jar;C:\Users\126ah\.m2\repository\org\openjdk\jmh\jmh-generator-annprocess\1.37\jmh-generator-annprocess-1.37.jar;C:\Users\126ah\.m2\repository\org\junit\jupiter\junit-jupiter\5.11.3\junit-jupiter-5.11.3.jar;C:\Users\126ah\.m2\repository\org\junit\jupiter\junit-jupiter-api\5.11.3\junit-jupiter-api-5.11.3.jar;C:\Users\126ah\.m2\repository\org\opentest4j\opentest4j\1.3.0\opentest4j-1.3.0.jar;C:\Users\126ah\.m2\repository\org\junit\platform\junit-platform-commons\1.11.3\junit-platform-commons-1.11.3.jar;C:\Users\126ah\.m2\repository\org\apiguardian\apiguardian-api\1.1.2\apiguardian-api-1.1.2.jar;C:\Users\126ah\.m2\repository\org\junit\jupiter\junit-jupiter-params\5.11.3\junit-jupiter-params-5.11.3.jar;C:\Users\126ah\.m2\repository\org\junit\jupiter\junit-jupiter-engine\5.11.3\junit-jupiter-engine-5.11.3.jar;C:\Users\126ah\.m2\repository\org\junit\platform\junit-platform-engine\1.11.3\junit-platform-engine-1.11.3.jar io.waypoint.bench.LuceneSearchMain indexes/msmarco-1m-lucene --and -k 10 -t blood pressure medication
  ```
