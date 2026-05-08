;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;;
;; Licensed under the Apache License, Version 2.0

(ns ai.miniforge.progress-detector.interface-test
  "Integration tests for the progress-detector public interface."
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.progress-detector.interface :as pd]))

;------------------------------------------------------------------------------ Layer 0
;; Test factories

(defn- detector-anomaly-data
  "Build a valid DetectorAnomalyData payload."
  [& {:keys [class severity category]
      :or {class :mechanical severity :error category :anomalies.agent/tool-loop}}]
  {:detector/kind     :detector/test
   :detector/version  "test-1.0"
   :anomaly/class     class
   :anomaly/severity  severity
   :anomaly/category  category
   :anomaly/evidence  {:summary "test evidence"}})

(defn- canonical-anomaly
  "Build a valid canonical anomaly with detector data under :anomaly/data."
  [& {:as opts}]
  (anomaly/anomaly :fault "test failure" (apply detector-anomaly-data
                                                (mapcat identity opts))))

(defn- review-issue
  "Build a reviewer issue entry."
  [description]
  {:severity :blocking
   :file "src/example/core.clj"
   :line 42
   :description description})

;------------------------------------------------------------------------------ Layer 1
;; Detector lifecycle (init / observe / reduce-observations)

(deftest interface-detector-lifecycle-test
  (testing "null-detector init returns expected shape"
    (let [det   (pd/null-detector)
          state (pd/init det {})]
      (is (vector? (:anomalies state)))
      (is (empty?  (:anomalies state)))))

  (testing "observe accumulates observations"
    (let [det (pd/null-detector)
          obs (pd/make-observation :tool/Bash 1 (java.time.Instant/now)
                                   {:tool/duration-ms 80})
          ;; observe takes [detector state observation] — detector first
          ;; for protocol-dispatch + (partial observe det) reducer use.
          ;; A `->` thread on (init …) would put state in detector slot.
          st0 (pd/init det {})
          st  (pd/observe det st0 obs)]
      (is (= 1 (count (:observations st))))
      (is (empty? (pd/current-anomalies st)))))

  (testing "reduce-observations folds a seq"
    (let [det  (pd/null-detector)
          obss (map #(pd/make-observation :tool/Read % (java.time.Instant/now))
                    (range 1 6))
          stf  (pd/reduce-observations det {} obss)]
      (is (= 5 (count (:observations stf)))))))

;------------------------------------------------------------------------------ Layer 1
;; multi-detector

(deftest interface-multi-detector-test
  (testing "multi-detector fans observations to all children"
    (let [det (pd/multi-detector [(pd/null-detector) (pd/null-detector)])
          st  (pd/init det {})
          st' (pd/observe det st
                          (pd/make-observation :tool/Write 1 (java.time.Instant/now)))]
      (is (= 2 (count (:sub-states st'))))
      (is (empty? (pd/current-anomalies st'))))))

;------------------------------------------------------------------------------ Layer 1
;; Anomaly validation — delegates to the anomaly component

(deftest interface-valid-anomaly-test
  (testing "valid-anomaly? accepts a canonical anomaly with detector data"
    (is (true? (pd/valid-anomaly? (canonical-anomaly)))))

  (testing "valid-anomaly? rejects malformed input"
    (is (false? (pd/valid-anomaly? {:not "an anomaly"})))
    (is (false? (pd/valid-anomaly? {})))
    (is (false? (pd/valid-anomaly? nil)))))

(deftest interface-detector-anomaly-data-test
  (testing "valid-detector-anomaly-data? accepts a complete payload"
    (is (true? (pd/valid-detector-anomaly-data?
                {:detector/kind     :detector/tool-loop
                 :detector/version  "stage-1.0"
                 :anomaly/class     :mechanical
                 :anomaly/severity  :error
                 :anomaly/category  :anomalies.agent/tool-loop
                 :anomaly/evidence  {:summary "x"}}))))

  (testing "rejects unknown :anomaly/class value"
    (is (false? (pd/valid-detector-anomaly-data?
                 {:detector/kind     :detector/tool-loop
                  :detector/version  "stage-1.0"
                  :anomaly/class     :NOT_A_VALID_CLASS
                  :anomaly/severity  :error
                  :anomaly/category  :anomalies.agent/tool-loop
                  :anomaly/evidence  {:summary "x"}}))))

  (testing "rejects unknown :anomaly/severity value"
    (is (false? (pd/valid-detector-anomaly-data?
                 {:detector/kind     :detector/tool-loop
                  :detector/version  "stage-1.0"
                  :anomaly/class     :mechanical
                  :anomaly/severity  :NOT_A_VALID_SEVERITY
                  :anomaly/category  :anomalies.agent/tool-loop
                  :anomaly/evidence  {:summary "x"}})))))

(deftest interface-valid-observation-test
  (testing "valid-observation? accepts well-formed observation"
    (is (true? (pd/valid-observation?
                {:tool/id :tool/Bash :seq 1
                 :timestamp (java.time.Instant/now)}))))

  (testing "valid-observation? rejects missing :seq"
    (is (false? (pd/valid-observation?
                 {:tool/id :tool/Bash :timestamp (java.time.Instant/now)})))))

;------------------------------------------------------------------------------ Layer 1
;; Tool-profile registration

(deftest interface-tool-profile-test
  (testing "make-tool-registry returns an empty atom-backed registry"
    (let [reg (pd/make-tool-registry)]
      (is (instance? clojure.lang.IDeref reg))
      (is (= [] (pd/all-tool-ids reg)))))

  (testing "register-tool-profile! + tool-determinism round-trip"
    (let [reg (pd/make-tool-registry)]
      (pd/register-tool-profile! reg
                                 {:tool/id :tool/Read
                                  :determinism :stable-with-resource-version
                                  :anomaly/categories #{:anomalies.agent/tool-loop}})
      (is (= :stable-with-resource-version (pd/tool-determinism :tool/Read reg)))
      (is (= #{:anomalies.agent/tool-loop} (pd/tool-categories  :tool/Read reg)))))

  (testing "tool-determinism returns :unstable for unregistered tools"
    (is (= :unstable (pd/tool-determinism :tool/Unknown (pd/make-tool-registry)))))

  (testing "register-tool-profile! validates against ToolProfile schema"
    (is (thrown? clojure.lang.ExceptionInfo
                 (pd/register-tool-profile! (pd/make-tool-registry)
                                            {:tool/id :tool/Bad
                                             :determinism :NOT_VALID})))))

;------------------------------------------------------------------------------ Layer 1
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

;------------------------------------------------------------------------------ Layer 1
;; make-observation

(deftest make-observation-test
  (testing "make-observation creates valid observation"
    (let [now (java.time.Instant/now)
          obs (pd/make-observation :tool/Bash 1 now)]
      (is (= :tool/Bash (:tool/id obs)))
      (is (= 1 (:seq obs)))
      (is (= now (:timestamp obs)))
      (is (pd/valid-observation? obs))))

  (testing "make-observation merges optional fields"
    (let [obs (pd/make-observation :tool/Write 2 (java.time.Instant/now)
                                   {:tool/duration-ms 30 :tool/error? false})]
      (is (= 30    (:tool/duration-ms obs)))
      (is (= false (:tool/error? obs))))))

;------------------------------------------------------------------------------ Layer 1
;; anomalies-by-severity / fatal-anomalies — read severity from :anomaly/data

(deftest anomalies-by-severity-test
  (testing "anomalies-by-severity reads severity from :anomaly/data"
    (let [state {:anomalies
                 [{:anomaly/data {:anomaly/severity :error :x 1}}
                  {:anomaly/data {:anomaly/severity :warn  :x 2}}
                  {:anomaly/data {:anomaly/severity :error :x 3}}
                  {:anomaly/data {:anomaly/severity :fatal :x 4}}]}]
      (is (= 2 (count (pd/anomalies-by-severity state :error))))
      (is (= 1 (count (pd/anomalies-by-severity state :warn))))
      (is (= 1 (count (pd/anomalies-by-severity state :fatal))))
      (is (= [{:anomaly/data {:anomaly/severity :fatal :x 4}}]
             (pd/fatal-anomalies state))))))

(deftest repair-loop-reexports-test
  (testing "review-fingerprint and stagnated? are available through the public interface"
    (let [review {:review/issues [(review-issue "add guard clause")]}
          fingerprint (pd/review-fingerprint review)]
      (is (seq fingerprint))
      (is (true? (pd/stagnated? fingerprint fingerprint)))
      (is (false? (pd/stagnated? nil fingerprint))))))
