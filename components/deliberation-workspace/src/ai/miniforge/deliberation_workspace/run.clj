;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.deliberation-workspace.run
  "Pure experimental coordinator. Model adapters see projections, never workspace internals."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.deliberation-workspace.admission :as admission]
            [ai.miniforge.deliberation-workspace.projection :as projection]
            [ai.miniforge.deliberation-workspace.scheduler :as scheduler]
            [ai.miniforge.deliberation-workspace.termination :as termination]))

(defn- event [ws e] (update ws :workspace/events (fnil conj []) e))

(defn activate-participant
  "One bounded work turn. Identity/basis come from trusted scheduling context.
   Model output is only a proposal. Closure turns use the same admission path."
  [workspace activate participant reason]
  (let [role (get-in workspace [:workspace/participant-roles participant] participant)
        activation (str "activation-" (inc (get-in workspace [:workspace/spent :activations] 0)))
        rendered (projection/project workspace participant
                    {:visibility (get workspace :workspace/visibility :full)
                     :readable-ids (get-in workspace [:workspace/readable participant])
                     :since (get-in workspace [:workspace/last-seen participant] 0)})
        proposed (activate {:role role :participant participant :activation activation
                            :projection rendered :closing? (= reason :closure)
                            :feedback (get-in workspace [:workspace/feedback participant])})
        spent (-> workspace
                  (update-in [:workspace/spent :activations] (fnil inc 0))
                  (assoc :workspace/last-actor participant)
                  (assoc-in [:workspace/last-seen participant] (:workspace/version workspace))
                  (event {:event :activation/completed :role participant :reason reason}))]
    (if (nil? proposed)
      (-> spent (update :workspace/quiet-rounds (fnil inc 0))
          (update :workspace/passed (fnil conj #{}) participant)
          (event {:event :transaction/passed :role participant}))
      (let [{:keys [rejection workspace transaction]}
            (admission/admit spent proposed {:role role :participant participant
                                            :activation activation :basis (:workspace/version spent)})]
        (if rejection
          (let [feedback {:status :rejected :reason (anomaly/subtype rejection)
                          :detail (:anomaly/message rejection)}]
            (-> spent (assoc-in [:workspace/feedback participant] feedback)
                (event {:event :transaction/rejected :role participant
                        :reason (:reason feedback)})))
          (-> workspace (assoc :workspace/quiet-rounds 0 :workspace/passed #{})
              (assoc-in [:workspace/feedback participant] {:status :committed})
              (event {:event :transaction/committed :role participant
                      :transaction transaction})))))))

(defn step [workspace activate]
  (if-let [{:activation/keys [role reason target]} (scheduler/next-activation workspace)]
    (let [ws (activate-participant workspace activate role reason)]
      (if target
        (update ws :workspace/events
                (fn [events]
                  (let [index (last (keep-indexed #(when (= :activation/completed (:event %2)) %1) events))]
                    (assoc-in events [index :target] target))))
        ws))
    (event workspace {:event :activation/none-eligible})))

(defn run
  "Closure reserve is INCLUDED in total allowance. Low-level callers without a
   reserve get explicit :unfunded closure; the lab requires one."
  [workspace activate {:keys [max-steps] :or {max-steps 1000}}]
  (loop [ws workspace n 0]
    (if-let [closing (termination/closing-rule ws)]
      (let [reserve (get ws :workspace/closure-reserve 0)
            closer (first (filter #(= :synthesizer
                                      (get-in ws [:workspace/participant-roles %] %))
                                  (:workspace/roles ws)))
            final? (and (:termination/forced-synthesis closing) (pos? reserve) closer)
            closed (if final? (activate-participant ws activate closer :closure) ws)]
        (assoc closed :workspace/termination
               (cond-> closing
                 (:termination/forced-synthesis closing)
                 (assoc :termination/closure (if final? :executed :unfunded)))))
      (if (>= n max-steps)
        (assoc ws :workspace/termination {:termination/rule :step-bound-exceeded})
        (recur (step ws activate) (inc n))))))
