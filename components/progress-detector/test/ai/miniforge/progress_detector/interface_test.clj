;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;;
;; Licensed under the Apache License, Version 2.0

(ns ai.miniforge.progress-detector.interface-test
  "Integration tests for the progress-detector public interface."
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.progress-detector.interface :as pd]))

;; ---------------------------------------------------------------------------
;; Detector lifecycle (init / observe / reduce-observations)

(deftest interface-detector-lifecycle-test
  (testing "null-detector init returns expected shape"
    (let [det   (pd/null-detector)
          state (pd/init det {})]
      (is (vector? (:anomalies state)))
      (is (empty?  (:anomalies state)))))

  (testing "observe accumulates observations"
    (let [det  (pd/null-detector)
          obs  (pd/make-observation :tool/bash 1 (java.time.Instant/now)
                                    {:tool/duration-ms 80})
          st   (-> (pd/init det {})
                   (pd/observe det obs))]
      (is (= 1 (count (:observations st))))
      (is (empty? (pd/current-anomalies st)))))

  (testing "reduce-observations folds a seq"
    (let [det  (pd/null-detector)
          obss (map #(pd/make-observation :tool/read % (java.time.Instant/now))
                    (range 1 6))
          stf  (pd/reduce-observations det {} obss)]
      (is (= 5 (count (:observations stf)))))))

;; ---------------------------------------------------------------------------
;; multi-detector

(deftest interface-multi-detector-test
  (testing "multi-detector fans observations to all children"
    (let [det (pd/multi-detector [(pd/null-detector) (pd/null-detector)])
          st  (pd/init det {})
          st' (pd/observe det st
                          (pd/make-observation :tool/write 1 (java.time.Instant/now)))]
      (is (= 2 (count (:sub-states st'))))
      (is (empty? (pd/current-anomalies st'))))))

;; ---------------------------------------------------------------------------
;; Anomaly schema helpers

(deftest interface-valid-anomaly-test
  (testing "valid-anomaly? accepts a well-formed anomaly"
    (let [anomaly {:anomaly/id          "abc"
                   :anomaly/category    :anomaly.category/stall
                   :anomaly/class       :anomaly.class/soft
                   :anomaly/severity    :anomaly.severity/info
                   :detector/kind       :detector.kind/shell
                   :anomaly/evidence    []
                   :anomaly/detected-at (java.time.Instant/now)}]
      (is (true? (pd/valid-anomaly? anomaly)))))

  (testing "valid-anomaly? rejects malformed anomaly"
    (is (false? (pd/valid-anomaly? {:anomaly/id 123}))))

  (testing "explain-anomaly returns nil for valid anomaly"
    (let [good {:anomaly/id          "x"
                :anomaly/category    :anomaly.category/loop
                :anomaly/class       :anomaly.class/hard
                :anomaly/severity    :anomaly.severity/critical
                :detector/kind       :detector.kind/llm
                :anomaly/evidence    []
                :anomaly/detected-at (java.time.Instant/now)}]
      (is (nil? (pd/explain-anomaly good)))))

  (testing "explain-anomaly returns errors for invalid anomaly"
    (is (some? (pd/explain-anomaly {})))))

(deftest interface-valid-observation-test
  (testing "valid-observation? accepts well-formed observation"
    (is (true? (pd/valid-observation?
                {:tool/id :tool/bash :seq 1
                 :timestamp (java.time.Instant/now)}))))

  (testing "valid-observation? rejects missing :seq"
    (is (false? (pd/valid-observation?
                 {:tool/id :tool/bash :timestamp (java.time.Instant/now)})))))

;; ---------------------------------------------------------------------------
;; Tool-profile registry

(deftest interface-tool-profile-test
  (testing "tool-determinism returns expected level for known tools"
    (is (= :volatile      (pd/tool-determinism :tool/bash)))
    (is (= :nondeterministic (pd/tool-determinism :tool/agent))))

  (testing "tool-determinism returns :volatile for unknown tool"
    (is (= :volatile (pd/tool-determinism :tool/totally-unknown))))

  (testing "tool-categories returns a set of category keywords"
    (let [cats (pd/tool-categories :tool/bash)]
      (is (set? cats))
      (is (pos? (count cats)))))

  (testing "tool-detector-kind returns a keyword"
    (is (keyword? (pd/tool-detector-kind :tool/bash))))

  (testing "all-tool-ids returns sorted vector"
    (let [ids (pd/all-tool-ids)]
      (is (vector? ids))
      (is (= ids (vec (sort ids))))))

  (testing "register-tool adds profile to registry"
    (let [reg (pd/load-tool-registry)
          new-reg (pd/register-tool reg :tool/custom
                                    {:detector/kind :detector.kind/shell
                                     :determinism   :stable})]
      (is (= :stable (get-in new-reg [:tool/custom :determinism])))
      (is (= :volatile (pd/tool-determinism :tool/bash reg))
          "original registry unchanged"))))

;; ---------------------------------------------------------------------------
;; Config merge

(deftest interface-resolve-config-test
  (testing "resolve-config produces enabled config from base layer"
    (let [cfg (pd/resolve-config [{:config/params {:window-size 5}}])]
      (is (true? (pd/config-enabled? cfg)))
      (is (= {:window-size 5} (pd/effective-config-params cfg)))))

  (testing "resolve-config handles disable then enable"
    (let [cfg (pd/resolve-config [{:config/params {}}
                                   {:config/directive :disable}
                                   {:config/directive :enable}])]
      (is (true? (pd/config-enabled? cfg)))))

  (testing "config-overlay is shorthand for [parent child]"
    (let [parent {:config/params {:a 1}}
          child  {:config/directive :tune :config/params {:b 2}}
          result (pd/config-overlay parent child)]
      (is (= {:a 1 :b 2} (pd/effective-config-params result))))))

;; ---------------------------------------------------------------------------
;; make-observation

(deftest make-observation-test
  (testing "make-observation creates valid observation"
    (let [now (java.time.Instant/now)
          obs (pd/make-observation :tool/bash 1 now)]
      (is (= :tool/bash (:tool/id obs)))
      (is (= 1 (:seq obs)))
      (is (= now (:timestamp obs)))
      (is (pd/valid-observation? obs))))

  (testing "make-observation merges optional fields"
    (let [obs (pd/make-observation :tool/write 2 (java.time.Instant/now)
                                   {:tool/duration-ms 30 :tool/error? false})]
      (is (= 30    (:tool/duration-ms obs)))
      (is (= false (:tool/error? obs))))))

;; ---------------------------------------------------------------------------
;; anomalies-by-severity / critical-anomalies

(deftest anomalies-by-severity-test
  (testing "anomalies-by-severity filters correctly"
    (let [state {:anomalies [{:anomaly/severity :anomaly.severity/critical :x 1}
                              {:anomaly/severity :anomaly.severity/warning  :x 2}
                              {:anomaly/severity :anomaly.severity/critical :x 3}]}]
      (is (= 2 (count (pd/anomalies-by-severity state :anomaly.severity/critical))))
      (is (= 1 (count (pd/anomalies-by-severity state :anomaly.severity/warning))))
      (is (= 0 (count (pd/anomalies-by-severity state :anomaly.severity/info))))))

  (testing "critical-anomalies returns only critical ones"
    (let [state {:anomalies [{:anomaly/severity :anomaly.severity/critical}
                              {:anomaly/severity :anomaly.severity/info}]}]
      (is (= 1 (count (pd/critical-anomalies state)))))))

(deftest current-anomalies-test
  (testing "returns empty vector when no anomalies"
    (is (= [] (pd/current-anomalies {}))))

  (testing "returns anomalies vector"
    (let [a {:anomaly/id "x"}]
      (is (= [a] (pd/current-anomalies {:anomalies [a]}))))))
