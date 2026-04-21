(ns ai.miniforge.pipeline-pack.loader-test
  (:require [clojure.test :refer [deftest is testing]]
            [ai.miniforge.pipeline-pack.loader :as loader]
            [ai.miniforge.pipeline-pack.schema :as pack-schema]
            [clojure.java.io :as io]))

(def ^:private simple-pack-dir
  (-> (io/resource "test-packs/simple-pack/pack.edn")
      io/file .getParentFile .getPath))

(def ^:private test-packs-dir
  (-> (io/resource "test-packs/simple-pack/pack.edn")
      io/file .getParentFile .getParentFile .getPath))

;; -- Schema validation --

(deftest validate-manifest-test
  (testing "Valid manifest passes"
    (let [manifest {:pack/id "test" :pack/name "Test" :pack/version "1.0"
                    :pack/description "Test pack" :pack/author "test"
                    :pack/trust-level :untrusted :pack/authority :authority/data
                    :pack/pipelines ["p.edn"] :pack/envs ["e.edn"]
                    :pack/created-at (java.time.Instant/now)
                    :pack/updated-at (java.time.Instant/now)}]
      (is (true? (pack-schema/valid-manifest? manifest)))))

  (testing "Missing required field fails"
    (is (false? (pack-schema/valid-manifest? {:pack/id "test"}))))

  (testing "Invalid trust level fails"
    (is (false? (pack-schema/valid-manifest?
                 {:pack/id "test" :pack/name "Test" :pack/version "1.0"
                  :pack/description "d" :pack/author "a"
                  :pack/trust-level :invalid
                  :pack/authority :authority/data
                  :pack/pipelines [] :pack/envs []
                  :pack/created-at (java.time.Instant/now)
                  :pack/updated-at (java.time.Instant/now)})))))

;; -- Loader --

(deftest load-pack-from-directory-test
  (testing "Load valid pack from test fixtures"
    (let [result (loader/load-pack-from-directory simple-pack-dir)]
      (is (:success? result))
      (let [pack (:pack result)]
        (is (= "simple-pack" (get-in pack [:pack/manifest :pack/id])))
        (is (= :untrusted (get-in pack [:pack/manifest :pack/trust-level])))
        (is (= :authority/data (get-in pack [:pack/manifest :pack/authority])))
        (is (= 1 (count (:pack/pipelines pack))))
        (is (= 1 (count (:pack/envs pack))))
        (is (.endsWith (first (:pack/pipelines pack)) "test-pipeline.edn")))))

  (testing "Pack includes loaded metric registry"
    (let [result (loader/load-pack-from-directory simple-pack-dir)
          pack (:pack result)]
      (is (some? (:pack/registry pack)))
      (is (= "test-metrics" (:registry/id (:pack/registry pack))))))

  (testing "Nonexistent directory returns failure"
    (let [result (loader/load-pack-from-directory "/nonexistent/path")]
      (is (not (:success? result)))))

  (testing "Directory without pack.edn returns failure"
    (let [result (loader/load-pack-from-directory test-packs-dir)]
      (is (not (:success? result))))))

;; -- Normalization --

(deftest normalize-defaults-test
  (testing "Defaults applied to minimal manifest"
    (let [minimal {:pack/id "x" :pack/name "X" :pack/version "1"
                   :pack/description "d" :pack/author "a"
                   :pack/created-at #inst "2026-03-17"
                   :pack/updated-at #inst "2026-03-17"}
          normalized (loader/normalize-manifest minimal)]
      (is (= :untrusted (:pack/trust-level normalized)))
      (is (= :authority/data (:pack/authority normalized)))
      (is (= [] (:pack/pipelines normalized)))
      (is (= [] (:pack/envs normalized))))))

;; -- Discovery --

(deftest discover-packs-test
  (testing "Discovers packs in test directory"
    (let [packs (loader/discover-packs test-packs-dir)]
      (is (= 1 (count packs)))
      (is (= "simple-pack" (:pack-id (first packs))))))

  (testing "Returns nil for nonexistent directory"
    (is (nil? (loader/discover-packs "/nonexistent")))))

(deftest load-all-packs-test
  (testing "Loads all discovered packs"
    (let [result (loader/load-all-packs test-packs-dir)]
      (is (= 1 (count (:loaded result))))
      (is (empty? (:failed result))))))
