# waypoint-web

A React front end for `waypoint-service`.

## Running it

The service is the source of data; the front end never talks to `core`.

```sh
# terminal 1 — the API, on whatever port is free
cd ..
PORT=8080 java -jar service/target/waypoint-service.jar

# terminal 2 — the dev server
npm install
npm run dev                                   # proxies to localhost:8080
WAYPOINT_API=http://localhost:8081 npm run dev  # ...or wherever it is
```

`npm run build` writes into `service/src/main/resources/static`, so the built
app ships inside `waypoint-service.jar` and Spring Boot serves it from the
context root:

```sh
npm run build
cd .. && mvn -pl service -am package -DskipTests
java -jar service/target/waypoint-service.jar   # app and API on one port
```

| command | does |
|---|---|
| `npm run dev` | dev server on 5173, proxying `/search`, `/stats`, `/health` |
| `npm run build` | typecheck, then build into the service's static resources |
| `npm test` | the whole suite once |
| `npm run typecheck` | types only |

## Decisions worth knowing about

**No CORS, ever.** Development proxies through Vite and production is served
from the jar, so the browser only ever makes same-origin requests. Nothing in
the Java service had to change to accommodate a front end, and the fetch code
that runs against the dev proxy is the same code that runs in production.

**Results are ids and scores, because that is what the index holds.** Waypoint
stores document keys and BM25 scores and no document text, so there are no
snippets to render and none are faked. What the UI does instead is make the
parts that *are* real legible: the rank, the score as a fraction of the best on
screen, the operator, the match count, and the round trip.

**`totalHitsExact` is shown, not swallowed.** When MaxScore pruning stops a
disjunction before it has enumerated every match, the service reports the count
as inexact, exactly as Lucene's `TotalHits.Relation` does. The UI says "or
more" and shows a `lower bound` badge rather than presenting a lower bound as a
total.

**Phrase search is disabled when the index cannot do it.** `hasPositions` is
not exposed by `/stats`, and the only honest way to find out is to ask. On
startup the app issues one phrase query for two tokens no corpus will contain;
a 503 naming positions disables the control and explains why in its tooltip.
Anything else leaves it enabled.

**Errors are the service's own sentences.** The service distinguishes a query
it cannot parse (400, the caller's problem) from an index that is not there
(503, the operator's, and retryable) and writes both for a human to read. The
UI adds a heading saying whose problem it is and a retry button where retrying
could work, and otherwise passes the message through unedited.

**The URL is the state.** Query, operator and result count all live in the
query string, so searches are shareable and the back button walks through them.
Changing `k` replaces rather than pushes, so back steps through searches rather
than through control fiddling.

**Timings are browser round trips.** The number next to each result set is
`performance.now()` around `fetch`, which includes the network, Tomcat and JSON
serialisation. It is not the engine number; `results/` has those.

**React and nothing else at runtime.** `core` has no dependencies at all, and
the front end keeps to the spirit of it: no UI framework, no CSS framework, no
state or data-fetching library. One hand-written stylesheet, ~65 KB gzipped of
JavaScript.

## Layout

```
src/
  lib/api.ts        typed client; preserves the service's 400/503 distinction
  lib/urlState.ts   query string <-> search parameters
  lib/format.ts     numbers, bytes, durations, score bar fractions
  lib/useSearch.ts  one search, aborting whatever it supersedes
  components/       SearchForm, Results, Panels, IndexStats
  App.tsx           URL state, startup probe, composition
```

Tests sit next to what they test. `App.test.tsx` drives the whole app against a
stubbed service and covers the behaviours above — the lower-bound badge, the
phrase probe, the two error classes, URL round tripping.
