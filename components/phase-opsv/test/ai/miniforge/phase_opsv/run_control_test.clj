;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.run-control-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.execution-grant.interface :as grant]
            [ai.miniforge.opsv-actuation.interface :as actuation]
            [ai.miniforge.phase-opsv.interface :as opsv]
            [ai.miniforge.phase-opsv.pr-fixtures :as f]
            [ai.miniforge.phase-opsv.run-control :as control]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} with-run [supervisor abort! test-fn]
  (let [{:keys [ctx runtime calls]} (f/setup)
        handles (opsv/register-run-control! supervisor (:execution/id ctx)
                   (:authority-directory runtime) abort!)
        controlled (merge runtime handles)]
    (try (test-fn (assoc-in ctx [:execution/opts :opsv/pr-execution] controlled) controlled calls)
         (finally (doseq [file (reverse (file-seq (io/file (get-in runtime [:provider :directory]))))]
                    (io/delete-file file))))))

(deftest ^{:stratum 0} stop-closes-all-fences-before-abort-callbacks-test
  (let [supervisor (opsv/create-run-supervisor) observed (atom [])
        first (opsv/register-run-control! supervisor (random-uuid) "/tmp/authority-a"
                (fn [] (swap! observed conj :first) true))
        second (opsv/register-run-control! supervisor (random-uuid) "/tmp/authority-b"
                 (fn [] (swap! observed conj (:stopped? (actuation/mutation-status (:fence first)))) true))
        result (opsv/stop-supervised-runs! supervisor f/now)]
    (is (:cleanup-confirmed? result))
    (is (:effects-settled? result))
    (is (= #{:first true} (set @observed)))
    (is (anomaly/anomaly? (control/at-boundary! (:control second) (constantly :unexpected))))
    (is (anomaly/anomaly? (opsv/register-run-control! supervisor (random-uuid) "/tmp/c" (constantly true))))
    (is (anomaly/anomaly? (opsv/stop-supervised-runs! (Object.) f/now)))))

(deftest ^{:stratum 0} failed-abort-does-not-skip-other-runs-or-reopen-admission-test
  (let [supervisor (opsv/create-run-supervisor) fail? (atom true) other (atom 0)
        first (opsv/register-run-control! supervisor (random-uuid) "/tmp/a"
                #(if @fail? (throw (AssertionError. "abort failed")) true))]
    (opsv/register-run-control! supervisor (random-uuid) "/tmp/b" #(do (swap! other inc) true))
    (is (false? (:cleanup-confirmed? (opsv/stop-supervised-runs! supervisor f/now))))
    (is (= 1 @other))
    (is (anomaly/anomaly? (control/at-boundary! (:control first) (constantly :unexpected))))
    (is (false? (:retired? (opsv/retire-run-control! (:control first) f/now))))
    (reset! fail? false)
    (is (:cleanup-confirmed? (opsv/stop-supervised-runs! supervisor f/now)))))

(deftest ^{:stratum 0} admitted-work-can-settle-but-cannot-retire-early-test
  (let [supervisor (opsv/create-run-supervisor)
        run (opsv/register-run-control! supervisor (random-uuid) "/tmp/a" (constantly true))
        entered (promise) finish (promise)
        work (future (control/at-boundary! (:control run) #(do (deliver entered true) @finish)))]
    (try
      (is (= true (deref entered 2000 :timeout)))
      (is (false? (:effects-settled? (opsv/stop-supervised-runs! supervisor f/now))))
      (is (false? (:retired? (opsv/retire-run-control! (:control run) f/now))))
      (deliver finish :settled)
      (is (= :settled (deref work 2000 :timeout)))
      (is (:retired? (opsv/retire-run-control! (:control run) f/now)))
      (is (anomaly/anomaly? (control/at-boundary! (:control run) (constantly :unexpected))))
      (finally (deliver finish :settled) (future-cancel work)))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} successful-pr-grant-is-revoked-by-global-stop-test
  (let [supervisor (opsv/create-run-supervisor) aborts (atom 0)]
    (with-run supervisor #(do (swap! aborts inc) true)
      (fn [ctx runtime calls]
        (let [output (opsv/actuate ctx)
              id (get-in output [:opsv/actuation-record :governed-effects 0 :evidence/grant-id])
              stopped (opsv/stop-supervised-runs! supervisor f/now)]
          (is (= :pr-only (get-in output [:opsv/actuation-record :effective-actuation-mode])))
          (is (:cleanup-confirmed? stopped))
          (is (= 1 @aborts))
          (is (some? (:grant/revoked-at (grant/current (:authority-directory runtime) id))))
          (is (= 2 (count @calls)))
          (is (:retired? (opsv/retire-run-control! (:control runtime) f/now)))
          (is (empty? (:runs (opsv/stop-supervised-runs! supervisor f/now)))))))))

(deftest ^{:stratum 1} stop-during-preflight-prevents-provider-post-test
  (let [supervisor (opsv/create-run-supervisor)]
    (with-run supervisor (constantly true)
      (fn [ctx runtime calls]
        (let [command (fn [options arguments]
                        (let [response (f/command calls options arguments)]
                          (opsv/stop-supervised-runs! supervisor f/now)
                          response))
              output (opsv/actuate (assoc-in ctx [:execution/opts :opsv/pr-execution :provider :run-command] command))]
          (is (not= :pr-only (get-in output [:opsv/actuation-record :effective-actuation-mode])))
          (is (= ["GET"] (mapv #(get-in % [:arguments 6]) @calls)))
          (is (control/stopped? (:control runtime)))
          (is (:cleanup-confirmed? (opsv/stop-supervised-runs! supervisor f/now))))))))

(deftest ^{:stratum 1} grant-registration-racing-stop-is-revoked-before-provider-test
  (let [supervisor (opsv/create-run-supervisor)]
    (with-run supervisor (constantly true)
      (fn [ctx runtime calls]
        (let [register grant/register! issued-id (atom nil)
              output (with-redefs [grant/register! (fn [directory issued]
                                                   (let [result (register directory issued)]
                                                     (reset! issued-id (:grant/id issued))
                                                     (opsv/stop-supervised-runs! supervisor f/now)
                                                     result))]
                       (opsv/actuate ctx))]
          (is (= :none (get-in output [:opsv/actuation-record :effective-actuation-mode])))
          (is (empty? @calls))
          (is (some? (:grant/revoked-at (grant/current (:authority-directory runtime) @issued-id)))))))))

(deftest ^{:stratum 1} mismatched-runtime-control-is-refused-before-issuance-test
  (let [supervisor (opsv/create-run-supervisor)]
    (with-run supervisor (constantly true)
      (fn [ctx _ calls]
        (let [output (opsv/actuate (assoc-in ctx [:execution/opts :opsv/pr-execution :fence]
                                             (actuation/create-mutation-fence)))]
          (is (anomaly/anomaly? output))
          (is (empty? @calls)))))))

(deftest ^{:stratum 1} failed-revocation-is-retained-for-cleanup-retry-test
  (let [supervisor (opsv/create-run-supervisor)]
    (with-run supervisor (constantly true)
      (fn [ctx runtime _]
        (let [output (opsv/actuate ctx)
              id (get-in output [:opsv/actuation-record :governed-effects 0 :evidence/grant-id])
              unconfirmed (with-redefs [grant/revoke-stored! (fn [& _] (anomaly/anomaly :unavailable "disk failed" {}))]
                            (opsv/stop-supervised-runs! supervisor f/now))]
          (is (false? (:cleanup-confirmed? unconfirmed)))
          (is (nil? (:grant/revoked-at (grant/current (:authority-directory runtime) id))))
          (is (control/stopped? (:control runtime)))
          (is (:cleanup-confirmed? (opsv/stop-supervised-runs! supervisor f/now)))
          (is (some? (:grant/revoked-at (grant/current (:authority-directory runtime) id)))))))))
