# Warm query latency (JMH)

1,000,000 MS MARCO passages, k=10, JDK 25.0.4.1, `Mode.SampleTime`,
2 forks x (3 x 2 s warm-up + 3 x 2 s measurement), microseconds per operation.

**Lucene has `--add-modules jdk.incubator.vector` on**, so these are Lucene's
vectorised postings decoder against Waypoint's scalar varint scan. Both engines
return identical top-10 documents for every query here; only the work differs.

Regenerate with:

```sh
java -cp bench/target/waypoint-bench.jar io.waypoint.bench.BenchMain latency \
  --wp-index indexes/msmarco-1m.wpt --lucene-index indexes/msmarco-1m-lucene \
  -f 2 -wi 3 -i 3 -r 2s -w 2s -rf json -rff results/latency.json
java scripts/Jmh.java results/latency.json
```

| query shape | matches | waypoint p50 | lucene p50 | |
|---|---:|---:|---:|---|
| rare term `manhattan` (df 555) | 555 | **18.6 us** | 26.7 us | waypoint 1.4x |
| disjunction `manhattan project physics` | 8,590 | **408.6 us** | 515.1 us | waypoint 1.3x |
| common term `the` (df 866,624) | 866,624 | 1,959.9 us | **901.1 us** | **lucene 2.2x** |
| conjunction `blood pressure medication` | 276 | 1,144.8 us | **401.2 us** | **lucene 2.9x** |
| conjunction `the AND manhattan` | 549 | 845.8 us | **333.3 us** | **lucene 2.5x** |
| reopen the index, warm JVM | — | **369.2 us** | 4,599.8 us | waypoint 12.5x |

Tails:

| benchmark | p50 | p95 | p99 |
|---|---:|---:|---:|
| waypointRareTerm | 18.6 | 40.2 | 73.0 |
| luceneRareTerm | 26.7 | 64.4 | 111.9 |
| waypointDisjunction | 408.6 | 839.7 | 1165.3 |
| luceneDisjunction | 515.1 | 1311.7 | 2191.0 |
| waypointCommonTerm | 1959.9 | 3526.7 | 4383.4 |
| luceneCommonTerm | 901.1 | 2191.4 | 3453.0 |
| waypointConjunction | 1144.8 | 2064.4 | 2670.6 |
| luceneConjunction | 401.2 | 968.2 | 1632.9 |
| waypointCommonConjunction | 845.8 | 1611.8 | 2130.4 |
| luceneCommonConjunction | 333.3 | 891.9 | 1431.6 |
| waypointReopen | 369.2 | 768.0 | 1151.0 |
| luceneReopen | 4599.8 | 8656.5 | 11123.8 |

## What changed, and by how much

The first published version of this table had no pruning and no skip data at
all. Against that version, measured on the same machine and the same corpus:

| query shape | before | after | |
|---|---:|---:|---|
| common term `the` | 27,525 us | **1,959.9 us** | **14.0x faster** |
| disjunction | 662.5 us | **408.6 us** | 1.6x faster |
| conjunction | 1,413.1 us | **1,144.8 us** | 1.2x faster |
| rare term | 15.5 us | 18.6 us | 1.2x **slower** |
| reopen | 342.5 us | 369.2 us | within noise |

Against Lucene, run for run, the common-term gap went from **25.6x to 2.2x**.

Lucene's own absolute numbers move between runs on this machine — its
conjunction measured 510 us in the earlier run and 401 us here — so only ratios
measured inside a single run are worth quoting. Every ratio in the first table
above comes from one run.

The rare-term row got slightly worse, and that is the honest cost of the format
change: a 555-document postings list is now four blocks with headers instead of
one flat run, and reading four headers to skip nothing is pure overhead. Three
microseconds to buy fourteen times on the common term is a trade worth making,
but it is a trade.

## Reading this

**The common-term row was the whole problem, and it is largely fixed.** `the`
matches 87% of the corpus. Each postings block now carries its pareto frontier
of `(freq, norm)` pairs, so once the heap is full a block whose best possible
document still loses is skipped without decoding a single posting. That is the
same mechanism as Lucene's `MaxScoreBulkScorer`, implemented more simply and
with a looser bound, and it closes most of the gap rather than all of it.

The remaining 2.2x is real and structural. Lucene's impacts are a proper pareto
frontier per block *with* two-level skip data above them, and its postings
decode through a vectorised path. Waypoint walks a linked chain of block
headers and decodes scalar varints.

**Conjunctions are now the worst shape at 2.5–2.9x**, and the reason is
visible in the format. `Cursor.advance` skips whole blocks, but to find the
block holding a target it walks every block header from where it is: there is
no second-level skip index, so a term with 866,624 postings means walking 6,771
headers scattered across the file. Lucene jumps. Adding a sparse skip table over
blocks is the obvious next change and is not done.

Two conjunction shapes are measured on purpose. `blood pressure medication` has
no clause long enough for block skipping to matter — its rarest term drives the
leapfrog over 5,276 documents and the others are walked almost entirely
regardless — so it barely moved. `the AND manhattan` is the shape where
skipping pays, and it is 1.9x faster than it was before the mid-block skip fix.
Reporting only the first would have said skip data does nothing, which is true
of that query and false in general.

**Disjunctions now win.** MaxScore orders clauses by the upper bound each can
contribute and stops the cheap ones from driving the merge once they cannot
place a document in the top k. That is the one place the exact match count is
given up: `search` returns a lower bound and says so through
`totalHitsExact()`, and `Index.count` recovers the true number on demand
without any of the scoring.

The reopen row is the cold-start mechanism with JVM startup subtracted:
`DirectoryReader.open` reads segment metadata, resolves codecs through
`ServiceLoader` and constructs the per-format reader stack; `Index.open` is one
`map()` and a 128-byte header read. Adding skip data, impacts and an optional
positions section did not change it, because none of those sections is touched
on open.
