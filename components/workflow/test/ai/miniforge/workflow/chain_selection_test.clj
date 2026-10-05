;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.chain-selection-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.workflow.chain-loader :as loader]
            [ai.miniforge.workflow.chain-resources :as resources]
            [ai.miniforge.workflow.chain-test-support :as support]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} resource-url [resources original path]
  (if (str/starts-with? path "chains/")
    (when (contains? resources path) (java.net.URL. "file:/unused-chain-fixture"))
    (original path)))

(defn- ^{:stratum 0} resource-name [path]
  (last (str/split path #"/")))

(deftest ^{:stratum 0} invalid-requests-stop-before-resource-discovery
  (with-redefs [resources/candidates (constantly ::unexpected-discovery)]
    (doseq [[id version] [[nil "1.0.0"] ["example" "1.0.0"]
                          [:example 1] [:example ""] [:example "\u2003"]]]
      (is (= :invalid-input (:anomaly/type (loader/try-load-chain id version)))))))

(deftest ^{:stratum 0} malformed-edn-is-a-fault
  (with-redefs [io/resource (constantly (java.net.URL. "file:/unused-chain-fixture"))
                slurp (constantly "{")]
    (is (= :fault (:anomaly/type (resources/read-definition "chains/example.edn"))))))

(deftest ^{:stratum 0} throwing-boundary-preserves-category
  (try+
    (loader/load-chain :example "\u2003")
    (is false "invalid input must not succeed")
    (catch [:anomaly/category :anomalies/invalid-input] data
      (is (= :example (:chain-id data))))))

(deftest ^{:stratum 0} exact-and-base-hits-do-not-enumerate-latest
  (with-redefs [resources/existing identity]
    (doseq [version ["1.0.0" :latest]]
      (is (= 1 (count (resources/candidates {:chain-id :example :version version} nil)))))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} selected [resources id version]
  (let [lookup (partial resource-url resources io/resource)
        names (mapv resource-name (keys resources))]
    (with-redefs [io/resource lookup
                  resources/names (constantly names)
                  resources/read-definition resources]
      (loader/try-load-chain id version))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} missing-exact-version-does-not-fall-back
  (let [resources {"chains/example.edn" (support/definition :example "1.0.0")
                   "chains/example-v2.0.0.edn" (support/definition :example "2.0.0")}
        result (selected resources :example "9.0.0")]
    (is (anomaly/anomaly? result))
    (is (= :not-found (:anomaly/type result)))
    (is (= ["chains/example-v9.0.0.edn"] (get-in result [:anomaly/data :looked-for])))))

(deftest ^{:stratum 2} loaded-definition-must-match-the-request
  (doseq [[id version] [[:other "1.0.0"] [:example "2.0.0"]
                        [:example nil] [:example ""] [:example " \t"] [:example "latest"]]]
    (let [resources {"chains/example-v1.0.0.edn" (support/definition id version)}
          result (selected resources :example "1.0.0")]
      (is (anomaly/anomaly? result))
      (is (= :invalid-input (:anomaly/type result))))))

(deftest ^{:stratum 2} exact-and-latest-selections-return-resolved-definitions
  (let [chain (support/definition :example "1.0.0")
        resources {"chains/example-v1.0.0.edn" chain}]
    (doseq [version ["1.0.0" "latest" :latest nil]]
      (is (= chain (:chain (selected resources :example version)))))))

(deftest ^{:stratum 2} latest-does-not-admit-an-unresolved-definition
  (doseq [version [nil "" " \n" "\u2003" "\u00a0" "latest" :latest]]
    (let [resources {"chains/example.edn" (support/definition :example version)}]
      (is (anomaly/anomaly? (selected resources :example "latest"))))))

(deftest ^{:stratum 2} read-faults-survive-selection
  (let [fault (anomaly/anomaly :fault "test read failure" {})]
    (is (= fault (selected {"chains/example-v1.0.0.edn" fault} :example "1.0.0")))))

(comment
  (support/definition :example "1.0.0"))
