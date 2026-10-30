;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.effect-transaction.governed-test
  (:require
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.effect-transaction.interface :as fx]
   [ai.miniforge.execution-grant.interface :as grant]
   [clojure.test :refer [deftest is testing]])
  (:import
   [java.nio.file Files]
   [java.nio.file.attribute FileAttribute]
   [java.time Instant]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} request
  {:workflow-run/id #uuid "00000000-0000-0000-0000-0000000000a1"
   :workflow-run/status :running :effect/class :effect/merge
   :effect/preflight {:preflight/type :preflight/merge-readiness :preflight/result :allow}
   :pr/repo "miniforge-ai/miniforge" :pr/number 7
   :pr/head-sha "0123456789abcdef0123456789abcdef01234567"})

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} options
  "govern! options with fresh stores; `calls` counts effect runs, which
   report `outcome`."
  [calls outcome decision]
  (let [tmp #(.getCanonicalPath
              (.toFile (Files/createTempDirectory % (into-array FileAttribute []))))]
    {:store-dir (tmp "governed-effects") :grant-dir (tmp "governed-grants")
     :basis [:effect/merge (:workflow-run/id request) 7] :request request
     :envelope {:envelope/id (random-uuid) :envelope/decision decision}
     :proposal {:merge/method :squash}
     :effect-fn (fn [_] (swap! calls inc) {:effect/outcome outcome})
     :probe-fn (fn [_] {:effect/observed {} :effect/matched? true})}))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} an-allowed-effect-runs-once-under-registered-authority-test
  (let [calls (atom 0)
        opts (options calls :succeeded :allow)
        done (fx/govern! opts (Instant/now))]
    (is (= :succeeded (:effect/state done)))
    (is (= :granted (:effect/authority done)))
    (is (grant/signed? (grant/current (:grant-dir opts) (:effect/grant-id done))))
    (is (fx/envelope-signed? (:effect/envelope done)))
    (testing "a second call for the same basis returns the record, not a second effect"
      (is (= (:effect/id done) (:effect/id (fx/govern! opts (Instant/now)))))
      (is (= 1 @calls)))))

(deftest ^{:stratum 2} a-refusing-envelope-revokes-and-never-runs-test
  (let [calls (atom 0)
        opts (options calls :succeeded :deny)
        issued (atom nil)
        issue grant/issue-for-effect]
    (with-redefs [grant/issue-for-effect (fn [& args] (reset! issued (apply issue args)))]
      (is (= :unauthorized (:anomaly/type (fx/govern! opts (Instant/now))))))
    (is (zero? @calls))
    (is (empty? (fx/list-records (:store-dir opts))))
    (is (some? (:grant/revoked-at (grant/current (:grant-dir opts) (:grant/id @issued)))))))

(deftest ^{:stratum 2} a-failed-effect-revokes-and-the-next-call-retries-test
  (let [calls (atom 0)
        opts (options calls :failed :allow)
        failed (fx/govern! opts (Instant/now))]
    (is (= :failed (:effect/state failed)))
    (is (some? (:grant/revoked-at (grant/current (:grant-dir opts) (:effect/grant-id failed)))))
    (is (= :succeeded (:effect/state (fx/govern! (assoc opts :effect-fn (fn [_] (swap! calls inc)
                                                                        {:effect/outcome :succeeded}))
                                                 (Instant/now)))))
    (is (= 2 @calls))))

(deftest ^{:stratum 2} a-grant-revoked-before-commit-never-runs-test
  (let [calls (atom 0)
        opts (options calls :succeeded :allow)
        register grant/register!]
    (with-redefs [grant/register! (fn [dir g]
                                    (let [r (register dir g)]
                                      (grant/revoke-stored! dir (:grant/id g) :revocation/operator (Instant/now))
                                      r))]
      (is (= :failed (:effect/state (fx/govern! opts (Instant/now))))))
    (is (zero? @calls))
    (is (not (anomaly/anomaly? (fx/list-records (:store-dir opts)))))))
