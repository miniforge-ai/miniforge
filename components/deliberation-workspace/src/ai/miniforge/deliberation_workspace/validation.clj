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
   schema conformance, operation-payload conformance, target existence, role
   permission, and basis staleness. Rejections are anomalies as data — a
   routable value the scheduler logs, never an exception.

   The abuse guards (hard-constraint immutability, anti-livelock,
   idempotency) compose onto this chain in a sibling namespace."
  (:require
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.deliberation-workspace.messages :as messages]
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

(defn ^{:stratum 0} created-specs
  "The object specifications `operation` asks to create."
  [operation]
  (get operation :creates []))

(defn ^{:stratum 0} text?
  "True when `value` is a non-blank string."
  [value]
  (and (string? value) (not (str/blank? value))))

(defn ^{:stratum 0} reject-operation
  "An `:invalid-input` rejection of `operation` with `subtype`."
  [subtype message operation]
  (anomaly/sub-anomaly :invalid-input subtype message {:op (:op operation)}))

(defn- ^{:stratum 0} link-defect
  "The first structural defect in a `:links` map, as `[reason data]`, or nil
   when the engine can write every edge the map declares: a map, keyed by
   `object/link-types`, each value a collection of object ids.

   The one reading for both writers of `:links` — `object/new-object` on a
   `:creates` spec, `commit/apply-links` on the map an operation carries
   itself.

   Total over arbitrary EDN, `keys` on a non-map included: a validator that
   crashes on the input it exists to refuse moves the crash rather than
   removing it."
  [links]
  (let [mapped? (or (nil? links) (map? links))
        unknown-edges (when mapped? (seq (remove object/link-types (keys links))))
        scalar-edges (when mapped? (seq (remove (comp coll? val) links)))]
    (cond
      (not mapped?)
      [:malformed-links {:links links}]

      unknown-edges
      [:unknown-link-type {:link-types (vec unknown-edges)}]

      scalar-edges
      [:non-collection-links {:link-types (mapv key scalar-edges)}])))

(defn- ^{:stratum 0} creation-defect
  "The first structural defect in a `:creates` specification, as
   `[reason data]`, or nil when the engine can construct the object.

   Covers the fields belonging to the spec alone; [[link-defect]] is the
   sibling reading for its `:links`, and `check-creates` runs the two in
   order. Each predicate here is total over arbitrary EDN.

   The id must be a non-blank string, which `object/new-object` does not
   itself demand: without one the object lands in `:workspace/objects` under
   a nil key, and the collision checks below read `:id`. Holding the graph
   to one key type keeps it addressable."
  [spec]
  (let [id (:id spec)
        statement (:statement spec)]
    (cond
      (not (and (string? id) (not (str/blank? id))))
      [:blank-id {:id id}]

      (not (contains? object/object-types (:type spec)))
      [:unknown-type {:type (:type spec)}]

      (not (and (string? statement) (not (str/blank? statement))))
      [:blank-statement {:statement statement}])))

(defn- ^{:stratum 0} proposed-specs
  "Every object specification the operations of one transaction ask to
   create, in the order the payload lists them.

   Operations whose `:creates` is not a sequence are skipped, not
   interpreted. `check-creates` refuses that shape on its own behalf, and
   reading specs out of a payload of unestablished shape reports the wrong
   fault."
  [operations]
  (for [operation operations
        :let [creates (get operation :creates)]
        :when (sequential? creates)
        spec creates]
    spec))

(defn- ^{:stratum 0} id-listing-defect
  "The first structural defect in an operation field that names object ids,
   as `[reason data]`, or nil when the field's readers can iterate it and
   look its elements up in the object graph.

   A scalar is refused because `set` and `seq` both throw on one. A string
   and a map throw on neither, and are refused for what they yield instead:
   `(set \"claim-1\")` walks the string into seven single-character ids, so
   the operation would be reported against ids no activation named, and a
   map yields its entries, which are not ids either.

   Absent and nil are legal: every reader defaults the field to empty.
   Unusable elements are ordered by printed form. `:targets` is canonically
   a set, and what the anomaly reports must not vary between runs over
   identical input."
  [value]
  (let [listed? (or (nil? value) (and (coll? value) (not (map? value))))
        unusable (when listed?
                   (seq (remove #(and (string? %) (not (str/blank? %))) value)))]
    (cond
      (not listed?)
      [:non-collection-ids {:value value}]

      unusable
      [:unusable-id {:ids (vec (sort-by pr-str unusable))}])))

(defn- ^{:stratum 0} id-listings
  "Every id-naming field the operations of one transaction carry, as
   `[operation field value]` in payload order.

   Read across the whole transaction rather than one operation at a time:
   the §3.5 backing check consults a SIBLING's `:discriminates` while
   validating a challenge, so a stage reading only its own operation would
   reach a malformed field before the operation carrying it had a turn.

   Two kinds of sibling are skipped rather than interpreted, both because
   `check-schema` refuses them on their own turn and reporting a field of
   theirs first would outrank it:

   - operations that are not maps, on which `contains?` throws — reading
     fields out of a payload that is not one moves the crash rather than
     removing it;
   - operations outside the §3.2 vocabulary, whose fields mean nothing.

   Skipping the second leaves no reader unguarded. The only field any stage
   reads from a SIBLING is `:discriminates`, and `guards/backed?` reaches it
   only after `(= :propose-experiment (:op sibling))` — a vocabulary member,
   so a scanned one."
  [operations]
  (for [operation operations
        :when (and (map? operation) (tx/known-operation? (:op operation)))
        field tx/id-fields
        :when (contains? operation field)]
    [operation field (get operation field)]))

(defn- ^{:stratum 0} closed-to?
  "True when `target` admits no further `op`. An `:accepted` object stays
   open to the reopening operations (N14 §2.3)."
  [op target]
  (and (object/terminal? target)
       (not (and (= :accepted (:object/status target))
                 (contains? tx/reopening-operations op)))))

(defn- ^{:stratum 0} created-earlier
  "The object `id` would be once the operations before `operation` in
   `siblings` commit, as a fresh object of its specified type, or nil."
  [siblings operation id]
  (some (fn [spec]
          (when (= id (:id spec))
            {:object/id id :object/type (:type spec) :object/status :open}))
        (mapcat #(get % :creates []) (take-while #(not (identical? % operation)) siblings))))

(defn- ^{:stratum 0} replacement-defect
  "The first defect in a `supersede` replacement, as `[reason data]`, or nil.
   `replacements` are the ids the operation names, `replacement` the object
   the single id resolves to."
  [targets replacements replacement]
  (cond
    (some #(not (contains? tx/supersedable-types (:object/type %))) targets)
    [:unsupersedable-target {:targets (mapv :object/id targets)}]

    (not= 1 (count replacements))
    [:replacement-count {:replacement (vec replacements)}]

    (nil? replacement)
    [:unknown-replacement {:replacement (first replacements)}]

    (object/terminal? replacement)
    [:terminal-replacement {:replacement (:object/id replacement)}]

    (some #(or (= (:object/id %) (:object/id replacement))
               (not= (:object/type %) (:object/type replacement)))
          targets)
    [:mismatched-replacement {:replacement (:object/id replacement)}]))

(defn ^{:stratum 0} validate-operation
  "Run `stages` against one operation in order, returning the first anomaly
   or nil. `context` carries :role, :basis and :siblings from the enclosing
   transaction; stages reading :siblings must tolerate its absence here."
  [workspace operation context stages]
  (some #(% workspace operation context) stages))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} check-schema [_workspace operation _context]
  (when-not (tx/known-operation? (:op operation))
    (reject :invalid-input :anomalies.deliberation/unknown-operation
            (messages/system-t :anomaly/unknown-operation)
            {:op (:op operation)})))

(defn- ^{:stratum 1} check-id-fields
  "N14 §3.2 conformance for the operation fields that name object ids.

   `tx/touched-ids` `set`s `:targets`; the §3.5 backing check `seq`s
   `:evidence` and `set`s a sibling's `:discriminates`. Both throw on a
   scalar, so one there throws out of `validate` itself. That is worse than a
   commit-time throw: no stage returns, `run/step` never receives an
   anomaly, and there is nothing to route. This stage runs ahead of every
   reader.

   The offending operation is named rather than the one whose turn noticed.
   The scan covers the whole transaction, `:tx/operations` is a vector so
   the first defect is fixed by the payload, and pointing repair at a
   sibling's payload would describe a fault the named operation does not
   have."
  [_workspace operation context]
  (some (fn [[carrier field value]]
          (when-let [[reason data] (id-listing-defect value)]
            (reject :invalid-input :anomalies.deliberation/invalid-object-ids
                    (messages/system-t :anomaly/invalid-object-ids)
                    (merge {:op (:op carrier) :field field :reason reason} data))))
        (id-listings (get context :siblings [operation]))))

(defn- ^{:stratum 1} check-creates
  "N14 §3.2 conformance for the objects an operation asks to create.

   `:creates` carries agent-supplied data that `object/new-object` and
   `insert-created` both treat as a programmer error, so every condition
   they throw on is a routable rejection here instead.

   `:creates` must be sequential. A map is `coll?` too, and `insert-created`
   reducing over one would `assoc` onto a MapEntry and die on a non-integer
   key. A set is refused for determinism: the per-spec scan below reports
   the FIRST defect it finds, and a set has no first, so the `:reason`
   routing dispatches on would vary between runs over identical input.
   Sorting one into an order is not available — the ids are exactly what
   may be missing or malformed in the payloads this describes.

   Two collision directions are refused, both because `insert-created`
   writes with `assoc-in`: a create at an id that already exists replaces
   the object outright, and every edge pointing there resolves to the new
   one. Against `:workspace/objects`, and against every other create in the
   same transaction — `commit` reduces all operations onto one accumulator,
   so one `:creates` vector and two sibling operations collide alike.

   The transaction is what a collision faults, and it is discarded whole
   (§3.4); `:op` records where the pipeline noticed. `:tx/operations` is a
   vector, so which operation notices is fixed by the payload, and `:ids`
   is ordered by first appearance.

   Only specs that would reach the graph are counted, read by the same
   `defect` the per-spec scan reports on. A defective spec never reaches
   `insert-created`, so counting one invents a collision whose blame lands
   on whichever operation the scan happens to run first, ahead of the
   defect its own operation would report."
  [workspace operation context]
  (let [creates (get operation :creates)
        known (objects-of workspace)
        defect (fn [spec] (or (creation-defect spec) (link-defect (:links spec))))]
    (cond
      (nil? creates) nil

      (not (sequential? creates))
      (reject :invalid-input :anomalies.deliberation/invalid-creation
              (messages/system-t :anomaly/malformed-creates)
              {:op (:op operation) :reason :malformed-creates :creates creates})

      :else
      (or (some (fn [spec]
                  (when-let [[reason data] (defect spec)]
                    (reject :invalid-input :anomalies.deliberation/invalid-creation
                            (messages/system-t :anomaly/invalid-creation)
                            (merge {:op (:op operation) :reason reason :id (:id spec)}
                                   data))))
                creates)
          (let [ids (->> (get context :siblings [operation])
                         proposed-specs
                         (remove defect)
                         (map :id))
                counts (frequencies ids)]
            (when-let [repeated (seq (distinct (filter #(< 1 (get counts %)) ids)))]
              (reject :conflict :anomalies.deliberation/duplicate-object-id
                      (messages/system-t :anomaly/repeated-object-id)
                      {:op (:op operation) :ids (vec repeated)})))
          (when-let [taken (seq (filter #(contains? known (:id %)) creates))]
            (reject :conflict :anomalies.deliberation/duplicate-object-id
                    (messages/system-t :anomaly/taken-object-id)
                    {:op (:op operation) :ids (mapv :id taken)}))))))

(defn- ^{:stratum 1} check-links
  "N14 §3.2 conformance for the edges an operation writes onto its targets.

   `commit/apply-links` reduces over the operation's own `:links`, so the
   map is held to the same contract `check-creates` holds a spec's `:links`
   to: `object/add-link` throws on an edge outside `object/link-types`, and
   a scalar destination dies in the inner `reduce`. A string destination is
   the case worth naming, since it would not crash at all — `reduce` walks
   its characters and writes one edge per letter.

   Agent-supplied payload, so a violation is a routable rejection rather
   than an exception."
  [_workspace operation _context]
  (when-let [[reason data] (link-defect (:links operation))]
    (reject :invalid-input :anomalies.deliberation/invalid-links
            (messages/system-t :anomaly/invalid-links)
            (merge {:op (:op operation) :reason reason} data))))

(defn- ^{:stratum 1} check-outcome
  "N14 §2.3 conformance for the outcome `close-goal` imposes on its goals.

   `close-goal` is the one operation whose status comes from the payload
   rather than from `tx/status-effect`, and `commit/apply-status` reads it
   as `(tx/goal-outcomes (:outcome operation))` — a set used as a function,
   so anything outside the set yields nil. nil is also what the table
   yields for an operation with no status effect at all, and one line later
   the two are indistinguishable: the goal is touched, the transaction
   commits, the version advances, and the goal stays `:open`.

   Nothing downstream notices. `termination/goals-terminal?` closes a run
   when every goal is terminal, so a synthesizer that believes it closed
   the last goal leaves the run to end on a budget boundary rather than on
   the §7 success rule.

   An absent outcome is refused for the same reason as an illegal one, and
   under the same `:reason`: both name a status the engine cannot impose,
   and both are repaired by supplying one it can.

   Every other operation is refused for carrying the field. `commit`
   derives their status from the operation precisely so a payload cannot
   name one — otherwise a transaction could say `:challenge` and carry
   `:accepted`. That defense stops the field taking effect; it does not
   report it, which leaves the same silence in the other direction. An
   explicit nil is absent rather than inapplicable: it names no status."
  [_workspace operation _context]
  (let [outcome (:outcome operation)]
    (if (= :close-goal (:op operation))
      (when-not (contains? tx/goal-outcomes outcome)
        (reject :invalid-input :anomalies.deliberation/invalid-outcome
                (messages/system-t :anomaly/unknown-outcome)
                {:op (:op operation) :reason :unknown-outcome :outcome outcome}))
      (when (some? outcome)
        (reject :invalid-input :anomalies.deliberation/invalid-outcome
                (messages/system-t :anomaly/inapplicable-outcome)
                {:op (:op operation) :reason :inapplicable-outcome
                 :outcome outcome})))))

(defn- ^{:stratum 1} check-statement
  "A revision or an answer carries the text that replaces or answers its
   target; without it `commit` would write nil (N14 §2.3)."
  [_workspace operation _context]
  (let [op (:op operation)
        statement (:statement operation)]
    (when (and (or (contains? tx/revisions op) (= :answer-question op))
               (not (and (string? statement) (not (str/blank? statement)))))
      (reject :invalid-input :anomalies.deliberation/missing-statement
              (messages/system-t :anomaly/missing-statement)
              {:op op :statement statement}))))

(defn- ^{:stratum 1} check-targets [workspace operation _context]
  (let [known (objects-of workspace)
        missing (remove #(contains? known %) (tx/touched-ids operation))]
    (when (seq missing)
      (reject :not-found :anomalies.deliberation/unknown-target
              (messages/system-t :anomaly/unknown-target)
              {:op (:op operation) :missing (set missing)}))))

(defn- ^{:stratum 1} check-replacement
  "N14 §3.2: `supersede` names exactly one distinct, live, same-type
   replacement, and no other operation names one."
  [workspace operation {:keys [siblings]}]
  (let [known (objects-of workspace)
        replacements (set (get operation :replacement #{}))
        id (first replacements)
        replacement (or (known id) (created-earlier siblings operation id))]
    (if (= :supersede (:op operation))
      (when-let [[reason data] (replacement-defect (keep known (tx/touched-ids operation))
                                                   replacements
                                                   replacement)]
        (reject :invalid-input :anomalies.deliberation/invalid-replacement
                (messages/system-t :anomaly/invalid-replacement)
                (merge {:op (:op operation) :reason reason} data)))
      (when (seq replacements)
        (reject :invalid-input :anomalies.deliberation/invalid-replacement
                (messages/system-t :anomaly/inapplicable-replacement)
                {:op (:op operation) :reason :inapplicable-replacement})))))

(defn- ^{:stratum 1} supersedes-decision-without-standing?
  "True when `operation` supersedes a decision on behalf of a role §5.3
   does not let supersede one."
  [workspace operation role]
  (and (= :supersede (:op operation))
       (not (contains? tx/decision-superseders role))
       (some #(= :decision (:object/type (get (objects-of workspace) %)))
             (tx/touched-ids operation))))

(defn- ^{:stratum 1} check-permission [workspace operation {:keys [role]}]
  (when (or (not (tx/permitted? role (:op operation)))
            (supersedes-decision-without-standing? workspace operation role))
    (reject :unauthorized :anomalies.deliberation/role-forbidden
            (messages/system-t :anomaly/role-forbidden)
            {:op (:op operation) :role role})))

(defn- ^{:stratum 1} check-basis
  "Target liveness and basis staleness (N14 §3.3). No operation may act on a
   terminal target, except the reopening operations on an `:accepted` one.
   Additive operations commute, so they commit on a stale basis; mergeable
   and exclusive operations require targets untouched since the basis
   version the projection was rendered from."
  [workspace operation {:keys [basis]}]
  (let [op (:op operation)
        targets (keep (objects-of workspace) (tx/touched-ids operation))
        terminal (seq (filter #(closed-to? op %) targets))
        moved (when (and (number? basis) (not= :additive (tx/class-of op)))
                (seq (filter #(> (:object/touched-at %) basis) targets)))]
    (cond
      (not (number? basis))
      (reject :invalid-input :anomalies.deliberation/missing-basis
              (messages/system-t :anomaly/missing-basis)
              {:op op :basis basis})

      terminal
      (reject :conflict :anomalies.deliberation/terminal-target
              (messages/system-t :anomaly/terminal-target)
              {:op op :targets (mapv :object/id terminal)})

      moved
      (reject :conflict :anomalies.deliberation/stale-basis
              (messages/system-t :anomaly/stale-basis)
              {:op op :basis basis :targets (mapv :object/id moved)}))))

(defn ^{:stratum 1} validate
  "Validate a whole transaction against `stages`. Returns nil when every
   operation passes, or the first anomaly. A rejected transaction is
   discarded whole (N14 §3.4) — partial application would leave the
   workspace in a state no activation ever proposed.

   Stages are supplied by the caller rather than defaulted, so the abuse
   guards extend the chain without reopening this namespace.

   `:tx/operations` is held to a shape here rather than by a stage, because
   a stage cannot run until this function has iterated it. `run/step` hands
   an activation's return value straight in, so a scalar there would throw
   out of the validator with no anomaly for the caller to route. Sequential
   for the reason `check-creates` requires it of `:creates`: a set has no
   first operation, so which one the pipeline reported would vary between
   runs over identical input."
  [workspace transaction stages]
  (let [operations (:tx/operations transaction)
        context {:role (:tx/role transaction)
                 :participant (:tx/participant transaction)
                 :basis (:tx/basis transaction)
                 :siblings operations}]
    (if (or (nil? operations) (sequential? operations))
      (some #(validate-operation workspace % context stages) operations)
      (reject :invalid-input :anomalies.deliberation/invalid-transaction
              (messages/system-t :anomaly/invalid-transaction)
              {:operations operations}))))

;------------------------------------------------------------------------------ Layer 2

(def ^{:stratum 2} payload-stages
  "The §3.4 stages that read only the transaction's own payload, never the
   object graph."
  [check-schema check-id-fields check-creates check-links check-outcome check-statement])

;; The §5.3 role-permission stage alone. §3.4 orders permission before basis
;; staleness, so admission runs this over the whole transaction before it
;; checks the declared basis.
(def ^{:stratum 2} permission-stages [check-permission])

(def ^{:stratum 2} basis-stages
  "The §3.3 basis-staleness stage alone, for checking a transaction's
   declared basis separately from its other graph checks."
  [check-basis])

(def ^{:stratum 2} graph-stages
  "The §3.4 stages that read the object graph."
  (into [check-targets check-replacement check-permission] basis-stages))

(def ^{:stratum 2} concurrency-stages
  "Ordered §3.4 stages. Schema conformance runs first because every later
   stage reads the operation vocabulary, and payload conformance follows it
   for the same reason: an operation outside the vocabulary should be
   reported as an unknown operation, not as a bad creation.

   `check-id-fields` leads the payload stages, and is the one ordering
   constraint that is not a preference: stages after it — payload, graph,
   and abuse guard alike — hand a field it establishes the shape of to
   `set` or `seq`, either of which throws on a scalar rather than rejecting.
   It still runs after `check-schema`, and skips siblings outside the
   vocabulary, so an unknown operation is never reported as a shape fault.

   Order among the payload stages after it is a preference. `check-outcome`
   reads one scalar field of the operation it is handed, so nothing depends
   on where it sits; it is placed last of them because it is the only one
   whose fault is silent absorption at commit rather than a crash there.

   The payload stages precede the three that read the object graph. A
   transaction can carry a malformed payload and a missing target at once,
   and the payload is the more basic fault: the ids a graph stage would
   report come out of the same payload whose shape is not yet established."
  (into payload-stages graph-stages))
