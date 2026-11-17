;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-load-provider-refusals-integration-test
  (:require [ai.miniforge.content-hash.interface :as hash]
            [ai.miniforge.effect-transaction.provider-dispatch-fixtures :as dispatch]
            [ai.miniforge.phase-opsv.boundary.http-transport :as http]
            [ai.miniforge.phase-opsv.kubernetes-load-provider-fixtures :as fixtures]
            [ai.miniforge.phase-opsv.prometheus-test-support :as wire]
            [cheshire.core :as json]
            [clojure.test :refer [deftest is]])
  (:import [java.net HttpURLConnection]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} check-authority [input]
  (doseq [[path value] [[[:effect/state] :proposed] [[:effect/authority] :unenforced]
                        [[:effect/grant-id] nil] [[:effect/envelope-id] nil]
                        [[:effect/class] :effect/deploy] [[:effect/proposal :effect/class] :effect/deploy]
                        [[:effect/proposal :effect/id] (random-uuid)]
                        [[:effect/proposal :opsv/experiment :opsv/operation] :cleanup-load]
                        [[:effect/proposal :opsv/experiment :kubernetes/context] "foreign"]
                        [[:effect/proposal :opsv/experiment :opsv/resources 0 :uid] "unproven"]
                        [[:effect/proposal :opsv/experiment :opsv/payload-hash] (hash/content-hash :foreign)]]]
    (let [[result calls] (fixtures/apply! input (assoc-in (:record input) path value)
                                         (fixtures/response input) dispatch/dispatch!)]
      (is (= :invalid-input (:anomaly/type result)))
      (is (empty? calls)))))

(defn- ^{:stratum 0} check-runtime [input]
  (doseq [[key value] [[:kubernetes/base-url "http://cluster.invalid"]
                       [:kubernetes/base-url "https://cluster.invalid/foreign"]
                       [:kubernetes/cluster-id "foreign"] [:kubernetes/namespace "foreign"]
                       [:kubernetes/context "foreign"] [:kubernetes/timeout-ms 0]]]
    (let [changed (assoc-in input [:runtime key] value)
          [result calls] (fixtures/apply! changed (:record input) (fixtures/response input) dispatch/dispatch!)]
      (is (= :invalid-input (:anomaly/type result)))
      (is (empty? calls)))))

(defn- ^{:stratum 0} check-payload [input]
  (let [original (get-in input [:proposal :kubernetes/request])]
    (doseq [payload [(assoc original :method :delete) (assoc original :path "/api/v1/namespaces/staging")
                     (assoc original :headers {}) (assoc-in original [:body "kind"] "Job")
                     (assoc-in original [:body "spec" "schedulingGates"] [])
                     (assoc-in original [:body "spec" "containers" 0 "command"] ["sh"])
                     (assoc-in original [:body "spec" "containers" 0 "env"] [])
                     (assoc-in original [:body "spec" "containers" 0 "resources" "limits" "cpu"] "0m")
                     (-> original
                         (assoc-in [:body "spec" "containers" 0 "resources" "limits" "cpu"] "0m")
                         (assoc-in [:body "spec" "containers" 0 "resources" "requests" "cpu"] "0m"))
                     (assoc-in original [:body "spec" "containers" 0 "resources" "limits" "memory"] "999999999999999999999999Mi")]]
      (let [record (-> (:record input)
                       (assoc-in [:effect/proposal :kubernetes/request] payload)
                       (assoc-in [:effect/proposal :opsv/experiment :opsv/payload-hash] (hash/content-hash payload)))
            [result calls] (fixtures/apply! input record (fixtures/response input) dispatch/dispatch!)]
        (is (= :invalid-input (:anomaly/type result)))
        (is (empty? calls))))))

(defn- ^{:stratum 0} check-responses [input]
  (let [native (fixtures/native-response input)]
    (doseq [changed [(assoc-in native ["metadata" "uid"] nil)
                     (assoc-in native ["metadata" "resourceVersion"] nil)
                     (assoc-in native ["metadata" "name"] "foreign")
                     (assoc-in native ["metadata" "ownerReferences"] "malformed")
                     (assoc-in native ["spec" "schedulingGates"] [])
                     (assoc-in native ["spec" "nodeName"] "foreign")
                     (assoc-in native ["spec" "initContainers"] [])
                     (assoc-in native ["spec" "ephemeralContainers"] [])
                     (update-in native ["spec" "containers" 0 "args"] conj "--unexpected")
                     (update-in native ["spec" "containers"] conj (first (get-in native ["spec" "containers"])))
                     (assoc-in native ["spec" "containers" 0 "image"] "foreign")]]
      (let [pending (wire/completed (wire/response (json/generate-string changed) {:status http/created-status}))
            [result calls] (fixtures/apply! input (:record input) pending dispatch/dispatch!)]
        (is (= :unknown-outcome (:effect/outcome result)))
        (is (= 1 (count calls)))))))

(defn- ^{:stratum 0} check-protocol [input]
  (doseq [[text options] [["{} {}" {:status http/created-status}]
                          ["{\"kind\":1,\"kind\":2}" {:status http/created-status}]
                          [(json/generate-string (fixtures/native-response input)) {:status http/success-status}]
                          ["{}" {:status HttpURLConnection/HTTP_CONFLICT}]
                          ["{}" {:status http/not-found-status}]
                          ["{}" {:status http/created-status :encodings ["gzip"]}]]]
    (let [pending (wire/completed (wire/response text options))
          [result calls] (fixtures/apply! input (:record input) pending dispatch/dispatch!)]
      (is (= :unknown-outcome (:effect/outcome result)))
      (is (= 1 (count calls))))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} current-claim-exact-load-scope-and-explicit-runtime-are-required-test
  (fixtures/with-input check-authority)
  (fixtures/with-input check-runtime))

(deftest ^{:stratum 1} rehashing-an-arbitrary-request-does-not-make-it-a-load-provider-request-test
  (fixtures/with-input check-payload))

(deftest ^{:stratum 1} uncertain-or-foreign-provider-replies-never-retry-or-claim-creation-test
  (fixtures/with-input check-responses)
  (fixtures/with-input check-protocol))

(comment ::no-live-provider-or-load-traffic)
