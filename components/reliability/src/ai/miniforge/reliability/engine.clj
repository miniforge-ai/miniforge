;; Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.

(ns ai.miniforge.reliability.engine
  "ReliabilityEngine — periodic SLI computation, SLO checking, and budget tracking.

   Layer 0: Engine record and creation
   Layer 1: Compute cycle (stateful, emits events)"
  (:require
   [ai.miniforge.reliability.dependency-health :as dependency-health]
   [ai.miniforge.reliability.sli :as sli]
   [ai.miniforge.reliability.slo :as slo]
   [ai.miniforge.reliability.budget :as budget]
   [ai.miniforge.event-stream.interface.stream :as stream]
   [ai.miniforge.event-stream.interface.events :as events]
   [clojure.edn :as edn]
   [clojure.java.io :as io]))

;------------------------------------------------------------------------------ Layer 0
;; Config loading

(def ^:private defaults
  (-> (io/resource "config/reliability/defaults.edn") slurp edn/read-string))

(defn- merge-engine-config
  [config]
  (let [base-config {:windows (:default-windows defaults)
                     :tiers (:default-tiers defaults)
                     :dependency-health dependency-health/default-config
                     :slo-targets slo/default-targets}
        merged-config (merge base-config config)]
    (assoc merged-config
           :dependency-health
           (merge (:dependency-health base-config)
                  (:dependency-health config)))))

;------------------------------------------------------------------------------ Layer 0
;; Engine record

(defrecord ReliabilityEngine
  [event-stream   ; event stream atom for emitting events
   state          ; atom: {:slis [...] :slo-checks [...] :budgets {...} :mode :nominal}
   config])       ; {:windows [:7d] :tiers [:standard :critical] :dependency-health {...}}

(defn create-engine
  "Create a ReliabilityEngine.

   Arguments:
     event-stream - event stream atom (from event-stream/create-event-stream)
     config       - optional map:
       :windows - vector of windows to compute (default [:7d])
       :tiers   - vector of tiers to check (default [:standard :critical])
       :slo-targets - custom SLO targets (default: slo/default-targets)"
  [event-stream & [config]]
  (->ReliabilityEngine
   event-stream
   (atom {:slis []
          :slo-checks []
          :budgets {}
          :dependency-health {}
          :dependency-health-state {}
          :mode :nominal
          :last-computed-at nil})
   (merge-engine-config config)))

;------------------------------------------------------------------------------ Layer 1
;; Pipeline stages

(defn- check-all-slos-across-tiers
  "Check SLOs for all SLI results across all tier/window combinations."
  [all-slis tiers windows slo-targets]
  (vec
   (for [tier tiers
         window windows
         :let [window-slis (filter #(= window (:sli/window %)) all-slis)
               checks (slo/check-all-slos window-slis tier slo-targets)]
         check checks]
     (assoc check :slo/tier tier))))

(defn- compute-budgets
  "Compute error budgets for all enforced SLO checks."
  [all-slo-checks]
  (into {}
        (for [{:keys [sli/name slo/actual slo/target slo/tier slo/window]}
              all-slo-checks
              :let [{:keys [error-budget/remaining error-budget/burn-rate]}
                    (budget/compute-error-budget actual target
                                                (contains? slo/inverted-slis name))]]
          [[name tier window]
           (budget/error-budget remaining burn-rate tier name window)])))

;------------------------------------------------------------------------------ Layer 2
;; Compute cycle

(defn compute-cycle!
  "Execute one reliability computation cycle.

   1. Compute all SLIs for each configured window
   2. Check SLOs for each configured tier
   3. Compute error budgets
   4. Emit reliability events to event stream
   5. Return degradation recommendation

   Arguments:
     engine  - ReliabilityEngine
     metrics - map of collected metrics (see sli/compute-all-slis)

   Returns:
     {:slis [...]
      :slo-checks [...]
      :budgets {...}
      :breaches [...]
      :recommendation :nominal | :degraded | :safe-mode}"
  [engine metrics]
  (let [{:keys [event-stream state config]} engine
        {:keys [windows tiers slo-targets dependency-health]} config
        prior-dependency-state (:dependency-health-state @state)
        dependency-incidents (:dependency/incidents metrics)
        dependency-recoveries (:dependency/recoveries metrics)
        computed-at (java.util.Date.)

        ;; 1. Compute SLIs for each window
        all-slis (vec (mapcat #(sli/compute-all-slis metrics %) windows))

        ;; 2. Check SLOs for each tier
        all-slo-checks (check-all-slos-across-tiers all-slis tiers windows slo-targets)
        breaches (slo/breached-slos all-slo-checks)

        ;; 3. Compute error budgets for enforced SLOs
        budgets (compute-budgets all-slo-checks)

        ;; 3b. Update rolling dependency-health projection
        next-dependency-state (dependency-health/apply-signals
                               prior-dependency-state
                               dependency-incidents
                               dependency-recoveries
                               dependency-health
                               computed-at)
        dependency-health-projection (dependency-health/project-health
                                      next-dependency-state
                                      dependency-health)

        ;; 4. Determine degradation recommendation
        any-critical-exhausted? (budget/critical-budget-exhausted? budgets)
        any-critical-low? (budget/critical-budget-low? budgets)
        recommendation (cond
                         any-critical-exhausted? :safe-mode
                         any-critical-low?       :degraded
                         :else                   :nominal)]

    ;; 5. Emit events
    (when event-stream
      ;; SLI computed events
      (doseq [sli-result all-slis]
        (stream/publish! event-stream
                         (events/sli-computed event-stream
                                             (:sli/name sli-result)
                                             (:sli/value sli-result)
                                             (:sli/window sli-result))))

      ;; SLO breach events
      (doseq [{:keys [sli/name slo/target slo/actual slo/tier slo/window]} breaches]
        (stream/publish! event-stream
                         (events/slo-breach event-stream name target actual tier window)))

      ;; Error budget updates
      (doseq [[_ b] budgets]
        (stream/publish! event-stream
                         (events/error-budget-update event-stream
                                                    (:error-budget/tier b)
                                                    (:error-budget/sli b)
                                                    (:error-budget/remaining b)
                                                    (:error-budget/burn-rate b)
                                                    (:error-budget/window b)))))

    ;; 6. Update engine state
    (reset! state {:slis all-slis
                   :slo-checks all-slo-checks
                   :budgets budgets
                   :dependency-health dependency-health-projection
                   :dependency-health-state next-dependency-state
                   :mode recommendation
                   :last-computed-at computed-at})

    ;; 7. Return result
    {:slis all-slis
     :slo-checks all-slo-checks
     :budgets budgets
     :dependency-health dependency-health-projection
     :breaches breaches
     :recommendation recommendation}))

(defn current-state
  "Get the current reliability state."
  [engine]
  @(:state engine))

(defn current-mode
  "Get the current degradation mode recommendation."
  [engine]
  (:mode @(:state engine)))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (def stream (stream/create-event-stream {:sinks []}))
  (def eng (create-engine stream))

  (def now (java.util.Date.))
  (def sample-metrics
    {:workflow-metrics [{:status :completed :timestamp now}
                        {:status :completed :timestamp now}
                        {:status :failed :timestamp now}]})

  (compute-cycle! eng sample-metrics)

  :leave-this-here)
