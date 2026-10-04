;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.opsv-provider-fixtures
  "Coordinator/provider acceptance through real stores and a simulated API transport."
  (:require [ai.miniforge.decision-envelope.interface :as envelope]
            [ai.miniforge.execution-grant.interface :as grant]
            [ai.miniforge.opsv-actuation.interface :as actuation]
            [ai.miniforge.opsv-provider-github.interface :as provider]
            [cheshire.core :as json]
            [clojure.java.io :as io])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]
           [java.time Instant]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} now (Instant/parse "2026-09-28T00:00:00Z"))

(def ^{:stratum 0} candidate
  {:workflow-run/id (random-uuid)
   :effect/id (random-uuid)
   :pr/repo "example/opsv"
   :pr/base "main"
   :pr/branch "opsv/scaling"
   :pr/head-sha "0123456789012345678901234567890123456789"
   :pr/title "Review scaling candidate"
   :opsv/policy-diff "HPA target: 70 -> 65"
   :opsv/evidence-bundle-id (random-uuid)
   :opsv/rollback-instructions "Restore the previous revision."
   :opsv/verification-result {:passed? false :confidence :low :caveats [] :criteria-evaluation []}})

(defn- ^{:stratum 0} temp-root []
  (.getCanonicalPath (.toFile (Files/createTempDirectory "opsv-provider-" (make-array FileAttribute 0)))))

(defn ^{:stratum 0} observe [runtime payload record]
  (provider/observe-pr! runtime record payload))

(defn- ^{:stratum 0} provider-pr [payload]
  {:number 17 :html_url "https://github.com/example/opsv/pull/17" :state "open"
   :title (:pr/title payload) :body (:pr/body payload) :draft (:pr/draft? payload)
   :base {:ref (:pr/base payload) :repo {:full_name (:pr/repo payload)}}
   :head {:ref (:pr/branch payload) :sha (:pr/head-sha payload)
          :repo {:full_name (:pr/repo payload)}}})

(defn- ^{:stratum 0} allowing-envelope []
  (envelope/envelope [] [] {:pins/pack-revision "opsv-test"
                            :pins/rule-ids [] :pins/event-watermark 0}))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} authorize! [authority prepared]
  (let [scope-keys (get-in grant/issuance-policies [:effect/pr-create :policy/scope-keys])
        request (assoc (select-keys prepared scope-keys)
                       :workflow-run/status :running :effect/class :effect/pr-create
                       :effect/preflight {:preflight/type :preflight/pr-create-readiness
                                          :preflight/result :allow})
        issued (grant/issue-for-effect authority request now)]
    (grant/register! authority issued)))

(defn ^{:stratum 1} simulated-github [calls payload _options args]
  (swap! calls conj args)
  (let [method (nth args 6)
        head {:ref "refs/heads/opsv/scaling" :object {:type "commit" :sha (:pr/head-sha payload)}}
        listing? (.contains (nth args 2) "/pulls?")
        data (if listing? [[(provider-pr payload)]] head)]
    (if (= "POST" method)
      {:exit 1 :err "Response lost after creation"}
      {:exit 0 :out (json/generate-string data)})))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} setup! []
  (let [root (temp-root)
        effects (str (io/file root "effects"))
        authority (str (io/file root "authority"))
        decision (allowing-envelope)
        prepared (actuation/prepare-governed-pr candidate decision)
        registered (authorize! authority prepared)
        payload (dissoc prepared :effect/id :workflow-run/id :pr/payload-hash
                                :pr/governance-hash :opsv/envelope :opsv/evidence-bundle-id)]
    {:root root :effects effects :authority authority :decision decision
     :grant registered :payload payload}))

(comment
  (provider-pr candidate))
