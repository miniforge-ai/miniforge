(ns ai.miniforge.workflow.state-profile-test
  (:require
   [ai.miniforge.workflow.interface :as workflow]
   [clojure.test :refer [deftest is testing]]))

(deftest workflow-state-profile-provider-test
  (testing "workflow component loads its own state-profile provider from resources"
    (let [provider (workflow/load-state-profile-provider)]
      (is (= :software-factory (workflow/default-state-profile-id)))
      (is (= #{:software-factory :etl}
             (set (workflow/available-state-profile-ids))))
      (is (= :software-factory
             (get-in provider [:profiles :software-factory :profile/id])))
      (is (= :etl
             (-> (workflow/resolve-state-profile :etl)
                 :profile/id)))))

  (testing "workflow provider exposes ETL completion semantics explicitly"
    (let [etl-profile (workflow/resolve-state-profile :etl)]
      (is (= #{:completed}
             (:success-terminal-statuses etl-profile)))
      (is (= :completed
             (get-in etl-profile [:event-mappings :published :to]))))))
