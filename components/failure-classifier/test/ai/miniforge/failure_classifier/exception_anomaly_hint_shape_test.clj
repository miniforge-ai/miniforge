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

(ns ai.miniforge.failure-classifier.exception-anomaly-hint-shape-test
  "Lock the anomaly-classification hint shape written into ex-data by
   the brick's private `invalid-constructor-input` exception builder
   (taxonomy.clj) after the W2 anomaly convergence flip, and the
   dispatch-equivalence of the new shape against the legacy one.

   Pre-flip the builder wrote `:anomaly/category :anomalies/incorrect`
   into ex-data — the legacy classification key. Post-flip it writes
   `:anomaly/type :invalid-input` directly, because
   `:anomalies/incorrect` is one of the cognitect-standard categories
   that the convergence runbook maps 1:1 to a generic type with no
   subtype.

   The classifier's `classify` dispatch reads `:anomaly/subtype` →
   `:anomaly/category` → `:anomaly/type` (#1001), so both producer
   flips are dispatch-equivalent — this test pins the wire shape so a
   later edit doesn't silently re-introduce `:anomaly/category` on
   producer paths, and confirms `:anomaly/type` alone routes to the
   same `:failure.class/*`.

   `missing-resource` (classifier.clj) is not directly testable —
   it runs at namespace load against a baked-in resource path — but
   its ex-data shape is symmetric to `invalid-constructor-input`'s
   and the dispatch test below pins the canonical-hint-only dispatch
   path it relies on."
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.failure-classifier.interface :as fc]))

;------------------------------------------------------------------------------ Layer 0
;; Named literals + test factories.

(def ^:private incomplete-attribution
  "Attribution map missing `:failure/class` — triggers
   `require-field!` → `invalid-constructor-input` through
   `make-classified-failure`."
  {:failure/message "boom"})

(def ^:private not-found-canonical-hint
  "Canonical anomaly hint for the cognitect-standard `:not-found`
   class — what `missing-resource` writes into ex-data post-flip."
  {:anomaly/type :not-found})

(def ^:private invalid-input-canonical-hint
  "Canonical anomaly hint for the cognitect-standard
   `:invalid-input` class — what `invalid-constructor-input` writes
   into ex-data post-flip."
  {:anomaly/type :invalid-input})

(defn- catch-ex-data
  "Run `thunk`, return the ex-data of any thrown ex-info (nil
   otherwise). Lets the producer assertions stay flat."
  [thunk]
  (try (thunk) nil
       (catch clojure.lang.ExceptionInfo e (ex-data e))))

;------------------------------------------------------------------------------ Layer 1
;; Shape assertions on the post-flip producers.

(deftest invalid-constructor-input-emits-canonical-invalid-input-hint
  (testing ":anomaly/type :invalid-input is written into ex-data"
    (let [data (catch-ex-data #(fc/make-classified-failure incomplete-attribution))]
      (is (some? data) "incomplete attribution throws ex-info")
      (is (= :invalid-input (:anomaly/type data))
          "canonical :anomaly/type is set per the runbook generic-standard map")))

  (testing "legacy hint keys are absent from the producer ex-data"
    ;; `contains?` (not nil-check) — pre-flip the producer wrote
    ;; `:anomaly/category :anomalies/incorrect`, and `(:anomaly/category
    ;; data)` would also return nil when the key were re-introduced with
    ;; an explicit nil value. Pin absence so a regression to either of
    ;; the legacy producer shapes is caught.
    (let [data (catch-ex-data #(fc/make-classified-failure incomplete-attribution))]
      (is (false? (contains? data :anomaly/category))
          ":anomaly/category key is not present on post-flip producer")
      (is (false? (contains? data :anomaly/subtype))
          ":anomalies/incorrect is cognitect-standard, no subtype key attached"))))

;------------------------------------------------------------------------------ Layer 2
;; Dispatch equivalence — the canonical hint is sufficient.

(deftest classifier-dispatch-on-canonical-not-found-hint
  (testing ":anomaly/type :not-found routes identically to :anomalies/not-found"
    (let [legacy-result (fc/classify {:anomaly/category :anomalies/not-found})
          canonical-result (fc/classify not-found-canonical-hint)]
      (is (= :failure.class/data-integrity legacy-result)
          "pre-flip dispatch contract (regression guard for the rules.edn
           parallel canonical entries added alongside the producer flip)")
      (is (= legacy-result canonical-result)
          "canonical hint alone produces the same failure class — guards
           the rules.edn change that makes the producer flip safe"))))

(deftest classifier-dispatch-on-canonical-invalid-input-hint
  (testing ":anomaly/type :invalid-input dispatches to the same class as :anomalies/incorrect"
    (let [legacy-result (fc/classify {:anomaly/category :anomalies/incorrect})
          canonical-result (fc/classify invalid-input-canonical-hint)]
      (is (= legacy-result canonical-result)
          "post-flip ex-data dispatches to the identical failure class
           the pre-flip ex-data would have"))))
