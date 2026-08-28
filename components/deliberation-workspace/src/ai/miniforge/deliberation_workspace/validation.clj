;; Title: Miniforge.ai
;; Subtitle: An agentic SDLC / fleet-control platform
;; Author: Christopher Lester
;; Line: Founder, Miniforge.ai (project)
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;;
;; Licensed under the Apache License, Version 2.0 (the "License");
;; you may not use this file except in compliance with the License.
;; You may obtain a copy of the License at
;;
;;     http://www.apache.org/licenses/LICENSE-2.0
;;
;; Unless required by applicable law or agreed to in writing, software
;; distributed under the License is distributed on an "AS IS" BASIS,
;; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
;; See the License for the specific language governing permissions and
;; limitations under the License.
(ns ai.miniforge.deliberation-workspace.validation
  "The concurrency stages of the N14 §3.4 transaction validation pipeline:
   schema conformance, creation-payload conformance, target existence, role
   permission, and basis staleness. Rejections are anomalies as data — a
   routable value the scheduler logs, never an exception.

   The abuse guards (hard-constraint immutability, anti-livelock,
   idempotency) compose onto this chain in a sibling namespace."
  (:require
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.deliberation-workspace.object :as object]
   [ai.miniforge.deliberation-workspace.transaction :as tx]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} reject
  "Build a rejection anomaly carrying a domain subtype, so the scheduler can
   route on the reason without parsing a message (N14 §3.4)."
  [type subtype message data]
  (anomaly/sub-anomaly type subtype message data))

(defn ^{:stratum 0} objects-of
  "The object graph of `workspace`."
  [workspace]
  (get workspace :workspace/objects {}))

(defn- ^{:stratum 0} creation-defect
  "The first structural defect in a `:creates` specification, as
   `[reason data]`, or nil when the engine can construct the object.

   Mirrors every condition `object/new-object` throws on, plus the shape
   those throws take for granted: the constructor calls `keys` on `:links`,
   so a non-map `:links` reaches it as a ClassCastException before any of
   its own guards run. Each predicate here is total over arbitrary EDN —
   a validator that crashes on the input it exists to refuse would move the
   crash rather than remove it.

   The id is required to be a non-blank string, which `new-object` does not
   itself demand. Without it a spec with no `:id` lands in the object graph
   under a nil key, and the collision check below — which reads `:id` — has
   nothing to compare. Every id in the component is already a string, so
   holding the graph to one key type costs nothing and keeps `:workspace/
   objects` addressable."
  [spec]
  (let [id (:id spec)
        statement (:statement spec)
        links (get spec :links {})
        mapped? (or (nil? links) (map? links))
        unknown-edges (when mapped? (seq (remove object/link-types (keys links))))
        scalar-edges (when mapped? (seq (remove (comp coll? val) links)))]
    (cond
      (not (and (string? id) (not (str/blank? id))))
      [:blank-id {:id id}]

      (not (contains? object/object-types (:type spec)))
      [:unknown-type {:type (:type spec)}]

      (not (and (string? statement) (not (str/blank? statement))))
      [:blank-statement {:statement statement}]

      (not mapped?)
      [:malformed-links {:links links}]

      unknown-edges
      [:unknown-link-type {:link-types (vec unknown-edges)}]

      scalar-edges
      [:non-collection-links {:link-types (mapv key scalar-edges)}])))

(defn ^{:stratum 0} validate-operation
  "Run `stages` against one operation in order, returning the first anomaly
   or nil. `context` carries :role and :basis from the enclosing transaction."
  [workspace operation context stages]
  (some #(% workspace operation context) stages))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} check-schema [_workspace operation _context]
  (when-not (tx/known-operation? (:op operation))
    (reject :invalid-input :anomalies.deliberation/unknown-operation
            "Operation is outside the closed N14 §3.2 vocabulary"
            {:op (:op operation)})))

(defn- ^{:stratum 1} check-creates
  "N14 §3.2 conformance for the objects an operation asks to create.

   `object/new-object` throws IllegalArgumentException on an unknown type, a
   blank statement, or malformed `:links`. That contract is right for a
   programmer error, but `:creates` carries agent-supplied data and no stage
   inspected it: a transaction cleared validation and then crashed the engine
   partway through commit. Those conditions are agent-reachable input, so
   they belong here as a routable rejection.

   `:creates` must be a sequence or a set, not merely a collection. A map
   is `coll?` too, and reducing over one yields MapEntries: `insert-created`
   would then `assoc` onto a MapEntry and die on a non-integer key, while
   the per-spec checks below would read nil out of every entry and
   blame `:blank-id` — a reason that misroutes, because the payload's shape
   is what is wrong.

   The id collisions are the same gap without the crash. `insert-created`
   writes with `assoc-in`, so a create at an id that already exists replaces
   it outright — the previous type, status, statement and links are gone,
   and every edge pointing at that id now resolves to the new object. Both
   directions are refused: against the objects the workspace already holds,
   and against the other specs in this same `:creates`, where the reduce
   would otherwise let a later spec quietly overwrite an earlier one.

   Collisions with a create in a SIBLING operation of the same transaction
   are still undetected: `validate-operation` sees one operation at a time,
   and blaming either of the two for the other's id would be arbitrary."
  [workspace operation _context]
  (let [creates (get operation :creates)
        known (objects-of workspace)]
    (cond
      (nil? creates) nil

      (not (or (sequential? creates) (set? creates)))
      (reject :invalid-input :anomalies.deliberation/invalid-creation
              "Operation :creates must be a sequence or set of object specifications"
              {:op (:op operation) :reason :malformed-creates :creates creates})

      :else
      (or (some (fn [spec]
                  (when-let [[reason data] (creation-defect spec)]
                    (reject :invalid-input :anomalies.deliberation/invalid-creation
                            "Operation creates an object the engine cannot construct"
                            (merge {:op (:op operation) :reason reason :id (:id spec)}
                                   data))))
                creates)
          (when-let [repeated (seq (for [[id n] (frequencies (map :id creates))
                                         :when (> n 1)]
                                     id))]
            (reject :conflict :anomalies.deliberation/duplicate-object-id
                    "Operation creates more than one object at the same id"
                    {:op (:op operation) :ids (vec repeated)}))
          (when-let [taken (seq (filter #(contains? known (:id %)) creates))]
            (reject :conflict :anomalies.deliberation/duplicate-object-id
                    "Operation creates objects at ids the workspace already holds"
                    {:op (:op operation) :ids (mapv :id taken)}))))))

(defn- ^{:stratum 1} check-targets [workspace operation _context]
  (let [known (objects-of workspace)
        missing (remove #(contains? known %) (tx/touched-ids operation))]
    (when (seq missing)
      (reject :not-found :anomalies.deliberation/unknown-target
              "Operation targets objects that do not exist"
              {:op (:op operation) :missing (set missing)}))))

(defn- ^{:stratum 1} check-permission [_workspace operation {:keys [role]}]
  (when-not (tx/permitted? role (:op operation))
    (reject :unauthorized :anomalies.deliberation/role-forbidden
            "Role may not propose this operation (N14 §5.3)"
            {:op (:op operation) :role role})))

(defn- ^{:stratum 1} check-basis
  "Basis staleness per concurrency class (N14 §3.3). Additive operations
   commute, so they commit on a stale basis provided their targets are still
   live; mergeable and exclusive operations require targets untouched since
   the basis version the projection was rendered from."
  [workspace operation {:keys [basis]}]
  (let [known (objects-of workspace)
        targets (keep known (tx/touched-ids operation))]
    (cond
      (not (number? basis))
      (reject :invalid-input :anomalies.deliberation/missing-basis
              "Transaction must declare the workspace version its projection was rendered from"
              {:op (:op operation) :basis basis})

      (= :additive (tx/class-of (:op operation)))
      (when-let [terminal (seq (filter object/terminal? targets))]
        (reject :conflict :anomalies.deliberation/terminal-target
                "Operation targets an object already in a terminal status"
                {:op (:op operation) :targets (mapv :object/id terminal)}))

      :else
      (when-let [moved (seq (filter #(> (:object/touched-at %) basis) targets))]
        (reject :conflict :anomalies.deliberation/stale-basis
                "Object changed since the projection this transaction was built from"
                {:op (:op operation) :basis basis
                 :targets (mapv :object/id moved)})))))

(defn ^{:stratum 1} validate
  "Validate a whole transaction against `stages`. Returns nil when every
   operation passes, or the first anomaly. A rejected transaction is
   discarded whole (N14 §3.4) — partial application would leave the
   workspace in a state no activation ever proposed.

   Stages are supplied by the caller rather than defaulted, so the abuse
   guards extend the chain without reopening this namespace."
  [workspace transaction stages]
  (let [operations (:tx/operations transaction)
        context {:role (:tx/role transaction)
                 :basis (:tx/basis transaction)
                 :siblings operations}]
    (some #(validate-operation workspace % context stages) operations)))

;------------------------------------------------------------------------------ Layer 2

(def ^{:stratum 2} concurrency-stages
  "Ordered §3.4 stages. Schema conformance runs first because every later
   stage reads the operation vocabulary, and payload conformance follows it
   for the same reason: an operation outside the vocabulary should be
   reported as an unknown operation, not as a bad creation."
  [check-schema check-creates check-targets check-permission check-basis])
