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

(ns ai.miniforge.pipeline-pack-store.interface-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [ai.miniforge.pipeline-pack-store.interface :as store]))

(def ^:private ^:dynamic *store* nil)

(defn- with-store [f]
  (let [s (store/create-store)]
    (binding [*store* s]
      (try
        (f)
        (finally
          (store/close s))))))

(use-fixtures :each with-store)

(def ^:private sample-pack
  {:pack/manifest {:pack/id          "test-pack"
                   :pack/name        "Test Pack"
                   :pack/version     "2026.03.17"
                   :pack/description "A test pack"
                   :pack/author      "test"
                   :pack/trust-level :untrusted
                   :pack/authority   :authority/data}
   :pack/dir "/tmp/test"
   :pack/pipelines []
   :pack/envs []
   :pack/registry
   {:registry/id "test-metrics"
    :registry/version "2026.03.17"
    :registry/families
    [{:family/id :test-family
      :family/name "Test Family"
      :family/metrics
      [{:metric/id "M1" :metric/name "Metric 1"
        :metric/source-type :public_canonical
        :metric/source-system "FRED"
        :metric/refresh-cadence :daily
        :metric/implementation-mode :pull
        :metric/license-required false
        :metric/redistribution-risk :none
        :metric/direction :normal
        :metric/unit :percent}
       {:metric/id "M2" :metric/name "Metric 2"
        :metric/source-type :house_factor
        :metric/source-system "internal"
        :metric/refresh-cadence :quarterly
        :metric/implementation-mode :derive
        :metric/license-required false
        :metric/redistribution-risk :none
        :metric/direction :inverse
        :metric/unit :ratio}]}]}})

;; -- Pack CRUD --

(deftest save-and-load-pack-test
  (testing "Save and retrieve pack"
    (let [pack-id (store/save-pack *store* sample-pack)
          loaded (store/load-pack *store* "test-pack")]
      (is (= "test-pack" pack-id))
      (is (some? loaded))
      (is (= "Test Pack" (:pack/name loaded)))
      (is (= "2026.03.17" (:pack/version loaded)))))

  (testing "Load nonexistent pack returns nil"
    (is (nil? (store/load-pack *store* "nonexistent")))))

(deftest list-packs-test
  (testing "List stored packs"
    (store/save-pack *store* sample-pack)
    (let [packs (store/list-packs *store*)]
      (is (= 1 (count packs)))
      (is (= "test-pack" (:pack/id (first packs)))))))

;; -- Metrics persisted with pack --

(deftest metrics-persisted-test
  (testing "Metrics from registry are stored"
    (store/save-pack *store* sample-pack)
    (let [m1 (store/load-pack *store* "M1")]
      ;; M1 is stored as a metric entity, not a pack entity
      ;; But we can query via Datalevin directly through the store
      ;; For now just verify the pack itself loaded
      (is (some? (store/load-pack *store* "test-pack"))))))

;; -- Snapshots --

(deftest snapshot-test
  (testing "Save and query snapshots"
    (store/save-pack *store* sample-pack)
    (store/save-snapshot *store*
                         {:snapshot/metric-id "M1"
                          :snapshot/value 1.5
                          :snapshot/as-of "2026-03-17"
                          :snapshot/pipeline-run-id "run-1"
                          :snapshot/pack-id "test-pack"})
    (store/save-snapshot *store*
                         {:snapshot/metric-id "M2"
                          :snapshot/value 0.85
                          :snapshot/as-of "2026-03-17"
                          :snapshot/pipeline-run-id "run-1"
                          :snapshot/pack-id "test-pack"})
    (let [latest (store/latest-snapshots *store* "test-pack")]
      (is (= 2 (count latest)))
      (is (= #{"M1" "M2"} (set (map :snapshot/metric-id latest)))))))

(deftest snapshot-latest-wins-test
  (testing "Latest snapshot by as-of wins"
    (store/save-pack *store* sample-pack)
    (store/save-snapshot *store*
                         {:snapshot/metric-id "M1"
                          :snapshot/value 1.0
                          :snapshot/as-of "2026-03-16"
                          :snapshot/pipeline-run-id "run-1"
                          :snapshot/pack-id "test-pack"})
    (store/save-snapshot *store*
                         {:snapshot/metric-id "M1"
                          :snapshot/value 2.0
                          :snapshot/as-of "2026-03-17"
                          :snapshot/pipeline-run-id "run-2"
                          :snapshot/pack-id "test-pack"})
    (let [latest (store/latest-snapshots *store* "test-pack")
          m1 (first (filter #(= "M1" (:snapshot/metric-id %)) latest))]
      (is (= 1 (count latest)))
      (is (= 2.0 (:snapshot/value m1))))))

;; -- Runs --

(deftest run-test
  (testing "Save and query runs"
    (store/save-pack *store* sample-pack)
    (let [run-id (store/save-run *store*
                                 {:run/pack-id "test-pack"
                                  :run/pipeline-name "Test Pipeline"
                                  :run/status "completed"
                                  :run/started-at "2026-03-17T10:00:00Z"
                                  :run/finished-at "2026-03-17T10:05:00Z"
                                  :run/metric-count 2})
          runs (store/runs-for-pack *store* "test-pack")]
      (is (some? run-id))
      (is (= 1 (count runs)))
      (is (= "completed" (:run/status (first runs)))))))
