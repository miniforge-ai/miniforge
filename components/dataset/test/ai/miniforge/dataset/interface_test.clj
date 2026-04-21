(ns ai.miniforge.dataset.interface-test
  (:require [clojure.test :refer [deftest is testing]]
            [ai.miniforge.dataset.interface :as ds]))

(deftest create-dataset-test
  (testing "T1: Create dataset with valid schema"
    (let [result (ds/create-dataset
                  {:dataset/name "test_dataset"
                   :dataset/type :table
                   :dataset/schema {:schema/id (java.util.UUID/randomUUID)
                                    :schema/version "1.0.0"
                                    :schema/fields [{:field/name "id"
                                                     :field/type :string
                                                     :field/nullable? false}]}
                   :dataset/storage-location {:storage/backend :local
                                              :storage/path "/tmp/test"
                                              :storage/format :edn}
                   :dataset/version {:dataset-version/id (java.util.UUID/randomUUID)
                                     :dataset-version/timestamp (java.time.Instant/now)
                                     :dataset-version/content-hash (apply str (repeat 64 "a"))
                                     :dataset-version/row-count 100
                                     :dataset-version/workflow-run-id (java.util.UUID/randomUUID)}})]
      (is (:success? result))
      (is (some? (get-in result [:dataset :dataset/id])))
      (is (some? (get-in result [:dataset :dataset/created-at])))))

  (testing "Reject invalid dataset type"
    (let [result (ds/create-dataset
                  {:dataset/name "bad"
                   :dataset/type :invalid-type
                   :dataset/schema {}
                   :dataset/storage-location {}
                   :dataset/version {}})]
      (is (not (:success? result))))))

(deftest validate-dataset-test
  (testing "Reject invalid name pattern"
    (let [result (ds/validate-dataset
                  {:dataset/id (java.util.UUID/randomUUID)
                   :dataset/name "UPPERCASE"
                   :dataset/type :table
                   :dataset/schema {}
                   :dataset/storage-location {}
                   :dataset/version {}
                   :dataset/lineage {}
                   :dataset/content-hash (apply str (repeat 64 "a"))})]
      (is (not (:success? result))))))

(deftest create-version-test
  (testing "T6: Create valid dataset version"
    (let [result (ds/create-version
                  {:dataset-version/dataset-id (java.util.UUID/randomUUID)
                   :dataset-version/content-hash (apply str (repeat 64 "b"))
                   :dataset-version/row-count 5000
                   :dataset-version/schema-version "1.0.0"
                   :dataset-version/workflow-run-id (java.util.UUID/randomUUID)})]
      (is (:success? result))
      (is (some? (get-in result [:version :dataset-version/id])))))

  (testing "Reject negative row count"
    (let [result (ds/create-version
                  {:dataset-version/dataset-id (java.util.UUID/randomUUID)
                   :dataset-version/content-hash (apply str (repeat 64 "c"))
                   :dataset-version/row-count -1
                   :dataset-version/schema-version "1.0.0"
                   :dataset-version/workflow-run-id (java.util.UUID/randomUUID)})]
      (is (not (:success? result))))))

(deftest partition-strategy-test
  (testing "T5: Valid partition strategies"
    (doseq [strategy [:time :entity :composite :hash]]
      (let [result (ds/create-partition-strategy
                    {:partition/strategy strategy
                     :partition/keys ["date"]})]
        (is (:success? result) (str "Strategy " strategy " should be valid")))))

  (testing "Reject invalid strategy"
    (let [result (ds/create-partition-strategy
                  {:partition/strategy :invalid
                   :partition/keys ["x"]})]
      (is (not (:success? result)))))

  (testing "Reject out-of-range retention"
    (let [result (ds/create-partition-strategy
                  {:partition/strategy :time
                   :partition/keys ["date"]
                   :partition/retention-days 50000})]
      (is (not (:success? result))))))

(deftest artifact-bridge-test
  (testing "N1 §9.1: Dataset to artifact mapping"
    (let [ds-id (java.util.UUID/randomUUID)
          dataset {:dataset/id ds-id
                   :dataset/name "test"
                   :dataset/type :table
                   :dataset/content-hash (apply str (repeat 64 "d"))
                   :dataset/created-at (java.time.Instant/now)
                   :dataset/schema {:schema/version "1.0.0"}
                   :dataset/storage-location {:storage/path "/tmp"}
                   :dataset/version {:dataset-version/id (java.util.UUID/randomUUID)}
                   :dataset/lineage {:lineage/source-datasets []}}
          artifact (ds/dataset->artifact dataset)]
      (is (= ds-id (:artifact/id artifact)))
      (is (= :table (:artifact/type artifact)))
      (is (= (apply str (repeat 64 "d")) (:artifact/content-hash artifact))))))
