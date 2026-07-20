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

(def ^:private completed-at "2026-07-18T12:00:00Z")

(def ^:private successful-result
  {:success? true
   :pipeline-run
   {:pipeline-run/id #uuid "00000000-0000-0000-0000-000000000001"
    :pipeline-run/status :completed
    :pipeline-run/created-at completed-at
    :pipeline-run/completed-at completed-at
    :pipeline-run/stage-runs
    [{:stage/name "Ingest" :status :completed}
     {:stage/name "Validate" :status :completed
      :quality-report {:report/pack-id :test
                       :report/total 10
                       :report/passed 9
                       :report/failed 1}}]}})

(def ^:private baseline-config
  {:pipeline {:pipeline/name "Fixture"
              :pipeline/version "1.0.0"
              :pipeline/mode :full-refresh
              :pipeline/stages []}
   :environment {:env/name "test"
                 :env/connectors {:conn/source {:connector/type :file}}
                 :env/stages {"Ingest" {:file/path "fixture.json"
                                        :file/format :json
                                        :auth/credential-id "must-not-leak"}}}})

(def ^:private projection-opts
  {:experiment-id "etl.fixture"
   :label "baseline"
   :source-hashes ["sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"]})

(defn- repo-root []
  (loop [dir (.getAbsoluteFile (io/file "."))]
    (cond
      (nil? dir) nil
      (.exists (io/file dir "workspace.edn")) dir
      :else (recur (.getParentFile dir)))))

(defn- read-edn [path]
  (edn/read-string (slurp path)))

(deftest shipped-registry-is-contract-valid
  (is (sut/valid-registry?)))

(deftest inventory-redacts-credentials
  (let [inventory (sut/factor-inventory baseline-config)
        header-inventory
        (sut/factor-inventory
         {:environment {:http/headers {"Authorization" "must-not-leak"}}})]
    (is (= 1 (:redacted_count inventory)))
    (is (not-any? #(re-find #"credential-id|must-not-leak" %)
                  (mapcat identity (:values inventory))))
    (is (= 1 (:redacted_count header-inventory)))
    (is (empty? (:values header-inventory)))))

(deftest project-emits-three-valid-etl-evaluations
  (let [result   (sut/project successful-result baseline-config projection-opts)
        snapshot (:snapshot result)
        quality  (last (:evaluations snapshot))]
    (is (schema/succeeded? result))
    (is (sut/valid-snapshot? snapshot))
    (is (= 3 (count (:evaluations snapshot))))
    (is (= "warn" (:status quality)))
    (is (= 0.9 (:score quality)))
    (is (= 1 (get-in snapshot [:metadata :resolved_run :redacted_count])))))

(deftest not-applicable-quality-components-match-registry
  (let [result  (sut/project (update-in successful-result
                                        [:pipeline-run :pipeline-run/stage-runs]
                                        #(mapv (fn [stage] (dissoc stage :quality-report)) %))
                             baseline-config projection-opts)
        quality (last (:evaluations (:snapshot result)))]
    (is (= "not_applicable" (:status quality)))
    (is (= {:passed_records 0.0 :evaluated_records 0.0 :failed_records 0.0}
           (:score_components quality)))))

(deftest stage-score-includes-unexecuted-configured-stages
  (let [failed-result
        (-> successful-result
            (assoc :success? false)
            (assoc-in [:pipeline-run :pipeline-run/status] :failed)
            (assoc-in [:pipeline-run :pipeline-run/stage-runs]
                      [{:stage/name "Ingest" :status :completed}
                       {:stage/name "Transform" :status :failed}]))
        three-stage-config
        (assoc-in baseline-config [:pipeline :pipeline/stages]
                  [{:stage/name "Ingest"}
                   {:stage/name "Transform"}
                   {:stage/name "Publish"}])
        result (sut/project failed-result three-stage-config projection-opts)
        stages (second (:evaluations (:snapshot result)))]
    (is (schema/succeeded? result))
    (is (= (/ 1.0 3.0) (:score stages)))
    (is (= 3.0 (get-in stages [:score_components :total_stages])))))

(deftest candidate-requires-exactly-one-factor-change
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

(deftest malformed-baseline-values-return-failure
  (let [result (sut/project successful-result baseline-config
                            (assoc projection-opts :baseline-factor-values "not-a-map"))]
    (is (schema/failed? result))
    (is (= "ETL workbench baseline factor values must be a map" (:error result)))))

(deftest added-factor-uses-canonical-missing-value
  (let [change (first (sut/factor-diff {} {"[:new-factor]" "42"}))]
    (is (= :ai.miniforge.workbench-etl-adapter.config/missing-factor
           (edn/read-string (:baseline change))))))

(deftest shipped-pack-factor-counts-are-reproducible
  (testing "current GitHub, GitLab, and risk-data packs have a concrete N"
    (let [root (repo-root)]
      (doseq [[pipeline env expected-factors expected-redacted]
              [["packs/data-foundry/github-data/pipelines/github-extract.edn"
                "packs/data-foundry/github-data/envs/local.edn" 75 5]
               ["packs/data-foundry/gitlab-data/pipelines/gitlab-extract.edn"
                "packs/data-foundry/gitlab-data/envs/local.edn" 103 8]
               ["packs/data-foundry/risk-data/pipelines/fred-risk-data.edn"
                "packs/data-foundry/risk-data/envs/fred-local.edn" 133 2]]]
        (let [config {:pipeline (read-edn (io/file root pipeline))
                      :environment (read-edn (io/file root env))}
              inventory (sut/factor-inventory config)]
          (is (= expected-factors (:factor_count inventory)) pipeline)
          (is (= expected-redacted (:redacted_count inventory)) env))))))
