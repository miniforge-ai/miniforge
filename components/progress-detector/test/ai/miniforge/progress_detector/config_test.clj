;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;;
;; Licensed under the Apache License, Version 2.0

(ns ai.miniforge.progress-detector.config-test
  "Tests for detector config merge semantics and overlay resolution."
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.progress-detector.config :as sut]))

;; ---------------------------------------------------------------------------
;; apply-directive — unit tests for each directive

(deftest apply-directive-inherit-test
  (testing ":inherit returns accumulated unchanged"
    (let [acc   {:detector/enabled? true :config/params {:x 1} :config/directives []}
          layer {:config/directive :inherit}
          result (sut/apply-directive acc layer)]
      (is (= acc result))))

  (testing "missing directive defaults to :inherit"
    (let [acc    {:detector/enabled? true :config/params {:x 1} :config/directives []}
          layer  {}
          result (sut/apply-directive acc layer)]
      (is (= acc result)))))

(deftest apply-directive-disable-test
  (testing ":disable sets :detector/enabled? to false"
    (let [acc    {:detector/enabled? true :config/params {} :config/directives []}
          result (sut/apply-directive acc {:config/directive :disable})]
      (is (false? (:detector/enabled? result)))))

  (testing ":disable does not touch :config/params"
    (let [acc    {:detector/enabled? true :config/params {:x 5} :config/directives []}
          result (sut/apply-directive acc {:config/directive :disable})]
      (is (= {:x 5} (:config/params result))))))

(deftest apply-directive-enable-test
  (testing ":enable sets :detector/enabled? to true even when previously disabled"
    (let [acc    {:detector/enabled? false :config/params {} :config/directives []}
          result (sut/apply-directive acc {:config/directive :enable})]
      (is (true? (:detector/enabled? result)))))

  (testing ":enable on already-enabled is idempotent"
    (let [acc    {:detector/enabled? true :config/params {} :config/directives []}
          result (sut/apply-directive acc {:config/directive :enable})]
      (is (true? (:detector/enabled? result))))))

(deftest apply-directive-tune-test
  (testing ":tune deep-merges :config/params"
    (let [acc   {:detector/enabled? true
                 :config/params     {:window-size 5 :threshold 0.5}
                 :config/directives []}
          layer {:config/directive :tune
                 :config/params    {:threshold 0.9 :new-key "val"}}
          result (sut/apply-directive acc layer)]
      (is (= 5      (get-in result [:config/params :window-size])) "inherited key preserved")
      (is (= 0.9    (get-in result [:config/params :threshold]))   "overridden key updated")
      (is (= "val"  (get-in result [:config/params :new-key]))     "new key added")))

  (testing ":tune does not change :detector/enabled? by default"
    (let [acc    {:detector/enabled? false :config/params {} :config/directives []}
          result (sut/apply-directive acc {:config/directive :tune :config/params {:x 1}})]
      (is (false? (:detector/enabled? result)))))

  (testing ":tune respects explicit :detector/enabled? override"
    (let [acc    {:detector/enabled? false :config/params {} :config/directives []}
          result (sut/apply-directive acc {:config/directive :tune
                                           :detector/enabled? true
                                           :config/params {}})]
      (is (true? (:detector/enabled? result))))))

;; ---------------------------------------------------------------------------
;; merge-config — integration tests

(deftest merge-config-single-layer-test
  (testing "single base layer produces valid config"
    (let [result (sut/merge-config [{:config/params {:window-size 10}}])]
      (is (true? (sut/enabled? result)))
      (is (= {:window-size 10} (sut/effective-params result)))
      (is (= [:inherit] (sut/directives-applied result))))))

(deftest merge-config-tune-test
  (testing "tune layer merges params onto base"
    (let [result (sut/merge-config
                  [{:config/params {:window-size 10 :threshold 0.5}}
                   {:config/directive :tune
                    :config/params   {:threshold 0.9 :extra "x"}}])]
      (is (= 10    (get-in result [:config/params :window-size])))
      (is (= 0.9   (get-in result [:config/params :threshold])))
      (is (= "x"   (get-in result [:config/params :extra])))
      (is (= [:inherit :tune] (sut/directives-applied result))))))

(deftest merge-config-disable-test
  (testing "disable makes config not enabled"
    (let [result (sut/merge-config [{:config/params {}}
                                    {:config/directive :disable}])]
      (is (false? (sut/enabled? result)))))

  (testing "disable blocks subsequent inherits"
    (let [result (sut/merge-config [{:config/params {}}
                                    {:config/directive :disable}
                                    {:config/directive :inherit}])]
      (is (false? (sut/enabled? result))))))

(deftest merge-config-enable-after-disable-test
  (testing "enable after disable re-enables"
    (let [result (sut/merge-config [{:config/params {}}
                                    {:config/directive :disable}
                                    {:config/directive :enable}])]
      (is (true? (sut/enabled? result)))))

  (testing "disable after enable takes effect"
    (let [result (sut/merge-config [{:config/params {}}
                                    {:config/directive :enable}
                                    {:config/directive :disable}])]
      (is (false? (sut/enabled? result))))))

(deftest merge-config-directive-audit-test
  (testing "directives-applied records all applied directives"
    (let [result (sut/merge-config
                  [{}
                   {:config/directive :tune :config/params {}}
                   {:config/directive :disable}
                   {:config/directive :enable}])]
      (is (= [:inherit :tune :disable :enable]
             (sut/directives-applied result))))))

(deftest merge-config-empty-layers-throws-test
  (testing "empty layers seq throws"
    (is (thrown? Exception (sut/merge-config [])))))

;; ---------------------------------------------------------------------------
;; enabled? / effective-params / directives-applied

(deftest enabled-default-test
  (testing "enabled? defaults to true when key absent"
    (is (true? (sut/enabled? {}))))

  (testing "enabled? returns false when explicitly false"
    (is (false? (sut/enabled? {:detector/enabled? false})))))

(deftest effective-params-test
  (testing "returns empty map when :config/params absent"
    (is (= {} (sut/effective-params {}))))

  (testing "returns the :config/params map"
    (is (= {:k 1} (sut/effective-params {:config/params {:k 1}})))))

;; ---------------------------------------------------------------------------
;; overlay

(deftest overlay-test
  (testing "overlay parent + child is equivalent to merge-config [parent child]"
    (let [parent {:config/params {:a 1}}
          child  {:config/directive :tune :config/params {:b 2}}
          via-overlay    (sut/overlay parent child)
          via-merge      (sut/merge-config [parent child])]
      (is (= via-overlay via-merge))))

  (testing "overlay disable child disables"
    (let [result (sut/overlay {:config/params {:x 1}}
                              {:config/directive :disable})]
      (is (false? (sut/enabled? result)))))

  (testing "overlay tune child merges params"
    (let [result (sut/overlay {:config/params {:x 1}}
                              {:config/directive :tune
                               :config/params   {:y 2}})]
      (is (= {:x 1 :y 2} (sut/effective-params result))))))

;; ---------------------------------------------------------------------------
;; Deep merge corner cases

(deftest deep-merge-nested-test
  (testing "tune deep-merges nested maps"
    (let [result (sut/merge-config
                  [{:config/params {:nested {:a 1 :b 2}}}
                   {:config/directive :tune
                    :config/params   {:nested {:b 99 :c 3}}}])]
      (is (= {:a 1 :b 99 :c 3}
             (get-in result [:config/params :nested]))
          "nested map keys merged correctly"))))
