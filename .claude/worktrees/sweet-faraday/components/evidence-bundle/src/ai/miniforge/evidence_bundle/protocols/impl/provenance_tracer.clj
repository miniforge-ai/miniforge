(ns ai.miniforge.evidence-bundle.protocols.impl.provenance-tracer
  "Implementation functions for ProvenanceTracer protocol.
   Traces artifact chains and provenance.")

;------------------------------------------------------------------------------ Layer 0
;; Provenance Query Helpers

(defn get-artifact-with-provenance
  "Get artifact with full provenance information."
  [artifact-store artifact-id]
  (when-let [artifact ((requiring-resolve 'ai.miniforge.artifact.interface/load-artifact)
                       artifact-store artifact-id)]
    artifact))

(defn get-source-artifacts
  "Get all source artifacts for a given artifact."
  [artifact-store artifact-id]
  (when-let [artifact (get-artifact-with-provenance artifact-store artifact-id)]
    (let [source-ids (get-in artifact [:artifact/provenance :provenance/source-artifacts] [])]
      (keep #(get-artifact-with-provenance artifact-store %) source-ids))))

(defn get-workflow-artifacts
  "Get all artifacts for a workflow."
  [artifact-store workflow-id]
  (let [query-fn (requiring-resolve 'ai.miniforge.artifact.interface/query)
        artifacts (query-fn artifact-store {})]
    (filter #(= workflow-id (get-in % [:artifact/provenance :provenance/workflow-id]))
            artifacts)))

;------------------------------------------------------------------------------ Layer 1
;; Provenance Tracing

(defn query-provenance-impl
  "Get full provenance for an artifact.
   Returns {:artifact {...} :workflow-id :original-intent {...}}"
  [artifact-store bundles artifact-id]
  (when-let [artifact (get-artifact-with-provenance artifact-store artifact-id)]
    (let [workflow-id (get-in artifact [:artifact/provenance :provenance/workflow-id])
          bundle (when workflow-id
                   (some #(when (= (:evidence-bundle/workflow-id %) workflow-id) %)
                         (vals @bundles)))
          source-artifacts (get-source-artifacts artifact-store artifact-id)]

      {:artifact artifact
       :workflow-id workflow-id
       :original-intent (get bundle :evidence/intent)
       :created-by-phase (get-in artifact [:artifact/provenance :provenance/phase])
       :created-by-agent (get-in artifact [:artifact/provenance :provenance/agent])
       :created-at (get-in artifact [:artifact/provenance :provenance/created-at])
       :source-artifacts source-artifacts
       :full-evidence-bundle bundle})))

(defn trace-artifact-chain-impl
  "Trace complete artifact chain for a workflow.
   Returns {:intent {...} :chain [...] :outcome {...}}"
  [artifact-store bundles workflow-id]
  (when-let [bundle (some #(when (= (:evidence-bundle/workflow-id %) workflow-id) %)
                          (vals @bundles))]
    (let [artifacts (get-workflow-artifacts artifact-store workflow-id)
          ;; Group artifacts by phase
          artifacts-by-phase (group-by
                              #(get-in % [:artifact/provenance :provenance/phase])
                              artifacts)
          ;; Build chain in phase order
          phase-order [:plan :design :implement :verify :review :release :observe]
          chain (keep
                 (fn [phase]
                   (when-let [phase-artifacts (get artifacts-by-phase phase)]
                     {:phase phase
                      :agent (get-in (first phase-artifacts)
                                     [:artifact/provenance :provenance/agent])
                      :artifacts (mapv :artifact/id phase-artifacts)
                      :timestamp (get-in (first phase-artifacts)
                                         [:artifact/provenance :provenance/created-at])}))
                 phase-order)]

      {:intent (:evidence/intent bundle)
       :chain chain
       :outcome (:evidence/outcome bundle)})))

;------------------------------------------------------------------------------ Layer 2
;; Intent Mismatch Detection

(defn query-intent-mismatches-impl
  "Find workflows where declared intent != actual behavior.
   Returns vector of mismatch records."
  [bundles opts]
  (let [time-range (:time-range opts)
        all-bundles (vals @bundles)
        filtered-bundles (if time-range
                           (let [[start end] time-range]
                             (filter
                              #(let [created (:evidence-bundle/created-at %)]
                                 (and (or (nil? start) (.isAfter created start))
                                      (or (nil? end) (.isBefore created end))))
                              all-bundles))
                           all-bundles)]

    (keep
     (fn [bundle]
       (let [sem-val (:evidence/semantic-validation bundle)
             declared (:semantic-validation/declared-intent sem-val)
             actual (:semantic-validation/actual-behavior sem-val)
             passed? (:semantic-validation/passed? sem-val)]

         (when (and declared actual (not passed?))
           {:workflow-id (:evidence-bundle/workflow-id bundle)
            :declared-intent declared
            :actual-behavior actual
            :violation-details sem-val
            :created-at (:evidence-bundle/created-at bundle)})))
     filtered-bundles)))
