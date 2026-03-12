(ns ai.miniforge.workflow.chain-loader-test
  "Tests for chain definition loading."
  (:require
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.workflow.chain-loader :as chain-loader]))

(deftest load-chain-versioned-test
  (testing "loads chain by ID and version from resources"
    (let [{:keys [chain source]} (chain-loader/load-chain :spec-to-pr "1.0.0")]
      (is (= :spec-to-pr (:chain/id chain)))
      (is (= "1.0.0" (:chain/version chain)))
      (is (= :resource source))
      (is (seq (:chain/steps chain))))))

(deftest load-chain-latest-test
  (testing "loads latest version when version is 'latest'"
    (let [{:keys [chain]} (chain-loader/load-chain :spec-to-pr "latest")]
      (is (= :spec-to-pr (:chain/id chain)))
      (is (some? (:chain/version chain))))))

(deftest load-chain-not-found-test
  (testing "throws when chain not found"
    (is (thrown-with-msg? Exception #"not found"
          (chain-loader/load-chain :nonexistent-chain "1.0.0")))))

(deftest list-chains-test
  (testing "lists available chains across composed resource roots"
    (let [chains (chain-loader/list-chains)]
      (is (seq chains))
      (is (some #(= :spec-to-pr (:id %)) chains))
      (is (some #(= :test-chain (:id %)) chains))
      (let [spec-to-pr (first (filter #(= :spec-to-pr (:id %)) chains))]
        (is (= "1.0.0" (:version spec-to-pr)))
        (is (pos? (:steps spec-to-pr)))))))

(deftest list-resource-names-test
  (testing "resource enumeration aggregates all chain roots on the classpath"
    (let [resource-names (set (chain-loader/list-resource-names "chains"))]
      (is (contains? resource-names "spec-to-pr-v1.0.0.edn"))
      (is (contains? resource-names "test-chain-v1.0.0.edn")))))
