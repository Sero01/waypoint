# Lucene teardown — part 2: terms dict, query execution, indexing chain

## Terms dictionary — it is NOT an FST any more

Widely-repeated folklore says Lucene's term index is an FST. **False as of
10.x.** `CHANGES.txt:768` — *GITHUB#14333: Introduce a specialized trie
for block tree index, instead of FST. (Guo Feng)*

- `.tip` (term index) is now a purpose-built on-disk **trie**:
  `codecs/lucene103/blocktree/TrieBuilder.java` (702 lines) and
  `TrieReader.java`. `FieldReader.java:86-87` opens it via
  `new TrieReader(indexIn.slice(...), rootFP)`.
- Node encoding is 4 shapes — `SIGN_NO_CHILDREN`,
  `SIGN_SINGLE_CHILD_WITH_OUTPUT`, `SIGN_SINGLE_CHILD_WITHOUT_OUTPUT`,
  `SIGN_MULTI_CHILDREN` (`TrieBuilder.java`), with terms/floor flags
  packed into spare bits.
- `util/fst/` still exists but in core only `FSTCompiler` and `Util`
  import it — the terms dictionary no longer does.
- `.tim` blocks hold 25–48 terms (`DEFAULT_MIN_BLOCK_SIZE = 25`,
  `DEFAULT_MAX_BLOCK_SIZE = 48`, `Lucene103BlockTreeTermsWriter.java:217,223`).

> Lesson for the writeup: anything I assert about Lucene must come from
> this tag's source. My own prior belief here was a version stale.

## Query execution

`search/` contains ~20 scorer implementations. `BooleanScorerSupplier`
picks among them at query time (`BooleanScorerSupplier.java:118-350`) —
this dispatch is itself part of the generality:

- `MaxScoreBulkScorer` — BlockMax-MaxScore, used for `ScoreMode.TOP_SCORES`
  disjunctions (`:308`, `:344`).
- `BooleanScorer` — bitset-based bulk OR when matches are dense and
  scores are not needed competitively (`:197-215`, `:317`).
- `WANDScorer` — fallback supporting both impact pruning and
  `minShouldMatch > 1` (`:295-298`).
- Plus `BlockMaxConjunction{Bulk,}Scorer`, `DenseConjunctionBulkScorer`,
  `ConjunctionBulkScorer`, `DisjunctionMax*`, `ReqExclBulkScorer`.

`MaxScoreBulkScorer` is not a naive MaxScore. It:
- scores in windows of `INNER_WINDOW_SIZE = 1 << 12` (4096 docs)
  (`MaxScoreBulkScorer.java:28`),
- partitions scorers into **essential / non-essential** sets and
  re-partitions when the min competitive score rises enough to make a
  better split (`:36-47`, `:119-152`),
- buffers window hits in a `FixedBitSet` + `double[] windowScores`
  (`:50-51`) rather than scoring doc-at-a-time.

**Read: query latency on disjunctive top-k is the hardest axis to win.**
This is many engineer-years of tuning against exactly that number.

## Two behaviours that would silently rig a benchmark

**1. BM25 norms are lossy — 1 byte, 256 buckets.**
`search/similarities/BM25Similarity.java:112-116` builds
`LENGTH_TABLE[256]` from `SmallFloat.byte4ToInt`. Document length is
quantised to 256 values before scoring; `:183` precomputes
`cache[i] = 1f / (k1 * ((1-b) + b * LENGTH_TABLE[i] / avgdl))`.
`:221-231` then evaluates `weight - weight/(1 + freq*normInverse)`
rather than textbook `freq/(freq+norm)` — deliberately, to stay
monotonic in float without promoting to double, and because it is
slightly faster.

So **Lucene does not compute textbook BM25.** An engine using exact
document lengths will produce different scores and can produce a
different top-k on near-ties. The parity test must either replicate
`SmallFloat` quantisation exactly, or compare with a rank-agreement
metric and declare it. This is the single most likely way to
accidentally publish a wrong "we match Lucene" claim.

**2. Lucene stops counting total hits at 1000 by default.**
`search/IndexSearcher.java:106` — `TOTAL_HITS_THRESHOLD = 1000`, passed
into `TopScoreDocCollectorManager` (`:605`). `TopDocs.totalHits` is
therefore a *lower bound*, not a count. A from-scratch engine that
counts every match is doing strictly more work — that unfairness runs
*against* me, and must be matched.

## Indexing chain — the biggest surface

`IndexWriter.java` alone is **6,794 lines**; `IndexingChain.java` 2,288;
`DocumentsWriterPerThread.java` 842. Defaults that cost time
(`IndexWriterConfig.java`):

- `DEFAULT_RAM_BUFFER_SIZE_MB = 16.0` (`:83`) — a segment is flushed
  every 16 MB of buffered docs.
- `DEFAULT_MAX_BUFFERED_DOCS = DISABLE_AUTO_FLUSH` (`:78`) — RAM, not
  doc count, drives flushing.
- `DEFAULT_USE_COMPOUND_FILE_SYSTEM = true` (`:99`) — every flushed
  segment is then repacked into a `.cfs` bundle.
- `mergePolicy = new TieredMergePolicy()` (`LiveIndexWriterConfig.java:136`).

So indexing a large corpus with defaults = flush many segments, repack
each into a compound file, then **merge them repeatedly** — postings for
a given term are decoded and re-encoded once per merge level they
survive. A single-pass builder that buffers the whole index in memory
and writes once does none of that.

That is a real and explainable cost. It is also the easiest place to
cheat: comparing my in-RAM single-pass build against Lucene at a 16 MB
buffer is not a fair fight. Fair version = raise Lucene's RAM buffer,
disable compound files, `forceMerge(1)` or not — and report the
configuration.
