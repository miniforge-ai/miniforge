;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.runner-phase-event
  "Phase outcome projection, preserving inner failures and diagnostic precedence."
  (:require [ai.miniforge.phase.interface :as phase]
            [ai.miniforge.response.interface :as response]
            [ai.miniforge.workflow.messages :as messages]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private inner-failure-statuses #{:error :failed :failure})

(defn- ^{:stratum 0} diagnostic-value [result field]
  (or (get-in result [:result :error :data field])
      (get-in result [:result :metrics field])
      (get-in result [:result field])))

(defn- ^{:stratum 0} error-info [result]
  (or (:error result)
      (get-in result [:result :error])
      (get-in result [:phase :error])
      (when-let [errors (:phase/gate-errors result)]
        {:message (messages/t :status/gate-failed)
         :details errors})
      (when-let [message (:message result)] {:message message})))

(defn- ^{:stratum 0} duration [result]
  (get result :duration-ms
       (get-in result [:phase/metrics :duration-ms]
               (get-in result [:metrics :duration-ms]))))

(defn- ^{:stratum 0} optional-fields [result]
  (let [review (response/review-decision (response/phase-output result))
        tokens (get-in result [:metrics :tokens] (get-in result [:phase/metrics :tokens]))
        cost (get-in result [:metrics :cost-usd] (get-in result [:phase/metrics :cost-usd]))
        fields {:phase/blocked-reason (phase/blocked-reason result)
                :phase/transition-request (phase/transition-request result)
                :review-decision review
                :tokens tokens
                :cost-usd cost}]
    (into {} (filter (comp boolean val)) fields)))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} inner-result-failed? [result]
  (or (contains? inner-failure-statuses (get-in result [:result :status]))
      (false? (get-in result [:result :success?]))))

(defn- ^{:stratum 1} diagnostics [result]
  (let [fields {:stop-reason (diagnostic-value result :stop-reason)
                :final-message-preview (diagnostic-value result :final-message-preview)
                :turn-count (diagnostic-value result :num-turns)
                :tool-call-count (diagnostic-value result :tool-call-count)}]
    (into {} (filter (comp boolean val)) fields)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} data [result]
  (let [succeeded? (and (get result :success? (phase/succeeded-or-done? result))
                        (not (inner-result-failed? result)))
        outcome (phase/outcome result succeeded?)
        error (when-not succeeded? (error-info result))
        diagnostic (when-not succeeded? (not-empty (diagnostics result)))
        base {:outcome outcome
              :duration-ms (duration result)}]
    (cond-> (merge base (optional-fields result))
      error (assoc :error error)
      diagnostic (assoc :meta diagnostic))))

(comment
  (data {}))
