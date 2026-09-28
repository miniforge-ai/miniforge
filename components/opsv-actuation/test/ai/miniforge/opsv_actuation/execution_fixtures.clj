;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.opsv-actuation.execution-fixtures
  "Runtime and provider fixtures shared by coordinator acceptance tests."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.decision-envelope.interface :as envelope]
            [ai.miniforge.effect-transaction.interface :as effect]
            [ai.miniforge.execution-grant.interface :as grant]
            [ai.miniforge.opsv-actuation.interface :as actuation]
            [ai.miniforge.opsv-actuation.interface-test :as fixture]
            [clojure.java.io :as io]
            [clojure.test :refer [is]])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]
           [java.time Instant]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} now (Instant/parse "2026-09-27T00:00:00Z"))

(defn ^{:stratum 0} tmp-dir []
  (.getCanonicalPath (.toFile (Files/createTempDirectory "opsv-commit-" (make-array FileAttribute 0)))))

(defn ^{:stratum 0} allowing-envelope []
  (envelope/envelope [] [] {:pins/pack-revision "opsv-test"
                            :pins/rule-ids [:opsv/actuation]
                            :pins/event-watermark 1}))

(defn ^{:stratum 0} effect-report [calls record payload]
  (swap! calls conj [record payload])
  {:effect/outcome :succeeded :effect/observed {:pr/number 17}})

(defn ^{:stratum 0} uncertain-report [calls record payload]
  (swap! calls conj [record payload])
  {:effect/outcome :unknown-outcome})

(defn ^{:stratum 0} grant-request [prepared]
  (merge (dissoc prepared :pr/title :pr/body :pr/draft? :opsv/envelope :opsv/evidence-bundle-id)
         {:workflow-run/status :running
          :effect/class :effect/pr-create
          :effect/preflight {:preflight/type :preflight/pr-create-readiness
                             :preflight/result :allow}}))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} clock [] now)

(defn ^{:stratum 1} setup
  ([] (setup fixture/candidate))
  ([candidate]
   (let [root (tmp-dir)
         dir (str (io/file root "effects"))
         grant-dir (str (io/file root "authority"))
         prepared (actuation/prepare-pr candidate)
         decision (allowing-envelope)
         governed (actuation/prepare-governed-pr candidate decision)
         issued (grant/issue-for-effect grant-dir (grant-request governed) now)
         registered (grant/register! grant-dir issued)]
     (is (not (anomaly/anomaly? registered)))
     {:dir dir :grant-dir grant-dir :candidate candidate :prepared prepared
      :grant registered :decision decision :calls (atom [])})))

(defn ^{:stratum 1} propose! [{:keys [dir candidate grant decision]}]
  (actuation/propose-pr! dir candidate (:grant/id grant) decision now))

(defn ^{:stratum 1} propose-altered! [{:keys [dir candidate grant decision]} alter]
  (let [payload (actuation/prepare-governed-pr candidate decision)
        options {:effect-id (:effect/id candidate)
                 :effect-class :effect/pr-create
                 :grant-id (:grant/id grant)
                 :envelope-id (:envelope/id decision)
                 :proposal (alter payload)}]
    (effect/propose! dir options now)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} commit! [{:keys [dir grant-dir candidate calls]}]
  (actuation/commit-pr! dir grant-dir (:effect/id candidate) clock (partial effect-report calls)))

(comment
  (setup))
