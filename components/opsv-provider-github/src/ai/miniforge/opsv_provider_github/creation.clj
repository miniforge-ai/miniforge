;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.opsv-provider-github.creation
  "Create once; only exact provider confirmation proves success."
  (:require [ai.miniforge.opsv-provider-github.messages :as msg]
            [ai.miniforge.opsv-provider-github.transport :as transport]
            [ai.miniforge.opsv-provider-github.wire :as wire]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} current-head? [payload ref]
  (and (= (str "refs/heads/" (:pr/branch payload)) (:ref ref))
       (= "commit" (get-in ref [:object :type]))
       (= (:pr/head-sha payload) (get-in ref [:object :sha]))))

(defn- ^{:stratum 0} readable-head? [ref]
  (let [sha (get-in ref [:object :sha])]
    (and (string? (:ref ref)) (= "commit" (get-in ref [:object :type]))
         (string? sha) (re-matches #"(?:[0-9a-f]{40}|[0-9a-f]{64})" sha))))

(defn- ^{:stratum 0} creation-result [payload response]
  (if (and (= "open" (:state response)) (wire/matching-pr? payload response))
    {:effect/outcome :succeeded :effect/observed (wire/observation response)}
    {:effect/outcome :unknown-outcome :effect/failure (msg/t :create/unconfirmed)}))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} create-once! [runtime payload]
  (let [path (wire/pulls-path payload)
        body (wire/create-body payload)
        response (transport/request! runtime "POST" path body false)]
    (creation-result payload response)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} create! [runtime payload dispatch]
  (let [ref (transport/request! runtime "GET" (wire/head-path payload) nil false)]
    (cond
      (not (readable-head? ref))
      {:effect/outcome :failed :effect/failure (msg/t :create/preflight-unavailable)}
      (current-head? payload ref) (dispatch #(create-once! runtime payload))
      :else {:effect/outcome :failed :effect/failure (msg/t :create/preflight-failed)})))

(comment
  (current-head? {} {}))
