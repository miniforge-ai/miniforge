;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-baseline-restoration-provider-fixtures
  (:require [ai.miniforge.effect-transaction.provider-dispatch-fixtures :as dispatch]
            [ai.miniforge.phase-opsv.kubernetes-baseline-restoration-fixtures :as restoration]
            [ai.miniforge.phase-opsv.kubernetes-baseline-restoration-provider-wire-fixtures :as transport]
            [ai.miniforge.phase-opsv.kubernetes-effect-record-fixtures :as records]
            [ai.miniforge.phase-opsv.kubernetes-inventory-fixtures :as inventory]
            [ai.miniforge.phase-opsv.kubernetes-load-recovery-authority-fixtures :as authority]
            [ai.miniforge.phase-opsv.kubernetes-replica-request-fixtures :as replica]
            [ai.miniforge.phase-opsv.prometheus-test-support :as wire]
            [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.test :refer [is]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} rebind! records/rebind!)

(def ^{:stratum 0} execute-response transport/execute-response)

(defn ^{:stratum 0} mutations [calls] (filterv #(= :patch (:method %)) calls))

(defn ^{:stratum 0} response [fixture]
  (-> (get-in fixture [:restoration/routes inventory/deployment-path])
      (assoc-in ["spec" "replicas"] replica/original-count)
      (assoc-in ["metadata" "resourceVersion"] "acknowledged-original-restoration")))

(defn- ^{:stratum 0} proposed! [{:keys [directory] :as fixture}]
  (let [runtime {:effects-directory (str (fs/path directory "baseline-restoration-effects"))}
        configured (authority/with-stores (assoc fixture :runtime runtime :request (:restoration/request fixture)))
        [proposal reads] (restoration/prepare fixture (:restoration/routes fixture))
        issued (authority/issue! configured proposal)]
    (is (seq reads))
    (is (every? #(= :get (:method %)) reads))
    (when issued (records/propose! configured proposal issued))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} with-proposed [f]
  (restoration/with-input #(some->> % proposed! f)))

(defn ^{:stratum 1} execute
  ([fixture] (execute fixture (response fixture)))
  ([fixture deployment]
   (execute-response fixture (wire/completed (wire/response (json/generate-string deployment))) dispatch/dispatch!)))

(comment
  (with-proposed response))
