(ns ai.miniforge.workflow-financial-etl.interface-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.logging.interface :as logging]
   [ai.miniforge.schema.interface :as schema]
   [ai.miniforge.workflow-financial-etl.core :as etl-core]
   [ai.miniforge.workflow-financial-etl.interface :as etl]))

(deftest run-etl-workflow-emits-completed-event
  (testing "successful ETL execution emits app-owned completed events"
    (let [[logger entries] (logging/collecting-logger)
          workflow-id (random-uuid)]
      (with-redefs [etl-core/classify-sources (fn [_logger sources] (schema/success :classified sources))
                    etl-core/scan-sources (fn [_logger sources] (schema/success :scanned sources {:findings []}))
                    etl-core/extract-knowledge (fn [_logger _sources] (schema/success :packs [{:pack/id "p1"}]))
                    etl-core/validate-packs (fn [_logger packs] (schema/success :validated packs))]
        (let [result (etl-core/run-etl-workflow logger workflow-id [{:source/id "s1"}])
              events (map :log/event @entries)]
          (is (:success? result))
          (is (some #(= :etl/completed %) events))
          (is (= 1 (get-in result [:stats :packs-generated]))))))))

(deftest run-etl-workflow-emits-failed-event
  (testing "failed ETL execution emits app-owned failure events"
    (let [[logger entries] (logging/collecting-logger)
          workflow-id (random-uuid)]
      (with-redefs [etl-core/classify-sources (fn [_logger _sources]
                                                (schema/failure nil
                                                                {:stage :classification
                                                                 :message "boom"}))]
        (let [result (etl-core/run-etl-workflow logger workflow-id [{:source/id "s1"}])
              failure-entry (some #(when (= :etl/failed (:log/event %)) %) @entries)]
          (is (false? (:success? result)))
          (is (= :classification (get-in failure-entry [:data :etl/failure-stage])))
          (is (= "boom" (get-in failure-entry [:data :etl/failure-reason]))))))))

(deftest etl-event-builders-include-product-fields
  (testing "ETL event builders retain ETL-specific payload fields"
    (let [completed (etl/etl-completed-event (random-uuid) 15 {:packs-generated 2})
          failed (etl/etl-failed-event (random-uuid) :validation "missing field" {:field :issuer})]
      (is (= :etl/completed (:event/type completed)))
      (is (= 15 (:etl/duration-ms completed)))
      (is (= :etl/failed (:event/type failed)))
      (is (= :validation (:etl/failure-stage failed)))
      (is (= {:field :issuer} (:etl/error-details failed))))))
