;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-load-start-integration-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.content-hash.interface :as hash]
            [ai.miniforge.effect-transaction.interface :as effect]
            [ai.miniforge.execution-grant.interface :as grant]
            [ai.miniforge.execution-grant.opsv-scope-fixtures :as clock]
            [ai.miniforge.phase-opsv.governed-load-proposal-fixtures :as load]
            [ai.miniforge.phase-opsv.governed-experiment-schema :as bounds]
            [ai.miniforge.phase-opsv.interface :as phase]
            [ai.miniforge.phase-opsv.kubernetes-load-container :as worker]
            [ai.miniforge.phase-opsv.kubernetes-load-quiescence-fixtures :as quiescence]
            [ai.miniforge.phase-opsv.kubernetes-load-start :as model]
            [ai.miniforge.phase-opsv.kubernetes-load-start-profile-fixtures :as native-defaults]
            [ai.miniforge.phase-opsv.kubernetes-load-stop-fixtures :as creation]
            [ai.miniforge.phase-opsv.kubernetes-owned-load-request-fixtures :as wire]
            [ai.miniforge.phase-opsv.kubernetes-recovery-plan-schema :as plan-schema]
            [babashka.fs :as fs]
            [clojure.test :refer [deftest is]]
            [malli.core :as m]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0 :private true} prepare
  (partial wire/prepare phase/prepare-kubernetes-load-start-proposal!))

(defn- ^{:stratum 0} check-forward-duration [{:keys [ctx request record pod prepared]}]
  ;; Pure constructor unit case: differing well-formed plan duration must not
  ;; replace the forward worker's original bounds. This is not plan admission.
  (let [duration (get-in request [:recovery/input :load :limits :opsv/duration-ms])
        recovery-duration (+ duration bounds/millis-per-second)
        input (assoc-in request [:recovery/input :recovery/duration-ms] recovery-duration)
        plan (assoc (:recovery/plan prepared) :recovery/duration-ms recovery-duration)
        owned {:load/pod pod
               :load/creation-record record
               :load/quiescence :not-started}
        result (model/prepare ctx input plan owned)]
    (is (m/validate plan-schema/Plan plan))
    (is (m/validate grant/OpsvExperimentScope result))
    (is (= duration (get-in result [:opsv/experiment :opsv/duration-ms])))
    (is (not= recovery-duration (get-in result [:opsv/experiment :opsv/duration-ms])))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} check-canonical [{:keys [ctx request record runtime pod directory] :as fixture}]
  (let [[proposal calls] (prepare fixture pod)
        payload (:kubernetes/request proposal)
        body (:body payload)
        scope (:opsv/experiment proposal)
        issuance (load/issuance-request proposal)
        breach-directory (str (fs/path directory "start-breach-history"))
        issued (grant/issue-for-effect breach-directory issuance clock/now)]
    (is (m/validate grant/OpsvExperimentScope proposal))
    (is (= (:execution/id ctx) (:workflow-run/id proposal)))
    (is (= (:effect/id request) (:effect/id proposal)))
    (is (= :effect/load-test (:effect/class proposal)))
    (is (= :start-load (:opsv/operation scope)))
    (is (= (get-in request [:recovery/input :load :limits :opsv/duration-ms]) (:opsv/duration-ms scope)))
    (is (= (hash/content-hash payload) (:opsv/payload-hash scope)))
    (is (= :patch (:method payload)))
    (is (= "application/json-patch+json" (:media-type payload)))
    (is (= (str (get-in record [:effect/proposal :kubernetes/request :path]) "/" (get-in pod ["metadata" "name"])) (:path payload)))
    (is (= [{"op" "test"
             "path" "/metadata/uid"
             "value" (get-in pod ["metadata" "uid"])}
            {"op" "test"
             "path" "/metadata/resourceVersion"
             "value" (get-in pod ["metadata" "resourceVersion"])}
            {"op" "test"
             "path" "/spec/schedulingGates"
             "value" worker/scheduling-gates}
            {"op" "remove"
             "path" "/spec/schedulingGates"}] body))
    (is (= (get-in pod ["metadata" "uid"]) (get-in scope [:opsv/resources 0 :uid])))
    (is (= [:get] (mapv :method calls)))
    (is (= record (effect/read-record (:effects-directory runtime) (:effect/id record))))
    (is (not-any? #(contains? proposal %) [:effect/grant-id :effect/envelope-id :load/started?]))
    (is (grant/valid? issued))
    (when (grant/valid? issued)
      (is (grant/authorized? (grant/authorize issued (load/usage proposal) clock/now))))
    (is (anomaly/anomaly? (grant/issue-for-effect breach-directory (assoc issuance :workflow-run/status :cancelled) clock/now)))))

(defn- ^{:stratum 1} check-refused [transform {:keys [pod] :as fixture}]
  (let [[result calls] (prepare fixture (transform pod))]
    (is (anomaly/anomaly? result))
    (is (= [:get] (mapv :method calls)))))

(defn- ^{:stratum 1} check-current-approval [{:keys [pod] :as fixture}]
  (let [changed (assoc-in fixture [:ctx :execution/opts :opsv/recovery-plan-admission :recovery/approved-plan-hashes] #{})
        [result calls] (prepare changed pod)]
    (is (anomaly/anomaly? result))
    (is (empty? calls))))

(defn- ^{:stratum 1} check-new-effect [{:keys [record pod] :as fixture}]
  (let [[result calls] (prepare (assoc-in fixture [:request :effect/id] (:effect/id record)) pod)]
    (is (= :conflict (:anomaly/type result)))
    (is (= [:get] (mapv :method calls)))))

(defn- ^{:stratum 1} check-invalid [{:keys [pod] :as fixture}]
  (let [[result calls] (prepare (assoc-in fixture [:request :load/started?] false) pod)]
    (is (= :invalid-input (:anomaly/type result)))
    (is (empty? calls))))

(defn- ^{:stratum 1} check-fresh-revision [{:keys [pod] :as fixture}]
  (let [[original _] (prepare fixture pod)
        [fresh _] (prepare fixture (assoc-in pod ["metadata" "resourceVersion"] "new-start-revision"))]
    (is (= "new-start-revision" (get-in fresh [:kubernetes/request :body 1 "value"])))
    (is (not= (get-in original [:opsv/experiment :opsv/payload-hash]) (get-in fresh [:opsv/experiment :opsv/payload-hash])))))

(deftest ^{:stratum 1} pure-start-constructor-preserves-original-load-duration-not-recovery-duration-test
  (creation/with-created check-forward-duration))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} canonical-gate-release-gets-separate-forward-authority-but-never-after-cancellation-test
  (creation/with-created check-canonical))

(deftest ^{:stratum 2} supported-native-defaults-and-canonical-cpu-quantities-preserve-start-intent-test
  (creation/with-created (comp check-canonical #(update % :pod native-defaults/server-defaulted))))

(deftest ^{:stratum 2} changed-started-foreign-or-deleting-worker-cannot-have-its-gate-released-test
  (doseq [transform [quiescence/terminated-pod
                     #(update % "spec" dissoc "schedulingGates")
                     #(assoc-in % ["spec" "nodeName"] "scheduled-worker")
                     #(assoc-in % ["status" "phase"] "Failed")
                     #(assoc-in % ["metadata" "uid"] "foreign-worker")
                     #(assoc-in % ["metadata" "deletionTimestamp"] "2026-11-09T00:00:00Z")
                     #(assoc-in % ["spec" "containers" 0 "image"] "docker.io/foreign/worker:latest")
                     #(assoc-in % ["spec" "containers" 0 "args"] ["unapproved-traffic"])
                     #(assoc-in % ["spec" "activeDeadlineSeconds"] 999)
                     #(assoc-in % ["spec" "initContainers"] [{"name" "extra-worker"}])]]
    (creation/with-created (partial check-refused transform))))

(deftest ^{:stratum 2} revoked-current-recovery-readiness-prevents-all-live-start-preparation-reads-test
  (creation/with-created check-current-approval))

(deftest ^{:stratum 2} unrequested-execution-extensions-cannot-be-adopted-when-releasing-the-gate-test
  (doseq [[path value] [[["spec" "containers" 0 "env"] [{"name" "HTTP_PROXY"
                                                        "value" "https://foreign.invalid"}]]
                       [["spec" "containers" 0 "envFrom"] [{"secretRef" {"name" "foreign-secret"}}]]
                       [["spec" "containers" 0 "lifecycle"] {"postStart" {"exec" {"command" ["/foreign-hook"]}}}]
                       [["spec" "containers" 0 "volumeMounts"] [{"name" "foreign-volume"
                                                                 "mountPath" "/credentials"}]]
                       [["spec" "containers" 0 "securityContext" "privileged"] true]
                       [["spec" "hostNetwork"] true]
                       [["spec" "hostPID"] true]
                       [["spec" "futureExecutionExtension"] true]
                       [["metadata" "labels" "access"] "unrequested-network-policy"]
                       [["metadata" "annotations"] {"container.apparmor.security.beta.kubernetes.io/load" "unconfined"}]
                       [["metadata" "finalizers"] ["foreign-controller/finalizer"]]
                       [["metadata" "ownerReferences"] [{"apiVersion" "apps/v1"
                                                          "kind" "ReplicaSet"
                                                          "name" "foreign-controller"
                                                          "uid" "foreign-controller-uid"
                                                          "controller" true}]]
                       [["spec" "dnsPolicy"] "None"]
                       [["spec" "schedulerName"] "foreign-scheduler"]
                       [["spec" "serviceAccountName"] "foreign-account"]
                       [["spec" "containers" 0 "terminationMessagePolicy"] "FallbackToLogsOnError"]]]
    (creation/with-created (partial check-refused #(assoc-in % path value)))))

(deftest ^{:stratum 2} creation-effect-identity-cannot-be-reused-for-forward-gate-release-test
  (creation/with-created check-new-effect))

(deftest ^{:stratum 2} caller-supplied-start-flags-are-rejected-before-provider-reads-test
  (creation/with-created check-invalid))

(deftest ^{:stratum 2} current-worker-resource-version-is-bound-to-the-start-payload-hash-test
  (creation/with-created check-fresh-revision))

(comment
  (creation/with-created check-canonical))
