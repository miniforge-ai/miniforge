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
(ns ai.miniforge.pipeline-runner.preflight-test
  "Preflight rejects unresolved required behavior before any connector opens:
   an ingest → middle → publish pipeline where the middle stage (or the ingest
   stage's spawn fn) names something that does not resolve. Every refusal
   leaves both connectors uncalled. A stage spawned mid-run is not
   preflighted, so the executor refuses it when it declares no processing.
   A declared pass-through leaves a receipt on its stage run. A quality rule
   that throws or returns a malformed verdict fails its stage as an execution
   error."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.miniforge.connector.interface :as conn]
            [ai.miniforge.data-quality.interface :as dq]
            [ai.miniforge.pipeline-runner.interface :as runner]))

;------------------------------------------------------------------------------ Layer 0

;; Recording connectors and fixture values
(defrecord ^{:stratum 0} RecordingConnector [calls]
  conn/Connector
  (connect [_ _config _auth]
    (swap! calls conj :connect)
    {:connection/handle "recording" :connector/status :connected})
  (close [_ _handle]
    {:connector/status :closed})

  conn/SourceConnector
  (discover [_ _handle _opts] {:schemas [] :discover/total-count 0})
  (extract [_ _handle _schema-name _opts]
    (swap! calls conj :extract)
    {:records [{:id "1"} {:id "2"}] :extract/cursor nil :extract/has-more false})
  (checkpoint [_ _handle _connector-id _cursor-state]
    {:checkpoint/status :committed})

  conn/SinkConnector
  (publish [_ _handle _schema-name records _opts]
    (swap! calls conj :publish)
    {:publish/records-written (count records) :publish/records-failed 0}))

(defrecord ^{:stratum 0} SourceOnlyConnector [calls]
  conn/Connector
  (connect [_ _config _auth]
    (swap! calls conj :connect)
    {:connection/handle "source-only" :connector/status :connected})
  (close [_ _handle]
    {:connector/status :closed})

  conn/SourceConnector
  (discover [_ _handle _opts] {:schemas [] :discover/total-count 0})
  (extract [_ _handle _schema-name _opts]
    (swap! calls conj :extract)
    {:records [] :extract/cursor nil :extract/has-more false})
  (checkpoint [_ _handle _connector-id _cursor-state]
    {:checkpoint/status :committed}))

(def ^{:stratum 0} ^:private source-ref (random-uuid))

(def ^{:stratum 0} ^:private sink-ref   (random-uuid))

(def ^{:stratum 0} ^:private ingest-id  (random-uuid))

(def ^{:stratum 0} ^:private middle-id  (random-uuid))

(def ^{:stratum 0} ^:private publish-id (random-uuid))

(def ^{:stratum 0} ^:private ds-raw     (random-uuid))

(def ^{:stratum 0} ^:private ds-mid     (random-uuid))

(def ^{:stratum 0} ^:private pass-through {:stage/pass-through? true})

(defn- ^{:stratum 0} problem-summary [result]
  (mapv #(select-keys % [:stage/id :preflight/problem :preflight/ref])
        (:preflight/problems result)))

(defn- ^{:stratum 0} refused-before-io? [{:keys [result source-calls sink-calls]}]
  (and (false? (:success? result))
       (nil? (:pipeline-run result))
       (empty? source-calls)
       (empty? sink-calls)))

(defn- ^{:stratum 0} pipeline [stages]
  {:pipeline/id (random-uuid)
   :pipeline/name "Preflight Fixture"
   :pipeline/version "1.0.0"
   :pipeline/stages stages
   :pipeline/mode :full-refresh
   :pipeline/input-datasets []
   :pipeline/output-datasets []
   :pipeline/created-at (java.time.Instant/now)
   :pipeline/updated-at (java.time.Instant/now)})

(def ^{:stratum 0} ^:private unprocessed-child-name "Unprocessed child")

(defn- ^{:stratum 0} stage-runs [outcome]
  (get-in outcome [:result :pipeline-run :pipeline-run/stage-runs]))

(defn- ^{:stratum 0} quality-config-with-check
  "Stage config for a validate stage whose single rule runs `check-fn`."
  [check-fn]
  {:stage/quality-pack {:pack/id :fixture-pack
                        :pack/rules [(dq/custom-rule :fixture-rule check-fn)]}})

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} unprocessed-child-spawn
  "Spawn fn: one transform-family child that declares no processing."
  [_records _config]
  [{:spawn/name unprocessed-child-name :spawn/family :transform}])

(defn- ^{:stratum 1} ingest-stage [config]
  {:stage/id ingest-id
   :stage/name "Ingest"
   :stage/family :ingest
   :stage/connector-ref source-ref
   :stage/input-datasets []
   :stage/output-datasets [ds-raw]
   :stage/dependencies []
   :stage/config config})

(defn- ^{:stratum 1} middle-stage [family config]
  {:stage/id middle-id
   :stage/name "Middle"
   :stage/family family
   :stage/input-datasets [ds-raw]
   :stage/output-datasets [ds-mid]
   :stage/dependencies [ingest-id]
   :stage/config config})

(def ^{:stratum 1} ^:private publish-stage
  {:stage/id publish-id
   :stage/name "Publish"
   :stage/family :publish
   :stage/connector-ref sink-ref
   :stage/input-datasets [ds-mid]
   :stage/output-datasets []
   :stage/dependencies [middle-id]})

;; Run a fixture against fresh recording connectors
(defn- ^{:stratum 1} run-fixture
  "Execute `pipeline-def` with recording source and sink connectors.
   Returns the runner result plus the calls each connector saw."
  ([pipeline-def context]
   (run-fixture pipeline-def context ->RecordingConnector))
  ([pipeline-def context sink-ctor]
   (let [source-calls (atom [])
         sink-calls   (atom [])
         connectors   {source-ref (->RecordingConnector source-calls)
                       sink-ref   (sink-ctor sink-calls)}
         result       (runner/execute-pipeline pipeline-def connectors context)]
     {:result       result
      :source-calls @source-calls
      :sink-calls   @sink-calls})))

(defn- ^{:stratum 1} stage-run [outcome stage-id]
  (first (filter #(= stage-id (:stage/id %)) (stage-runs outcome))))

;------------------------------------------------------------------------------ Layer 2

;; Tests
(deftest ^{:stratum 2} test-unresolved-transform-refused-before-io
  (testing "given a transform type with no registered fn → configuration failure, no connector called"
    (let [middle  (middle-stage :transform {:stage/transform {:transform/type :no-such-transform}})
          outcome (run-fixture (pipeline [(ingest-stage {}) middle publish-stage]) {:transforms {}})]
      (is (refused-before-io? outcome))
      (is (= [{:stage/id middle-id :preflight/problem :transform/unresolved :preflight/ref :no-such-transform}]
             (problem-summary (:result outcome)))))))

(deftest ^{:stratum 2} test-unresolved-spawn-fn-refused-before-io
  (testing "given a spawn fn that is not registered → configuration failure, no connector called"
    (let [ingest  (ingest-stage {:stage/spawn-fn :no-such-spawn})
          outcome (run-fixture (pipeline [ingest (middle-stage :normalize pass-through) publish-stage]) {})]
      (is (refused-before-io? outcome))
      (is (= [{:stage/id ingest-id :preflight/problem :spawn-fn/unresolved :preflight/ref :no-such-spawn}]
             (problem-summary (:result outcome)))))))

(deftest ^{:stratum 2} test-unknown-quality-rule-refused-before-io
  (testing "given a quality rule with no implementation → configuration failure, no connector called"
    (let [pack    {:pack/id :fixture-pack
                   :pack/rules [(dq/required-rule :id)
                                {:rule/type :custom :rule/id :no-such-rule}]}
          middle  (middle-stage :validate {:stage/quality-pack pack})
          outcome (run-fixture (pipeline [(ingest-stage {}) middle publish-stage]) {})]
      (is (refused-before-io? outcome))
      (is (= [{:stage/id middle-id :preflight/problem :quality-rule/unresolved :preflight/ref :no-such-rule}]
             (problem-summary (:result outcome)))))))

(deftest ^{:stratum 2} test-validator-without-pack-refused-before-io
  (testing "given a validate stage with no pack and no pass-through → configuration failure"
    (let [outcome (run-fixture (pipeline [(ingest-stage {}) (middle-stage :validate {}) publish-stage]) {})]
      (is (refused-before-io? outcome))
      (is (= [{:stage/id middle-id :preflight/problem :processing/undeclared :preflight/ref :validate}]
             (problem-summary (:result outcome)))))))

(deftest ^{:stratum 2} test-empty-quality-pack-refused-before-io
  (testing "given a quality pack with no rules → configuration failure"
    (let [middle  (middle-stage :validate {:stage/quality-pack {:pack/id :empty-pack :pack/rules []}})
          outcome (run-fixture (pipeline [(ingest-stage {}) middle publish-stage]) {})]
      (is (refused-before-io? outcome))
      (is (= [{:stage/id middle-id :preflight/problem :quality-pack/empty :preflight/ref :empty-pack}]
             (problem-summary (:result outcome)))))))

(deftest ^{:stratum 2} test-pass-through-with-transform-is-a-conflict
  (testing "given pass-through and a transform on one stage → conflict"
    (let [config  {:stage/pass-through? true :stage/transform {:transform/type :double}}
          middle  (middle-stage :transform config)
          outcome (run-fixture (pipeline [(ingest-stage {}) middle publish-stage]) {:transforms {:double identity}})]
      (is (refused-before-io? outcome))
      (is (= [{:stage/id middle-id :preflight/problem :pass-through/conflict :preflight/ref :transform}]
             (problem-summary (:result outcome)))))))

(deftest ^{:stratum 2} test-sink-without-publish-role-refused-before-io
  (testing "given a publish stage bound to a source-only connector → role failure"
    (let [outcome (run-fixture (pipeline [(ingest-stage {}) (middle-stage :normalize pass-through) publish-stage])
                               {}
                               ->SourceOnlyConnector)]
      (is (refused-before-io? outcome))
      (is (= [{:stage/id publish-id :preflight/problem :connector/role :preflight/ref :connector/sink}]
             (problem-summary (:result outcome)))))))

(deftest ^{:stratum 2} test-every-preflight-problem-reported
  (testing "given two unresolved stages → both problems reported, not only the first"
    (let [ingest  (ingest-stage {:stage/spawn-fn :no-such-spawn})
          middle  (middle-stage :transform {})
          outcome (run-fixture (pipeline [ingest middle publish-stage]) {})]
      (is (refused-before-io? outcome))
      (is (= #{:spawn-fn/unresolved :processing/undeclared}
             (set (map :preflight/problem (:preflight/problems (:result outcome))))))
      (is (string? (:error (:result outcome)))))))

(deftest ^{:stratum 2} test-declared-pass-through-runs-with-receipt
  (testing "given an explicit pass-through → both connectors run and the stage run records it"
    (doseq [family [:normalize :validate]]
      (let [middle     (middle-stage family pass-through)
            outcome    (run-fixture (pipeline [(ingest-stage {}) middle publish-stage]) {})
            middle-run (stage-run outcome middle-id)]
        (is (:success? (:result outcome)) (str family))
        (is (= [:connect :extract] (:source-calls outcome)))
        (is (= [:connect :publish] (:sink-calls outcome)))
        (is (= :completed (:status middle-run)))
        (is (true? (:pass-through? middle-run)) (str family))))))

(deftest ^{:stratum 2} test-spawned-stage-without-processing-fails
  (testing "given a spawn fn that adds a transform-family child with no processing → the child fails, nothing publishes"
    (let [ingest    (ingest-stage {:stage/spawn-fn :unprocessed})
          middle    (middle-stage :normalize pass-through)
          outcome   (run-fixture (pipeline [ingest middle publish-stage])
                                 {:spawn-fns {:unprocessed unprocessed-child-spawn}})
          child-run (first (filter #(= unprocessed-child-name (:stage/name %))
                                   (stage-runs outcome)))]
      (is (false? (:success? (:result outcome))))
      (is (= :failed (:status child-run)))
      (is (= :configuration (:error-kind child-run)))
      (is (empty? (:sink-calls outcome))))))

(deftest ^{:stratum 2} test-preflight-accepts-resolved-pipeline
  (testing "given every named behavior resolves → preflight succeeds with no problems"
    (let [middle     (middle-stage :transform {:stage/transform {:transform/type :double}})
          connectors {source-ref (->RecordingConnector (atom []))
                      sink-ref   (->RecordingConnector (atom []))}
          result     (runner/preflight (pipeline [(ingest-stage {}) middle publish-stage])
                                       connectors
                                       {:transforms {:double identity}})]
      (is (:success? result))
      (is (= [] (get-in result [:preflight :preflight/problems]))))))

(deftest ^{:stratum 2} test-broken-quality-rule-fails-the-stage
  (testing "given a rule that throws or returns a malformed verdict → the validate stage fails as an execution error, nothing publishes"
    (doseq [[check-fn kind] [[(fn [_] (throw (ex-info "boom" {}))) :rule/threw]
                             [(constantly {:valid? "yes"})          :rule/malformed-verdict]]]
      (let [outcome    (run-fixture (pipeline [(ingest-stage {}) (middle-stage :validate (quality-config-with-check check-fn)) publish-stage]) {})
            middle-run (stage-run outcome middle-id)]
        (is (false? (:success? (:result outcome))))
        (is (= :failed (:status middle-run)))
        (is (= :execution (:error-kind middle-run)))
        (is (= #{kind} (set (map :error/kind (:quality/rule-errors middle-run)))))
        (is (empty? (:sink-calls outcome)))))))

(comment
  (clojure.test/run-tests))
