;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.opsv-provider-github.interface-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.opsv-provider-github.interface :as provider]
            [ai.miniforge.opsv-provider-github.fixtures :as f]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} bad-head-or-unavailable-read-prevents-post-test
  (doseq [response [(f/response {}) {:exit 1 :err "secret"}
                    (f/response {:ref "refs/heads/opsv/scaling"
                                 :object {:type "commit" :sha "wrong"}})]]
    (let [calls (atom [])
          runtime (f/runtime calls [response])
          result (provider/create-pr! runtime (f/transaction :committing) f/payload)]
      (is (= :failed (:effect/outcome result)))
      (is (= 1 (count @calls))))))

(deftest ^{:stratum 0} post-errors-and-non-json-responses-stay-unknown-test
  (doseq [response [{:exit 1 :err "secret"} {:exit 0 :out "not JSON"}
                    (f/response nil) (f/response {:number 17})]]
    (let [calls (atom [])
          runtime (f/runtime calls [(f/head-response) response])
          result (provider/create-pr! runtime (f/transaction :committing) f/payload)]
      (is (= :unknown-outcome (:effect/outcome result)))
      (is (= 2 (count @calls)))
      (is (not (.contains (pr-str result) "secret"))))))

(deftest ^{:stratum 0} changed-provider-fields-never-report-success-test
  (doseq [path [[:title] [:body] [:draft] [:head :sha] [:head :ref]
                [:base :ref] [:base :repo :full_name] [:head :repo :full_name]
                [:state] [:html_url]]]
    (let [calls (atom [])
          changed (assoc-in (f/pull-request) path "changed")
          runtime (f/runtime calls [(f/head-response) (f/response changed)])
          result (provider/create-pr! runtime (f/transaction :committing) f/payload)]
      (is (= :unknown-outcome (:effect/outcome result)) (str path)))))

(deftest ^{:stratum 0} invalid-runtime-or-unbound-payload-refuses-all-io-test
  (doseq [payload [(assoc f/payload :pr/body "changed")
                   (assoc f/payload :pr/repo "../../elsewhere")
                   (assoc f/payload :pr/draft? nil)]]
    (let [calls (atom [])
          runtime (f/runtime calls [])]
      (is (anomaly/anomaly? (provider/create-pr! runtime (f/transaction :committing) payload)))
      (is (empty? @calls))))
  (doseq [state [:proposed :succeeded :unknown-outcome]]
    (let [calls (atom [])
          runtime (f/runtime calls [])]
      (is (anomaly/anomaly? (provider/create-pr! runtime (f/transaction state) f/payload)))
      (is (empty? @calls)))))

(deftest ^{:stratum 0} exact-read-only-reconciliation-test
  (let [calls (atom [])
        runtime (f/runtime calls [(f/response [[] [(f/pull-request)]])])
        result (provider/observe-pr! runtime (f/transaction :unknown-outcome) f/payload)
        args (:arguments (first @calls))]
    (is (true? (:effect/matched? result)))
    (is (= 17 (get-in result [:effect/observed :pr/number])))
    (is (= 1 (count @calls)))
    (is (= "GET" (nth args 6)))
    (is (= ["--paginate" "--slurp"] (take-last 2 args)))
    (is (.contains (nth args 2) "head=example%3Aopsv%2Fscaling"))))

(deftest ^{:stratum 0} missing-mismatched-ambiguous-or-unavailable-observations-stay-unresolved-test
  (doseq [response [(f/response [[]])
                    (f/response [[(assoc (f/pull-request) :body "other")]])
                    (f/response [[(f/pull-request) (f/pull-request)]])
                    (f/response [nil]) {:exit 1}]]
    (let [calls (atom [])
          runtime (f/runtime calls [response])
          result (provider/observe-pr! runtime (f/transaction :committing) f/payload)]
      (is (anomaly/anomaly? result))
      (is (not (contains? result :effect/observed)))
      (is (= 1 (count @calls))))))

(comment
  (f/pull-request))
