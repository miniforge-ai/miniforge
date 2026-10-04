;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.deliberation-workspace.admission
  "Trusted admission boundary; callers supply authenticated activation context."
  (:require [ai.miniforge.deliberation-workspace.commit :as commit]
            [ai.miniforge.deliberation-workspace.guards :as guards]
            [ai.miniforge.deliberation-workspace.semantics :as semantics]
            [ai.miniforge.deliberation-workspace.validation :as v]
            [clojure.string :as str]))

(def stages (vec (concat v/concurrency-stages [semantics/check] guards/guard-stages)))

(defn transaction [proposal {:keys [role participant activation basis]}]
  (assoc proposal :tx/role role :tx/participant participant
         :tx/activation activation :tx/basis basis))

(defn admit
  "Returns {:workspace :transaction} or {:rejection}. Applies operations sequentially
   to a private candidate, then publishes ONE atomic transaction/version. No partial state escapes."
  [workspace proposal {:keys [role participant activation basis] :as context}]
  (cond
    (not (and (contains? #{:interpreter :proposer :implementer :skeptic :verifier
                           :synthesizer :meta-watchdog :user} role)
              (or (keyword? participant) (and (string? participant) (not (str/blank? participant))))
              (string? activation) (not (str/blank? activation))
              (nat-int? basis) (<= basis (:workspace/version workspace))))
    {:rejection (v/reject :unauthorized :anomalies.deliberation/invalid-context
                         "Runtime activation context is missing, invalid, or from a future version" {})}

    (not (map? proposal))
    {:rejection (v/reject :invalid-input :anomalies.deliberation/invalid-transaction
                         "Proposal must be a transaction map" {})}

    (or (and (:tx/role proposal) (not= role (:tx/role proposal)))
        (seq (remove #{:tx/role :tx/activation :tx/basis :tx/operations}
                     (keys proposal))))
    {:rejection (v/reject :unauthorized :anomalies.deliberation/actor-mismatch
                         "Model payload cannot supply authority or a different responsibility" {})}

    (not (and (sequential? (:tx/operations proposal))
              (seq (:tx/operations proposal)) (<= (count (:tx/operations proposal)) 128)))
    {:rejection (v/reject :invalid-input :anomalies.deliberation/invalid-transaction
                         "A proposal requires between 1 and 128 operations" {})}

    :else
    (let [tx (transaction proposal context)]
      (if-let [bad (or (v/validate workspace tx v/payload-stages)
                       (v/validate workspace tx v/basis-stages))]
        {:rejection bad}
        (loop [candidate workspace operations (:tx/operations tx)]
          (if-let [operation (first operations)]
            (let [one (assoc tx :tx/operations [operation]
                             :tx/basis (:workspace/version candidate))]
              (if-let [bad (v/validate candidate one stages)]
                {:rejection bad}
                (recur (commit/commit candidate one) (next operations))))
            {:workspace (commit/commit workspace tx) :transaction tx}))))))
