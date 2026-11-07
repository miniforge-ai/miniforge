;; Title: Miniforge.ai
;; Subtitle: An agentic SDLC / fleet-control platform
;; Author: Christopher Lester
;; Line: Founder, Miniforge.ai (project)
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;;
;; Licensed under the Apache License, Version 2.0 (the "License");
;; you may not use this file except in compliance with the License.
;; You may obtain a copy of the License at
;;
;;     http://www.apache.org/licenses/LICENSE-2.0
;;
;; Unless required by applicable law or agreed to in writing, software
;; distributed under the License is distributed on an "AS IS" BASIS,
;; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
;; See the License for the specific language governing permissions and
;; limitations under the License.
(ns ai.miniforge.agent-workspace.fixtures
  "A leased fake environment that answers git, a PR grant for an allowed
   repository set, a temporary effect store and a recording PR port."
  (:require
   [ai.miniforge.agent-environment.interface :as env]
   [ai.miniforge.agent-workspace.interface :as workspace]
   [ai.miniforge.execution-grant.interface :as grant]
   [ai.miniforge.tenancy.interface :as tenancy]
   [clojure.string :as str])
  (:import
   [java.nio.file Files]
   [java.nio.file.attribute FileAttribute]
   [java.time Instant]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ttl-seconds
  "Long enough that no test outlives its grants or environment."
  3600)

(def ^{:stratum 0} sample-hash (apply str (repeat 64 "a")))

(def ^{:stratum 0} base-sha (apply str (repeat 40 "b")))

(def ^{:stratum 0} allowed ["acme-example/frontend" "acme-example/api" "acme-example/worker"])

(def ^{:stratum 0} limits
  {:limits/cpu-millicores 500 :limits/memory-mib 512 :limits/ephemeral-storage-mib 1024})

(def ^{:stratum 0} repo-base-url "https://git.example.test/")

(defn ^{:stratum 0} repo [repo-id] {:repo/id repo-id :repo/base-ref "main"})

(defn- ^{:stratum 0} acting-for [tenant-name now]
  (tenancy/establish-acting (tenancy/operator-identity tenant-name now) now))

(defn- ^{:stratum 0} collect! [receipts receipt] (swap! receipts conj receipt))

(defn- ^{:stratum 0} store-artifact [_lease path _text] {:artifact/path path})

(defn- ^{:stratum 0} temp-dir []
  (str (Files/createTempDirectory "agent-workspace-test" (make-array FileAttribute 0))))

(defn ^{:stratum 0} git-commands [w]
  (filterv #(= "git" (first %)) (map :exec/argv (env/fake-commands (:provider w) (:namespace w)))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} record-pr! [opened proposal]
  (let [n (count (swap! opened conj proposal))]
    {:pr/number n :pr/url (str repo-base-url (:repo/id proposal) "/pull/" n)}))

(defn ^{:stratum 1} request [repo-ids]
  {:workspace/contract-version workspace/contract-version
   :workspace/root "/work/repos"
   :workspace/repos (mapv repo repo-ids)})

(defn ^{:stratum 1} answer-git
  "Every git command succeeds; `rev-parse` reports the base."
  [command]
  {:exec/exit 0
   :exec/stdout (if (some #{"rev-parse"} (:exec/argv command)) (str base-sha "\n") "")
   :exec/stderr ""})

(defn ^{:stratum 1} issue [acting effect-class scope now]
  (grant/issue {:principal (str (:acting/principal-id acting))
                :effect-class effect-class
                :scope scope
                :expires-at (.plusSeconds ^Instant now ttl-seconds)}
               now))

(defn ^{:stratum 1} pushed-branches [w]
  (into #{} (comp (filter #(some #{"push"} %)) (map last) (map #(first (str/split % #":")))) (git-commands w)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} world
  "A leased fake environment, a PR grant over `allowed`, and a workspace
   controller whose PR port is `open-pr!` (default: record and succeed)."
  ([] (world nil))
  ([open-pr!]
   (let [now (Instant/now)
         acting (acting-for "workspace-tenant" now)
         run-id (random-uuid)
         provider (env/fake-provider {:fake/exec-fn answer-git})
         ec (env/create-controller {:provider provider :clock #(Instant/now)
                                    :emit! (partial collect! (atom [])) :persist! store-artifact})
         scope {:environment/tenant-id (:acting/tenant-id acting) :environment/run-id run-id}
         env-request {:environment/contract-version env/contract-version
                      :environment/tenant-id (:acting/tenant-id acting)
                      :environment/principal-id (:acting/principal-id acting)
                      :environment/run-id run-id
                      :environment/task-id (random-uuid)
                      :environment/template-hash sample-hash
                      :environment/policy-hash sample-hash
                      :environment/isolation :isolation/shared-kernel
                      :environment/limits limits
                      :environment/expires-at (.plusSeconds now ttl-seconds)}
         environment (env/acquire! ec acting (issue acting :effect/provision scope now) env-request)
         pr-grant (issue acting :effect/pr-create
                         {:workspace/tenant-id (:acting/tenant-id acting) :workspace/run-id run-id
                          :workspace/repos (set allowed)}
                         now)
         opened (atom [])
         wc (workspace/create-controller {:environment ec :store-dir (temp-dir) :clock #(Instant/now)
                                          :repo-base-url repo-base-url
                                          :open-pr! (or open-pr! (partial record-pr! opened))})]
     {:acting acting :provider provider :lease (:environment/lease environment)
      :namespace (:environment/namespace environment) :grant pr-grant :wc wc :opened opened :now now})))
