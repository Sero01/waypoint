# Lucene teardown — part 1: storage & postings

Source: apache/lucene @ tag `releases/lucene/10.5.1`, shallow clone at
`C:\Users\126ah\Projects\lucene-study\lucene`.
Paths below are relative to `lucene/core/src/java/org/apache/lucene/`
unless marked `java21/` (the MR-JAR source set).

Toolchain: Lucene 10.x requires **Java 21+** (`lucene/SYSTEM_REQUIREMENTS.md`).
Temurin 21.0.12.1 installed at `~/.jdks/jdk-21.0.12.1+1`.

## Codec composition

`codecs/Codec.java:59` — default codec is resolved by SPI name lookup:
`LOADER.lookup("Lucene104")`. Registered in
`core/src/resources/META-INF/services/org.apache.lucene.codecs.Codec`.

`Lucene104Codec` is a *composition of eight independent formats*, each
separately pluggable (`codecs/lucene104/Lucene104Codec.java`):

| Format | Implementation | Needed for pure BM25 text search? |
|---|---|---|
| postings | `Lucene104PostingsFormat` (wrapped in `PerFieldPostingsFormat`) | **yes** |
| terms dict | `Lucene103BlockTreeTerms*` (inside postings) | **yes** |
| norms | `Lucene90NormsFormat` | yes (BM25 length norm) |
| stored fields | `Lucene90StoredFieldsFormat` | only to return the doc |
| doc values | `Lucene90DocValuesFormat` (per-field) | no |
| term vectors | `Lucene90TermVectorsFormat` | no |
| points | (BKD) | no |
| knn vectors | `Lucene99HnswVectorsFormat` | no |
| field infos | `Lucene94FieldInfosFormat` | bookkeeping |
| segment info | `Lucene99SegmentInfoFormat` | bookkeeping |
| live docs | `Lucene90LiveDocsFormat` | **only because deletes exist** |
| compound | `Lucene90CompoundFormat` | packaging |

> First candidate tax: a from-scratch engine that indexes text only,
> never deletes, and never updates can drop **live docs, doc values,
> term vectors, points, knn vectors, and the compound file layer**
> outright. Whether that is a *measurable* win is the open question.

## Postings format (`codecs/lucene104/Lucene104PostingsFormat.java`)

Files: `.tim` (term dict), `.tip` (term index), `.doc` (docids+freqs+skip),
`.pos` (positions), `.pay` (payloads/offsets).

- Block size is **256**, not 128 (`ForUtil.java:33`, `BLOCK_SIZE = 256`).
  Doc deltas are d-gapped then bit-packed at uniform width per block;
  freq blocks use **patching** (PFor), doc-delta blocks do not
  (`Lucene104PostingsFormat.java` format javadoc).
- Tail beyond the last full block is written as a **VInt block**.
- **Skip data is interleaved with the postings, not a separate file**, on
  two levels: level 0 between every 256-doc block, level 1 between every
  32 blocks (`LEVEL1_NUM_DOCS = LEVEL1_FACTOR * BLOCK_SIZE`,
  `Lucene104PostingsFormat.java:351`) = every 8192 docs.
- Level 0/1 skip records carry **impacts** (competitive score bounds),
  which is what makes BlockMax-WAND possible.
- `SingletonDocID`: a term appearing in exactly one doc stores the docid
  inline in the term dictionary and writes nothing to `.doc`.
- `Lucene104PostingsReader.java:561` — postings shorter than
  `LEVEL1_NUM_DOCS` skip level-1 machinery entirely.

## SIMD decode is behind an incubator flag  ← key finding

Lucene core is a **multi-release JAR**: `core/src/java21/` overrides
`core/src/java/` on JDK 21+.

`java21/.../MemorySegmentPostingDecodingUtil.java:22-24` imports
`jdk.incubator.vector.{IntVector,VectorOperators,VectorSpecies}` and
decodes bit-packed postings with `IntVector.fromMemorySegment(...)` +
`lanewise(LSHR/AND)` — i.e. **real SIMD shift/mask unpacking**
(lines 41-81).

But `internal/vectorization/VectorizationProvider.java:147-196` gates it.
It falls back to the scalar `DefaultPostingDecodingUtil` unless ALL hold:

1. HotSpot VM (`:151-154` — rejects other VMs)
2. not JVMCI (`:157-160`)
3. **`jdk.incubator.vector` module present and readable** — otherwise it
   logs: *"Java vector incubator module is not readable. For optimal
   vector performance, pass `--add-modules jdk.incubator.vector` to
   enable Vector API."* (`:163-168`)
4. C2 enabled (`:174-180`)

**Implication for the benchmark.** A stock `java -jar` run does *not*
pass `--add-modules jdk.incubator.vector`. So out of the box, Lucene's
postings decode is the **scalar** path. This creates a fork the benchmark
must declare explicitly, not stumble into:

- Benchmarking against default-JVM Lucene = benchmarking its scalar
  decode. Beating that and calling it "beat Lucene" is the rigged-
  workload failure mode.
- Benchmarking with `--add-modules jdk.incubator.vector` = the honest
  ceiling, and the number Lucene's own committers quote.

Correct answer: **report both**, and state the flag in the README. The
gap between the two is itself a publishable number and costs one extra
benchmark run.
