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

(ns ai.miniforge.supervisory-state.golden-fixtures
  "Generate the canonical golden fixtures for the supervisory entity
   contract — one supervisory snapshot event (N3 §3.19) per entity
   family, serialized through the production transit encoder.

   The fixtures are the cross-language contract surface consumed by the
   miniforge-control `supervisory-entities` Rust crate: that repo vendors
   the generated files and round-trips them in CI, so any change to an
   entity shape here is caught as a fixture diff on the producer side
   (see `golden-fixtures-test`) and a deserialization failure on the
   consumer side — instead of a silently dropped field at runtime.

   Determinism: every value is pinned (fixed UUIDs, fixed instants, no
   wall clock, no randomness) so regeneration is byte-identical until
   the contract actually changes. Every value is also obviously
   synthetic — `golden/*` names, `example.invalid` URLs — so a fixture
   can never be mistaken for captured production data."
  (:require
   [ai.miniforge.event-stream.interface :as es]
   [ai.miniforge.supervisory-state.emitter :as emitter]
   [ai.miniforge.supervisory-state.schema :as schema]
   [clojure.java.io :as io]
   [malli.core :as m]
   [malli.error :as me])
  (:import
   [java.util Date]))

;------------------------------------------------------------------------------ Layer 0
;; Pinned synthetic identity — fixed UUIDs, one fixed instant

(defn- golden-uuid
  "A fixed, obviously-synthetic UUID for slot `n` (1-255)."
  [n]
  (java.util.UUID/fromString
   (format "00000000-0000-4000-8000-%012x" (long n))))

(def ^:private t0
  "The single pinned timestamp used everywhere in the fixtures."
  (Date/from (java.time.Instant/parse "2026-01-01T00:00:00Z")))

(def ^:private envelope-id-slot-offset
  "golden-uuid slot offset for envelope event ids. Entity ids occupy
   slots 1-9; envelope event ids occupy 100-108 (offset + 0-based
   family index), so no fixture's `:event/id` can collide with an
   entity id."
  100)

(def ^:private pinned-sequence-number
  "Every fixture pins `:event/sequence-number` to the first slot —
   sequence numbering is per-workflow runtime state, meaningless in a
   single-event fixture."
  1)

(def ^:private workflow-run-id (golden-uuid 1))
(def ^:private correlation-id  (golden-uuid 2))
(def ^:private spec-id         (golden-uuid 3))
(def ^:private agent-id        (golden-uuid 4))
(def ^:private policy-eval-id  (golden-uuid 5))
(def ^:private attention-id    (golden-uuid 6))
(def ^:private task-id         (golden-uuid 7))
(def ^:private decision-id     (golden-uuid 8))
(def ^:private intervention-id (golden-uuid 9))

;------------------------------------------------------------------------------ Layer 1
;; Canonical synthetic entities — one per family, exercising the
;; optional blocks the Rust contract also models (PR scoring, nested
;; spec snapshot, violations) so absence-vs-presence both round-trip.
;;
;; Numeric payload values (scores, counts, intervals) are arbitrary
;; pinned data — the entity def is the name; the values carry no
;; semantics beyond "plausible for the field's type and range".
;; They are deliberately asymmetric (additions 10 vs deletions 2,
;; score 0.5 vs threshold 0.8) so a field transposition in either
;; codec shows up as a fixture diff instead of cancelling out.

(def ^:private workflow-run-entity
  {:workflow-run/id workflow-run-id
   :workflow-run/workflow-key "golden-synthetic-workflow"
   :workflow-run/intent "Golden fixture — synthetic, not a real run"
   :workflow-run/status :running
   :workflow-run/current-phase :implement
   :workflow-run/started-at t0
   :workflow-run/updated-at t0
   :workflow-run/trigger-source :cli
   :workflow-run/correlation-id correlation-id
   :workflow-run/spec {:spec/title "Golden synthetic spec"
                       :spec/description "Synthetic spec snapshot"}
   :workflow-run/spec-id spec-id
   :workflow-run/prs [{:pr/repo "golden/synthetic"
                       :pr/number 1
                       :pr/url "https://example.invalid/golden/pr/1"
                       :pr/branch "golden/branch-1"
                       :pr/title "Golden synthetic PR"
                       :pr/author "golden-bot"
                       :pr/merge-order 0}]})

(def ^:private spec-entity
  {:spec/id spec-id
   :spec/title "Golden synthetic spec"
   :spec/status :active
   :spec/created-at t0
   :spec/updated-at t0
   :spec/description "Golden fixture — synthetic spec entity"
   :spec/tags ["golden"]
   :spec/origin :miniforge})

(def ^:private agent-entity
  {:agent/id agent-id
   :agent/vendor "golden-vendor"
   :agent/external-id "golden-agent-1"
   :agent/name "Golden Synthetic Agent"
   :agent/status :executing
   :agent/capabilities [:implement]
   :agent/heartbeat-interval-ms 5000
   :agent/metadata {"golden" true}
   :agent/tags ["golden"]
   :agent/registered-at t0
   :agent/last-heartbeat t0
   :agent/task "golden synthetic task"})

(def ^:private pr-entity
  {:pr/repo "golden/synthetic"
   :pr/number 1
   :pr/url "https://example.invalid/golden/pr/1"
   :pr/branch "golden/branch-1"
   :pr/title "Golden synthetic PR"
   :pr/status :reviewing
   :pr/merge-order 0
   :pr/depends-on []
   :pr/blocks [2]
   :pr/ci-status :running
   :pr/author "golden-bot"
   :pr/additions 10
   :pr/deletions 2
   :pr/changed-files-count 3
   :pr/behind-main false
   :pr/workflow-run-id workflow-run-id
   :pr/readiness {:readiness/score 0.5
                  :readiness/threshold 0.8
                  :readiness/ready? false
                  :readiness/factors [{:factor :ci
                                       :weight 1.0
                                       :score 0.5
                                       :contribution 0.5
                                       :explanation "golden synthetic factor"}]}
   :pr/risk {:risk/score 0.25
             :risk/level :low
             :risk/factors [{:factor :size
                             :weight 1.0
                             :value 12
                             :score 0.25
                             :explanation "golden synthetic factor"}]}
   :pr/policy {:policy/overall :fail
               :policy/packs-applied ["golden-pack"]
               :policy/summary {:critical 0 :major 1 :minor 0 :info 0 :total 1}
               :policy/violations [{:rule-id :golden/rule-001
                                    :severity :medium
                                    :message "Golden synthetic violation"
                                    :path "golden.clj"
                                    :waived? false}]
               :policy/artifacts-checked 1}
   :pr/recommendation :review})

(def ^:private policy-eval-entity
  {:policy-eval/id policy-eval-id
   :policy-eval/workflow-run-id workflow-run-id
   :policy-eval/gate-id :golden/gate
   :policy-eval/target-type :pr
   :policy-eval/target-id "golden/synthetic#1"
   :policy-eval/passed? false
   :policy-eval/packs-applied ["golden-pack"]
   :policy-eval/violations [{:violation/rule-id :golden/rule-001
                             :violation/severity :medium
                             :violation/category :testing
                             :violation/message "Golden synthetic violation"
                             :violation/location "golden.clj:1"
                             :violation/remediable? true}]
   :policy-eval/evaluated-at t0})

(def ^:private attention-entity
  {:attention/id attention-id
   :attention/severity :warning
   :attention/source-type :workflow
   :attention/source-id workflow-run-id
   :attention/summary "Golden synthetic attention item"
   :attention/derived-at t0
   :attention/resolved? false})

(def ^:private task-node-entity
  {:task/id task-id
   :task/workflow-run-id workflow-run-id
   :task/description "Golden synthetic task"
   :task/type :golden
   :task/component "golden-component"
   :task/status :running
   :task/kanban-column :active
   :task/dependencies []
   :task/dependents []
   :task/started-at t0
   :task/elapsed-ms 1000
   :task/exclusive-files? false
   :task/stratum? false})

(def ^:private decision-entity
  {:decision/id decision-id
   :decision/agent-id agent-id
   :decision/workflow-run-id workflow-run-id
   :decision/type :choice
   :decision/priority :medium
   :decision/status :pending
   :decision/summary "Golden synthetic decision"
   :decision/context "golden synthetic context"
   :decision/options ["golden option a" "golden option b"]
   :decision/created-at t0})

(def ^:private intervention-entity
  {:intervention/id intervention-id
   :intervention/type :pause
   :intervention/target-type :workflow
   :intervention/target-id workflow-run-id
   :intervention/requested-by "golden-operator"
   :intervention/request-source :tui
   :intervention/state :proposed
   :intervention/requested-at t0
   :intervention/updated-at t0
   :intervention/justification "golden synthetic justification"
   :intervention/approval-required? false})

;------------------------------------------------------------------------------ Layer 2
;; Family table — name, emitter constructor, schema, entity

(def families
  "The contract surface: every entity family the Clojure side emits as
   a `:supervisory/*-upserted` event. Worktree and AutomationEdge are
   absent by design — those families originate in the miniforge-control
   Rust adapters (N5-delta-3) and their fixtures live with their
   producer."
  [{:family :workflow-run :ctor emitter/workflow-upserted     :schema schema/WorkflowRun        :entity workflow-run-entity}
   {:family :spec         :ctor emitter/spec-upserted         :schema schema/Spec               :entity spec-entity}
   {:family :agent        :ctor emitter/agent-upserted        :schema schema/AgentSession       :entity agent-entity}
   {:family :pr           :ctor emitter/pr-upserted           :schema schema/PrFleetEntry       :entity pr-entity}
   {:family :policy-eval  :ctor emitter/policy-evaluated      :schema schema/PolicyEvaluation   :entity policy-eval-entity}
   {:family :attention    :ctor emitter/attention-derived     :schema schema/AttentionItem      :entity attention-entity}
   {:family :task-node    :ctor emitter/task-node-upserted    :schema schema/TaskNode           :entity task-node-entity}
   {:family :decision     :ctor emitter/decision-upserted     :schema schema/DecisionCard       :entity decision-entity}
   {:family :intervention :ctor emitter/intervention-upserted :schema schema/InterventionRequest :entity intervention-entity}])

;------------------------------------------------------------------------------ Layer 3
;; Event construction — real constructor + real encoder, pinned envelope

(defn- validate!
  "Throw with a malli explanation when `entity` does not conform to
   `entity-schema`. The generator fails closed: a fixture that does not
   validate against the current schema must never be written."
  [family entity-schema entity]
  (when-not (m/validate entity-schema entity)
    (throw (ex-info (str "Golden fixture entity invalid for " family)
                    {:family family
                     :errors (me/humanize (m/explain entity-schema entity))}))))

(defn- pin-envelope
  "Replace the nondeterministic envelope fields (`create-envelope`
   stamps a wall-clock timestamp and a snowflake/random event id) with
   pinned values so regeneration is byte-identical. The KEY SET is the
   production key set; only the values are pinned."
  [event n]
  (assoc event
         :event/id (golden-uuid (+ envelope-id-slot-offset n))
         :event/timestamp t0
         :event/sequence-number pinned-sequence-number))

(defn golden-events
  "Build the pinned upsert event for every family, as a fully realized
   vector — callers traverse it repeatedly, and the constructors touch
   stream state (sequence numbering), so laziness would mean silent
   recomputation. Validates each entity against its schema first —
   fails closed on drift."
  []
  (let [stream (es/create-event-stream {:sinks []})]
    (into []
          (map-indexed
           (fn [n {:keys [family ctor schema entity]}]
             (validate! family schema entity)
             {:family family
              :event (pin-envelope (ctor stream entity) n)}))
          families)))

;------------------------------------------------------------------------------ Layer 4
;; Disk layout — one file per family + a manifest for consumers

(defn- clear-stale-fixtures!
  "Delete previously generated fixture files in `dir` so a removed or
   renamed family cannot leave an orphan behind that only the drift
   gate would notice. Touches only the generator's own output shapes."
  [dir]
  (doseq [^java.io.File f (or (.listFiles (io/file dir)) [])
          :when (or (.endsWith (.getName f) ".transit.json")
                    (= "manifest.edn" (.getName f)))]
    (io/delete-file f)))

(defn write-golden-fixtures!
  "Write `<family>.transit.json` per family plus `manifest.edn` into
   `:out-dir` (string path), replacing any previously generated files
   there. Returns a summary map. Designed for `clojure -X` invocation;
   see the `fixtures:supervisory` bb task."
  [{:keys [out-dir]}]
  (when-not (and (string? out-dir) (seq out-dir))
    (throw (ex-info "write-golden-fixtures! requires a non-blank :out-dir string"
                    {:out-dir out-dir})))
  (let [dir (io/file out-dir)]
    (.mkdirs dir)
    (clear-stale-fixtures! dir)
    (let [events (golden-events)]
      (doseq [{:keys [family event]} events]
        (spit (io/file dir (str (name family) ".transit.json"))
              (es/serialize-event event)
              :encoding "UTF-8"))
      (spit (io/file dir "manifest.edn")
            (pr-str {:supervisory/schema-version schema/schema-version
                     :fixture/families (mapv :family events)})
            :encoding "UTF-8")
      {:out-dir (.getPath dir)
       :schema-version schema/schema-version
       :families (mapv :family events)})))
