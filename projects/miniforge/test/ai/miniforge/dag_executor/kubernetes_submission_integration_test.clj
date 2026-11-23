;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.dag-executor.kubernetes-submission-integration-test
  (:require [ai.miniforge.dag-executor.protocols.impl.kubernetes-job-creation :as creation]
            [ai.miniforge.dag-executor.protocols.impl.runtime.deadline :as deadline]
            [ai.miniforge.dag-executor.protocols.impl.runtime.deadline-command :as command]
            [ai.miniforge.dag-executor.protocols.impl.runtime.process :as process]
            [ai.miniforge.dag-executor.kubernetes-creation-fixtures :as replies]
            [ai.miniforge.dag-executor.kubernetes-submission-fixtures :as fixtures]
            [ai.miniforge.dag-executor.kubernetes-submission-native-fixtures :as native]
            [ai.miniforge.dag-executor.result :as result]
            [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0 :private true} blocked-budget-ms
  "Expire actual stalled stdin without waiting for the fixture's long lifetime."
  500)

(def ^{:stratum 0 :private true} scheduling-ceiling-ms
  "Allow owned-process cleanup, but not the fixture's thirty-second lifetime."
  4000)

(defn- ^{:stratum 0} verify-complete! [job directory executable]
  (let [reply (creation/create! executable job fixtures/budget-ms)
        argv (str/split-lines (slurp (str (fs/path directory "argv"))))
        delivered (json/parse-string-strict (slurp (str (fs/path directory "stdin"))) true)]
    (is (result/ok? reply))
    (is (= job delivered))
    (is (= ["create" "-n" "fixture-namespace" "-f" "-" "-o" "json"
            (str "--request-timeout=" fixtures/budget-ms "ms")] argv))
    (is (= (:uid replies/identity-metadata) (get-in reply [:data :job-uid])))
    (is (= (:resourceVersion replies/identity-metadata)
           (get-in reply [:data :job-creation-resource-version])))))

(defn- ^{:stratum 0} verify-unaccepted! [job _directory executable]
  (let [reply (creation/create! executable job fixtures/budget-ms)]
    (is (= :input-error (get-in reply [:error :data :reason])))))

(deftest ^{:stratum 0 :integration true} test-native-missing-executable-preserves-sanitized-io-reason
  (fs/with-temp-dir [directory {}]
    (let [executable (str (fs/path directory "missing"))
          reply (creation/create! executable (fixtures/job "λ") fixtures/budget-ms)]
      (is (= {:reason :command-io-failed} (get-in reply [:error :data])))
      (is (not (str/includes? (pr-str reply) executable))))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} verify-blocked! [job _directory executable]
  (let [child (atom nil)
        start! (comp (partial reset! child) @#'command/start!)
        started (System/nanoTime)]
    (try+
      (let [reply (with-redefs-fn {#'command/start! start!}
                    #(creation/create! executable job blocked-budget-ms))
            elapsed-ms (/ (- (System/nanoTime) started) deadline/nanos-per-ms)]
        (is (= :deadline (get-in reply [:error :data :reason])))
        (is (< elapsed-ms scheduling-ceiling-ms))
        (is (not (.isAlive ^Process @child))))
      (finally (when-let [owned @child] (process/destroy-process-tree! owned))))))

(deftest ^{:stratum 1 :integration true} test-real-creation-delivers-complete-utf8-and-retains-exact-receipt
  (doseq [value ["first\nsecond\u0000λ" native/oversized-value]
          :let [job (fixtures/job value)]]
    (native/with-executable! job native/complete-script (partial verify-complete! job))))

(deftest ^{:stratum 1 :integration true} test-valid-receipt-with-unaccepted-stdin-is-not-admitted
  (let [job (fixtures/job native/oversized-value)]
    (native/with-executable! job native/early-reply-script (partial verify-unaccepted! job))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2 :integration true} test-real-stalled-creation-is-killed-within-the-local-budget
  (let [job (fixtures/job native/oversized-value)]
    (native/with-executable! job native/blocked-script (partial verify-blocked! job))))

(comment (clojure.test/run-tests 'ai.miniforge.dag-executor.kubernetes-submission-integration-test))
