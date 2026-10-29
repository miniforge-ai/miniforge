;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.checkpoint-publication-policy-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.workflow.checkpoint-store :as store]
            [ai.miniforge.workflow.checkpoint-store-lock :as checkpoint-lock]
            [ai.miniforge.workflow.checkpoint-store-paths :as paths]
            [ai.miniforge.workflow.checkpoint-test-support :as fixture]
            [babashka.fs :as fs]
            [clojure.test :refer [deftest is]])
  (:import [java.io IOException]
           [java.nio.file Files LinkOption]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private sticky-world-writable-mode 01777)

(def ^{:stratum 0} ^:private original-content "original history target")

(defn- ^{:stratum 0} verify-unsafe-lock-root [root]
  (fs/set-posix-file-permissions root "rwxrwxrwx")
  (let [called? (atom false)
        result (checkpoint-lock/with-run-lock root (random-uuid) #(reset! called? true))]
    (is (anomaly/anomaly? result))
    (is (false? @called?))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} verify-unsafe-run [root]
  (let [ctx (assoc-in (fixture/plan-completed-context root {})
                      [:execution/phase-results :plan :phase/decision-envelope]
                      {:envelope/decision :deny})
        run (fs/path (paths/workflow-checkpoint-dir root (:execution/id ctx)))
        victim (fs/path root "victim.edn")]
    (fs/create-dir run)
    (spit (fs/file victim) original-content)
    (fs/create-sym-link (fs/path run store/gate-history-filename) victim)
    (Files/setAttribute run "unix:mode" (int sticky-world-writable-mode) (make-array LinkOption 0))
    (is (anomaly/anomaly? (store/persist-execution-state! ctx)))
    (is (= original-content (slurp (fs/file victim))))
    (is (not (fs/exists? (fs/path run "phases"))))))

(defn- ^{:stratum 1} verify-callback-error [root]
  (let [failure (IOException. original-content)]
    (try
      (checkpoint-lock/with-run-lock root (random-uuid) #(throw failure))
      (is false "callback failures must propagate")
      (catch IOException actual (is (identical? failure actual))))))

(deftest ^{:stratum 1} unsafe-lock-root-does-not-invoke-the-operation (fixture/call-with-posix-checkpoint-root verify-unsafe-lock-root))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} unsafe-run-is-refused-before-phase-or-history-effects (fixture/call-with-posix-checkpoint-root verify-unsafe-run))

(deftest ^{:stratum 2} lock-validation-does-not-convert-operation-exceptions (fixture/call-with-posix-checkpoint-root verify-callback-error))

(comment
  (clojure.test/run-tests 'ai.miniforge.workflow.checkpoint-publication-policy-test))
