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

(ns ai.miniforge.web-dashboard.fleet-onboarding-integration-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [cheshire.core :as json]
   [ai.miniforge.pr-train.interface :as pr-train]
   [ai.miniforge.repo-dag.interface :as repo-dag]
   [ai.miniforge.web-dashboard.server :as server]
   [ai.miniforge.web-dashboard.state.core :as state-core]
   [ai.miniforge.web-dashboard.state.trains :as trains]))

(defn- temp-config-path
  []
  (let [dir (.toFile (java.nio.file.Files/createTempDirectory
                      "fleet-e2e-config"
                      (make-array java.nio.file.attribute.FileAttribute 0)))]
    (.deleteOnExit dir)
    (str (.getAbsolutePath dir) "/config.edn")))

(defn- arg-value
  [args flag]
  (some (fn [[a b]]
          (when (= a flag) b))
        (partition 2 1 args)))

(defn- fake-run-gh
  [& args]
  (let [[cmd subcmd] args]
    (cond
      (and (= cmd "api")
           (= subcmd "orgs/acme/repos?per_page=100"))
      {:success? true
       :out (json/generate-string [{:full_name "acme/service"}
                                   {:full_name "bad slug"}])
       :err ""}

      (and (= cmd "pr")
           (= subcmd "list"))
      (let [repo (arg-value args "--repo")
            rows (case repo
                   "acme/service"
                   [{:number 101
                     :title "Add service endpoint"
                     :url "https://github.com/acme/service/pull/101"
                     :state "OPEN"
                     :headRefName "feature/add-endpoint"
                     :isDraft false
                     :reviewDecision "APPROVED"
                     :statusCheckRollup [{:status "COMPLETED"
                                          :conclusion "SUCCESS"}]}
                    {:number 102
                     :title "Refactor auth middleware"
                     :url "https://github.com/acme/service/pull/102"
                     :state "OPEN"
                     :headRefName "chore/auth-refactor"
                     :isDraft false
                     :reviewDecision "REVIEW_REQUIRED"
                     :statusCheckRollup [{:status "IN_PROGRESS"}]}]
                   [])]
        {:success? true
         :out (json/generate-string rows)
         :err ""})

      :else
      {:success? false
       :out ""
       :err (str "Unexpected gh call: " args)})))

(defn- route-json
  [handler method uri query-string]
  (let [response (handler {:uri uri
                           :request-method method
                           :query-string query-string
                           :params {}})]
    {:status (:status response)
     :json (json/parse-string (:body response) true)}))

(deftest fleet-onboarding-route-flow-integration-test
  (testing "discover repos + sync PRs through server routes"
    (let [config-path (temp-config-path)
          pr-manager (pr-train/create-manager)
          dag-manager (repo-dag/create-manager)
          state (state-core/create-state {:pr-train-manager pr-manager
                                          :repo-dag-manager dag-manager})]
      (with-redefs-fn {#'trains/default-fleet-config-path config-path
                       #'trains/run-gh fake-run-gh}
        (fn []
          (let [handler (#'server/create-handler state)
                discover-res (route-json handler :post "/api/fleet/repos/discover" "owner=acme")
                repos-res (route-json handler :get "/api/fleet/repos" nil)
                sync-res (route-json handler :post "/api/fleet/prs/sync" nil)
                trains-list (vec (pr-train/list-trains pr-manager))
                dag-list (vec (repo-dag/get-all-dags dag-manager))
                train (first trains-list)
                prs-by-number (into {} (map (juxt :pr/number identity) (:train/prs train)))]
            (is (= 200 (:status discover-res)))
            (is (true? (get-in discover-res [:json :success?])))
            (is (= 1 (get-in discover-res [:json :added])))
            (is (= ["acme/service"] (get-in repos-res [:json :repos])))

            (is (= 200 (:status sync-res)))
            (is (true? (get-in sync-res [:json :success?])))
            (is (= 1 (get-in sync-res [:json :synced])))
            (is (= 0 (get-in sync-res [:json :failed])))
            (is (= 2 (get-in sync-res [:json :summary :added-prs])))
            (is (= 2 (get-in sync-res [:json :summary :tracked-prs])))

            (is (= 1 (count trains-list)))
            (is (= "External PRs: acme/service" (:train/name train)))
            (is (= 2 (count (:train/prs train))))
            (is (= :approved (get-in prs-by-number [101 :pr/status])))
            (is (= :passed (get-in prs-by-number [101 :pr/ci-status])))
            (is (= :reviewing (get-in prs-by-number [102 :pr/status])))

            (is (= 1 (count dag-list)))
            (is (= "External PR Fleet" (:dag/name (first dag-list))))
            (is (= #{"acme/service"}
                   (set (map :repo/name (:dag/repos (first dag-list))))))))))))
