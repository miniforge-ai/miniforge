;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-load-stop-integration-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.content-hash.interface :as hash]
            [ai.miniforge.execution-grant.interface :as grant]
            [ai.miniforge.execution-grant.opsv-scope-fixtures :as clock]
            [ai.miniforge.phase-opsv.kubernetes-load-ownership-fixtures :as ownership]
            [ai.miniforge.phase-opsv.kubernetes-load-stop-fixtures :as fixtures]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} check-canonical [{:keys [ctx request pod record] :as fixture}]
  (let [[result calls] (fixtures/prepare fixture pod)
        wire (:kubernetes/request result)
        scope (:opsv/experiment result)
        metadata (get pod "metadata")]
    (is (= (:effect/id request) (:effect/id result)))
    (is (= (:execution/id ctx) (:workflow-run/id result)))
    (is (= :effect/load-test (:effect/class result)))
    (is (= :stop-load (:opsv/operation scope)))
    (is (= (hash/content-hash wire) (:opsv/payload-hash scope)))
    (is (= :patch (:method wire)))
    (is (= "application/json-patch+json" (:media-type wire)))
    (is (= (str (get-in record [:effect/proposal :kubernetes/request :path]) "/" (get metadata "name")) (:path wire)))
    (is (= [{"op" "test" "path" "/metadata/uid" "value" (get metadata "uid")}
            {"op" "test" "path" "/metadata/resourceVersion" "value" (get metadata "resourceVersion")}
            {"op" "replace" "path" "/spec/activeDeadlineSeconds" "value" 1}] (:body wire)))
    (is (= (get metadata "uid") (get-in scope [:opsv/resources 0 :uid])))
    (is (= (get-in request [:recovery/input :recovery/duration-ms]) (:opsv/duration-ms scope)))
    (is (= 1 (count calls)))
    (is (= :get (:method (first calls))))
    (is (nil? (:effect/grant-id result)))
    (is (nil? (:effect/envelope-id result)))
    (is (nil? (:load/quiescence result)))))

(defn- ^{:stratum 0} check-fresh-revision [{:keys [pod] :as fixture}]
  (let [[first-result _] (fixtures/prepare fixture pod)
        [next-result _] (fixtures/prepare fixture (assoc-in pod ["metadata" "resourceVersion"] "new-live-revision"))]
    (is (= "new-live-revision" (get-in next-result [:kubernetes/request :body 1 "value"])))
    (is (not= (get-in first-result [:opsv/experiment :opsv/payload-hash])
              (get-in next-result [:opsv/experiment :opsv/payload-hash])))))

(defn- ^{:stratum 0} check-current-approval [{:keys [ctx pod] :as fixture}]
  (let [revoked (assoc-in ctx [:execution/opts :opsv/recovery-plan-admission :recovery/approved-plan-hashes] #{})
        [result calls] (fixtures/prepare (assoc fixture :ctx revoked) pod)]
    (is (anomaly/anomaly? result))
    (is (empty? calls))))

(defn- ^{:stratum 0} check-substituted-intent [{:keys [pod] :as fixture}]
  (doseq [[path value] [[[:load :limits :effect/id] (random-uuid)]
                       [[:load :limits :opsv/load-limits :load/max-requests] 999]
                       [[:load :comparison/artifact-ref] (random-uuid)]
                       [[:load :declaration :kubernetes/deployment-name] "other-service"]
                       [[:load :declaration :runtime/target :namespace] "other-namespace"]]]
    (let [[result calls] (fixtures/prepare (update-in fixture [:request :recovery/input] assoc-in path value) pod)]
      (is (anomaly/anomaly? result))
      (is (empty? calls)))))

(defn- ^{:stratum 0} check-unsafe-input [{:keys [pod record] :as fixture}]
  (doseq [changed [(assoc-in fixture [:request :effect/id] (:effect/id record))
                   (assoc-in fixture [:request :approved?] true)
                   (assoc-in fixture [:request :recovery/artifact-ref] (random-uuid))]]
    (let [[result _] (fixtures/prepare changed pod)] (is (anomaly/anomaly? result))))
  (doseq [deadline [nil 0 -1 "1"]]
    (let [[result _] (fixtures/prepare fixture (assoc-in pod ["spec" "activeDeadlineSeconds"] deadline))]
      (is (anomaly/anomaly? result)))))

(defn- ^{:stratum 0} check-owned-only [{:keys [pod record] :as fixture}]
  (let [[foreign _] (fixtures/prepare fixture (assoc-in pod ["metadata" "uid"] "other-uid"))]
    (is (anomaly/anomaly? foreign)))
  (ownership/replace-record! fixture (assoc record :effect/state :unknown-outcome))
  (let [[uncertain calls] (fixtures/prepare fixture pod)]
    (is (anomaly/anomaly? uncertain))
    (is (empty? calls))))

(defn- ^{:stratum 0} check-revoked-forward [{:keys [runtime record pod] :as fixture}]
  (let [directory (:effects-directory runtime)
        id (:effect/grant-id record)
        revoked (grant/revoke-stored! directory id :revocation/operator clock/now)
        [result calls] (fixtures/prepare fixture pod)]
    (is (some? (:grant/revoked-at revoked)))
    (is (some? (:grant/revoked-at (grant/current directory id))))
    (is (= :stop-load (get-in result [:opsv/experiment :opsv/operation])))
    (is (= :get (:method (first calls))))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} exact-stop-request-is-read-only-and-carries-no-authority-or-termination-proof-test
  (fixtures/with-created check-canonical))

(deftest ^{:stratum 1} each-request-uses-the-fresh-provider-revision-and-new-payload-hash-test
  (fixtures/with-created check-fresh-revision))

(deftest ^{:stratum 1} current-retained-plan-approval-is-required-before-any-provider-read-test
  (fixtures/with-created check-current-approval))

(deftest ^{:stratum 1} changed-creation-payload-original-service-or-target-cannot-reuse-retained-plan-test
  (fixtures/with-created check-substituted-intent))

(deftest ^{:stratum 1} reused-creation-id-caller-approval-flags-and-unbounded-deadlines-refuse-test
  (fixtures/with-created check-unsafe-input))

(deftest ^{:stratum 1} fresh-owned-identity-and-certain-native-creation-are-required-test
  (fixtures/with-created check-owned-only))

(deftest ^{:stratum 1} revoked-forward-grants-do-not-disable-read-only-recovery-preparation-test
  (fixtures/with-created check-revoked-forward))
