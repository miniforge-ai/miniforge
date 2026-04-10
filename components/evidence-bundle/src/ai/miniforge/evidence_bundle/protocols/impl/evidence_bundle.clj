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

(ns ai.miniforge.evidence-bundle.protocols.impl.evidence-bundle
  "Implementation functions for EvidenceBundle protocol.
   Pure functions organized in layers."
  (:require
   [ai.miniforge.evidence-bundle.schema :as schema]
   [ai.miniforge.logging.interface :as log]))

;------------------------------------------------------------------------------ Layer 0
;; Evidence Collection Helpers

(defn collect-phase-evidence
  "Extract evidence from a phase execution.
   Returns phase evidence map per N6 spec."
  [phase-name phase-result]
  (when phase-result
    {:phase/name phase-name
     :phase/agent (:agent phase-result)
     :phase/agent-instance-id (or (:agent-instance-id phase-result) (random-uuid))
     :phase/started-at (:started-at phase-result)
     :phase/completed-at (:completed-at phase-result)
     :phase/duration-ms (get phase-result :duration-ms 0)
     :phase/output (get phase-result :output {})
     :phase/artifacts (get phase-result :artifacts [])
     :phase/inner-loop-iterations (get phase-result :inner-loop-iterations 0)
     :phase/event-stream-range (get phase-result :event-stream-range {})}))

(defn collect-all-phase-evidence
  "Collect evidence from all executed phases.
   Returns map of phase-name -> evidence."
  [workflow-state]
  (let [phases (:workflow/phases workflow-state {})]
    (reduce-kv
     (fn [acc phase-name phase-result]
       (if-let [evidence (collect-phase-evidence phase-name phase-result)]
         (assoc acc (keyword "evidence" (name phase-name)) evidence)
         acc))
     {}
     phases)))

;------------------------------------------------------------------------------ Layer 1
;; Bundle Creation

(defn- extract-execution-evidence
  "Extract N11 §9.1 evidence fields from the workflow result's :execution/output.
   Returns a map of evidence keys to merge into the bundle, or empty map."
  [workflow-state]
  (let [output (get workflow-state :execution/output {})]
    (cond-> {}
      (contains? output :evidence/execution-mode)
      (assoc :evidence/execution-mode (:evidence/execution-mode output))

      (contains? output :evidence/runtime-class)
      (assoc :evidence/runtime-class (:evidence/runtime-class output))

      (contains? output :evidence/task-started-at)
      (assoc :evidence/task-started-at (:evidence/task-started-at output))

      (contains? output :evidence/task-finished-at)
      (assoc :evidence/task-finished-at (:evidence/task-finished-at output))

      (contains? output :evidence/image-digest)
      (assoc :evidence/image-digest (:evidence/image-digest output)))))

(defn create-bundle-impl
  "Create evidence bundle from workflow state.
   Merges N11 §9.1 execution evidence fields from :execution/output.
   Returns [bundle updated-bundles-atom-value]"
  [bundles _artifact-store logger workflow-id opts]
  (let [workflow-state (:workflow-state opts)
        ;; Extract intent and outcome from workflow state if not provided
        workflow-spec (:workflow/spec workflow-state)
        intent (or (:intent opts)
                  (when workflow-spec
                    (let [extract-fn (requiring-resolve 'ai.miniforge.evidence-bundle.collector/extract-intent)]
                      (extract-fn workflow-spec))))
        outcome (or (:outcome opts)
                   (let [build-fn (requiring-resolve 'ai.miniforge.evidence-bundle.collector/build-outcome-evidence)]
                     (build-fn workflow-state)))
        policy-checks (or (:policy-checks opts)
                         (let [collect-fn (requiring-resolve 'ai.miniforge.evidence-bundle.collector/collect-policy-checks)]
                           (collect-fn workflow-state)))
        tool-invocations (let [collect-fn (requiring-resolve
                                           'ai.miniforge.evidence-bundle.collector/collect-tool-invocations)]
                           (collect-fn workflow-state))
        semantic-validation (get opts :semantic-validation {})
        collect-all-fn (requiring-resolve 'ai.miniforge.evidence-bundle.collector/collect-all-phases)
        phase-evidence (collect-all-fn workflow-state)

        ;; N11 §9.1: extract execution evidence from workflow result
        execution-evidence (extract-execution-evidence workflow-state)

        bundle-id (random-uuid)
        bundle (merge
                (schema/create-evidence-bundle-template)
                {:evidence-bundle/id bundle-id
                 :evidence-bundle/workflow-id workflow-id
                 :evidence-bundle/created-at (java.time.Instant/now)
                 :evidence/intent intent
                 :evidence/semantic-validation semantic-validation
                 :evidence/policy-checks policy-checks
                 :evidence/outcome outcome}
                phase-evidence
                execution-evidence)
        bundle (cond-> bundle
                 (seq tool-invocations)
                 (assoc :evidence/tool-invocations tool-invocations))

        new-bundles (assoc @bundles bundle-id bundle)]

    (log/info logger :evidence-bundle :bundle/created
              {:data {:bundle-id bundle-id
                      :workflow-id workflow-id}})

    [bundle new-bundles]))

(defn get-bundle-impl
  "Retrieve bundle by ID."
  [bundles bundle-id]
  (get @bundles bundle-id))

(defn get-bundle-by-workflow-impl
  "Retrieve bundle by workflow ID."
  [bundles workflow-id]
  (some #(when (= (:evidence-bundle/workflow-id %) workflow-id) %)
        (vals @bundles)))

;------------------------------------------------------------------------------ Layer 2
;; Query Operations

(defn matches-criteria?
  "Check if bundle matches query criteria."
  [bundle criteria]
  (every?
   (fn [[k v]]
     (case k
       :time-range
       (let [[start end] v
             created (:evidence-bundle/created-at bundle)]
         (and (or (nil? start) (.isAfter created start))
              (or (nil? end) (.isBefore created end))))

       :intent-type
       (= v (get-in bundle [:evidence/intent :intent/type]))

       :status
       (= v (get-in bundle [:evidence/outcome :outcome/success]))

       true))
   criteria))

(defn query-bundles-impl
  "Query bundles by criteria."
  [bundles criteria]
  (filter #(matches-criteria? % criteria) (vals @bundles)))

;------------------------------------------------------------------------------ Layer 3
;; Validation

(defn validate-bundle-impl
  "Validate bundle structure and integrity.
   Returns {:valid? bool :errors [...]}"
  [bundle]
  (let [errors (atom [])

        ;; Check required top-level fields
        required-fields [:evidence-bundle/id
                         :evidence-bundle/workflow-id
                         :evidence-bundle/created-at
                         :evidence/intent
                         :evidence/outcome]

        _ (doseq [field required-fields]
            (when-not (get bundle field)
              (swap! errors conj {:field field :error "Required field missing"})))

        ;; Validate intent structure
        intent (:evidence/intent bundle)
        intent-validation (when intent
                            (schema/validate-schema schema/intent-schema intent))

        _ (when (and intent-validation (not (:valid? intent-validation)))
            (swap! errors concat (:errors intent-validation)))

        ;; Validate semantic validation structure (only if present and non-empty)
        sem-val (:evidence/semantic-validation bundle)
        sem-val-validation (when (and sem-val (seq sem-val))
                             (schema/validate-schema
                              schema/semantic-validation-schema sem-val))

        _ (when (and sem-val-validation (not (:valid? sem-val-validation)))
            (swap! errors concat (:errors sem-val-validation)))]

    {:valid? (empty? @errors)
     :errors @errors}))

;------------------------------------------------------------------------------ Layer 4
;; Export Operations

(defn export-bundle-impl
  "Export bundle to file.
   Returns true on success, false on error."
  [bundles logger bundle-id output-path]
  (try
    (when-let [bundle (get @bundles bundle-id)]
      (spit output-path (pr-str bundle))
      (log/info logger :evidence-bundle :bundle/exported
                {:data {:bundle-id bundle-id
                        :output-path output-path}})
      true)
    (catch Exception e
      (log/error logger :evidence-bundle :bundle/export-failed
                 {:data {:bundle-id bundle-id
                         :error (.getMessage e)}})
      false)))
