# Lucene teardown — part 3: which axis is actually winnable

Status: **hypotheses derived from source reading at `releases/lucene/10.5.1`.
Not yet measured.** Each carries the evidence and the honest counter-argument.

## Open-time cost (feeds the cold-start axis)

What Lucene does before it can answer one query:
1. list the directory, find the largest `segments_N` generation, parse
   `SegmentInfos` (`index/SegmentInfos.java:187-209`);
2. per segment, open the codec's files and validate each —
   `CodecUtil.checkIndexHeader` + `CodecUtil.retrieveChecksum` on `.doc`,
   `.pos`, `.pay` and the meta file
   (`Lucene104PostingsReader.java:87,110,113,138-160,178`);
3. build a `FieldReader`/`TrieReader` per field
   (`blocktree/FieldReader.java:86`);
4. `MMapDirectory` preloads **nothing** by default
   (`store/MMapDirectory.java:174`, `preload = NO_FILES`), so the first
   query pays the page faults.

Steps 2–3 are **per segment**. An index left at 20 segments pays that 20
times. A from-scratch engine with one flat mmap'd file pays it once.

## Ranked candidates

### 1. Index build throughput — most likely honest win
**Why it should win:** defaults flush a segment every 16 MB
(`IndexWriterConfig.java:83`), repack each into a compound file (`:99`),
then merge under `TieredMergePolicy` — postings are decoded and
re-encoded once per merge level they survive. A single-pass in-memory
builder writes each posting exactly once. Lucene also writes norms,
stored fields and field infos on the same pass.
**Counter-argument:** trivially riggable. Comparing an in-RAM single-pass
build against a 16 MB buffer is not a fair fight. Fair version raises
Lucene's RAM buffer, turns off compound files, and states whether
`forceMerge(1)` ran. Some of the gap is then just "I wrote less data",
which must be said out loud.
**Confidence the win survives a fair setup: moderate-high.**

### 2. Cold open + first-query latency — narrowest, most defensible
**Why it should win:** the open path above, versus one file open, one
header check, one root-offset read. Directly relevant to serverless and
short-lived containers, and it rhymes with `g0.coldstart` already on the
board.
**Counter-argument:** the absolute numbers may be single-digit
milliseconds on a small index, which is true but unexciting. It gets
interesting only at many segments or a large term dictionary — and
"Lucene is slow to open when you leave it at 20 segments" invites the
reply "so call forceMerge".
**Confidence: high that it wins; moderate that it impresses.**

### 3. Query latency, static index — hardest, highest prize
**Why it might win:** no live-docs `Bits` check per doc
(`search/Weight.java:297,311,334`), no per-leaf iteration
(`IndexSearcher.java:648`), no scorer-selection dispatch
(`BooleanScorerSupplier.java:118-350`), single segment so docids are
already global.
**Counter-argument — the strong one:** `MaxScoreBulkScorer` is windowed
BlockMax-MaxScore with essential/non-essential partitioning
(`MaxScoreBulkScorer.java:28-51,119-152`), and on a JVM started with
`--add-modules jdk.incubator.vector` the postings decode is SIMD
(`java21/.../MemorySegmentPostingDecodingUtil.java:41-81`). That is the
part of Lucene that has been tuned hardest for longest.
**Confidence: low.** Winnable, if at all, only on single-term or pure
conjunctive queries where pruning gives Lucene nothing.

### 4. Index size on disk — do not attempt
Bit-packed 256-doc blocks with patched frequency blocks, interleaved
2-level skip data, a compressed trie term index, prefix-coded term
blocks. Nothing cheap wins here.

## The flag question — must be decided, not defaulted

Lucene's SIMD decode requires `--add-modules jdk.incubator.vector`
(`VectorizationProvider.java:163-168`). Stock `java -jar` does not pass
it, so a careless benchmark measures Lucene's *scalar* path and
overstates any win.

Recommendation: run every benchmark **both ways** and publish both
columns. It costs one extra run and it is the single cheapest thing that
makes the whole writeup credible. The scalar-vs-SIMD delta is a
publishable number in its own right.

## Recommended shape

Pick **(1) build throughput as the headline**, with **(2) cold open** as
the supporting number, and report **(3) query latency as a loss** —
measured, conceded, and explained. A writeup that says "I beat it here,
it beats me there, here is exactly why" survives an interviewer.
One that only claims wins does not.
