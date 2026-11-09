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
(ns ai.miniforge.agent-environment-kubernetes.fixtures
  "Settings, targets, requests and a scripted kubectl for provider tests."
  (:require
   [ai.miniforge.agent-environment-kubernetes.interface :as kubernetes]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} kata "kata-test")

(def ^{:stratum 0} kata-nodes {"sandbox.example/kata" "true"})

(def ^{:stratum 0} uid "ns-uid-1")

(def ^{:stratum 0} namespace-name "mf-env-0123456789abcdef01234567")

(def ^{:stratum 0} request
  {:environment/tenant-id #uuid "00000000-0000-0000-0000-00000000000a"
   :environment/run-id #uuid "00000000-0000-0000-0000-00000000000b"
   :environment/task-id #uuid "00000000-0000-0000-0000-00000000000c"
   :environment/isolation :isolation/dedicated-kernel
   :environment/limits {:limits/cpu-millicores 500 :limits/memory-mib 512 :limits/ephemeral-storage-mib 1024}})

(defn ^{:stratum 0} result [exit out] {:kubectl/exit exit :kubectl/out out :kubectl/err ""})

(def ^{:stratum 0} not-found {:kubectl/exit 1 :kubectl/out "" :kubectl/err "Error from server (NotFound)"})

(defn ^{:stratum 0} refusal [reason] {:kubectl/exit 1 :kubectl/out "" :kubectl/err (str "Error from server (" reason ")")})

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} target
  {:target/namespace namespace-name :target/uid uid :target/generation 1})

(defn ^{:stratum 1} settings
  "Defaults plus a test context, with `:isolation/dedicated-kernel` mapped
   to the `kata` RuntimeClass on `kata-nodes`."
  []
  (-> (kubernetes/default-settings)
      (assoc :kubernetes/context "kind-test")
      (assoc-in [:kubernetes/runtimes :isolation/dedicated-kernel]
                {:runtime/class kata :runtime/node-selector kata-nodes})))

(defn ^{:stratum 1} scripted
  "A runner answering by the first kubectl verb words; unmatched calls
   succeed with no output. Records every call in `calls`."
  [calls answers]
  (fn [_settings args stdin]
    (swap! calls conj {:args args :stdin stdin})
    (or (some (fn [[prefix answer]] (when (= prefix (take (count prefix) args)) answer)) answers)
        (result 0 ""))))

(defn ^{:stratum 1} refusing-pods
  "Wrap `runner` so creating a Pod is refused, as admission would."
  [runner]
  (fn [settings args stdin]
    (if (and (= "create" (first args)) (str/includes? (str stdin) "\"Pod\""))
      not-found
      (runner settings args stdin))))

;------------------------------------------------------------------------------ Layer 2

(def ^{:stratum 2} namespace-json
  (str "{\"metadata\":{\"uid\":\"" uid "\",\"labels\":{\"miniforge.ai/lease-generation\":\""
       (:target/generation target) "\"}}}"))
