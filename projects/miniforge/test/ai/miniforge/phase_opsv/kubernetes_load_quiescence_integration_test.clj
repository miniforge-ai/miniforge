;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-load-quiescence-integration-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.phase-opsv.kubernetes-load-ownership-fixtures :as ownership]
            [ai.miniforge.phase-opsv.kubernetes-load-quiescence-fixtures :as fixtures]
            [ai.miniforge.phase-opsv.prometheus-test-support :as wire]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0 :private true} termination-path ["status" "containerStatuses" 0 "state" "terminated"])

(defn- ^{:stratum 0} check-observation [fixture pod expected]
  (let [[result calls] (fixtures/observe fixture pod)]
    (is (= expected (:load/quiescence result)))
    (is (= pod (:load/pod result)))
    (is (= (:record fixture) (:load/creation-record result)))
    (is (= #{:load/quiescence :load/pod :load/creation-record} (set (keys result))))
    (is (= 1 (count calls)))
    (is (= :get (:method (first calls))))))

(defn- ^{:stratum 0} check-missing-or-foreign [{:keys [pod] :as fixture}]
  (let [[missing calls] (fixtures/observe-response fixture
                         (wire/completed (wire/response "{}" {:status 404})))
        [foreign _] (fixtures/observe fixture (assoc-in pod ["metadata" "uid"] "foreign-uid"))]
    (is (anomaly/anomaly? missing))
    (is (anomaly/anomaly? foreign))
    (is (nil? (:load/quiescence missing)))
    (is (nil? (:load/quiescence foreign)))
    (is (= 1 (count calls)))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} check-not-started [{:keys [pod] :as fixture}]
  (check-observation fixture pod :not-started)
  (doseq [changed [(assoc-in pod ["spec" "nodeName"] "assigned-node")
                   (update pod "spec" dissoc "schedulingGates")
                   (assoc-in pod ["status" "phase"] "Failed")
                   (assoc-in pod ["status" "containerStatuses"] [{"state" {"running" {}}}])
                   (assoc-in pod ["status" "initContainerStatuses"] [{}])
                   (assoc-in pod ["status" "ephemeralContainerStatuses"] [{}])]]
    (check-observation fixture changed :unresolved)))

(defn- ^{:stratum 1} check-terminal [{:keys [pod] :as fixture}]
  (let [terminal (fixtures/terminated-pod pod)]
    (doseq [reason ["Completed" "Error" "OOMKilled"]]
      (check-observation fixture (-> terminal
                                    (assoc-in ["status" "phase"] "Failed")
                                    (assoc-in (conj termination-path "reason") reason)) :terminated))
    (check-observation fixture terminal :terminated)))

(defn- ^{:stratum 1} check-unknown-status [{:keys [pod] :as fixture}]
  (let [terminal (fixtures/terminated-pod pod)]
    (doseq [changed [(assoc-in terminal ["status" "phase"] "Running")
                     (assoc-in terminal (conj termination-path "reason") "ContainerStatusUnknown")
                     (assoc-in terminal (conj termination-path "startedAt") "0001-01-01T00:00:00Z")
                     (assoc-in terminal (conj termination-path "finishedAt") "malformed-time")
                     (assoc-in terminal (conj termination-path "finishedAt") "2026-11-08T00:59:59Z")
                     (update-in terminal termination-path dissoc "containerID")
                     (assoc-in terminal (conj termination-path "containerID") "other-container")
                     (assoc-in terminal ["status" "containerStatuses" 0 "restartCount"] 1)
                     (assoc-in terminal ["status" "containerStatuses" 0 "state" "running"] {})
                     (update terminal "status" dissoc "containerStatuses")]]
      (check-observation fixture changed :unresolved))))

(defn- ^{:stratum 1} check-other-workers [{:keys [pod] :as fixture}]
  (let [terminal (fixtures/terminated-pod pod)]
    (doseq [changed [(assoc-in terminal ["spec" "restartPolicy"] "Always")
                     (assoc-in terminal ["spec" "containers" 0 "restartPolicy"] "Always")
                     (update-in terminal ["spec" "containers"] conj {"name" "sidecar"})
                     (assoc-in terminal ["spec" "initContainers"] [{"name" "native-sidecar"}])
                     (assoc-in terminal ["spec" "ephemeralContainers"] [{"name" "debugger"}])
                     (assoc-in terminal ["status" "initContainerStatuses"] [{}])
                     (assoc-in terminal ["status" "ephemeralContainerStatuses"] [{}])]]
      (check-observation fixture changed :unresolved))))

(defn- ^{:stratum 1} check-malformed [{:keys [pod] :as fixture}]
  (doseq [path [["spec"] ["status"] ["spec" "containers"] ["spec" "schedulingGates"]
                ["status" "containerStatuses"] ["status" "initContainerStatuses"]
                ["status" "ephemeralContainerStatuses"]]]
    (check-observation fixture (assoc-in pod path 42) :unresolved)))

(defn- ^{:stratum 1} check-incomplete-worker [{:keys [pod] :as fixture}]
  (doseq [changed [(update pod "spec" dissoc "containers")
                   (assoc-in pod ["spec" "containers"] [])]]
    (check-observation fixture changed :unresolved)))

(deftest ^{:stratum 1} disappearance-or-foreign-identity-is-not-owned-quiescence-test
  (ownership/with-created check-missing-or-foreign))

(defn- ^{:stratum 1} check-fresh-state [{:keys [pod record] :as fixture}]
  (check-observation fixture (fixtures/terminated-pod pod) :terminated)
  (check-observation fixture
    (-> pod (update "spec" dissoc "schedulingGates")
        (assoc-in ["spec" "activeDeadlineSeconds"] 1)
        (assoc-in ["metadata" "deletionTimestamp"] "2026-11-08T01:02:00Z")
        (assoc-in ["status" "phase"] "Failed")) :unresolved)
  (ownership/replace-record! fixture (assoc record :effect/state :unknown-outcome))
  (let [[result calls] (fixtures/observe fixture (fixtures/terminated-pod pod))]
    (is (= :conflict (:anomaly/type result)))
    (is (nil? (:load/quiescence result)))
    (is (empty? calls))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} intact-creation-gate-and-no-scheduling-or-activity-prove-only-not-started-test
  (ownership/with-created check-not-started))

(deftest ^{:stratum 2} actual-single-worker-termination-is-distinct-from-a-terminal-pod-phase-test
  (ownership/with-created check-terminal))

(deftest ^{:stratum 2} missing-synthetic-inconsistent-or-restarting-container-state-remains-unresolved-test
  (ownership/with-created check-unknown-status))

(deftest ^{:stratum 2} every-possible-worker-must-be-accounted-for-test
  (ownership/with-created check-other-workers))

(deftest ^{:stratum 2} malformed-provider-state-is-unresolved-not-an-exception-or-proof-test
  (ownership/with-created check-malformed))

(deftest ^{:stratum 2} omitted-or-empty-regular-worker-data-cannot-prove-not-started-test
  (ownership/with-created check-incomplete-worker))

(deftest ^{:stratum 2} deadline-deletion-and-previous-observations-do-not-establish-current-quiescence-test
  (ownership/with-created check-fresh-state))
