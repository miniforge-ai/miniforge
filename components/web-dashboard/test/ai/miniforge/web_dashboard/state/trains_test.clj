;; Copyright 2025 miniforge.ai
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

(ns ai.miniforge.web-dashboard.state.trains-test
  (:require
   [clojure.test :refer [deftest is]]
   [cheshire.core :as json]
   [ai.miniforge.response.interface :as response]
   [ai.miniforge.web-dashboard.state.core :as core]
   [ai.miniforge.web-dashboard.state.trains :as sut]))

(defn- temp-config-path
  []
  (let [dir (.toFile (java.nio.file.Files/createTempDirectory
                      "fleet-config"
                      (make-array java.nio.file.attribute.FileAttribute 0)))]
    (.deleteOnExit dir)
    (str (.getAbsolutePath dir) "/config.edn")))

(deftest add-configured-repo-validates-and-dedupes-test
  (let [config-path (temp-config-path)
        state (atom {})]
    (with-redefs-fn {#'sut/default-fleet-config-path config-path}
      (fn []
        (let [invalid (sut/add-configured-repo! state "not-a-repo-slug")]
          (is (false? (:success? invalid)))
          (is (= :anomalies/fault (get-in invalid [:anomaly :anomaly/category]))))

        (let [first-add (sut/add-configured-repo! state "Acme/Service")
              second-add (sut/add-configured-repo! state "acme/service")]
          (is (true? (:success? first-add)))
          (is (true? (:added? first-add)))
          (is (= "acme/service" (:repo first-add)))
          (is (true? (:success? second-add)))
          (is (false? (:added? second-add)))
          (is (= ["acme/service"] (sut/get-configured-repos state))))))))

(deftest fetch-open-prs-parses-provider-fields-test
  (with-redefs-fn {#'sut/run-gh
                   (fn [& _args]
                     {:success? true
                      :out (json/generate-string
                            [{:number 40
                              :title "Second PR"
                              :url "https://github.com/acme/service/pull/40"
                              :state "OPEN"
                              :headRefName "feature/two"
                              :isDraft true
                              :reviewDecision "REVIEW_REQUIRED"
                              :statusCheckRollup [{:status "IN_PROGRESS"}]}
                             {:number 12
                              :title "First PR"
                              :url "https://github.com/acme/service/pull/12"
                              :state "OPEN"
                              :headRefName "feature/one"
                              :isDraft false
                              :reviewDecision "APPROVED"
                              :statusCheckRollup [{:status "COMPLETED"
                                                   :conclusion "SUCCESS"}]}])
                      :err ""})}
    (fn []
      (let [result (#'sut/fetch-open-prs "acme/service")
            prs (:prs result)
            by-number (into {} (map (juxt :pr/number identity) prs))]
        (is (true? (:success? result)))
        (is (= [12 40] (mapv :pr/number prs)))
        (is (= :approved (get-in by-number [12 :pr/status])))
        (is (= :passed (get-in by-number [12 :pr/ci-status])))
        (is (= :draft (get-in by-number [40 :pr/status])))
        (is (= :running (get-in by-number [40 :pr/ci-status])))))))

(deftest apply-sync-plan-orders-mutations-test
  (let [calls (atom [])
        train-id (random-uuid)
        plan {:to-add [{:pr/number 101
                        :pr/url "https://github.com/acme/service/pull/101"
                        :pr/branch "feature/a"
                        :pr/title "A"}]
              :to-remove [99]
              :status-map {101 {:pr/status :open :pr/ci-status :pending}}}]
    (with-redefs-fn {#'core/safe-call
                     (fn [_ns-sym fn-sym & _args]
                       (swap! calls conj fn-sym)
                       nil)}
      (fn []
        (#'sut/apply-sync-plan! :manager train-id "acme/service" plan)
        (is (= ['add-pr 'remove-pr 'sync-pr-status 'link-prs]
               @calls))))))

(deftest sync-configured-repos-aggregates-results-and-failures-test
  (let [state (atom {:pr-train-manager :manager})
        sync-results {"acme/service"
                      {:success? true
                       :success true
                       :repo "acme/service"
                       :added 2
                       :removed 1
                       :tracked-prs 3}
                      "acme/web"
                      {:success? false
                       :success false
                       :repo "acme/web"
                       :error "Provider unavailable"
                       :anomaly (response/make-anomaly :anomalies/unavailable "Provider unavailable")}}]
    (with-redefs-fn {#'sut/get-configured-repos (fn [_] ["acme/service" "acme/web"])
                     #'sut/sync-repo-prs-into-train! (fn [_ repo] (get sync-results repo))}
      (fn []
        (let [result (sut/sync-configured-repos! state)]
          (is (false? (:success? result)))
          (is (= 1 (:synced result)))
          (is (= 1 (:failed result)))
          (is (= 2 (count (:results result))))
          (is (= {:added-prs 2
                  :removed-prs 1
                  :tracked-prs 3}
                 (:summary result)))
          (is (= :anomalies/fault (get-in result [:anomaly :anomaly/category]))))))))
