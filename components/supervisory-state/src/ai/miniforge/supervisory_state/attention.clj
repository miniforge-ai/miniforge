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

(ns ai.miniforge.supervisory-state.attention
  "Pure derivation of AttentionItems from the canonical entity table.

   Implements the v1 subset of N5-delta-supervisory-control-plane §5.1 rules.
   Each rule is data: a predicate that yields source entities, a severity, and
   a summary formatter. The runner maps rules over entities and returns a
   deterministic attention list keyed by stable UUIDv5 IDs so consumers don't
   see flicker on identical signals."
  (:import
   (java.util UUID)
   (java.security MessageDigest)
   (java.nio ByteBuffer)))

;------------------------------------------------------------------------------ Layer 0
;; Deterministic AttentionItem ID

(def ^:private attention-ns
  "Stable namespace UUID for attention-item IDs (matches the Rust side's
   `ATTENTION_NS` constant in miniforge-control/state/src/projections.rs)."
  (UUID/fromString "6ba7b810-9dad-11d1-80b4-00c04fd430c8"))

(defn- uuid-v5
  "Derive a deterministic UUID v5 from a namespace UUID and a string key.

   Uses SHA-1 per RFC-4122 §4.3. The bit-math constants are encoded as
   signed longs because Clojure parses `0xFFFFFFFFFFFF0FFF` (>Long.MAX_VALUE)
   as BigInt, which breaks `bit-and`."
  [^UUID ns-uuid ^String key]
  (let [md (MessageDigest/getInstance "SHA-1")
        ns-buf (ByteBuffer/wrap (byte-array 16))]
    (.putLong ns-buf (.getMostSignificantBits ns-uuid))
    (.putLong ns-buf (.getLeastSignificantBits ns-uuid))
    (.update md (.array ns-buf))
    (.update md (.getBytes key "UTF-8"))
    (let [hash    (.digest md)
          msb-raw (.getLong (ByteBuffer/wrap hash 0 8))
          lsb-raw (.getLong (ByteBuffer/wrap hash 8 8))
          ;; 0xFFFFFFFFFFFF0FFF as signed long = -61441.
          ;; Clears the 4-bit version nibble (bits 48-51), then OR in 0x5000
          ;; to set UUID version = 5 (SHA-1 name-based).
          hi      (bit-or (bit-and msb-raw (unchecked-long -61441)) 0x5000)
          ;; Clear the top two bits (variant field, 62-63), then set to 10
          ;; (RFC-4122). Long/MIN_VALUE == 0x8000000000000000.
          lo      (bit-or (bit-and lsb-raw 0x3FFFFFFFFFFFFFFF)
                          Long/MIN_VALUE)]
      (UUID. hi lo))))

(defn- attention-id
  "Stable ID derived from source-type and source-id so the same logical
   signal keeps the same UUID across derivations."
  [source-type source-id]
  (uuid-v5 attention-ns (str (name source-type) ":" (pr-str source-id))))

(defn- now [] (java.util.Date.))

(defn- item
  ([severity source-type source-id summary]
   (item severity source-type source-id summary {}))
  ([severity source-type source-id summary extra]
   (merge {:attention/id          (attention-id source-type source-id)
           :attention/severity    severity
           :attention/source-type source-type
           :attention/source-id   source-id
           :attention/summary     summary
           :attention/derived-at  (now)
           :attention/resolved?   false}
          extra)))

;------------------------------------------------------------------------------ Layer 1
;; Individual rules — each returns a seq of AttentionItems

(defn- workflow-failed-rule
  "Critical: workflow is in :failed state (N5-delta-1 §5.1)."
  [{:keys [workflows]}]
  (for [[_ wf] workflows
        :when (= :failed (:workflow-run/status wf))]
    (item :critical :workflow
          (str (:workflow-run/id wf))
          (str "Workflow failed: " (:workflow-run/workflow-key wf)))))

(defn- workflow-completed-rule
  "Info: workflow completed successfully (N5-delta-1 §5.1)."
  [{:keys [workflows]}]
  (for [[_ wf] workflows
        :when (= :completed (:workflow-run/status wf))]
    (item :info :workflow
          (str (:workflow-run/id wf))
          (str "Workflow completed: " (:workflow-run/workflow-key wf)))))

(def ^:private stale-workflow-threshold-ms
  "A running workflow is considered stale if `:workflow-run/updated-at` is
   older than this. 10 minutes matches the ballpark the Clojure TUI uses
   for its own stale detection."
  (* 10 60 1000))

(defn- workflow-stale-rule
  "Warning: running workflow hasn't produced an event recently."
  [{:keys [workflows]}]
  (let [cutoff (- (.getTime (now)) stale-workflow-threshold-ms)]
    (for [[_ wf] workflows
          :when (and (= :running (:workflow-run/status wf))
                     (some-> wf :workflow-run/updated-at .getTime (< cutoff)))]
      (item :warning :workflow
            (str (:workflow-run/id wf))
            (str "Workflow stale: " (:workflow-run/workflow-key wf))))))

(defn- agent-blocked-rule
  "Warning: agent in :blocked state (awaiting decision)."
  [{:keys [agents]}]
  (for [[_ a] agents
        :when (= :blocked (:agent/status a))]
    (item :warning :agent
          (str (:agent/id a))
          (str "Agent blocked: " (:agent/name a)))))

(defn- agent-failed-rule
  "Critical: agent in terminal failed state."
  [{:keys [agents]}]
  (for [[_ a] agents
        :when (= :failed (:agent/status a))]
    (item :critical :agent
          (str (:agent/id a))
          (str "Agent failed: " (:agent/name a)))))

(defn- pr-ci-failed-rule
  "Warning: PR has CI failing and is not yet terminal."
  [{:keys [prs]}]
  (for [[_ pr] prs
        :when (and (= :failed (:pr/ci-status pr))
                   (not (#{:merged :closed :failed} (:pr/status pr))))]
    (item :warning :pr
          [(:pr/repo pr) (:pr/number pr)]
          (str "CI failed: " (:pr/repo pr) "#" (:pr/number pr) " — " (:pr/title pr)))))

(defn- pr-behind-main-rule
  "Warning: non-terminal PR is behind main (potential merge conflict)."
  [{:keys [prs]}]
  (for [[_ pr] prs
        :when (and (true? (:pr/behind-main pr))
                   (not (#{:merged :closed :failed} (:pr/status pr))))]
    (item :warning :pr
          [(:pr/repo pr) (:pr/number pr)]
          (str "PR behind main: " (:pr/repo pr) "#" (:pr/number pr)))))

(defn- policy-violation-rule
  "Critical (high/critical violations) or Info (lower) for failed policy evals."
  [{:keys [policy-evals]}]
  (for [[_ ev] policy-evals
        :when (false? (:policy-eval/passed? ev))
        :let [critical? (some #(#{:critical :high} (:violation/severity %))
                              (:policy-eval/violations ev))
              sev       (if critical? :critical :info)
              gate-id   (:policy-eval/gate-id ev)
              target-type (:policy-eval/target-type ev)
              target-id (:policy-eval/target-id ev)
              first-violation (first (:policy-eval/violations ev))
              rule-id   (:violation/rule-id first-violation)
              message   (:violation/message first-violation)
              extra-count (max 0 (dec (count (:policy-eval/violations ev))))
              pr-target? (and (= :pr target-type)
                              (vector? target-id)
                              (= 2 (count target-id)))
              target-summary
              (when (some? target-id)
                (case target-type
                  :pr (if pr-target?
                        (str "PR " (first target-id) "#" (second target-id))
                        (str "PR " target-id))
                  :workflow-output (str "workflow output " target-id)
                  :artifact (str "artifact " target-id)
                  (when target-type
                    (str (name target-type) " " target-id))))
              summary  (str "Policy violation"
                            (when gate-id (str " in " (name gate-id)))
                            (when target-summary (str " for " target-summary))
                            (when rule-id (str ": " (name rule-id)))
                            (when (seq message) (str " — " message))
                            (when (pos? extra-count) (str " (+" extra-count " more)")))]]
    (item sev :policy
          (str (:policy-eval/id ev))
          summary
          {:attention/workflow-run-id (:policy-eval/workflow-run-id ev)
           :attention/gate-id gate-id
           :attention/target-type target-type
           :attention/target-id target-id})))

;------------------------------------------------------------------------------ Layer 2
;; Rule registry + runner

(def rules
  "Rule registry. Each rule is `(table) -> seq<AttentionItem>`."
  [workflow-failed-rule
   workflow-completed-rule
   workflow-stale-rule
   agent-blocked-rule
   agent-failed-rule
   pr-ci-failed-rule
   pr-behind-main-rule
   policy-violation-rule])

(defn derive-items
  "Run all rules against the entity table and return a map `{id → item}`.

   Using a map keyed by attention/id guarantees deduplication: if two rules
   would produce the same logical signal (same source-type + source-id) the
   later rule's entry wins — in practice they produce identical items so
   this is idempotent. Named `derive-items` rather than `derive` to avoid
   shadowing `clojure.core/derive`."
  [table]
  (->> rules
       (mapcat #(% table))
       (map (juxt :attention/id identity))
       (into {})))

(defn derive-seq
  "Same as `derive-items` but returns a sorted sequence suitable for display.

   Sort: severity rank (critical → warning → info), then summary ascending."
  [table]
  (let [rank {:critical 0 :warning 1 :info 2}]
    (->> (derive-items table)
         vals
         (sort-by (juxt #(rank (:attention/severity %)) :attention/summary)))))
