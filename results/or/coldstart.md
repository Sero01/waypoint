# Cold start: time to first result

Query: `manhattan project physics` (OR), k=10, 12 forked JVMs per configuration after 3 discarded warm-up runs.

`java.version` 25.0.4.1 / OpenJDK 64-Bit Server VM 25.0.4.1+1-LTS

`os` Windows 11 10.0 amd64, 8 available processors, 7918 MB physical memory (2092 MB free at the start of this run)

`floor` 91.9 ms on the waypoint classpath, 118.3 ms on the lucene classpath -- JVM startup that both engines pay and neither can avoid.

| configuration | external p50 (ms) | p5 | p95 | above floor (ms) | in-process p50 (ms) |
|---|---:|---:|---:|---:|---:|
| (floor) jvm + waypoint classpath | 91.9 | 88.7 | 104.1 | - | - |
| (floor) jvm + lucene classpath | 118.3 | 114.1 | 126.7 | - | - |
| waypoint | 124.1 | 119.9 | 131.0 | 32.3 | 34.8 |
| waypoint + AppCDS | 109.2 | 107.6 | 111.3 | 17.4 | 18.0 |
| waypoint + AOT cache | 99.1 | 94.3 | 104.9 | 7.2 | 15.9 |
| lucene (scalar) | 404.5 | 396.2 | 430.4 | 286.2 | 296.8 |
| lucene (scalar) + AppCDS | 240.1 | 227.1 | 271.3 | 121.8 | 138.9 |
| lucene (scalar) + AOT cache | 203.5 | 197.2 | 259.0 | 85.2 | 98.9 |
| lucene + SIMD | 504.3 | 491.0 | 643.1 | 385.9 | 369.3 |
| lucene + SIMD + AppCDS | 293.8 | 280.8 | 339.8 | 175.4 | 167.2 |
| lucene + SIMD + AOT cache | 342.6 | 328.4 | 355.3 | 224.3 | 234.0 |

## Result

**Stock `java -cp`, which is what almost everyone runs: 124.1 ms vs 404.5 ms (lucene (scalar)) = **3.3x**.

**Both engines tuned as hard as JDK 25 allows: waypoint + AOT cache 99.1 ms vs lucene (scalar) + AOT cache 203.5 ms = 2.1x.**

Pre-registered kill criterion (design doc section 3): with the AOT cache enabled for **both** engines and Lucene given a `forceMerge(1)` single-segment index, the external median must be at least **3x** better or the headline claim is withdrawn. Measured: **2.1x** -- **not met**.

Discounting JVM startup, which is a floor neither engine controls, the work actually attributable to the engine is 7.2 ms vs 85.2 ms = **11.8x**.

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
  C:\Users\126ah\.jdks\jdk-25.0.4.1+1\bin\java.exe -cp C:\Users\126ah\Projects\waypoint-the-search-engine\cli\target\waypoint-cli.jar io.waypoint.cli.Main search indexes/msmarco-1m.wpt -k 10 -t manhattan project physics
  ```
- **waypoint + AppCDS**
  ```
  C:\Users\126ah\.jdks\jdk-25.0.4.1+1\bin\java.exe -XX:SharedArchiveFile=target\coldstart-or\waypoint.jsa -cp C:\Users\126ah\Projects\waypoint-the-search-engine\cli\target\waypoint-cli.jar io.waypoint.cli.Main search indexes/msmarco-1m.wpt -k 10 -t manhattan project physics
  ```
- **waypoint + AOT cache**
  ```
  C:\Users\126ah\.jdks\jdk-25.0.4.1+1\bin\java.exe -XX:AOTCache=target\coldstart-or\waypoint.aot -cp C:\Users\126ah\Projects\waypoint-the-search-engine\cli\target\waypoint-cli.jar io.waypoint.cli.Main search indexes/msmarco-1m.wpt -k 10 -t manhattan project physics
  ```
- **lucene (scalar)**
  ```
  C:\Users\126ah\.jdks\jdk-25.0.4.1+1\bin\java.exe -cp C:\Users\126ah\Projects\waypoint-the-search-engine\bench\target\original-waypoint-bench.jar;C:\Users\126ah\.m2\repository\io\waypoint\waypoint-core\1.0.0-SNAPSHOT\waypoint-core-1.0.0-SNAPSHOT.jar;C:\Users\126ah\.m2\repository\org\apache\lucene\lucene-core\10.5.1\lucene-core-10.5.1.jar;C:\Users\126ah\.m2\repository\org\apache\lucene\lucene-analysis-common\10.5.1\lucene-analysis-common-10.5.1.jar;C:\Users\126ah\.m2\repository\org\openjdk\jmh\jmh-core\1.37\jmh-core-1.37.jar;C:\Users\126ah\.m2\repository\net\sf\jopt-simple\jopt-simple\5.0.4\jopt-simple-5.0.4.jar;C:\Users\126ah\.m2\repository\org\apache\commons\commons-math3\3.6.1\commons-math3-3.6.1.jar;C:\Users\126ah\.m2\repository\org\openjdk\jmh\jmh-generator-annprocess\1.37\jmh-generator-annprocess-1.37.jar;C:\Users\126ah\.m2\repository\org\junit\jupiter\junit-jupiter\5.11.3\junit-jupiter-5.11.3.jar;C:\Users\126ah\.m2\repository\org\junit\jupiter\junit-jupiter-api\5.11.3\junit-jupiter-api-5.11.3.jar;C:\Users\126ah\.m2\repository\org\opentest4j\opentest4j\1.3.0\opentest4j-1.3.0.jar;C:\Users\126ah\.m2\repository\org\junit\platform\junit-platform-commons\1.11.3\junit-platform-commons-1.11.3.jar;C:\Users\126ah\.m2\repository\org\apiguardian\apiguardian-api\1.1.2\apiguardian-api-1.1.2.jar;C:\Users\126ah\.m2\repository\org\junit\jupiter\junit-jupiter-params\5.11.3\junit-jupiter-params-5.11.3.jar;C:\Users\126ah\.m2\repository\org\junit\jupiter\junit-jupiter-engine\5.11.3\junit-jupiter-engine-5.11.3.jar;C:\Users\126ah\.m2\repository\org\junit\platform\junit-platform-engine\1.11.3\junit-platform-engine-1.11.3.jar io.waypoint.bench.LuceneSearchMain indexes/msmarco-1m-lucene -k 10 -t manhattan project physics
  ```
- **lucene (scalar) + AppCDS**
  ```
  C:\Users\126ah\.jdks\jdk-25.0.4.1+1\bin\java.exe -XX:SharedArchiveFile=target\coldstart-or\lucene-scalar.jsa -cp C:\Users\126ah\Projects\waypoint-the-search-engine\bench\target\original-waypoint-bench.jar;C:\Users\126ah\.m2\repository\io\waypoint\waypoint-core\1.0.0-SNAPSHOT\waypoint-core-1.0.0-SNAPSHOT.jar;C:\Users\126ah\.m2\repository\org\apache\lucene\lucene-core\10.5.1\lucene-core-10.5.1.jar;C:\Users\126ah\.m2\repository\org\apache\lucene\lucene-analysis-common\10.5.1\lucene-analysis-common-10.5.1.jar;C:\Users\126ah\.m2\repository\org\openjdk\jmh\jmh-core\1.37\jmh-core-1.37.jar;C:\Users\126ah\.m2\repository\net\sf\jopt-simple\jopt-simple\5.0.4\jopt-simple-5.0.4.jar;C:\Users\126ah\.m2\repository\org\apache\commons\commons-math3\3.6.1\commons-math3-3.6.1.jar;C:\Users\126ah\.m2\repository\org\openjdk\jmh\jmh-generator-annprocess\1.37\jmh-generator-annprocess-1.37.jar;C:\Users\126ah\.m2\repository\org\junit\jupiter\junit-jupiter\5.11.3\junit-jupiter-5.11.3.jar;C:\Users\126ah\.m2\repository\org\junit\jupiter\junit-jupiter-api\5.11.3\junit-jupiter-api-5.11.3.jar;C:\Users\126ah\.m2\repository\org\opentest4j\opentest4j\1.3.0\opentest4j-1.3.0.jar;C:\Users\126ah\.m2\repository\org\junit\platform\junit-platform-commons\1.11.3\junit-platform-commons-1.11.3.jar;C:\Users\126ah\.m2\repository\org\apiguardian\apiguardian-api\1.1.2\apiguardian-api-1.1.2.jar;C:\Users\126ah\.m2\repository\org\junit\jupiter\junit-jupiter-params\5.11.3\junit-jupiter-params-5.11.3.jar;C:\Users\126ah\.m2\repository\org\junit\jupiter\junit-jupiter-engine\5.11.3\junit-jupiter-engine-5.11.3.jar;C:\Users\126ah\.m2\repository\org\junit\platform\junit-platform-engine\1.11.3\junit-platform-engine-1.11.3.jar io.waypoint.bench.LuceneSearchMain indexes/msmarco-1m-lucene -k 10 -t manhattan project physics
  ```
- **lucene (scalar) + AOT cache**
  ```
  C:\Users\126ah\.jdks\jdk-25.0.4.1+1\bin\java.exe -XX:AOTCache=target\coldstart-or\lucene-scalar.aot -cp C:\Users\126ah\Projects\waypoint-the-search-engine\bench\target\original-waypoint-bench.jar;C:\Users\126ah\.m2\repository\io\waypoint\waypoint-core\1.0.0-SNAPSHOT\waypoint-core-1.0.0-SNAPSHOT.jar;C:\Users\126ah\.m2\repository\org\apache\lucene\lucene-core\10.5.1\lucene-core-10.5.1.jar;C:\Users\126ah\.m2\repository\org\apache\lucene\lucene-analysis-common\10.5.1\lucene-analysis-common-10.5.1.jar;C:\Users\126ah\.m2\repository\org\openjdk\jmh\jmh-core\1.37\jmh-core-1.37.jar;C:\Users\126ah\.m2\repository\net\sf\jopt-simple\jopt-simple\5.0.4\jopt-simple-5.0.4.jar;C:\Users\126ah\.m2\repository\org\apache\commons\commons-math3\3.6.1\commons-math3-3.6.1.jar;C:\Users\126ah\.m2\repository\org\openjdk\jmh\jmh-generator-annprocess\1.37\jmh-generator-annprocess-1.37.jar;C:\Users\126ah\.m2\repository\org\junit\jupiter\junit-jupiter\5.11.3\junit-jupiter-5.11.3.jar;C:\Users\126ah\.m2\repository\org\junit\jupiter\junit-jupiter-api\5.11.3\junit-jupiter-api-5.11.3.jar;C:\Users\126ah\.m2\repository\org\opentest4j\opentest4j\1.3.0\opentest4j-1.3.0.jar;C:\Users\126ah\.m2\repository\org\junit\platform\junit-platform-commons\1.11.3\junit-platform-commons-1.11.3.jar;C:\Users\126ah\.m2\repository\org\apiguardian\apiguardian-api\1.1.2\apiguardian-api-1.1.2.jar;C:\Users\126ah\.m2\repository\org\junit\jupiter\junit-jupiter-params\5.11.3\junit-jupiter-params-5.11.3.jar;C:\Users\126ah\.m2\repository\org\junit\jupiter\junit-jupiter-engine\5.11.3\junit-jupiter-engine-5.11.3.jar;C:\Users\126ah\.m2\repository\org\junit\platform\junit-platform-engine\1.11.3\junit-platform-engine-1.11.3.jar io.waypoint.bench.LuceneSearchMain indexes/msmarco-1m-lucene -k 10 -t manhattan project physics
  ```
- **lucene + SIMD**
  ```
  C:\Users\126ah\.jdks\jdk-25.0.4.1+1\bin\java.exe --add-modules jdk.incubator.vector -cp C:\Users\126ah\Projects\waypoint-the-search-engine\bench\target\original-waypoint-bench.jar;C:\Users\126ah\.m2\repository\io\waypoint\waypoint-core\1.0.0-SNAPSHOT\waypoint-core-1.0.0-SNAPSHOT.jar;C:\Users\126ah\.m2\repository\org\apache\lucene\lucene-core\10.5.1\lucene-core-10.5.1.jar;C:\Users\126ah\.m2\repository\org\apache\lucene\lucene-analysis-common\10.5.1\lucene-analysis-common-10.5.1.jar;C:\Users\126ah\.m2\repository\org\openjdk\jmh\jmh-core\1.37\jmh-core-1.37.jar;C:\Users\126ah\.m2\repository\net\sf\jopt-simple\jopt-simple\5.0.4\jopt-simple-5.0.4.jar;C:\Users\126ah\.m2\repository\org\apache\commons\commons-math3\3.6.1\commons-math3-3.6.1.jar;C:\Users\126ah\.m2\repository\org\openjdk\jmh\jmh-generator-annprocess\1.37\jmh-generator-annprocess-1.37.jar;C:\Users\126ah\.m2\repository\org\junit\jupiter\junit-jupiter\5.11.3\junit-jupiter-5.11.3.jar;C:\Users\126ah\.m2\repository\org\junit\jupiter\junit-jupiter-api\5.11.3\junit-jupiter-api-5.11.3.jar;C:\Users\126ah\.m2\repository\org\opentest4j\opentest4j\1.3.0\opentest4j-1.3.0.jar;C:\Users\126ah\.m2\repository\org\junit\platform\junit-platform-commons\1.11.3\junit-platform-commons-1.11.3.jar;C:\Users\126ah\.m2\repository\org\apiguardian\apiguardian-api\1.1.2\apiguardian-api-1.1.2.jar;C:\Users\126ah\.m2\repository\org\junit\jupiter\junit-jupiter-params\5.11.3\junit-jupiter-params-5.11.3.jar;C:\Users\126ah\.m2\repository\org\junit\jupiter\junit-jupiter-engine\5.11.3\junit-jupiter-engine-5.11.3.jar;C:\Users\126ah\.m2\repository\org\junit\platform\junit-platform-engine\1.11.3\junit-platform-engine-1.11.3.jar io.waypoint.bench.LuceneSearchMain indexes/msmarco-1m-lucene -k 10 -t manhattan project physics
  ```
- **lucene + SIMD + AppCDS**
  ```
  C:\Users\126ah\.jdks\jdk-25.0.4.1+1\bin\java.exe --add-modules jdk.incubator.vector -XX:SharedArchiveFile=target\coldstart-or\lucene-simd.jsa -cp C:\Users\126ah\Projects\waypoint-the-search-engine\bench\target\original-waypoint-bench.jar;C:\Users\126ah\.m2\repository\io\waypoint\waypoint-core\1.0.0-SNAPSHOT\waypoint-core-1.0.0-SNAPSHOT.jar;C:\Users\126ah\.m2\repository\org\apache\lucene\lucene-core\10.5.1\lucene-core-10.5.1.jar;C:\Users\126ah\.m2\repository\org\apache\lucene\lucene-analysis-common\10.5.1\lucene-analysis-common-10.5.1.jar;C:\Users\126ah\.m2\repository\org\openjdk\jmh\jmh-core\1.37\jmh-core-1.37.jar;C:\Users\126ah\.m2\repository\net\sf\jopt-simple\jopt-simple\5.0.4\jopt-simple-5.0.4.jar;C:\Users\126ah\.m2\repository\org\apache\commons\commons-math3\3.6.1\commons-math3-3.6.1.jar;C:\Users\126ah\.m2\repository\org\openjdk\jmh\jmh-generator-annprocess\1.37\jmh-generator-annprocess-1.37.jar;C:\Users\126ah\.m2\repository\org\junit\jupiter\junit-jupiter\5.11.3\junit-jupiter-5.11.3.jar;C:\Users\126ah\.m2\repository\org\junit\jupiter\junit-jupiter-api\5.11.3\junit-jupiter-api-5.11.3.jar;C:\Users\126ah\.m2\repository\org\opentest4j\opentest4j\1.3.0\opentest4j-1.3.0.jar;C:\Users\126ah\.m2\repository\org\junit\platform\junit-platform-commons\1.11.3\junit-platform-commons-1.11.3.jar;C:\Users\126ah\.m2\repository\org\apiguardian\apiguardian-api\1.1.2\apiguardian-api-1.1.2.jar;C:\Users\126ah\.m2\repository\org\junit\jupiter\junit-jupiter-params\5.11.3\junit-jupiter-params-5.11.3.jar;C:\Users\126ah\.m2\repository\org\junit\jupiter\junit-jupiter-engine\5.11.3\junit-jupiter-engine-5.11.3.jar;C:\Users\126ah\.m2\repository\org\junit\platform\junit-platform-engine\1.11.3\junit-platform-engine-1.11.3.jar io.waypoint.bench.LuceneSearchMain indexes/msmarco-1m-lucene -k 10 -t manhattan project physics
  ```
- **lucene + SIMD + AOT cache**
  ```
  C:\Users\126ah\.jdks\jdk-25.0.4.1+1\bin\java.exe --add-modules jdk.incubator.vector -XX:AOTCache=target\coldstart-or\lucene-simd.aot -cp C:\Users\126ah\Projects\waypoint-the-search-engine\bench\target\original-waypoint-bench.jar;C:\Users\126ah\.m2\repository\io\waypoint\waypoint-core\1.0.0-SNAPSHOT\waypoint-core-1.0.0-SNAPSHOT.jar;C:\Users\126ah\.m2\repository\org\apache\lucene\lucene-core\10.5.1\lucene-core-10.5.1.jar;C:\Users\126ah\.m2\repository\org\apache\lucene\lucene-analysis-common\10.5.1\lucene-analysis-common-10.5.1.jar;C:\Users\126ah\.m2\repository\org\openjdk\jmh\jmh-core\1.37\jmh-core-1.37.jar;C:\Users\126ah\.m2\repository\net\sf\jopt-simple\jopt-simple\5.0.4\jopt-simple-5.0.4.jar;C:\Users\126ah\.m2\repository\org\apache\commons\commons-math3\3.6.1\commons-math3-3.6.1.jar;C:\Users\126ah\.m2\repository\org\openjdk\jmh\jmh-generator-annprocess\1.37\jmh-generator-annprocess-1.37.jar;C:\Users\126ah\.m2\repository\org\junit\jupiter\junit-jupiter\5.11.3\junit-jupiter-5.11.3.jar;C:\Users\126ah\.m2\repository\org\junit\jupiter\junit-jupiter-api\5.11.3\junit-jupiter-api-5.11.3.jar;C:\Users\126ah\.m2\repository\org\opentest4j\opentest4j\1.3.0\opentest4j-1.3.0.jar;C:\Users\126ah\.m2\repository\org\junit\platform\junit-platform-commons\1.11.3\junit-platform-commons-1.11.3.jar;C:\Users\126ah\.m2\repository\org\apiguardian\apiguardian-api\1.1.2\apiguardian-api-1.1.2.jar;C:\Users\126ah\.m2\repository\org\junit\jupiter\junit-jupiter-params\5.11.3\junit-jupiter-params-5.11.3.jar;C:\Users\126ah\.m2\repository\org\junit\jupiter\junit-jupiter-engine\5.11.3\junit-jupiter-engine-5.11.3.jar;C:\Users\126ah\.m2\repository\org\junit\platform\junit-platform-engine\1.11.3\junit-platform-engine-1.11.3.jar io.waypoint.bench.LuceneSearchMain indexes/msmarco-1m-lucene -k 10 -t manhattan project physics
  ```
