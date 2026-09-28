;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.pr-fixtures
  "Trusted host configuration with real durable stores and a simulated provider."
  (:require [ai.miniforge.opsv-actuation.interface :as actuation]
            [ai.miniforge.evidence-bundle.interface :as evidence]
            [ai.miniforge.event-stream.interface :as events]
            [ai.miniforge.phase-opsv.governance-fixtures :as governance]
            [cheshire.core :as json]
            [clojure.java.io :as io])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]
           [java.time Instant]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} now (Instant/parse "2026-09-28T00:00:00Z"))

(def ^{:stratum 0} target
  {:pr/repo "example/opsv" :pr/base "main" :pr/branch "opsv/scaling"
   :pr/head-sha "0123456789012345678901234567890123456789"
   :pr/title "Tune catalog scaling" :opsv/policy-diff "HPA target: 70 -> 65"
   :opsv/rollback-instructions "Restore previous policy revision."
   :opsv/policy-hash (apply str (repeat 64 "a"))})

(def ^{:stratum 0} verification
  {:passed? true :confidence :high :caveats []
   :criteria-evaluation [{:criterion/id "latency" :criterion/passed? true
                          :criterion/observed 175 :criterion/expected 200
                          :criterion/reason-code :within-threshold}]})

(defn- ^{:stratum 0} root []
  (.getCanonicalPath (.toFile (Files/createTempDirectory "opsv-runtime-" (make-array FileAttribute 0)))))

(defn- ^{:stratum 0} audit-context [ctx]
  (let [store (evidence/create-opsv-assembly-store)
        assembly (evidence/allocate-opsv-assembly! store (:execution/id ctx))]
    (-> ctx
        (assoc :event-stream (events/create-event-stream {:sinks []})
               :opsv/evidence-assembly-store store)
        (assoc-in [:execution/input :opsv/evidence-bundle-id] (:evidence-bundle/id assembly)))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} command [calls options arguments]
  (swap! calls conj {:options options :arguments arguments})
  (let [post? (= "POST" (nth arguments 6))
        body (when post? (json/parse-string-strict (:in options) true))
        head {:ref "refs/heads/opsv/scaling" :object {:type "commit" :sha (:pr/head-sha target)}}
        pr {:number 17 :html_url "https://github.com/example/opsv/pull/17" :state "open"
            :title (:title body) :body (:body body) :draft (:draft body)
            :base {:ref (:base body) :repo {:full_name (:pr/repo target)}}
            :head {:ref (:head body) :sha (:pr/head-sha target) :repo {:full_name (:pr/repo target)}}}]
    {:exit 0 :out (json/generate-string (if post? pr head))}))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} setup []
  (let [directory (root)
        calls (atom [])
        runtime {:effects-directory (str (io/file directory "effects"))
                 :authority-directory (str (io/file directory "authority"))
                 :clock (constantly now) :fence (actuation/create-mutation-fence)
                 :target target
                 :provider {:directory directory :hostname "github.com"
                            :run-command (partial command calls)}}
        ctx (-> (governance/context)
                audit-context
                (assoc :execution/status :running)
                (assoc-in [:execution/opts :opsv/pr-execution] runtime)
                (update-in governance/output-path assoc
                           :opsv/policy-hash (:opsv/policy-hash target)
                           :opsv/verification-result verification))]
    {:ctx ctx :runtime runtime :calls calls}))

(comment
  (setup))
