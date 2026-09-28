;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.opsv-provider-github.fixtures
  (:require [ai.miniforge.content-hash.interface :as hash]
            [cheshire.core :as json])
  (:import [java.time Instant]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} payload
  {:pr/repo "example/opsv"
   :pr/base "main"
   :pr/branch "opsv/scaling"
   :pr/head-sha "0123456789012345678901234567890123456789"
   :pr/title "Tune scaling"
   :pr/body "Evidence {owner}\nRollback @literal-file\n"
   :pr/draft? true})

(defn ^{:stratum 0} response [data]
  {:exit 0 :out (json/generate-string data) :err ""})

(defn ^{:stratum 0} run-command [calls responses options args]
  (swap! calls conj {:options options :arguments args})
  (let [result (first @responses)]
    (swap! responses subvec 1)
    result))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} transaction [state]
  (let [id (random-uuid)
        prepared (assoc payload :pr/payload-hash (hash/content-hash payload))
        now (Instant/parse "2026-09-28T00:00:00Z")]
    {:effect/id id
     :effect/class :effect/pr-create
     :effect/grant-id (random-uuid)
     :effect/envelope-id (random-uuid)
     :effect/proposal prepared
     :effect/state state
     :effect/authority :granted
     :effect/at now
     :effect/updated-at now}))

(defn ^{:stratum 1} head-response []
  (response {:ref "refs/heads/opsv/scaling"
             :object {:type "commit" :sha (:pr/head-sha payload)}}))

(defn ^{:stratum 1} pull-request []
  {:number 17 :html_url "https://github.com/example/opsv/pull/17" :state "open"
   :title (:pr/title payload) :body (:pr/body payload) :draft (:pr/draft? payload)
   :base {:ref (:pr/base payload) :repo {:full_name (:pr/repo payload)}}
   :head {:ref (:pr/branch payload) :sha (:pr/head-sha payload)
          :repo {:full_name (:pr/repo payload)}}})

(defn ^{:stratum 1} runtime [calls replies]
  {:directory "." :hostname "github.com"
   :run-command (partial run-command calls (atom replies))})

(comment
  (transaction :committing))
