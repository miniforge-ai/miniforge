;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.governed-load-proposal-integration-test
  (:require [ai.miniforge.content-hash.interface :as hash]
            [ai.miniforge.effect-transaction.interface :as fx]
            [ai.miniforge.execution-grant.interface :as grant]
            [ai.miniforge.execution-grant.opsv-commit-fixtures :as commit]
            [ai.miniforge.execution-grant.opsv-scope-fixtures :as clock]
            [ai.miniforge.phase-opsv.governed-load-proposal-fixtures :as fixtures]
            [ai.miniforge.phase-opsv.interface :as phase]
            [clojure.test :refer [deftest is]]
            [malli.core :as m]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} effect! [calls record]
  (swap! calls conj record)
  (commit/success))

(defn- ^{:stratum 0} check-wire [{:keys [ctx input id]}]
  (let [p (phase/prepare-governed-kubernetes-load-proposal! ctx input)
        scope (:opsv/experiment p)
        request (:kubernetes/request p)
        body (:body request)
        pod (get body "spec")
        worker (first (get pod "containers"))
        limits (:limits input)]
    (is (m/validate grant/OpsvExperimentScope p))
    (is (= :effect/load-test (:effect/class p)))
    (is (= :start-load (:opsv/operation scope)))
    (is (= id (:comparison/artifact-ref scope)))
    (is (= (:execution/id ctx) (:workflow-run/id p)))
    (is (= (hash/content-hash request) (:opsv/payload-hash scope)))
    (is (= :post (:method request)))
    (is (= "Pod" (get body "kind")))
    (is (= "v1" (:api-version (first (:opsv/resources scope)))))
    (is (= "Pod" (:kind (first (:opsv/resources scope)))))
    (is (= (get-in scope [:runtime/target :namespace]) (get-in body ["metadata" "namespace"])))
    (is (= (get-in body ["metadata" "name"]) (get-in scope [:opsv/resources 0 :name])))
    (is (nil? (get-in scope [:opsv/resources 0 :uid])))
    (is (= (quot (:opsv/duration-ms limits) 1000) (get-in body ["spec" "activeDeadlineSeconds"])))
    (is (= ["miniforge.ai/opsv-authorized-start"] (mapv #(get % "name") (get pod "schedulingGates"))))
    (is (not (contains? pod "nodeName")))
    (is (not (contains? (get body "metadata") "ownerReferences")))
    (is (= "Never" (get pod "restartPolicy")))
    (is (false? (get pod "automountServiceAccountToken")))
    (is (false? (get pod "enableServiceLinks")))
    (is (true? (get-in pod ["securityContext" "runAsNonRoot"])))
    (is (= ["/usr/bin/fortio"] (get worker "command")))
    (is (= (get-in input [:runtime :load/image]) (get worker "image")))
    (is (= (get-in worker ["resources" "requests"]) (get-in worker ["resources" "limits"])))
    (is (true? (get-in worker ["securityContext" "readOnlyRootFilesystem"])))
    (is (= (str (get-in limits [:opsv/load-limits :load/max-requests])) (fixtures/argument p "-n")))
    (is (= (str (get-in limits [:opsv/load-limits :load/max-concurrency])) (fixtures/argument p "-c")))
    (is (= (str (get-in limits [:opsv/load-limits :load/max-rate-per-second])) (fixtures/argument p "-qps")))
    (is (= (first (:opsv/traffic-destinations limits)) (last (get worker "args"))))
    (doseq [flag ["-nocatchup=true" "-uniform=true" "-stdclient=true" "-keepalive=false" "-L=false" "-k=false"]]
      (is (some #{flag} (get worker "args"))))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} check-authority [{:keys [ctx input directory]}]
  (let [p (phase/prepare-governed-kubernetes-load-proposal! ctx input)
        dir (str directory "/load-authority")
        g (grant/issue-for-effect dir (fixtures/issuance-request p) clock/now)
        proposed (commit/proposed! dir g p)
        calls (atom [])
        result (fx/commit-current! dir (:effect/id p) (:grant/id g) (partial grant/current dir)
                                   (constantly clock/now) (partial effect! calls))]
    (is (grant/valid? g))
    (is (= :proposed (:effect/state proposed)))
    (is (= :succeeded (:effect/state result)))
    (is (= 1 (count @calls)))
    (is (= :committing (:effect/state (first @calls))))
    (is (= result (fx/read-record dir (:effect/id p))))
    (doseq [[path replacement] [[[:opsv/experiment :opsv/payload-hash] (hash/content-hash :changed)]
                                [[:opsv/experiment :opsv/operation] :cleanup-load]]]
      (is (= :scope-mismatch
             (:grant/outcome (grant/authorize g (fixtures/usage (assoc-in p path replacement)) clock/now)))))))

(deftest ^{:stratum 1} native-load-proposal-has-fixed-bounded-wire-and-dedicated-scope-test
  (fixtures/with-input check-wire))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} load-proposal-composes-with-real-issuer-and-pinned-durable-commit-test
  (fixtures/with-input check-authority))

(comment ::only-the-provider-callback-is-simulated-no-pod-or-load-execution)
