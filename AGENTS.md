# AGENTS.md — Rama Application Design Guide

Design Rama modules in this order. Each decision constrains the next.

---

## 1. What Rama Replaces

Rama consolidates infrastructure into one deployable unit:

- Message queue → depot
- Stream processor → ETL topology
- Database → PState
- Query layer → query topology
- Cache → PState with subindexed maps

If you still need external infrastructure for these roles, reassess the design.

---

## 2. Core Design Questions

### Data model

- Pick a high‑cardinality, evenly distributed partition key. Avoid timestamps, status, country codes.
- Design PState schemas query‑first. Use nested maps, not flat tables.
- Mark unbounded inner maps with `{:subindex? true}`.

### Processing

- Exactly‑once or money/inventory → microbatch.
- Low latency, at‑least‑once → stream.
- Aggregations across multiple records → `<<batch` + `defgenerator`.
- Cross‑partition writes require `|hash` jumps. Too many jumps means the key is wrong.
- Global state must be genuinely singular and use `{:global? true}` + `|global`.
- Small, frequently read shared data → `declare-object` with `TaskGlobalObject`.

### Queries

- If direct `foreign-select-one` can answer it, skip query topology.
- Distributed aggregation across tasks → query topology with `|hash` then `|origin`.

---

## 3. Components (Quick Reference)

### Depot

```clojure
(declare-depot setup *name (hash-by :partition-key))
```

- One depot per event type.
- Partition key should match the primary PState access pattern.

### PState

```clojure
(declare-pstate topology $$name schema)
(declare-pstate topology $$name schema {:global? true})
```

- Declare on `mb` or `s`, never on `setup`.
- Top-level key matches the depot partition key.
- Use `fixed-keys-schema` for fixed records, `map-schema` for dynamic keys.

### Stream topology

```clojure
(let [s (stream-topology topologies "name")] ...)
```

- ~1ms latency, at‑least‑once.
- Supports `ack-return>`.

### Microbatch topology

```clojure
(let [mb (microbatch-topology topologies "name")] ...)
```

- ~100ms latency, exactly‑once.
- Use `wait-for-microbatch-processed-count` in tests.

### Query topology

```clojure
(<<query-topology topologies "name"
  [*input :> *output]
  (|hash *input)
  (local-select> [...] $$p :> *val)
  (|origin)
  (agg-fn *val :> *output))
```

### Task global object

```clojure
(declare-object setup *name (MyTaskGlobal. initial-val))
```

- Use for config, clients, caches.
- Never for durable data.

---

## 4. ETL Operators (Inside `<<sources`)

| Operator | Purpose |
|---|---|
| `(source> *depot :> %mb)` | Subscribe to depot |
| `(%mb :> {:keys [*a *b]})` | Emit records |
| `(local-select> [path] $$p :> *v)` | Read local PState |
| `(local-transform> [path] $$p)` | Write local PState |
| `(+compound $$p {k (agg v)})` | Aggregation write |
| `(|hash *var)` | Route to partition |
| `(|global)` | Route to global task |
| `(<<if cond branch)` | Conditional |
| `(<<ramafn %name [args] body)` | Inline op |
| `(ack-return> *val)` | Stream ack return |

---

## 5. Path Navigators

| Navigator | Purpose |
|---|---|
| `(keypath k)` | Navigate into map |
| `(nil->val x)` | Treat nil as x |
| `(termval v)` | Set literal |
| `(term fn)` | Transform value |
| `(multi-path p1 p2)` | Atomic multi-write |
| `MAP-VALS` | All values |
| `ALL` | All [k v] |
| `STAY` | Identity |
| `FIRST` | First element |
| `(sorted-map-range s e)` | Range on subindex |

---

## 6. Batch Blocks

Rules:

- Always declare a partitioner (`|hash` or `|global`) before aggregation.
- Use `:new-val>` when downstream logic needs the updated value.

---

## 7. Design Checklist

1. Identify entities and partition keys.
2. Design PState schemas for required queries.
3. Identify depots (one per event type).
4. Map cross‑partition writes and `|hash` jumps.
5. Choose topology type (stream vs microbatch).
6. Mark batch computations (`<<batch` + `defgenerator`).
7. Identify query topologies.
8. Identify task globals.

---

## 8. Naming Conventions

| Symbol | Meaning | Example |
|---|---|---|
| `*name` | Depot or dataflow var | `*transfer-depot` |
| `$$name` | PState | `$$funds` |
| `%name` | Anonymous op | `%microbatch` |
| `<<name` | Block macro | `<<batch` |
| `\|name` | Partitioner | `\|hash` |
| `:>` | Output binding | `(op :> *v)` |
| `"name"` | Topology name | `"banking"` |

Inside topology code, use sigils. Outside the module, use quoted strings like `"*transfer-depot"` and `"$$funds"`.

---

## 9. Testing Checklist

Pattern:

```clojure
(with-open [ipc (rtest/create-ipc)]
  (rtest/launch-module! ipc MyModule {:tasks 4 :threads 2})
  (let [my-depot  (foreign-depot ipc module-name "*my-depot")
        my-pstate (foreign-pstate ipc module-name "$$my-pstate")]
    (foreign-append! my-depot record)
    (rtest/wait-for-microbatch-processed-count ipc module-name "topo-name" N)
    (is (= expected (foreign-select-one (keypath k) my-pstate)))))
```

Checklist items:

- Success and failure paths for each conditional branch.
- Idempotency by appending the same record twice.
- Cross‑partition writes verified on both sides.
- Use `with-redefs` for external calls in integration tests.

---

## 10. Common Mistakes

- Low‑cardinality partition key.
- Schema designed for writes, not queries.
- Missing `{:subindex? true}` on unbounded maps.
- `|global` on the hot path instead of batching.
- Missing `|hash` before batch aggregations.
- Stream topology for cross‑partition financial writes.
- `declare-pstate` on `setup`.
