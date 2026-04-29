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

(ns ai.miniforge.pipeline.interface-test
  (:require [clojure.test :refer [deftest is testing]]
            [ai.miniforge.pipeline.interface :as pipe]))

(def ^:private ds-a (java.util.UUID/randomUUID))
(def ^:private ds-b (java.util.UUID/randomUUID))
(def ^:private ds-c (java.util.UUID/randomUUID))
(def ^:private conn-src (java.util.UUID/randomUUID))
(def ^:private conn-sink (java.util.UUID/randomUUID))

(def ^:private stage-1-id (java.util.UUID/randomUUID))
(def ^:private stage-2-id (java.util.UUID/randomUUID))
(def ^:private stage-3-id (java.util.UUID/randomUUID))

(def ^:private valid-stages
  [{:stage/id stage-1-id
    :stage/name "Ingest Data"
    :stage/family :ingest
    :stage/connector-ref conn-src
    :stage/input-datasets []
    :stage/output-datasets [ds-a]
    :stage/dependencies []}
   {:stage/id stage-2-id
    :stage/name "Normalize"
    :stage/family :normalize
    :stage/input-datasets [ds-a]
    :stage/output-datasets [ds-b]
    :stage/dependencies [stage-1-id]}
   {:stage/id stage-3-id
    :stage/name "Publish"
    :stage/family :publish
    :stage/connector-ref conn-sink
    :stage/input-datasets [ds-b]
    :stage/output-datasets []
    :stage/dependencies [stage-2-id]}])

(def ^:private valid-pipeline-opts
  {:pipeline/name "Test Pipeline"
   :pipeline/version "1.0.0"
   :pipeline/stages valid-stages
   :pipeline/mode :full-refresh
   :pipeline/input-datasets []
   :pipeline/output-datasets [ds-b]})

;; ---------------------------------------------------------------------------
;; Constants
;; ---------------------------------------------------------------------------

(deftest stage-families-test
  (testing "N3 §2.2 all stage families"
    (is (= #{:ingest :extract :normalize :transform :aggregate :validate :enrich :publish}
           pipe/stage-families))))

(deftest execution-modes-test
  (testing "N3 §3 execution modes"
    (is (= #{:full-refresh :incremental :backfill :reprocess}
           pipe/execution-modes))))

;; ---------------------------------------------------------------------------
;; validate-stage (direct)
;; ---------------------------------------------------------------------------

(deftest validate-stage-valid-ingest-test
  (testing "Valid ingest stage passes"
    (let [result (pipe/validate-stage
                  {:stage/id (java.util.UUID/randomUUID)
                   :stage/name "Ingest"
                   :stage/family :ingest
                   :stage/connector-ref conn-src
                   :stage/input-datasets []
                   :stage/output-datasets [ds-a]
                   :stage/dependencies []})]
      (is (:success? result)))))

(deftest validate-stage-valid-transform-test
  (testing "Valid transform stage passes"
    (let [result (pipe/validate-stage
                  {:stage/id (java.util.UUID/randomUUID)
                   :stage/name "Transform"
                   :stage/family :transform
                   :stage/input-datasets [ds-a]
                   :stage/output-datasets [ds-b]
                   :stage/dependencies []})]
      (is (:success? result)))))

(deftest validate-stage-valid-publish-test
  (testing "Valid publish stage passes"
    (let [result (pipe/validate-stage
                  {:stage/id (java.util.UUID/randomUUID)
                   :stage/name "Publish"
                   :stage/family :publish
                   :stage/connector-ref conn-sink
                   :stage/input-datasets [ds-b]
                   :stage/output-datasets []
                   :stage/dependencies []})]
      (is (:success? result)))))

(deftest validate-stage-invalid-family-test
  (testing "Reject unknown family"
    (let [result (pipe/validate-stage
                  {:stage/id (java.util.UUID/randomUUID)
                   :stage/name "Bad"
                   :stage/family :unknown
                   :stage/input-datasets [ds-a]
                   :stage/output-datasets [ds-b]
                   :stage/dependencies []})]
      (is (not (:success? result))))))

(deftest validate-stage-ingest-requires-connector-test
  (testing "Ingest without connector-ref fails"
    (let [result (pipe/validate-stage
                  {:stage/id (java.util.UUID/randomUUID)
                   :stage/name "Ingest"
                   :stage/family :ingest
                   :stage/input-datasets []
                   :stage/output-datasets [ds-a]
                   :stage/dependencies []})]
      (is (not (:success? result))))))

(deftest validate-stage-publish-requires-connector-test
  (testing "Publish without connector-ref fails"
    (let [result (pipe/validate-stage
                  {:stage/id (java.util.UUID/randomUUID)
                   :stage/name "Publish"
                   :stage/family :publish
                   :stage/input-datasets [ds-b]
                   :stage/output-datasets []
                   :stage/dependencies []})]
      (is (not (:success? result))))))

(deftest validate-stage-transform-forbids-connector-test
  (testing "Transform with connector-ref fails"
    (let [result (pipe/validate-stage
                  {:stage/id (java.util.UUID/randomUUID)
                   :stage/name "Transform"
                   :stage/family :transform
                   :stage/connector-ref conn-src
                   :stage/input-datasets [ds-a]
                   :stage/output-datasets [ds-b]
                   :stage/dependencies []})]
      (is (not (:success? result))))))

(deftest validate-stage-enrich-forbids-connector-test
  (testing "Enrich with connector-ref fails"
    (let [result (pipe/validate-stage
                  {:stage/id (java.util.UUID/randomUUID)
                   :stage/name "Enrich"
                   :stage/family :enrich
                   :stage/connector-ref conn-src
                   :stage/input-datasets [ds-a]
                   :stage/output-datasets [ds-b]
                   :stage/dependencies []})]
      (is (not (:success? result))))))

(deftest validate-stage-ingest-no-input-datasets-test
  (testing "Ingest with input-datasets fails"
    (let [result (pipe/validate-stage
                  {:stage/id (java.util.UUID/randomUUID)
                   :stage/name "Bad Ingest"
                   :stage/family :ingest
                   :stage/connector-ref conn-src
                   :stage/input-datasets [ds-a]
                   :stage/output-datasets [ds-b]
                   :stage/dependencies []})]
      (is (not (:success? result))))))

(deftest validate-stage-publish-no-output-datasets-test
  (testing "Publish with output-datasets fails"
    (let [result (pipe/validate-stage
                  {:stage/id (java.util.UUID/randomUUID)
                   :stage/name "Bad Publish"
                   :stage/family :publish
                   :stage/connector-ref conn-sink
                   :stage/input-datasets [ds-a]
                   :stage/output-datasets [ds-b]
                   :stage/dependencies []})]
      (is (not (:success? result))))))

(deftest validate-stage-transform-requires-input-test
  (testing "Transform without input-datasets fails"
    (let [result (pipe/validate-stage
                  {:stage/id (java.util.UUID/randomUUID)
                   :stage/name "No Input"
                   :stage/family :transform
                   :stage/input-datasets []
                   :stage/output-datasets [ds-b]
                   :stage/dependencies []})]
      (is (not (:success? result))))))

(deftest validate-stage-transform-requires-output-test
  (testing "Transform without output-datasets fails"
    (let [result (pipe/validate-stage
                  {:stage/id (java.util.UUID/randomUUID)
                   :stage/name "No Output"
                   :stage/family :transform
                   :stage/input-datasets [ds-a]
                   :stage/output-datasets []
                   :stage/dependencies []})]
      (is (not (:success? result))))))

(deftest validate-stage-missing-id-test
  (testing "Missing stage id fails"
    (let [result (pipe/validate-stage
                  {:stage/name "No ID"
                   :stage/family :transform
                   :stage/input-datasets [ds-a]
                   :stage/output-datasets [ds-b]
                   :stage/dependencies []})]
      (is (not (:success? result))))))

(deftest validate-stage-missing-name-test
  (testing "Missing name fails"
    (let [result (pipe/validate-stage
                  {:stage/id (java.util.UUID/randomUUID)
                   :stage/family :transform
                   :stage/input-datasets [ds-a]
                   :stage/output-datasets [ds-b]
                   :stage/dependencies []})]
      (is (not (:success? result))))))

(deftest validate-stage-missing-deps-vector-test
  (testing "Non-vector dependencies fails"
    (let [result (pipe/validate-stage
                  {:stage/id (java.util.UUID/randomUUID)
                   :stage/name "Bad Deps"
                   :stage/family :transform
                   :stage/input-datasets [ds-a]
                   :stage/output-datasets [ds-b]
                   :stage/dependencies #{stage-1-id}})]
      (is (not (:success? result))))))

;; ---------------------------------------------------------------------------
;; create-stage
;; ---------------------------------------------------------------------------

(deftest create-stage-test
  (testing "Valid ingest stage"
    (let [result (pipe/create-stage
                  {:stage/name "Ingest"
                   :stage/family :ingest
                   :stage/connector-ref conn-src
                   :stage/output-datasets [ds-a]})]
      (is (:success? result))
      (is (uuid? (get-in result [:stage :stage/id])))
      ;; Defaults filled in
      (is (= [] (get-in result [:stage :stage/dependencies])))
      (is (= [] (get-in result [:stage :stage/input-datasets])))))

  (testing "Reject ingest without connector"
    (let [result (pipe/create-stage
                  {:stage/name "Bad Ingest"
                   :stage/family :ingest
                   :stage/output-datasets [ds-a]})]
      (is (not (:success? result)))))

  (testing "Reject transform with connector"
    (let [result (pipe/create-stage
                  {:stage/name "Bad Transform"
                   :stage/family :transform
                   :stage/connector-ref conn-src
                   :stage/input-datasets [ds-a]
                   :stage/output-datasets [ds-b]})]
      (is (not (:success? result))))))

(deftest create-stage-auto-defaults-test
  (testing "Auto-fills id, dependencies, input/output-datasets"
    (let [result (pipe/create-stage
                  {:stage/name "Ingest"
                   :stage/family :ingest
                   :stage/connector-ref conn-src
                   :stage/output-datasets [ds-a]})]
      (is (:success? result))
      (is (uuid? (get-in result [:stage :stage/id])))
      (is (= [] (get-in result [:stage :stage/dependencies])))
      (is (= [] (get-in result [:stage :stage/input-datasets]))))))

;; ---------------------------------------------------------------------------
;; validate-pipeline (direct)
;; ---------------------------------------------------------------------------

(deftest validate-pipeline-valid-test
  (testing "Valid pipeline passes validation"
    (let [pipeline (assoc valid-pipeline-opts :pipeline/id (java.util.UUID/randomUUID))
          result (pipe/validate-pipeline pipeline)]
      (is (:success? result)))))

(deftest validate-pipeline-missing-name-test
  (testing "Missing name fails"
    (let [result (pipe/validate-pipeline
                  (-> valid-pipeline-opts
                      (assoc :pipeline/id (java.util.UUID/randomUUID))
                      (dissoc :pipeline/name)))]
      (is (not (:success? result))))))

(deftest validate-pipeline-missing-version-test
  (testing "Missing version fails"
    (let [result (pipe/validate-pipeline
                  (-> valid-pipeline-opts
                      (assoc :pipeline/id (java.util.UUID/randomUUID))
                      (dissoc :pipeline/version)))]
      (is (not (:success? result))))))

(deftest validate-pipeline-invalid-mode-test
  (testing "Invalid mode fails"
    (let [result (pipe/validate-pipeline
                  (assoc valid-pipeline-opts
                         :pipeline/id (java.util.UUID/randomUUID)
                         :pipeline/mode :invalid))]
      (is (not (:success? result))))))

(deftest validate-pipeline-empty-stages-test
  (testing "Empty stages vector fails"
    (let [result (pipe/validate-pipeline
                  (assoc valid-pipeline-opts
                         :pipeline/id (java.util.UUID/randomUUID)
                         :pipeline/stages []))]
      (is (not (:success? result))))))

(deftest validate-pipeline-non-vector-stages-test
  (testing "Non-vector stages fails"
    (let [result (pipe/validate-pipeline
                  (assoc valid-pipeline-opts
                         :pipeline/id (java.util.UUID/randomUUID)
                         :pipeline/stages #{}))]
      (is (not (:success? result))))))

(deftest validate-pipeline-non-vector-input-datasets-test
  (testing "Non-vector input-datasets fails"
    (let [result (pipe/validate-pipeline
                  (assoc valid-pipeline-opts
                         :pipeline/id (java.util.UUID/randomUUID)
                         :pipeline/input-datasets #{}))]
      (is (not (:success? result))))))

(deftest validate-pipeline-non-vector-output-datasets-test
  (testing "Non-vector output-datasets fails"
    (let [result (pipe/validate-pipeline
                  (assoc valid-pipeline-opts
                         :pipeline/id (java.util.UUID/randomUUID)
                         :pipeline/output-datasets nil))]
      (is (not (:success? result))))))

(deftest validate-pipeline-unknown-dependency-test
  (testing "Stage with unknown dependency fails"
    (let [bogus-dep (java.util.UUID/randomUUID)
          stages [(assoc (first valid-stages) :stage/dependencies [bogus-dep])]
          result (pipe/validate-pipeline
                  (assoc valid-pipeline-opts
                         :pipeline/id (java.util.UUID/randomUUID)
                         :pipeline/stages stages))]
      (is (not (:success? result))))))

(deftest validate-pipeline-cycle-test
  (testing "Cyclic stages fail validation"
    (let [id-a (java.util.UUID/randomUUID)
          id-b (java.util.UUID/randomUUID)
          cyclic [{:stage/id id-a :stage/name "A" :stage/family :transform
                   :stage/input-datasets [ds-a] :stage/output-datasets [ds-b]
                   :stage/dependencies [id-b]}
                  {:stage/id id-b :stage/name "B" :stage/family :transform
                   :stage/input-datasets [ds-b] :stage/output-datasets [ds-a]
                   :stage/dependencies [id-a]}]
          result (pipe/validate-pipeline
                  (assoc valid-pipeline-opts
                         :pipeline/id (java.util.UUID/randomUUID)
                         :pipeline/stages cyclic))]
      (is (not (:success? result))))))

;; ---------------------------------------------------------------------------
;; create-pipeline
;; ---------------------------------------------------------------------------

(deftest create-pipeline-test
  (testing "Valid pipeline"
    (let [result (pipe/create-pipeline valid-pipeline-opts)]
      (is (:success? result))
      (is (some? (get-in result [:pipeline :pipeline/id])))
      (is (some? (get-in result [:pipeline :pipeline/created-at])))
      (is (some? (get-in result [:pipeline :pipeline/updated-at])))))

  (testing "Reject invalid mode"
    (let [result (pipe/create-pipeline
                  (assoc valid-pipeline-opts :pipeline/mode :invalid))]
      (is (not (:success? result))))))

(deftest create-pipeline-auto-id-test
  (testing "Auto-generates pipeline id"
    (let [result (pipe/create-pipeline valid-pipeline-opts)]
      (is (:success? result))
      (is (uuid? (get-in result [:pipeline :pipeline/id]))))))

(deftest create-pipeline-preserves-id-test
  (testing "Preserves provided id"
    (let [id (java.util.UUID/randomUUID)
          result (pipe/create-pipeline (assoc valid-pipeline-opts :pipeline/id id))]
      (is (:success? result))
      (is (= id (get-in result [:pipeline :pipeline/id]))))))

;; ---------------------------------------------------------------------------
;; DAG conversion
;; ---------------------------------------------------------------------------

(deftest dag-conversion-test
  (testing "Stages convert to DAG"
    (let [result (pipe/stages->dag valid-stages)]
      (is (:success? result))
      (is (= 3 (count (get-in result [:dag :tasks]))))
      (is (= 2 (count (get-in result [:dag :edges]))))
      (is (= 3 (count (get-in result [:dag :execution-order]))))))

  (testing "Execution order is topological"
    (let [order (pipe/execution-order valid-stages)]
      (is (= 3 (count order)))
      (is (< (.indexOf order stage-1-id) (.indexOf order stage-2-id)))
      (is (< (.indexOf order stage-2-id) (.indexOf order stage-3-id))))))

(deftest dag-single-stage-test
  (testing "Single stage DAG"
    (let [stages [{:stage/id stage-1-id
                   :stage/name "Solo Ingest"
                   :stage/family :ingest
                   :stage/connector-ref conn-src
                   :stage/input-datasets []
                   :stage/output-datasets [ds-a]
                   :stage/dependencies []}]
          result (pipe/stages->dag stages)]
      (is (:success? result))
      (is (= 1 (count (get-in result [:dag :tasks]))))
      (is (= 0 (count (get-in result [:dag :edges])))))))

(deftest dag-diamond-topology-test
  (testing "Diamond DAG topology"
    (let [id-root (java.util.UUID/randomUUID)
          id-left (java.util.UUID/randomUUID)
          id-right (java.util.UUID/randomUUID)
          id-join (java.util.UUID/randomUUID)
          stages [{:stage/id id-root :stage/name "Root" :stage/family :ingest
                   :stage/connector-ref conn-src
                   :stage/input-datasets [] :stage/output-datasets [ds-a]
                   :stage/dependencies []}
                  {:stage/id id-left :stage/name "Left" :stage/family :transform
                   :stage/input-datasets [ds-a] :stage/output-datasets [ds-b]
                   :stage/dependencies [id-root]}
                  {:stage/id id-right :stage/name "Right" :stage/family :transform
                   :stage/input-datasets [ds-a] :stage/output-datasets [ds-c]
                   :stage/dependencies [id-root]}
                  {:stage/id id-join :stage/name "Join" :stage/family :publish
                   :stage/connector-ref conn-sink
                   :stage/input-datasets [ds-b ds-c] :stage/output-datasets []
                   :stage/dependencies [id-left id-right]}]
          result (pipe/stages->dag stages)
          order (pipe/execution-order stages)]
      (is (:success? result))
      (is (= 4 (count (get-in result [:dag :tasks]))))
      (is (= 4 (count (get-in result [:dag :edges]))))
      ;; Root before left and right, both before join
      (is (< (.indexOf order id-root) (.indexOf order id-left)))
      (is (< (.indexOf order id-root) (.indexOf order id-right)))
      (is (< (.indexOf order id-left) (.indexOf order id-join)))
      (is (< (.indexOf order id-right) (.indexOf order id-join))))))

(deftest cycle-detection-test
  (testing "Detect cyclic dependencies"
    (let [id-a (java.util.UUID/randomUUID)
          id-b (java.util.UUID/randomUUID)
          cyclic [{:stage/id id-a :stage/name "A" :stage/family :transform
                   :stage/input-datasets [ds-a] :stage/output-datasets [ds-b]
                   :stage/dependencies [id-b]}
                  {:stage/id id-b :stage/name "B" :stage/family :transform
                   :stage/input-datasets [ds-b] :stage/output-datasets [ds-a]
                   :stage/dependencies [id-a]}]]
      (is (nil? (pipe/execution-order cyclic)))
      (is (not (:success? (pipe/stages->dag cyclic)))))))

(deftest cycle-detection-three-node-test
  (testing "Detect 3-node cycle"
    (let [id-a (java.util.UUID/randomUUID)
          id-b (java.util.UUID/randomUUID)
          id-c (java.util.UUID/randomUUID)
          cyclic [{:stage/id id-a :stage/name "A" :stage/family :transform
                   :stage/input-datasets [ds-a] :stage/output-datasets [ds-b]
                   :stage/dependencies [id-c]}
                  {:stage/id id-b :stage/name "B" :stage/family :transform
                   :stage/input-datasets [ds-b] :stage/output-datasets [ds-c]
                   :stage/dependencies [id-a]}
                  {:stage/id id-c :stage/name "C" :stage/family :transform
                   :stage/input-datasets [ds-c] :stage/output-datasets [ds-a]
                   :stage/dependencies [id-b]}]]
      (is (nil? (pipe/execution-order cyclic)))
      (is (not (:success? (pipe/stages->dag cyclic)))))))
