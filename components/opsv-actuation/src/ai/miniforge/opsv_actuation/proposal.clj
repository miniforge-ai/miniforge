;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.opsv-actuation.proposal
  "Pure construction of the exact provider payload to authorize."
  (:require [ai.miniforge.content-hash.interface :as content-hash]
            [ai.miniforge.opsv-actuation.messages :as msg]
            [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} provider-target-keys
  "Provider fields retained verbatim in the payload and its digest."
  [:pr/repo :pr/base :pr/branch :pr/head-sha :pr/title])

(defn- ^{:stratum 0} verified?
  [{:keys [passed? criteria-evaluation]}]
  (and passed? (seq criteria-evaluation)
       (every? :criterion/passed? criteria-evaluation)))

(defn- ^{:stratum 0} body
  [input merge-eligible?]
  (let [bundle-id (:opsv/evidence-bundle-id input)
        verification (:opsv/verification-result input)
        status-key (if merge-eligible? :pr/verified :pr/ineligible)]
    (str/join "\n\n"
              [(msg/t :pr/policy-heading) (:opsv/policy-diff input)
               (msg/t :pr/evidence {:bundle-id bundle-id})
               (msg/t :pr/verification-heading) (msg/t status-key)
               (content-hash/canonical-edn verification)
               (msg/t :pr/rollback-heading) (:opsv/rollback-instructions input)])))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} provider-payload
  [input]
  (let [merge-eligible? (verified? (:opsv/verification-result input))
        description (body input merge-eligible?)]
    (assoc (select-keys input provider-target-keys)
           :pr/body description
           :pr/draft? (not merge-eligible?))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} prepare-pr
  [input]
  (let [payload (provider-payload input)
        digest (content-hash/content-hash payload)
        identity (select-keys input [:workflow-run/id :effect/id])]
    (assoc (merge identity payload) :pr/payload-hash digest)))

(comment
  (verified? {:passed? false :criteria-evaluation []}))
