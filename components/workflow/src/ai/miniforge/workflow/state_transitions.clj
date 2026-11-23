;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.state-transitions
  "Phase transition tables; guard order and accounting remain explicit data."
  (:require [ai.miniforge.workflow.state-identity :as identity]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private guarded-phases #{:review :verify :release :implement})

(defn- ^{:stratum 0} target-state [entries index fallback]
  (get (get entries index) :state-id fallback))

(defn- ^{:stratum 0} next-index [entries {:keys [index config]}]
  (when (and (not (:terminal? config)) (< (inc index) (count entries))) (inc index)))

(defn- ^{:stratum 0} guarded-failure [target redirect? self]
  (let [retry {:target self :guard :verdict/infra-retriable? :actions [:infra/inc-count]}
        terminal {:target :failed :guard :verdict/terminal?}
        fallback {:target :failed}]
    (if redirect?
      [retry terminal {:target :failed :guard :budget/redirects-spent?}
       {:target target :actions [:redirect/inc-count]} fallback]
      [retry terminal fallback])))

(defn ^{:stratum 0} paused-state [{:keys [state-id paused-state-id]}]
  [paused-state-id {:on {:resume state-id
                        :cancel :cancelled
                        :fail :failed
                        :complete :completed}}])

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} success-target [entries {:keys [config] :as entry}]
  (let [explicit (identity/first-phase-index entries (:on-success config))
        index (if (some? explicit) explicit (next-index entries entry))]
    (target-state entries index :completed)))

(defn- ^{:stratum 1} failure-transition [entries {:keys [config state-id]}]
  (let [index (identity/first-phase-index entries (:on-fail config))
        target (target-state entries index :failed)]
    (if (contains? guarded-phases (:phase config))
      (guarded-failure target (some? index) state-id)
      target)))

(defn- ^{:stratum 1} already-done-target [entries]
  (target-state entries (identity/first-phase-index entries :done) :completed))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} phase-state [entries {:keys [state-id paused-state-id] :as entry}]
  (let [success (success-target entries entry)
        failure (failure-transition entries entry)
        done (already-done-target entries)]
    [state-id {:on {:phase/retry state-id
                    :phase/succeed success
                    :phase/already-done done
                    :phase/fail failure
                    :pause paused-state-id
                    :cancel :cancelled
                    :fail :failed
                    :complete :completed}}]))

(comment ::phase-transition-language)
