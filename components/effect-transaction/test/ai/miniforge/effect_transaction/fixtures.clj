;; Title: Miniforge.ai
;; Subtitle: An agentic SDLC / fleet-control platform
;; Author: Christopher Lester
;; Line: Founder, Miniforge.ai (project)
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;;
;; Licensed under the Apache License, Version 2.0 (the "License");
;; you may not use this file except in compliance with the License.
;; You may obtain a copy of the License at
;;
;;     http://www.apache.org/licenses/LICENSE-2.0
;;
;; Unless required by applicable law or agreed to in writing, software
;; distributed under the License is distributed on an "AS IS" BASIS,
;; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
;; See the License for the specific language governing permissions and
;; limitations under the License.
(ns ai.miniforge.effect-transaction.fixtures
  (:require
   [ai.miniforge.effect-transaction.interface :as fx]
   [ai.miniforge.execution-grant.interface :as grant])
  (:import
   [java.nio.file Files]
   [java.nio.file.attribute FileAttribute]
   [java.time Instant]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} now (Instant/parse "2026-08-01T00:00:00Z"))

(def ^{:stratum 0} later (Instant/parse "2026-08-01T01:00:00Z"))

(def ^{:stratum 0} much-later (Instant/parse "2026-08-02T00:00:00Z"))

(def ^{:stratum 0} concurrency-timeout-ms 5000)

(def ^{:stratum 0} merge-proposal
  {:pr/repo "miniforge-ai/miniforge"
   :pr/number 42})

(def ^{:stratum 0} no-usage
  "Caller usage is intentionally absent at the fenced commit boundary."
  {})

(defn ^{:stratum 0} claimed-usage
  "Build an untrusted caller usage claim for fencing tests."
  [count]
  {:usage/count count})

(defn ^{:stratum 0} tmp-dir
  "A fresh directory for one effect-transaction test."
  []
  (str (.toFile (Files/createTempDirectory "fx-test" (into-array FileAttribute [])))))

(defn ^{:stratum 0} succeed!
  []
  {:effect/outcome :succeeded})

(defn ^{:stratum 0} throw-exception!
  [message]
  (throw (ex-info message {})))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} record-success!
  "Record that a test effect fired and report definite success."
  [fired]
  (reset! fired true)
  (succeed!))

(defn ^{:stratum 1} count-success!
  [calls]
  (swap! calls inc)
  (succeed!))

(defn ^{:stratum 1} block-and-count-success!
  [calls entered release]
  (swap! calls inc)
  (deliver entered true)
  @release
  (succeed!))

(defn ^{:stratum 1} merge-grant
  "A merge grant matching the canonical test proposal."
  ([] (merge-grant {}))
  ([overrides]
   (let [effect-id (get overrides :effect-id (random-uuid))
         scope (assoc (get overrides :scope merge-proposal)
                      :effect/id effect-id)
         grant-overrides (dissoc overrides :effect-id :scope)]
     (grant/issue (merge {:principal "agent:implementer"
                          :effect-class :effect/merge
                          :scope scope
                          :constraints {:constraint/max-count 1}
                          :delegable? false
                          :expires-at later}
                         grant-overrides)
                  now))))

(defn ^{:stratum 1} propose-merge!
  "Persist the effect that `merge-grant` authorizes."
  ([dir g] (propose-merge! dir g merge-proposal))
  ([dir g proposal]
   (fx/propose! dir
                {:effect-id (get-in g [:grant/scope :effect/id])
                 :effect-class :effect/merge
                 :grant-id (:grant/id g)
                 :envelope-id (random-uuid)
                 :proposal proposal}
                now)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} merge-case!
  "Create one grant and its exactly bound durable proposal."
  ([dir] (merge-case! dir {}))
  ([dir grant-overrides]
   (let [g (merge-grant grant-overrides)]
     {:grant g
      :transaction (propose-merge! dir g)})))
