;; Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.

(ns ai.miniforge.improvement.pipeline
  "Improvement pipeline: manage proposal lifecycle.

   Layer 0: Pipeline state management
   Layer 1: Pipeline operations")

;------------------------------------------------------------------------------ Layer 0
;; Pipeline state

(defn create-pipeline
  "Create an improvement pipeline store.
   Returns: atom wrapping {:proposals {} :deployed [] :rolled-back []}"
  []
  (atom {:proposals {}
         :deployed []
         :rolled-back []}))

(defn store-proposal!
  "Store a new improvement proposal in the pipeline."
  [pipeline proposal]
  (swap! pipeline assoc-in [:proposals (:improvement/id proposal)] proposal)
  proposal)

(defn get-proposals
  "Get proposals filtered by status."
  [pipeline & [status]]
  (let [all (vals (:proposals @pipeline))]
    (if status
      (filter #(= status (:improvement/status %)) all)
      all)))

;------------------------------------------------------------------------------ Layer 1
;; Lifecycle operations

(defn approve-proposal!
  "Mark a proposal as approved for deployment."
  [pipeline proposal-id]
  (swap! pipeline update-in [:proposals proposal-id]
         assoc :improvement/status :approved
               :improvement/approved-at (java.util.Date.))
  (get-in @pipeline [:proposals proposal-id]))

(defn deploy-proposal!
  "Mark a proposal as deployed."
  [pipeline proposal-id]
  (let [proposal (get-in @pipeline [:proposals proposal-id])]
    (when proposal
      (swap! pipeline
             (fn [s]
               (-> s
                   (assoc-in [:proposals proposal-id :improvement/status] :deployed)
                   (update :deployed conj proposal-id))))
      (get-in @pipeline [:proposals proposal-id]))))

(defn rollback-proposal!
  "Mark a deployed proposal as rolled back."
  [pipeline proposal-id reason]
  (let [proposal (get-in @pipeline [:proposals proposal-id])]
    (when proposal
      (swap! pipeline
             (fn [s]
               (-> s
                   (assoc-in [:proposals proposal-id :improvement/status] :rolled-back)
                   (assoc-in [:proposals proposal-id :improvement/rollback-reason] reason)
                   (update :rolled-back conj proposal-id))))
      (get-in @pipeline [:proposals proposal-id]))))
