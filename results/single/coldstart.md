# Cold start: time to first result

Query: `manhattan` (OR), k=10, 12 forked JVMs per configuration after 3 discarded warm-up runs.

`java.version` 25.0.4.1 / OpenJDK 64-Bit Server VM 25.0.4.1+1-LTS

`os` Windows 11 10.0 amd64, 8 available processors, 7918 MB physical memory (2112 MB free at the start of this run)

`floor` 90.2 ms on the waypoint classpath, 117.2 ms on the lucene classpath -- JVM startup that both engines pay and neither can avoid.

| configuration | external p50 (ms) | p5 | p95 | above floor (ms) | in-process p50 (ms) |
|---|---:|---:|---:|---:|---:|
| (floor) jvm + waypoint classpath | 90.2 | 87.4 | 97.0 | - | - |
| (floor) jvm + lucene classpath | 117.2 | 114.4 | 124.8 | - | - |
| waypoint | 116.8 | 112.8 | 124.0 | 26.5 | 29.1 |
| waypoint + AppCDS | 101.6 | 98.2 | 105.2 | 11.4 | 13.0 |
| waypoint + AOT cache | 96.3 | 92.4 | 106.8 | 6.1 | 13.8 |
| lucene (scalar) | 379.9 | 372.8 | 441.2 | 262.7 | 271.9 |
| lucene (scalar) + AppCDS | 226.0 | 220.2 | 262.0 | 108.8 | 126.0 |
| lucene (scalar) + AOT cache | 191.8 | 186.6 | 236.5 | 74.7 | 93.5 |
| lucene + SIMD | 448.2 | 442.4 | 512.0 | 331.1 | 318.6 |
| lucene + SIMD + AppCDS | 263.2 | 252.8 | 279.0 | 146.0 | 135.2 |
| lucene + SIMD + AOT cache | 302.7 | 295.5 | 355.9 | 185.5 | 195.5 |

## Result

**Stock `java -cp`, which is what almost everyone runs: 116.8 ms vs 379.9 ms (lucene (scalar)) = **3.3x**.

**Both engines tuned as hard as JDK 25 allows: waypoint + AOT cache 96.3 ms vs lucene (scalar) + AOT cache 191.8 ms = 2.0x.**

Pre-registered kill criterion (design doc section 3): with the AOT cache enabled for **both** engines and Lucene given a `forceMerge(1)` single-segment index, the external median must be at least **3x** better or the headline claim is withdrawn. Measured: **2.0x** -- **not met**.

Discounting JVM startup, which is a floor neither engine controls, the work actually attributable to the engine is 6.1 ms vs 74.7 ms = **12.3x**.

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
  C:\Users\126ah\.jdks\jdk-25.0.4.1+1\bin\java.exe -cp C:\Users\126ah\Projects\waypoint-the-search-engine\cli\target\waypoint-cli.jar io.waypoint.cli.Main search indexes/msmarco-1m.wpt -k 10 -t manhattan
  ```
- **waypoint + AppCDS**
  ```
  C:\Users\126ah\.jdks\jdk-25.0.4.1+1\bin\java.exe -XX:SharedArchiveFile=target\coldstart-single\waypoint.jsa -cp C:\Users\126ah\Projects\waypoint-the-search-engine\cli\target\waypoint-cli.jar io.waypoint.cli.Main search indexes/msmarco-1m.wpt -k 10 -t manhattan
  ```
- **waypoint + AOT cache**
  ```
  C:\Users\126ah\.jdks\jdk-25.0.4.1+1\bin\java.exe -XX:AOTCache=target\coldstart-single\waypoint.aot -cp C:\Users\126ah\Projects\waypoint-the-search-engine\cli\target\waypoint-cli.jar io.waypoint.cli.Main search indexes/msmarco-1m.wpt -k 10 -t manhattan
  ```
- **lucene (scalar)**
  ```
  C:\Users\126ah\.jdks\jdk-25.0.4.1+1\bin\java.exe -cp C:\Users\126ah\Projects\waypoint-the-search-engine\bench\target\original-waypoint-bench.jar;C:\Users\126ah\.m2\repository\io\waypoint\waypoint-core\1.0.0-SNAPSHOT\waypoint-core-1.0.0-SNAPSHOT.jar;C:\Users\126ah\.m2\repository\org\apache\lucene\lucene-core\10.5.1\lucene-core-10.5.1.jar;C:\Users\126ah\.m2\repository\org\apache\lucene\lucene-analysis-common\10.5.1\lucene-analysis-common-10.5.1.jar;C:\Users\126ah\.m2\repository\org\openjdk\jmh\jmh-core\1.37\jmh-core-1.37.jar;C:\Users\126ah\.m2\repository\net\sf\jopt-simple\jopt-simple\5.0.4\jopt-simple-5.0.4.jar;C:\Users\126ah\.m2\repository\org\apache\commons\commons-math3\3.6.1\commons-math3-3.6.1.jar;C:\Users\126ah\.m2\repository\org\openjdk\jmh\jmh-generator-annprocess\1.37\jmh-generator-annprocess-1.37.jar;C:\Users\126ah\.m2\repository\org\junit\jupiter\junit-jupiter\5.11.3\junit-jupiter-5.11.3.jar;C:\Users\126ah\.m2\repository\org\junit\jupiter\junit-jupiter-api\5.11.3\junit-jupiter-api-5.11.3.jar;C:\Users\126ah\.m2\repository\org\opentest4j\opentest4j\1.3.0\opentest4j-1.3.0.jar;C:\Users\126ah\.m2\repository\org\junit\platform\junit-platform-commons\1.11.3\junit-platform-commons-1.11.3.jar;C:\Users\126ah\.m2\repository\org\apiguardian\apiguardian-api\1.1.2\apiguardian-api-1.1.2.jar;C:\Users\126ah\.m2\repository\org\junit\jupiter\junit-jupiter-params\5.11.3\junit-jupiter-params-5.11.3.jar;C:\Users\126ah\.m2\repository\org\junit\jupiter\junit-jupiter-engine\5.11.3\junit-jupiter-engine-5.11.3.jar;C:\Users\126ah\.m2\repository\org\junit\platform\junit-platform-engine\1.11.3\junit-platform-engine-1.11.3.jar io.waypoint.bench.LuceneSearchMain indexes/msmarco-1m-lucene -k 10 -t manhattan
  ```
- **lucene (scalar) + AppCDS**
  ```
  C:\Users\126ah\.jdks\jdk-25.0.4.1+1\bin\java.exe -XX:SharedArchiveFile=target\coldstart-single\lucene-scalar.jsa -cp C:\Users\126ah\Projects\waypoint-the-search-engine\bench\target\original-waypoint-bench.jar;C:\Users\126ah\.m2\repository\io\waypoint\waypoint-core\1.0.0-SNAPSHOT\waypoint-core-1.0.0-SNAPSHOT.jar;C:\Users\126ah\.m2\repository\org\apache\lucene\lucene-core\10.5.1\lucene-core-10.5.1.jar;C:\Users\126ah\.m2\repository\org\apache\lucene\lucene-analysis-common\10.5.1\lucene-analysis-common-10.5.1.jar;C:\Users\126ah\.m2\repository\org\openjdk\jmh\jmh-core\1.37\jmh-core-1.37.jar;C:\Users\126ah\.m2\repository\net\sf\jopt-simple\jopt-simple\5.0.4\jopt-simple-5.0.4.jar;C:\Users\126ah\.m2\repository\org\apache\commons\commons-math3\3.6.1\commons-math3-3.6.1.jar;C:\Users\126ah\.m2\repository\org\openjdk\jmh\jmh-generator-annprocess\1.37\jmh-generator-annprocess-1.37.jar;C:\Users\126ah\.m2\repository\org\junit\jupiter\junit-jupiter\5.11.3\junit-jupiter-5.11.3.jar;C:\Users\126ah\.m2\repository\org\junit\jupiter\junit-jupiter-api\5.11.3\junit-jupiter-api-5.11.3.jar;C:\Users\126ah\.m2\repository\org\opentest4j\opentest4j\1.3.0\opentest4j-1.3.0.jar;C:\Users\126ah\.m2\repository\org\junit\platform\junit-platform-commons\1.11.3\junit-platform-commons-1.11.3.jar;C:\Users\126ah\.m2\repository\org\apiguardian\apiguardian-api\1.1.2\apiguardian-api-1.1.2.jar;C:\Users\126ah\.m2\repository\org\junit\jupiter\junit-jupiter-params\5.11.3\junit-jupiter-params-5.11.3.jar;C:\Users\126ah\.m2\repository\org\junit\jupiter\junit-jupiter-engine\5.11.3\junit-jupiter-engine-5.11.3.jar;C:\Users\126ah\.m2\repository\org\junit\platform\junit-platform-engine\1.11.3\junit-platform-engine-1.11.3.jar io.waypoint.bench.LuceneSearchMain indexes/msmarco-1m-lucene -k 10 -t manhattan
  ```
- **lucene (scalar) + AOT cache**
  ```
  C:\Users\126ah\.jdks\jdk-25.0.4.1+1\bin\java.exe -XX:AOTCache=target\coldstart-single\lucene-scalar.aot -cp C:\Users\126ah\Projects\waypoint-the-search-engine\bench\target\original-waypoint-bench.jar;C:\Users\126ah\.m2\repository\io\waypoint\waypoint-core\1.0.0-SNAPSHOT\waypoint-core-1.0.0-SNAPSHOT.jar;C:\Users\126ah\.m2\repository\org\apache\lucene\lucene-core\10.5.1\lucene-core-10.5.1.jar;C:\Users\126ah\.m2\repository\org\apache\lucene\lucene-analysis-common\10.5.1\lucene-analysis-common-10.5.1.jar;C:\Users\126ah\.m2\repository\org\openjdk\jmh\jmh-core\1.37\jmh-core-1.37.jar;C:\Users\126ah\.m2\repository\net\sf\jopt-simple\jopt-simple\5.0.4\jopt-simple-5.0.4.jar;C:\Users\126ah\.m2\repository\org\apache\commons\commons-math3\3.6.1\commons-math3-3.6.1.jar;C:\Users\126ah\.m2\repository\org\openjdk\jmh\jmh-generator-annprocess\1.37\jmh-generator-annprocess-1.37.jar;C:\Users\126ah\.m2\repository\org\junit\jupiter\junit-jupiter\5.11.3\junit-jupiter-5.11.3.jar;C:\Users\126ah\.m2\repository\org\junit\jupiter\junit-jupiter-api\5.11.3\junit-jupiter-api-5.11.3.jar;C:\Users\126ah\.m2\repository\org\opentest4j\opentest4j\1.3.0\opentest4j-1.3.0.jar;C:\Users\126ah\.m2\repository\org\junit\platform\junit-platform-commons\1.11.3\junit-platform-commons-1.11.3.jar;C:\Users\126ah\.m2\repository\org\apiguardian\apiguardian-api\1.1.2\apiguardian-api-1.1.2.jar;C:\Users\126ah\.m2\repository\org\junit\jupiter\junit-jupiter-params\5.11.3\junit-jupiter-params-5.11.3.jar;C:\Users\126ah\.m2\repository\org\junit\jupiter\junit-jupiter-engine\5.11.3\junit-jupiter-engine-5.11.3.jar;C:\Users\126ah\.m2\repository\org\junit\platform\junit-platform-engine\1.11.3\junit-platform-engine-1.11.3.jar io.waypoint.bench.LuceneSearchMain indexes/msmarco-1m-lucene -k 10 -t manhattan
  ```
- **lucene + SIMD**
  ```
  C:\Users\126ah\.jdks\jdk-25.0.4.1+1\bin\java.exe --add-modules jdk.incubator.vector -cp C:\Users\126ah\Projects\waypoint-the-search-engine\bench\target\original-waypoint-bench.jar;C:\Users\126ah\.m2\repository\io\waypoint\waypoint-core\1.0.0-SNAPSHOT\waypoint-core-1.0.0-SNAPSHOT.jar;C:\Users\126ah\.m2\repository\org\apache\lucene\lucene-core\10.5.1\lucene-core-10.5.1.jar;C:\Users\126ah\.m2\repository\org\apache\lucene\lucene-analysis-common\10.5.1\lucene-analysis-common-10.5.1.jar;C:\Users\126ah\.m2\repository\org\openjdk\jmh\jmh-core\1.37\jmh-core-1.37.jar;C:\Users\126ah\.m2\repository\net\sf\jopt-simple\jopt-simple\5.0.4\jopt-simple-5.0.4.jar;C:\Users\126ah\.m2\repository\org\apache\commons\commons-math3\3.6.1\commons-math3-3.6.1.jar;C:\Users\126ah\.m2\repository\org\openjdk\jmh\jmh-generator-annprocess\1.37\jmh-generator-annprocess-1.37.jar;C:\Users\126ah\.m2\repository\org\junit\jupiter\junit-jupiter\5.11.3\junit-jupiter-5.11.3.jar;C:\Users\126ah\.m2\repository\org\junit\jupiter\junit-jupiter-api\5.11.3\junit-jupiter-api-5.11.3.jar;C:\Users\126ah\.m2\repository\org\opentest4j\opentest4j\1.3.0\opentest4j-1.3.0.jar;C:\Users\126ah\.m2\repository\org\junit\platform\junit-platform-commons\1.11.3\junit-platform-commons-1.11.3.jar;C:\Users\126ah\.m2\repository\org\apiguardian\apiguardian-api\1.1.2\apiguardian-api-1.1.2.jar;C:\Users\126ah\.m2\repository\org\junit\jupiter\junit-jupiter-params\5.11.3\junit-jupiter-params-5.11.3.jar;C:\Users\126ah\.m2\repository\org\junit\jupiter\junit-jupiter-engine\5.11.3\junit-jupiter-engine-5.11.3.jar;C:\Users\126ah\.m2\repository\org\junit\platform\junit-platform-engine\1.11.3\junit-platform-engine-1.11.3.jar io.waypoint.bench.LuceneSearchMain indexes/msmarco-1m-lucene -k 10 -t manhattan
  ```
- **lucene + SIMD + AppCDS**
  ```
  C:\Users\126ah\.jdks\jdk-25.0.4.1+1\bin\java.exe --add-modules jdk.incubator.vector -XX:SharedArchiveFile=target\coldstart-single\lucene-simd.jsa -cp C:\Users\126ah\Projects\waypoint-the-search-engine\bench\target\original-waypoint-bench.jar;C:\Users\126ah\.m2\repository\io\waypoint\waypoint-core\1.0.0-SNAPSHOT\waypoint-core-1.0.0-SNAPSHOT.jar;C:\Users\126ah\.m2\repository\org\apache\lucene\lucene-core\10.5.1\lucene-core-10.5.1.jar;C:\Users\126ah\.m2\repository\org\apache\lucene\lucene-analysis-common\10.5.1\lucene-analysis-common-10.5.1.jar;C:\Users\126ah\.m2\repository\org\openjdk\jmh\jmh-core\1.37\jmh-core-1.37.jar;C:\Users\126ah\.m2\repository\net\sf\jopt-simple\jopt-simple\5.0.4\jopt-simple-5.0.4.jar;C:\Users\126ah\.m2\repository\org\apache\commons\commons-math3\3.6.1\commons-math3-3.6.1.jar;C:\Users\126ah\.m2\repository\org\openjdk\jmh\jmh-generator-annprocess\1.37\jmh-generator-annprocess-1.37.jar;C:\Users\126ah\.m2\repository\org\junit\jupiter\junit-jupiter\5.11.3\junit-jupiter-5.11.3.jar;C:\Users\126ah\.m2\repository\org\junit\jupiter\junit-jupiter-api\5.11.3\junit-jupiter-api-5.11.3.jar;C:\Users\126ah\.m2\repository\org\opentest4j\opentest4j\1.3.0\opentest4j-1.3.0.jar;C:\Users\126ah\.m2\repository\org\junit\platform\junit-platform-commons\1.11.3\junit-platform-commons-1.11.3.jar;C:\Users\126ah\.m2\repository\org\apiguardian\apiguardian-api\1.1.2\apiguardian-api-1.1.2.jar;C:\Users\126ah\.m2\repository\org\junit\jupiter\junit-jupiter-params\5.11.3\junit-jupiter-params-5.11.3.jar;C:\Users\126ah\.m2\repository\org\junit\jupiter\junit-jupiter-engine\5.11.3\junit-jupiter-engine-5.11.3.jar;C:\Users\126ah\.m2\repository\org\junit\platform\junit-platform-engine\1.11.3\junit-platform-engine-1.11.3.jar io.waypoint.bench.LuceneSearchMain indexes/msmarco-1m-lucene -k 10 -t manhattan
  ```
- **lucene + SIMD + AOT cache**
  ```
  C:\Users\126ah\.jdks\jdk-25.0.4.1+1\bin\java.exe --add-modules jdk.incubator.vector -XX:AOTCache=target\coldstart-single\lucene-simd.aot -cp C:\Users\126ah\Projects\waypoint-the-search-engine\bench\target\original-waypoint-bench.jar;C:\Users\126ah\.m2\repository\io\waypoint\waypoint-core\1.0.0-SNAPSHOT\waypoint-core-1.0.0-SNAPSHOT.jar;C:\Users\126ah\.m2\repository\org\apache\lucene\lucene-core\10.5.1\lucene-core-10.5.1.jar;C:\Users\126ah\.m2\repository\org\apache\lucene\lucene-analysis-common\10.5.1\lucene-analysis-common-10.5.1.jar;C:\Users\126ah\.m2\repository\org\openjdk\jmh\jmh-core\1.37\jmh-core-1.37.jar;C:\Users\126ah\.m2\repository\net\sf\jopt-simple\jopt-simple\5.0.4\jopt-simple-5.0.4.jar;C:\Users\126ah\.m2\repository\org\apache\commons\commons-math3\3.6.1\commons-math3-3.6.1.jar;C:\Users\126ah\.m2\repository\org\openjdk\jmh\jmh-generator-annprocess\1.37\jmh-generator-annprocess-1.37.jar;C:\Users\126ah\.m2\repository\org\junit\jupiter\junit-jupiter\5.11.3\junit-jupiter-5.11.3.jar;C:\Users\126ah\.m2\repository\org\junit\jupiter\junit-jupiter-api\5.11.3\junit-jupiter-api-5.11.3.jar;C:\Users\126ah\.m2\repository\org\opentest4j\opentest4j\1.3.0\opentest4j-1.3.0.jar;C:\Users\126ah\.m2\repository\org\junit\platform\junit-platform-commons\1.11.3\junit-platform-commons-1.11.3.jar;C:\Users\126ah\.m2\repository\org\apiguardian\apiguardian-api\1.1.2\apiguardian-api-1.1.2.jar;C:\Users\126ah\.m2\repository\org\junit\jupiter\junit-jupiter-params\5.11.3\junit-jupiter-params-5.11.3.jar;C:\Users\126ah\.m2\repository\org\junit\jupiter\junit-jupiter-engine\5.11.3\junit-jupiter-engine-5.11.3.jar;C:\Users\126ah\.m2\repository\org\junit\platform\junit-platform-engine\1.11.3\junit-platform-engine-1.11.3.jar io.waypoint.bench.LuceneSearchMain indexes/msmarco-1m-lucene -k 10 -t manhattan
  ```
