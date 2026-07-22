# loci

A humane, Raskin-faithful interface for the agent age — *agent-as-verb, not agent-as-app*.
One substrate, one LEAP gesture, content stored once and **molded** on demand.

This repo is the Clojure build. (The earlier single-file interaction prototype is `index.html`.)

## Architecture (in dependency order)

| Layer | Role | record / recall |
|---|---|---|
| 1. Event log | deterministic, reversible (an immutable time-aware DB later: XTDB / Datahike) | **record** |
| 2. Content store | tables, documents, blobs — stored canonically, `datafy`/`nav`-able | **record** |
| 3. Recall / AI-memory | entities, relations, temporal, embeddings — `remember`/`recall` (Khora's role) | **recall** |
| 4. Mold layer | views molded per object, by user *or* agent (Clerk viewer registry) | both |
| 5. Shell | spaces + LEAP | — |

The **mold layer** (`loci.mold`) is built first and is intentionally UI-free: a viewer is
data (`{:id :label :pred :render}`), the registry is data, and a view's `:render` returns
plain Clojure data that any target (Clerk, Portal, a terminal) can draw.

The **recall layer** is stubbed behind the `loci.mold/Recall` protocol — swap in a Khora
(Python) sidecar or a Clojure-native engine later without touching callers.

## Run

```bash
# the loci shell — Clojure substrate served to a web frontend (layer 5)
clojure -M:serve        # then open http://localhost:7777

# headless proof of layers 1/2/4 — no browser
clojure -M:demo

# Clerk notebook (an alternative render target)
clojure -X:start        # starts Clerk + opens notebooks/loci.clj
```

The shell talks to the Clojure backend over a JSON API — the HTTP boundary is the
substrate/assistance seam. Molding is done server-side by `loci.mold`; the
frontend only lays out the result.

| endpoint | returns |
|---|---|
| `GET /` | the shell |
| `GET /api/state` | spaces + objects |
| `GET /api/object/:id` | an object's default mold + the "view this as…" menu |
| `GET /api/mold?id=&view=` | the object re-molded by a chosen viewer |
| `GET /api/leap?q=` | incremental find across content + view-verbs |
| `GET /api/undo` | revert the last substrate event |

## Layout

```
src/loci/substrate.clj   layer 1: append-only event log behind a Store protocol
src/loci/content.clj     layer 2: populated content + viewers; datafy/nav
src/loci/mold.clj        layer 4: viewer registry, mold, Recall protocol (UI-free)
src/loci/server.clj      layer 5 backend: substrate + mold served as JSON
resources/public/index.html   layer 5 frontend: spaces + LEAP shell
src/loci/demo.clj        headless walkthrough  (clojure -M:demo)
notebooks/loci.clj       Clerk render target (alternative to the shell)
```
