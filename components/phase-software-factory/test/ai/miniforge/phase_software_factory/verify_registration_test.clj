;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-software-factory.verify-registration-test
  (:require [ai.miniforge.phase-software-factory.verify :as verify]
            [ai.miniforge.phase.interface :as phase]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} execute-empty [_executor _environment _command _options]
  {:data {}})

(defn- ^{:stratum 0} parsed-sentinel [_output _exit]
  {:parsed? true})

(deftest ^{:stratum 0} enter-resolves-the-current-callback
  (let [interceptor (phase/get-phase-interceptor-method {:phase :verify})
        config (:config interceptor)]
    (with-redefs [verify/enter-verify identity]
      (is (= {:phase-config config} ((:enter interceptor) {}))))))

(deftest ^{:stratum 0} leave-and-error-capture-the-current-callbacks
  (let [interceptor (with-redefs [verify/leave-verify identity
                                verify/error-verify vector]
                      (phase/get-phase-interceptor-method {:phase :verify}))]
    (is (identical? identity (:leave interceptor)))
    (is (identical? vector (:error interceptor)))))

(deftest ^{:stratum 0} explicit-config-overrides-registered-defaults
  (let [interceptor (phase/get-phase-interceptor-method {:phase :verify
                                                        :timeout-ms 123})
        config (:config interceptor)]
    (is (= ::verify/verify (:name interceptor)))
    (is (= 123 (:timeout-ms config)))
    (is (= (:gates verify/default-config) (:gates config)))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} capsule-runner-retains-the-public-parser-seam
  (with-redefs [verify/parse-test-output parsed-sentinel]
    (is (= {:parsed? true}
           (verify/run-tests-in-capsule! execute-empty :executor :environment "/tmp")))))

(comment
  (clojure.test/run-tests 'ai.miniforge.phase-software-factory.verify-registration-test))
