;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.opsv-actuation.governance
  "Bind the provider digest, evidence join and normalized runtime decision."
  (:require [ai.miniforge.content-hash.interface :as hash]
            [ai.miniforge.opsv-actuation.proposal :as proposal])
  (:import [java.util Date]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} integrity-keys
  [:workflow-run/id :effect/id :pr/payload-hash :opsv/evidence-bundle-id :opsv/policy-hash :opsv/envelope])

(defn- ^{:stratum 0} portable-time [timestamp]
  (Date. (inst-ms timestamp)))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} digest [prepared]
  (hash/content-hash (select-keys prepared integrity-keys)))

(defn- ^{:stratum 1} receipt [candidate decision]
  (assoc (merge (proposal/prepare-pr candidate) (select-keys candidate [:opsv/policy-hash]))
         :opsv/evidence-bundle-id (:opsv/evidence-bundle-id candidate)
         :opsv/envelope (update decision :envelope/at portable-time)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} prepare [candidate decision]
  (let [prepared (receipt candidate decision)]
    (assoc prepared :pr/governance-hash (digest prepared))))

(comment
  (digest {}))
