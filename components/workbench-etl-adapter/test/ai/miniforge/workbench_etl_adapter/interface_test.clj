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

(ns ai.miniforge.workbench-etl-adapter.interface-test
  (:require
   [ai.miniforge.schema.interface :as schema]
   [ai.miniforge.workbench-etl-adapter.interface :as sut]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 0

(def ^:private completed-at
  "Stable fixture instant shared by generated snapshot timestamps."
  "2026-07-18T12:00:00Z")

(def ^:private pipeline-run-id
  "Stable run identity used by evidence and snapshot fixtures."
  #uuid "00000000-0000-0000-0000-000000000001")

(def ^:private pipeline-version
  "Fixture workflow version required by the snapshot contract."
  "1.0.0")

(def ^:private quality-report-total
  "Record population used to exercise a non-perfect quality score."
  10)

(def ^:private quality-report-passed
  "Accepted fixture records, chosen to land on the shipped warning threshold."
  9)

(def ^:private projection-opts
  "Minimum provenance required for a baseline workbench projection."
  {:experiment-id "etl.fixture"
   :label "baseline"
   :source-hashes ["sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"]})

(def ^:private quality-state-var-id
  "Registry key whose operator thresholds drive record-quality status."
  "miniforge.etl.data_quality_pass_rate")

(def ^:private quality-policy-override
  "Test-only policy proving evaluation reads thresholds from registry data."
  {:pass 0.9 :warn 0.8})

(def ^:private shipped-pack-factor-expectations
  "Reproducible non-secret and redacted factor counts for the ETL packs in
   this repository; these are the defensible N values guarded by the adapter."
  [{:pipeline "packs/data-foundry/github-data/pipelines/github-extract.edn"
    :environment "packs/data-foundry/github-data/envs/local.edn"
    :factor-count 75
    :redacted-count 5}
   {:pipeline "packs/data-foundry/gitlab-data/pipelines/gitlab-extract.edn"
    :environment "packs/data-foundry/gitlab-data/envs/local.edn"
    :factor-count 103
    :redacted-count 8}
   {:pipeline "packs/data-foundry/risk-data/pipelines/fred-risk-data.edn"
    :environment "packs/data-foundry/risk-data/envs/fred-local.edn"
    :factor-count 133
   :redacted-count 2}])

(defn- quality-report []
  {:report/pack-id :test
   :report/total quality-report-total
   :report/passed quality-report-passed
   :report/failed (- quality-report-total quality-report-passed)})

(defn- stage-run
  [stage-name status & {:as extra}]
  (merge {:stage/name stage-name :status status} extra))

(defn- pipeline-result
  [& {:keys [success? status stage-runs]
      :or {success? true
           status :completed
           stage-runs [(stage-run "Ingest" :completed)
                       (stage-run "Validate" :completed
                                  :quality-report (quality-report))]}}]
  {:success? success?
   :pipeline-run
   {:pipeline-run/id pipeline-run-id
    :pipeline-run/status status
    :pipeline-run/created-at completed-at
    :pipeline-run/completed-at completed-at
    :pipeline-run/stage-runs stage-runs}})

(defn- resolved-run-config
  [& {:keys [mode stages file-format]
      :or {mode :full-refresh stages [] file-format :json}}]
  {:pipeline {:pipeline/name "Fixture"
              :pipeline/version pipeline-version
              :pipeline/mode mode
              :pipeline/stages stages}
   :environment {:env/name "test"
                 :env/connectors {:conn/source {:connector/type :file}}
                 :env/stages {"Ingest" {:file/path "fixture.json"
                                        :file/format file-format
                                        :auth/credential-id "must-not-leak"}}}})

(def ^:private successful-result
  "Completed ETL fixture with one record-level quality report."
  (pipeline-result))

(def ^:private baseline-config
  "Resolved-run fixture containing one credential-bearing leaf."
  (resolved-run-config))

(defn- repo-root []
  (loop [dir (.getAbsoluteFile (io/file "."))]
    (cond
      (nil? dir) nil
      (.exists (io/file dir "workspace.edn")) dir
      :else (recur (.getParentFile dir)))))

(defn- read-edn [path]
  (edn/read-string (slurp path)))

;------------------------------------------------------------------------------ Layer 1

(deftest test-shipped-registry-is-contract-valid
  (is (sut/valid-registry?)))

(deftest test-inventory-redacts-credentials
  (let [inventory (sut/factor-inventory baseline-config)
        header-inventory
        (sut/factor-inventory
         {:environment {:http/headers {"Authorization" "must-not-leak"}}})]
    (is (= 1 (:redacted_count inventory)))
    (is (not-any? #(re-find #"credential-id|must-not-leak" %)
                  (mapcat identity (:values inventory))))
    (is (= 1 (:redacted_count header-inventory)))
    (is (empty? (:values header-inventory)))))

(deftest test-project-emits-shipped-etl-evaluations
  (let [result   (sut/project successful-result baseline-config projection-opts)
        snapshot (:snapshot result)
        quality  (last (:evaluations snapshot))]
    (is (schema/succeeded? result))
    (is (sut/valid-snapshot? snapshot))
    (is (= (count (:state_vars (sut/state-var-registry)))
           (count (:evaluations snapshot))))
    (is (= "warn" (:status quality)))
    (is (= (double (/ quality-report-passed quality-report-total))
           (:score quality)))
    (is (= 1 (get-in snapshot [:metadata :resolved_run :redacted_count])))))

(deftest test-not-applicable-quality-components-match-registry
  (let [result  (sut/project (update-in successful-result
                                        [:pipeline-run :pipeline-run/stage-runs]
                                        #(mapv (fn [stage] (dissoc stage :quality-report)) %))
                             baseline-config projection-opts)
        quality (last (:evaluations (:snapshot result)))]
    (is (= "not_applicable" (:status quality)))
    (is (= {:passed_records 0.0 :evaluated_records 0.0 :failed_records 0.0}
           (:score_components quality)))))

(deftest test-evaluation-thresholds-come-from-registry-data
  (let [registry (update (sut/state-var-registry) :state_vars
                         (fn [state-vars]
                           (mapv #(if (= quality-state-var-id (:id %))
                                    (assoc % :thresholds quality-policy-override)
                                    %)
                                 state-vars)))]
    (with-redefs [sut/state-var-registry (constantly registry)]
      (let [result  (sut/project successful-result baseline-config projection-opts)
            quality (last (:evaluations (:snapshot result)))]
        (is (schema/succeeded? result))
        (is (= "pass" (:status quality)))
        (is (= "none" (:gate_effect quality)))))))

(deftest test-stage-score-includes-unexecuted-configured-stages
  (let [failed-result (pipeline-result
                       :success? false
                       :status :failed
                       :stage-runs [(stage-run "Ingest" :completed)
                                    (stage-run "Transform" :failed)])
        configured-stages [{:stage/name "Ingest"}
                           {:stage/name "Transform"}
                           {:stage/name "Publish"}]
        three-stage-config (resolved-run-config :stages configured-stages)
        result (sut/project failed-result three-stage-config projection-opts)
        stages (second (:evaluations (:snapshot result)))]
    (is (schema/succeeded? result))
    (is (= (/ 1.0 3.0) (:score stages)))
    (is (= (double (count (get-in three-stage-config
                                  [:pipeline :pipeline/stages])))
           (get-in stages [:score_components :total_stages])))))

(deftest test-candidate-requires-exactly-one-factor-change
  (let [baseline-values (:values (sut/factor-inventory baseline-config))
        one-change      (assoc-in baseline-config
                                  [:environment :env/stages "Ingest" :file/format]
                                  :edn)
        two-changes     (assoc-in one-change [:pipeline :pipeline/mode] :incremental)
        one-result      (sut/project successful-result one-change
                                     (assoc projection-opts
                                            :label "candidate"
                                            :baseline-factor-values baseline-values))
        zero-result     (sut/project successful-result baseline-config
                                     (assoc projection-opts
                                            :baseline-factor-values baseline-values))
        two-result      (sut/project successful-result two-changes
                                     (assoc projection-opts
                                            :baseline-factor-values baseline-values))]
    (is (schema/succeeded? one-result))
    (is (= "[:environment :env/stages \"Ingest\" :file/format]"
           (get-in one-result [:snapshot :variant :axes :factor_id])))
    (is (schema/failed? zero-result))
    (is (= 0 (:change-count zero-result)))
    (is (schema/failed? two-result))
    (is (= 2 (:change-count two-result)))))

(deftest test-malformed-baseline-values-return-failure
  (let [result (sut/project successful-result baseline-config
                            (assoc projection-opts :baseline-factor-values "not-a-map"))]
    (is (schema/failed? result))
    (is (= "ETL workbench baseline factor values must be a map" (:error result)))))

(deftest test-added-factor-uses-canonical-missing-value
  (let [change (first (sut/factor-diff {} {"[:new-factor]" "true"}))]
    (is (= :ai.miniforge.workbench-etl-adapter.config/missing-factor
           (edn/read-string (:baseline change))))))

(deftest ^:integration test-shipped-pack-factor-counts-are-reproducible
  (testing "given shipped ETL packs → concrete factor inventories remain stable"
    (let [root (repo-root)]
      (doseq [{:keys [pipeline environment factor-count redacted-count]}
              shipped-pack-factor-expectations]
        (let [config {:pipeline (read-edn (io/file root pipeline))
                      :environment (read-edn (io/file root environment))}
              inventory (sut/factor-inventory config)]
          (is (= factor-count (:factor_count inventory)) pipeline)
          (is (= redacted-count (:redacted_count inventory)) environment))))))
