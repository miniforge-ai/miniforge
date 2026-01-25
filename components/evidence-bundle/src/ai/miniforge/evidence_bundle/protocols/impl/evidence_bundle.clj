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
     :phase/duration-ms (or (:duration-ms phase-result) 0)
     :phase/output (or (:output phase-result) {})
     :phase/artifacts (or (:artifacts phase-result) [])
     :phase/inner-loop-iterations (or (:inner-loop-iterations phase-result) 0)
     :phase/event-stream-range (or (:event-stream-range phase-result) {})}))

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

(defn create-bundle-impl
  "Create evidence bundle from workflow state.
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
        semantic-validation (or (:semantic-validation opts) {})
        collect-all-fn (requiring-resolve 'ai.miniforge.evidence-bundle.collector/collect-all-phases)
        phase-evidence (collect-all-fn workflow-state)

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
                phase-evidence)
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

        ;; Validate semantic validation structure
        sem-val (:evidence/semantic-validation bundle)
        sem-val-validation (when sem-val
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
