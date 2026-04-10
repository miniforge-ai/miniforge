(ns ai.miniforge.data-foundry.pipeline-runner.interface-test
  (:require [clojure.test :refer [deftest is testing]]
            [ai.miniforge.data-foundry.pipeline-runner.interface :as runner]
            [ai.miniforge.data-foundry.connector.interface :as conn]
            [ai.miniforge.data-foundry.data-quality.interface :as dq]))

;; ---------------------------------------------------------------------------
;; Constants
;; ---------------------------------------------------------------------------

(deftest run-statuses-test
  (testing "N3 §2.4 run statuses"
    (is (= #{:pending :executing :completed :failed :cancelled}
           runner/run-statuses))))

;; ---------------------------------------------------------------------------
;; Mock connectors
;; ---------------------------------------------------------------------------

(defrecord MockSourceConnector [extract-result]
  conn/Connector
  (connect [_ config auth]
    {:connection/handle "mock-handle"
     :connector/status :connected
     :connection/opened-at (java.time.Instant/now)})
  (close [_ handle]
    {:connector/status :closed
     :connection/closed-at (java.time.Instant/now)})

  conn/SourceConnector
  (discover [_ handle opts] {:schemas [] :discover/total-count 0})
  (extract [_ handle schema-name opts]
    (or extract-result
        {:records [{:id "1" :value 100} {:id "2" :value 200}]
         :extract/cursor {:cursor/type :offset :cursor/value 2}
         :extract/has-more false
         :extract/row-count 2
         :extract/completed-at (java.time.Instant/now)}))
  (checkpoint [_ handle connector-id cursor-state]
    {:checkpoint/id (java.util.UUID/randomUUID)
     :checkpoint/created (java.time.Instant/now)
     :checkpoint/status :committed}))

(defrecord MockSinkConnector [publish-result]
  conn/Connector
  (connect [_ config auth]
    {:connection/handle "mock-sink-handle"
     :connector/status :connected
     :connection/opened-at (java.time.Instant/now)})
  (close [_ handle]
    {:connector/status :closed
     :connection/closed-at (java.time.Instant/now)})

  conn/SinkConnector
  (publish [_ handle schema-name records opts]
    (or publish-result
        {:publish/records-written (count records)
         :publish/records-failed 0})))

(defrecord FailingSourceConnector []
  conn/Connector
  (connect [_ config auth]
    {:connection/handle "fail-handle"
     :connector/status :connected})
  (close [_ handle]
    {:connector/status :closed})

  conn/SourceConnector
  (discover [_ handle opts] {:schemas []})
  (extract [_ handle schema-name opts]
    (throw (ex-info "Connection refused" {:cause :network})))
  (checkpoint [_ handle connector-id cursor-state] {}))

(defrecord InspectableSourceConnector [calls extract-fn]
  conn/Connector
  (connect [_ _config _auth]
    {:connection/handle "inspect-handle"
     :connector/status :connected
     :connection/opened-at (java.time.Instant/now)})
  (close [_ _handle]
    {:connector/status :closed
     :connection/closed-at (java.time.Instant/now)})

  conn/SourceConnector
  (discover [_ _handle _opts] {:schemas [] :discover/total-count 0})
  (extract [_ _handle schema-name opts]
    (swap! calls conj {:schema-name schema-name :opts opts})
    (extract-fn schema-name opts))
  (checkpoint [_ _handle _connector-id _cursor-state]
    {:checkpoint/status :committed}))

;; ---------------------------------------------------------------------------
;; Test fixtures
;; ---------------------------------------------------------------------------

(def ^:private ds-a (java.util.UUID/randomUUID))
(def ^:private ds-b (java.util.UUID/randomUUID))
(def ^:private ds-c (java.util.UUID/randomUUID))
(def ^:private conn-src (java.util.UUID/randomUUID))
(def ^:private conn-sink (java.util.UUID/randomUUID))

(def ^:private stage-1-id (java.util.UUID/randomUUID))
(def ^:private stage-2-id (java.util.UUID/randomUUID))
(def ^:private stage-3-id (java.util.UUID/randomUUID))
(def ^:private stage-5-id (java.util.UUID/randomUUID))
(def ^:private stage-6-id (java.util.UUID/randomUUID))

(def ^:private test-pipeline
  {:pipeline/id (java.util.UUID/randomUUID)
   :pipeline/name "Test Pipeline"
   :pipeline/version "1.0.0"
   :pipeline/stages
   [{:stage/id stage-1-id
     :stage/name "Ingest"
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
     :stage/dependencies [stage-1-id]}]
   :pipeline/mode :full-refresh
   :pipeline/input-datasets []
   :pipeline/output-datasets [ds-b]
   :pipeline/created-at (java.time.Instant/now)
   :pipeline/updated-at (java.time.Instant/now)})

(def ^:private three-stage-pipeline
  {:pipeline/id (java.util.UUID/randomUUID)
   :pipeline/name "Three Stage Pipeline"
   :pipeline/version "1.0.0"
   :pipeline/stages
   [{:stage/id stage-1-id
     :stage/name "Ingest"
     :stage/family :ingest
     :stage/connector-ref conn-src
     :stage/input-datasets []
     :stage/output-datasets [ds-a]
     :stage/dependencies []}
    {:stage/id stage-2-id
     :stage/name "Transform"
     :stage/family :transform
     :stage/input-datasets [ds-a]
     :stage/output-datasets [ds-b]
     :stage/dependencies [stage-1-id]}
    {:stage/id stage-3-id
     :stage/name "Publish"
     :stage/family :publish
     :stage/connector-ref conn-sink
     :stage/input-datasets [ds-b]
     :stage/output-datasets []
     :stage/dependencies [stage-2-id]}]
   :pipeline/mode :full-refresh
   :pipeline/input-datasets []
   :pipeline/output-datasets []
   :pipeline/created-at (java.time.Instant/now)
   :pipeline/updated-at (java.time.Instant/now)})

(def ^:private multi-ingest-pipeline
  {:pipeline/id (java.util.UUID/randomUUID)
   :pipeline/name "Multi Ingest Pipeline"
   :pipeline/version "1.0.0"
   :pipeline/stages
   [{:stage/id stage-5-id
     :stage/name "Ingest Issues"
     :stage/family :ingest
     :stage/connector-ref conn-src
     :stage/input-datasets []
     :stage/output-datasets [ds-a]
     :stage/dependencies []
     :stage/config {:gitlab/resource "issues"}}
    {:stage/id stage-6-id
     :stage/name "Ingest Merge Requests"
     :stage/family :ingest
     :stage/connector-ref conn-src
     :stage/input-datasets []
     :stage/output-datasets [ds-b]
     :stage/dependencies []
     :stage/config {:gitlab/resource "merge-requests"}}]
   :pipeline/mode :incremental
   :pipeline/input-datasets []
   :pipeline/output-datasets [ds-a ds-b]
   :pipeline/created-at (java.time.Instant/now)
   :pipeline/updated-at (java.time.Instant/now)})

;; ---------------------------------------------------------------------------
;; create-pipeline-run
;; ---------------------------------------------------------------------------

(deftest create-pipeline-run-test
  (testing "Create valid pipeline run"
    (let [result (runner/create-pipeline-run
                  {:pipeline-run/pipeline-id (java.util.UUID/randomUUID)
                   :pipeline-run/version "1.0.0"
                   :pipeline-run/mode :full-refresh})]
      (is (:success? result))
      (is (= :pending (get-in result [:pipeline-run :pipeline-run/status])))
      (is (uuid? (get-in result [:pipeline-run :pipeline-run/id])))
      (is (inst? (get-in result [:pipeline-run :pipeline-run/created-at])))))

  (testing "Defaults mode to :full-refresh when nil"
    (let [result (runner/create-pipeline-run
                  {:pipeline-run/pipeline-id (java.util.UUID/randomUUID)
                   :pipeline-run/version "1.0.0"})]
      (is (:success? result))
      (is (= :full-refresh (get-in result [:pipeline-run :pipeline-run/mode])))))

  (testing "Initializes empty collections"
    (let [result (runner/create-pipeline-run
                  {:pipeline-run/pipeline-id (java.util.UUID/randomUUID)
                   :pipeline-run/version "1.0.0"
                   :pipeline-run/mode :full-refresh})
          run (:pipeline-run result)]
      (is (= [] (:pipeline-run/input-dataset-versions run)))
      (is (= [] (:pipeline-run/output-dataset-versions run)))
      (is (= [] (:pipeline-run/stage-runs run)))
      (is (= {} (:pipeline-run/connector-cursors run)))
      (is (= [] (:pipeline-run/policy-evaluations run))))))

;; ---------------------------------------------------------------------------
;; validate-pipeline-run
;; ---------------------------------------------------------------------------

(deftest validate-pipeline-run-valid-test
  (testing "Valid pipeline run passes"
    (let [run {:pipeline-run/id (java.util.UUID/randomUUID)
               :pipeline-run/pipeline-id (java.util.UUID/randomUUID)
               :pipeline-run/version "1.0.0"
               :pipeline-run/mode :full-refresh
               :pipeline-run/status :pending}]
      (is (:valid? (runner/validate-pipeline-run run))))))

(deftest validate-pipeline-run-missing-id-test
  (testing "Missing id fails"
    (let [result (runner/validate-pipeline-run
                  {:pipeline-run/pipeline-id (java.util.UUID/randomUUID)
                   :pipeline-run/version "1.0.0"
                   :pipeline-run/mode :full-refresh
                   :pipeline-run/status :pending})]
      (is (not (:valid? result))))))

(deftest validate-pipeline-run-missing-pipeline-id-test
  (testing "Missing pipeline-id fails"
    (let [result (runner/validate-pipeline-run
                  {:pipeline-run/id (java.util.UUID/randomUUID)
                   :pipeline-run/version "1.0.0"
                   :pipeline-run/mode :full-refresh
                   :pipeline-run/status :pending})]
      (is (not (:valid? result))))))

(deftest validate-pipeline-run-missing-version-test
  (testing "Missing version fails"
    (let [result (runner/validate-pipeline-run
                  {:pipeline-run/id (java.util.UUID/randomUUID)
                   :pipeline-run/pipeline-id (java.util.UUID/randomUUID)
                   :pipeline-run/mode :full-refresh
                   :pipeline-run/status :pending})]
      (is (not (:valid? result))))))

(deftest validate-pipeline-run-invalid-mode-test
  (testing "Invalid mode fails"
    (let [result (runner/validate-pipeline-run
                  {:pipeline-run/id (java.util.UUID/randomUUID)
                   :pipeline-run/pipeline-id (java.util.UUID/randomUUID)
                   :pipeline-run/version "1.0.0"
                   :pipeline-run/mode :invalid
                   :pipeline-run/status :pending})]
      (is (not (:valid? result))))))

(deftest validate-pipeline-run-invalid-status-test
  (testing "Invalid status fails"
    (let [result (runner/validate-pipeline-run
                  {:pipeline-run/id (java.util.UUID/randomUUID)
                   :pipeline-run/pipeline-id (java.util.UUID/randomUUID)
                   :pipeline-run/version "1.0.0"
                   :pipeline-run/mode :full-refresh
                   :pipeline-run/status :unknown})]
      (is (not (:valid? result))))))

(deftest validate-pipeline-run-multiple-errors-test
  (testing "Multiple validation errors accumulate"
    (let [result (runner/validate-pipeline-run {})]
      (is (not (:valid? result)))
      (is (>= (count (:errors result)) 3)))))

;; ---------------------------------------------------------------------------
;; execute-pipeline — success paths
;; ---------------------------------------------------------------------------

(deftest execute-pipeline-success-test
  (testing "Execute pipeline with mock connector (full-refresh)"
    (let [mock-conn (->MockSourceConnector nil)
          connectors {conn-src mock-conn}
          result (runner/execute-pipeline test-pipeline connectors {})]
      (is (:success? result))
      (is (= :completed (get-in result [:pipeline-run :pipeline-run/status])))
      (is (= 2 (count (get-in result [:pipeline-run :pipeline-run/stage-runs]))))
      (is (every? #(= :completed (:status %))
                  (get-in result [:pipeline-run :pipeline-run/stage-runs]))))))

(deftest execute-pipeline-stage-order-test
  (testing "Stages execute in topological order"
    (let [mock-conn (->MockSourceConnector nil)
          result (runner/execute-pipeline test-pipeline {conn-src mock-conn} {})
          stage-runs (get-in result [:pipeline-run :pipeline-run/stage-runs])]
      (is (= "Ingest" (:stage/name (first stage-runs))))
      (is (= "Normalize" (:stage/name (second stage-runs)))))))

(deftest execute-pipeline-cursors-captured-test
  (testing "Cursor from ingest stage is captured by stage id"
    (let [mock-conn (->MockSourceConnector nil)
          result (runner/execute-pipeline test-pipeline {conn-src mock-conn} {})
          cursors (get-in result [:pipeline-run :pipeline-run/connector-cursors])]
      (is (some? (get cursors stage-1-id)))
      (is (= conn-src (get-in cursors [stage-1-id :stage/connector-ref])))
      (is (= "Ingest" (get-in cursors [stage-1-id :stage/name])))
      (is (= "Ingest" (get-in cursors [stage-1-id :stage/schema-name])))
      (is (some? (get-in cursors [stage-1-id :cursor :cursor/value]))))))

(deftest execute-pipeline-resumes-from-stage-cursor-test
  (testing "Prior stage cursor is passed back into extract"
    (let [calls (atom [])
          prior {:cursor/type :offset :cursor/value 42}
          connector (->InspectableSourceConnector
                     calls
                     (fn [_schema-name _opts]
                       {:records [{:id "1"}]
                        :extract/cursor {:cursor/type :offset :cursor/value 43}
                        :extract/has-more false
                        :extract/row-count 1}))
          _result (runner/execute-pipeline
                   test-pipeline
                   {conn-src connector}
                   {:pipeline-run/connector-cursors
                    {stage-1-id {:cursor prior}}})]
      (is (= prior (get-in (first @calls) [:opts :extract/cursor]))))))

(deftest execute-pipeline-separates-cursors-for-shared-connector-test
  (testing "Multiple ingest stages sharing one connector keep separate cursors"
    (let [calls (atom [])
          connector (->InspectableSourceConnector
                     calls
                     (fn [schema-name _opts]
                       {:records [{:schema schema-name}]
                        :extract/cursor {:cursor/type :timestamp-watermark
                                         :cursor/value (str schema-name "-cursor")}
                        :extract/has-more false
                        :extract/row-count 1}))
          result (runner/execute-pipeline multi-ingest-pipeline {conn-src connector} {})
          cursors (get-in result [:pipeline-run :pipeline-run/connector-cursors])]
      (is (= "issues" (get-in cursors [stage-5-id :stage/schema-name])))
      (is (= "merge-requests" (get-in cursors [stage-6-id :stage/schema-name])))
      (is (= "issues-cursor" (get-in cursors [stage-5-id :cursor :cursor/value])))
      (is (= "merge-requests-cursor" (get-in cursors [stage-6-id :cursor :cursor/value]))))))

(deftest execute-pipeline-timestamps-test
  (testing "Pipeline run has start/complete timestamps"
    (let [mock-conn (->MockSourceConnector nil)
          result (runner/execute-pipeline test-pipeline {conn-src mock-conn} {})
          run (:pipeline-run result)]
      (is (inst? (:pipeline-run/started-at run)))
      (is (inst? (:pipeline-run/completed-at run))))))

;; ---------------------------------------------------------------------------
;; execute-pipeline — three stage with publish
;; ---------------------------------------------------------------------------

(deftest execute-three-stage-pipeline-test
  (testing "Ingest -> Transform -> Publish pipeline"
    (let [mock-source (->MockSourceConnector nil)
          mock-sink (->MockSinkConnector nil)
          connectors {conn-src mock-source conn-sink mock-sink}
          result (runner/execute-pipeline three-stage-pipeline connectors {})]
      (is (:success? result))
      (is (= :completed (get-in result [:pipeline-run :pipeline-run/status])))
      (is (= 3 (count (get-in result [:pipeline-run :pipeline-run/stage-runs]))))
      (is (every? #(= :completed (:status %))
                  (get-in result [:pipeline-run :pipeline-run/stage-runs]))))))

;; ---------------------------------------------------------------------------
;; execute-pipeline — failure paths
;; ---------------------------------------------------------------------------

(deftest execute-pipeline-missing-connector-test
  (testing "Pipeline fails when connector missing"
    (let [result (runner/execute-pipeline test-pipeline {} {})]
      (is (not (:success? result)))
      (is (= :failed (get-in result [:pipeline-run :pipeline-run/status]))))))

(deftest execute-pipeline-connector-exception-test
  (testing "Pipeline fails when connector throws exception"
    (let [failing-conn (->FailingSourceConnector)
          result (runner/execute-pipeline test-pipeline {conn-src failing-conn} {})]
      (is (not (:success? result)))
      (is (= :failed (get-in result [:pipeline-run :pipeline-run/status])))
      ;; First stage should be failed
      (let [stage-runs (get-in result [:pipeline-run :pipeline-run/stage-runs])]
        (is (= :failed (:status (first stage-runs))))
        (is (some? (:error-message (first stage-runs))))))))

(deftest execute-pipeline-stops-on-failure-test
  (testing "Pipeline stops executing after first stage failure"
    (let [failing-conn (->FailingSourceConnector)
          result (runner/execute-pipeline test-pipeline {conn-src failing-conn} {})
          stage-runs (get-in result [:pipeline-run :pipeline-run/stage-runs])]
      ;; Only ingest stage should have run (failed); normalize should not have run
      (is (= 1 (count stage-runs)))
      (is (= "Ingest" (:stage/name (first stage-runs)))))))

(deftest execute-pipeline-publish-missing-connector-test
  (testing "Publish stage fails when sink connector missing"
    (let [mock-source (->MockSourceConnector nil)
          ;; Source exists but sink does not
          connectors {conn-src mock-source}
          result (runner/execute-pipeline three-stage-pipeline connectors {})]
      (is (not (:success? result)))
      (is (= :failed (get-in result [:pipeline-run :pipeline-run/status])))
      ;; Ingest and transform should succeed, publish should fail
      (let [stage-runs (get-in result [:pipeline-run :pipeline-run/stage-runs])]
        (is (= :completed (:status (first stage-runs))))
        (is (= :completed (:status (second stage-runs))))
        (is (= :failed (:status (nth stage-runs 2))))))))

(deftest execute-pipeline-invalid-pipeline-test
  (testing "Invalid pipeline definition returns validation errors"
    (let [bad-pipeline (assoc test-pipeline :pipeline/stages [])
          result (runner/execute-pipeline bad-pipeline {} {})]
      (is (not (:success? result)))
      (is (seq (:errors result))))))

(deftest execute-pipeline-custom-extract-result-test
  (testing "Custom extract result flows through pipeline"
    (let [custom-records [{:id "A"} {:id "B"} {:id "C"}]
          mock-conn (->MockSourceConnector
                     {:records custom-records
                      :extract/cursor {:cursor/type :offset :cursor/value 3}
                      :extract/has-more false
                      :extract/row-count 3})
          result (runner/execute-pipeline test-pipeline {conn-src mock-conn} {})]
      (is (:success? result))
      (is (= :completed (get-in result [:pipeline-run :pipeline-run/status]))))))

;; ---------------------------------------------------------------------------
;; execute-pipeline — validate stage
;; ---------------------------------------------------------------------------

(def ^:private stage-4-id (java.util.UUID/randomUUID))
(def ^:private ds-d (java.util.UUID/randomUUID))

(def ^:private validate-pipeline
  {:pipeline/id (java.util.UUID/randomUUID)
   :pipeline/name "Pipeline with Validate"
   :pipeline/version "1.0.0"
   :pipeline/stages
   [{:stage/id stage-1-id
     :stage/name "Ingest"
     :stage/family :ingest
     :stage/connector-ref conn-src
     :stage/input-datasets []
     :stage/output-datasets [ds-a]
     :stage/dependencies []}
    {:stage/id stage-4-id
     :stage/name "Validate"
     :stage/family :validate
     :stage/input-datasets [ds-a]
     :stage/output-datasets [ds-d]
     :stage/dependencies [stage-1-id]
     :stage/config {:stage/quality-pack
                    {:pack/id :test-quality
                     :pack/rules [(dq/required-rule :id)]}}}]
   :pipeline/mode :full-refresh
   :pipeline/input-datasets []
   :pipeline/output-datasets [ds-d]
   :pipeline/created-at (java.time.Instant/now)
   :pipeline/updated-at (java.time.Instant/now)})

(deftest execute-pipeline-validate-stage-test
  (testing "Validate stage filters records and produces quality report"
    (let [mock-conn (->MockSourceConnector
                     {:records [{:id "1" :value 100}
                                {:id nil :value 200}
                                {:id "3" :value 300}]
                      :extract/cursor {:cursor/type :offset :cursor/value 3}
                      :extract/has-more false
                      :extract/row-count 3})
          result (runner/execute-pipeline validate-pipeline {conn-src mock-conn} {})]
      (is (:success? result))
      (is (= :completed (get-in result [:pipeline-run :pipeline-run/status])))
      ;; Both stages should complete
      (let [stage-runs (get-in result [:pipeline-run :pipeline-run/stage-runs])]
        (is (= 2 (count stage-runs)))
        (is (every? #(= :completed (:status %)) stage-runs))
        ;; Validate stage should have quality-report
        (let [validate-run (second stage-runs)]
          (is (some? (:quality-report validate-run)))
          (is (= :test-quality (get-in validate-run [:quality-report :report/pack-id])))
          (is (= 3 (get-in validate-run [:quality-report :report/total])))
          (is (= 1 (get-in validate-run [:quality-report :report/failed]))))))))

(deftest execute-pipeline-validate-no-pack-test
  (testing "Validate stage without quality pack is pass-through"
    (let [pipeline-no-pack
          (assoc-in validate-pipeline
                    [:pipeline/stages 1 :stage/config] {})
          mock-conn (->MockSourceConnector nil)
          result (runner/execute-pipeline pipeline-no-pack {conn-src mock-conn} {})]
      (is (:success? result))
      (is (= :completed (get-in result [:pipeline-run :pipeline-run/status]))))))

;; ---------------------------------------------------------------------------
;; execute-pipeline — transform stage with registered function
;; ---------------------------------------------------------------------------

(def ^:private stage-t-id (java.util.UUID/randomUUID))
(def ^:private ds-t (java.util.UUID/randomUUID))

(def ^:private transform-pipeline
  {:pipeline/id (java.util.UUID/randomUUID)
   :pipeline/name "Pipeline with Transform"
   :pipeline/version "1.0.0"
   :pipeline/stages
   [{:stage/id stage-1-id
     :stage/name "Ingest"
     :stage/family :ingest
     :stage/connector-ref conn-src
     :stage/input-datasets []
     :stage/output-datasets [ds-a]
     :stage/dependencies []}
    {:stage/id stage-t-id
     :stage/name "Derive"
     :stage/family :transform
     :stage/input-datasets [ds-a]
     :stage/output-datasets [ds-t]
     :stage/dependencies [stage-1-id]
     :stage/config
     {:stage/transform
      {:transform/type :test-double}}}]
   :pipeline/mode :full-refresh
   :pipeline/input-datasets []
   :pipeline/output-datasets [ds-t]
   :pipeline/created-at (java.time.Instant/now)
   :pipeline/updated-at (java.time.Instant/now)})

(deftest execute-pipeline-transform-fn-test
  (testing "Transform stage calls registered transform function"
    (let [double-fn (fn [records _config]
                      (mapv #(update % :value * 2) records))
          mock-conn (->MockSourceConnector
                     {:records [{:id "1" :value 10} {:id "2" :value 20}]
                      :extract/cursor nil :extract/has-more false :extract/row-count 2})
          result (runner/execute-pipeline
                  transform-pipeline {conn-src mock-conn}
                  {:transforms {:test-double double-fn}})]
      (is (:success? result))
      (is (= :completed (get-in result [:pipeline-run :pipeline-run/status]))))))

(deftest execute-pipeline-transform-passthrough-test
  (testing "Transform stage without matching fn is pass-through"
    (let [mock-conn (->MockSourceConnector nil)
          result (runner/execute-pipeline
                  transform-pipeline {conn-src mock-conn}
                  {:transforms {}})]
      (is (:success? result))
      (is (= :completed (get-in result [:pipeline-run :pipeline-run/status]))))))

;; ---------------------------------------------------------------------------
;; Built-in transforms — derived-ratio
;; ---------------------------------------------------------------------------

(deftest derived-ratio-transform-test
  (testing "derived-ratio computes ratio from two series"
    (let [input [{:date "2025-01-01" :series_id "NCBEILQ027S" :value "70000"}
                 {:date "2025-01-01" :series_id "GDP" :value "28000"}
                 {:date "2025-04-01" :series_id "NCBEILQ027S" :value "72000"}
                 {:date "2025-04-01" :series_id "GDP" :value "28500"}]
          config {:stage/transform
                  {:transform/type              :derived-ratio
                   :transform/numerator-series  "NCBEILQ027S"
                   :transform/denominator-series "GDP"
                   :transform/numerator-scale   0.001
                   :transform/output-scale      100.0
                   :transform/output-series     "BUFFETT_INDICATOR"}}
          result ((runner/built-in-transforms :derived-ratio) input config)
          derived (filter #(= "BUFFETT_INDICATOR" (:series_id %)) result)]
      ;; (70000 * 0.001) / 28000 * 100 = 70/28000*100 = 0.25
      (is (= 2 (count derived)))
      (is (= "BUFFETT_INDICATOR" (:series_id (first derived))))
      ;; Q1: 70000*0.001 = 70, 70/28000*100 = 0.25
      (is (= "0.25" (:value (first (filter #(= "2025-01-01" (:date %)) derived)))))
      ;; Q2: 72000*0.001 = 72, 72/28500*100 ≈ 0.25
      ;; Original input records should also be present
      (is (= 6 (count result))))))

(deftest derived-ratio-handles-dot-values-test
  (testing "derived-ratio skips FRED dot-values"
    (let [input [{:date "2025-01-01" :series_id "A" :value "100"}
                 {:date "2025-01-01" :series_id "B" :value "."}]
          config {:stage/transform
                  {:transform/type              :derived-ratio
                   :transform/numerator-series  "A"
                   :transform/denominator-series "B"
                   :transform/output-series     "X"}}
          result ((runner/built-in-transforms :derived-ratio) input config)
          derived (filter #(= "X" (:series_id %)) result)]
      ;; No derived records since denominator is "."
      (is (empty? derived)))))

;; ---------------------------------------------------------------------------
;; execute-pipeline — spawn-requests (dependent ingest fan-out)
;; ---------------------------------------------------------------------------

(def ^:private spawn-parent-id (java.util.UUID/randomUUID))
(def ^:private conn-parent (java.util.UUID/randomUUID))
(def ^:private conn-child  (java.util.UUID/randomUUID))
(def ^:private ds-parent   (java.util.UUID/randomUUID))

(def ^:private spawn-pipeline
  "Parent ingest stage fans out one child ingest per output record."
  {:pipeline/id (java.util.UUID/randomUUID)
   :pipeline/name "Spawn Pipeline"
   :pipeline/version "1.0.0"
   :pipeline/stages
   [{:stage/id spawn-parent-id
     :stage/name "Ingest Issues"
     :stage/family :ingest
     :stage/connector-ref conn-parent
     :stage/input-datasets []
     :stage/output-datasets [ds-parent]
     :stage/dependencies []
     :stage/config {:gitlab/resource "issues"
                    :stage/spawn-fn :notes-per-issue}}]
   :pipeline/mode :full-refresh
   :pipeline/input-datasets []
   :pipeline/output-datasets [ds-parent]
   :pipeline/created-at (java.time.Instant/now)
   :pipeline/updated-at (java.time.Instant/now)})

(defn- issue->notes-spawn
  "Build a spawn descriptor for notes-per-issue with an explicit connector-ref."
  [issue]
  {:spawn/name         (str "Notes for Issue #" (:iid issue))
   :spawn/family       :ingest
   :spawn/connector-ref conn-child
   :spawn/config       {:gitlab/resource "notes"
                         :gitlab/issue-iid (:iid issue)}})

(defn- issue->notes-spawn-short
  "Build a spawn descriptor with a shorter name label."
  [issue]
  {:spawn/name         (str "Notes #" (:iid issue))
   :spawn/family       :ingest
   :spawn/connector-ref conn-child
   :spawn/config       {:gitlab/resource "notes"
                         :gitlab/noteable-iid (:iid issue)}})

(defn- issue->notes-spawn-inherit
  "Build a spawn descriptor that omits :spawn/connector-ref (inherits from parent)."
  [issue]
  {:spawn/name   (str "Notes for Issue #" (:iid issue))
   :spawn/family :ingest
   :spawn/config {:gitlab/resource     "notes"
                  :gitlab/noteable-kind "issues"
                  :gitlab/noteable-iid  (:iid issue)}})

(deftest execute-pipeline-spawn-creates-child-stages-test
  (testing "Spawn fn fires for each issue record, child stages run and complete"
    (let [issues-conn (->MockSourceConnector
                       {:records [{:iid 1 :title "Bug 1"}
                                  {:iid 2 :title "Bug 2"}]
                        :extract/cursor nil
                        :extract/has-more false
                        :extract/row-count 2})
          notes-conn  (->MockSourceConnector
                       {:records [{:id "n1" :body "a note"}]
                        :extract/cursor nil
                        :extract/has-more false
                        :extract/row-count 1})
          spawn-fn    (fn [records _config]
                        (map issue->notes-spawn records))
          result (runner/execute-pipeline
                  spawn-pipeline
                  {conn-parent issues-conn conn-child notes-conn}
                  {:spawn-fns {:notes-per-issue spawn-fn}})]
      (is (:success? result))
      (is (= :completed (get-in result [:pipeline-run :pipeline-run/status])))
      ;; 1 parent + 2 spawned child stages = 3 total
      (let [runs (get-in result [:pipeline-run :pipeline-run/stage-runs])]
        (is (= 3 (count runs)))
        (is (every? #(= :completed (:status %)) runs))
        ;; Parent ran first
        (is (= "Ingest Issues" (:stage/name (first runs))))
        ;; Both children ran
        (let [child-names (set (map :stage/name (rest runs)))]
          (is (contains? child-names "Notes for Issue #1"))
          (is (contains? child-names "Notes for Issue #2")))))))

(deftest execute-pipeline-no-spawn-fn-test
  (testing "Stage without :stage/spawn-fn runs normally — no spawning"
    (let [mock-conn (->MockSourceConnector nil)
          result    (runner/execute-pipeline test-pipeline {conn-src mock-conn} {})]
      (is (:success? result))
      (is (= 2 (count (get-in result [:pipeline-run :pipeline-run/stage-runs])))))))

(deftest execute-pipeline-spawn-child-failure-test
  (testing "Failure in a spawned child stage fails the pipeline"
    (let [issues-conn (->MockSourceConnector
                       {:records [{:iid 1 :title "Bug 1"}]
                        :extract/cursor nil
                        :extract/has-more false
                        :extract/row-count 1})
          failing-notes (->FailingSourceConnector)
          spawn-fn    (fn [records _config]
                        (map issue->notes-spawn records))
          result (runner/execute-pipeline
                  spawn-pipeline
                  {conn-parent issues-conn conn-child failing-notes}
                  {:spawn-fns {:notes-per-issue spawn-fn}})]
      (is (not (:success? result)))
      (is (= :failed (get-in result [:pipeline-run :pipeline-run/status]))))))

;; ---------------------------------------------------------------------------
;; execute-pipeline — spawn downstream propagation (fan-out → collect → publish)
;; ---------------------------------------------------------------------------

(def ^:private spawn-fanout-id (java.util.UUID/randomUUID))
(def ^:private spawn-pub-id    (java.util.UUID/randomUUID))
(def ^:private ds-fanout       (java.util.UUID/randomUUID))

(def ^:private spawn-with-publish-pipeline
  "Parent ingest spawns child stages; a publish stage collects from all of them."
  {:pipeline/id (java.util.UUID/randomUUID)
   :pipeline/name "Spawn With Publish"
   :pipeline/version "1.0.0"
   :pipeline/stages
   [{:stage/id spawn-fanout-id
     :stage/name "Ingest Issues"
     :stage/family :ingest
     :stage/connector-ref conn-parent
     :stage/input-datasets []
     :stage/output-datasets [ds-fanout]
     :stage/dependencies []
     :stage/config {:stage/spawn-fn :notes-per-issue}}
    {:stage/id spawn-pub-id
     :stage/name "Publish"
     :stage/family :publish
     :stage/connector-ref conn-sink
     :stage/input-datasets [ds-fanout]
     :stage/output-datasets []
     :stage/dependencies [spawn-fanout-id]}]
   :pipeline/mode :full-refresh
   :pipeline/input-datasets []
   :pipeline/output-datasets []
   :pipeline/created-at (java.time.Instant/now)
   :pipeline/updated-at (java.time.Instant/now)})

(deftest execute-pipeline-spawn-downstream-collects-test
  (testing "Downstream stage waits for and collects records from all spawned children"
    (let [published-records (atom nil)
          issues-conn  (->MockSourceConnector
                        {:records [{:iid 1 :title "Issue A"}
                                   {:iid 2 :title "Issue B"}]
                         :extract/cursor nil
                         :extract/has-more false
                         :extract/row-count 2})
          notes-conn   (->MockSourceConnector
                        {:records [{:id "note-1" :body "note body"}]
                         :extract/cursor nil
                         :extract/has-more false
                         :extract/row-count 1})
          ;; Capture what the publish stage receives
          sink-conn    (reify
                         conn/Connector
                         (connect [_ _ _] {:connection/handle "sink-h"
                                           :connector/status :connected
                                           :connection/opened-at (java.time.Instant/now)})
                         (close [_ _] {:connector/status :closed
                                       :connection/closed-at (java.time.Instant/now)})
                         conn/SinkConnector
                         (publish [_ _ _ records _]
                           (reset! published-records records)
                           {:publish/records-written (count records)
                            :publish/records-failed 0}))
          spawn-fn     (fn [records _config]
                         (map issue->notes-spawn-short records))
          result (runner/execute-pipeline
                  spawn-with-publish-pipeline
                  {conn-parent issues-conn conn-child notes-conn conn-sink sink-conn}
                  {:spawn-fns {:notes-per-issue spawn-fn}})]
      (is (:success? result))
      (is (= :completed (get-in result [:pipeline-run :pipeline-run/status])))
      ;; 1 parent + 2 spawned + 1 publish = 4 total stage-runs
      (let [runs (get-in result [:pipeline-run :pipeline-run/stage-runs])]
        (is (= 4 (count runs)))
        (is (every? #(= :completed (:status %)) runs))
        (is (= "Publish" (:stage/name (last runs)))))
      ;; Publish received records from all spawned children (2 × 1 note = 2)
      ;; plus the parent issues (2), totalling 4 records
      (is (some? @published-records))
      (is (= 4 (count @published-records))))))

(deftest execute-pipeline-spawn-inherits-connector-ref-test
  (testing "Spawned stage inherits parent connector-ref when spawn omits :spawn/connector-ref"
    (let [notes-conn  (->MockSourceConnector
                       {:records [{:id "n1" :body "a note"}]
                        :extract/cursor nil
                        :extract/has-more false
                        :extract/row-count 1})
          ;; Parent connector returns two issues
          issues-conn (->MockSourceConnector
                       {:records [{:iid 10 :title "Issue A"}
                                  {:iid 11 :title "Issue B"}]
                        :extract/cursor nil
                        :extract/has-more false
                        :extract/row-count 2})
          ;; Spawn fn omits :spawn/connector-ref — should inherit conn-parent
          spawn-fn    (fn [records _config]
                        (map issue->notes-spawn-inherit records))
          result (runner/execute-pipeline
                  spawn-pipeline
                  ;; Parent connector also handles "notes" for the spawned stages
                  {conn-parent (->MockSourceConnector
                                {:records [{:iid 10 :title "Issue A"}
                                           {:iid 11 :title "Issue B"}]
                                 :extract/cursor nil
                                 :extract/has-more false
                                 :extract/row-count 2})}
                  {:spawn-fns {:notes-per-issue spawn-fn}})]
      ;; Pipeline should complete — spawned stages use the inherited connector-ref
      (is (:success? result))
      (is (= :completed (get-in result [:pipeline-run :pipeline-run/status])))
      ;; 1 parent + 2 spawned = 3 total stage-runs
      (let [runs (get-in result [:pipeline-run :pipeline-run/stage-runs])]
        (is (= 3 (count runs)))
        (is (every? #(= :completed (:status %)) runs))))))
