(ns ai.miniforge.workflow.state-profile-test
  (:require
   [ai.miniforge.workflow.interface :as workflow]
   [clojure.test :refer [deftest is testing]]))

(deftest workflow-state-profile-provider-test
  (testing "workflow state-profile provider loads from app-owned resources on the classpath"
    (let [provider (workflow/load-state-profile-provider)]
      (is (= :software-factory (workflow/default-state-profile-id)))
      (is (= #{:software-factory :etl}
             (set (workflow/available-state-profile-ids))))
      (is (= :software-factory
             (get-in provider [:profiles :software-factory :profile/id])))
      (is (= :etl
             (-> (workflow/resolve-state-profile :etl)
                 :profile/id)))))

  (testing "ETL completion semantics are still available through the ETL-owned resource layer"
    (let [etl-profile (workflow/resolve-state-profile :etl)]
      (is (= #{:completed}
             (:success-terminal-statuses etl-profile)))
      (is (= :completed
             (get-in etl-profile [:event-mappings :published :to])))))

  (testing "missing app profile ids are explicit rather than silently falling back"
    (is (nil? (workflow/resolve-state-profile :missing-profile)))))
