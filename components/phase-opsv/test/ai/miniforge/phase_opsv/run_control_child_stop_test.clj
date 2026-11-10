;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.run-control-child-stop-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.execution-grant.interface :as grant]
            [ai.miniforge.phase-opsv.interface :as opsv]
            [ai.miniforge.phase-opsv.pr-fixtures :as clock]
            [ai.miniforge.phase-opsv.run-control :as control]
            [ai.miniforge.phase-opsv.run-control-fixtures :as fixtures]
            [clojure.test :refer [deftest is]]
            [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0 :private true} unused-authority-directory
  "Empty-grant cases bind an identity only; no authority files are read or written here."
  "/tmp/child-stop-no-grants")

(defn- ^{:stratum 0} acknowledge-abort! [calls]
  (swap! calls inc)
  true)

(defn- ^{:stratum 0} replacement [supervisor workflow-id directory]
  (opsv/register-run-control! supervisor workflow-id directory (constantly true)))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} check-revoked [supervisor aborts ctx runtime calls]
  (let [output (opsv/actuate ctx)
        id (get-in output [:opsv/actuation-record :governed-effects 0 :evidence/grant-id])
        handle (:control runtime)
        directory (:authority-directory runtime)
        workflow-id (:execution/id ctx)
        peer (replacement supervisor (random-uuid) directory)
        stopped (opsv/stop-run-control! handle clock/now)]
    (is (uuid? id))
    (is (= 2 (count @calls)))
    (is (true? (:cleanup-confirmed? stopped)))
    (is (some? (:grant/revoked-at (grant/current directory id))))
    (is (= 1 @aborts))
    (is (anomaly/anomaly? (control/at-boundary! handle (constantly :unexpected))))
    (is (= :peer-live (control/at-boundary! (:control peer) (constantly :peer-live))))
    (is (anomaly/anomaly? (replacement supervisor workflow-id directory)))
    (is (true? (:cleanup-confirmed? (opsv/stop-run-control! handle clock/now))))
    (is (= 1 @aborts))
    (is (true? (:retired? (opsv/retire-run-control! handle clock/now))))
    (is (not (anomaly/anomaly? (replacement supervisor workflow-id directory))))
    (is (anomaly/anomaly? (control/at-boundary! handle (constantly :unexpected))))))

(deftest ^{:stratum 1} admitted-child-work-can-settle-but-stop-never-releases-the-registration-test
  (let [supervisor (opsv/create-run-supervisor)
        workflow-id (random-uuid)
        directory unused-authority-directory
        run (replacement supervisor workflow-id directory)
        entered (promise)
        finish (promise)
        work (future (control/at-boundary! (:control run) (partial fixtures/await-work entered finish)))]
    (try+
      (is (= true (deref entered fixtures/wait-ms :timeout)))
      (is (= 1 (get-in (opsv/stop-run-control! (:control run) clock/now) [:mutation-status :in-flight])))
      (is (anomaly/anomaly? (replacement supervisor workflow-id directory)))
      (deliver finish :settled)
      (is (= :settled (deref work fixtures/wait-ms :timeout)))
      (is (true? (:cleanup-confirmed? (opsv/stop-run-control! (:control run) clock/now))))
      (is (anomaly/anomaly? (replacement supervisor workflow-id directory)))
      (finally
        (deliver finish :settled)
        (future-cancel work)
        (opsv/stop-supervised-runs! supervisor clock/now)))))

(deftest ^{:stratum 1} invalid-child-stop-cannot-stop-a-parent-or-accept-a-missing-clock-test
  (let [supervisor (opsv/create-run-supervisor)
        run (replacement supervisor (random-uuid) unused-authority-directory)]
    (try+
      (is (anomaly/anomaly? (opsv/stop-run-control! supervisor clock/now)))
      (is (anomaly/anomaly? (opsv/stop-run-control! (Object.) clock/now)))
      (is (anomaly/anomaly? (opsv/stop-run-control! (:control run) nil)))
      (is (= :still-live (control/at-boundary! (:control run) (constantly :still-live))))
      (finally (opsv/stop-supervised-runs! supervisor clock/now)))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} child-stop-revokes-real-forward-grants-without-forgetting-or-stopping-siblings-test
  (let [supervisor (opsv/create-run-supervisor)
        aborts (atom 0)]
    (try+
      (fixtures/with-run supervisor (partial acknowledge-abort! aborts)
        (partial check-revoked supervisor aborts))
      (finally (opsv/stop-supervised-runs! supervisor clock/now)))))
