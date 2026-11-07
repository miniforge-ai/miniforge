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
(ns ai.miniforge.agent-environment-kubernetes.settings
  "Provider settings: which cluster context, which pinned image, which
   RuntimeClass and nodes supply each isolation level, and the ceilings.
   The context is never taken from the caller's current kubectl context."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} defaults-resource "config/agent-environment-kubernetes/provider.edn")

(def ^{:stratum 0} NonBlank [:and :string [:fn (complement str/blank?)]])

(def ^{:stratum 0} PinnedImage
  "A fully qualified image reference pinned to a sha256 digest."
  [:re #"^[a-z0-9.-]+(:[0-9]+)?/[a-z0-9._/-]+@sha256:[0-9a-f]{64}$"])

(def ^{:stratum 0} Isolation [:enum :isolation/shared-kernel :isolation/dedicated-kernel])

(def ^{:stratum 0} Limits
  [:map {:closed true}
   [:limits/cpu-millicores pos-int?]
   [:limits/memory-mib pos-int?]
   [:limits/ephemeral-storage-mib pos-int?]])

(defn- ^{:stratum 0} dedicated-kernel-names-a-class?
  "A dedicated kernel is never mapped to the node default runtime."
  [settings]
  (let [runtimes (:kubernetes/runtimes settings)]
    (or (not (contains? runtimes :isolation/dedicated-kernel))
        (some? (get-in runtimes [:isolation/dedicated-kernel :runtime/class])))))

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} Settings
  "`:kubernetes/runtimes` maps each offered isolation level to its
   RuntimeClass (nil for the node default, never for a dedicated kernel)
   and node selector; an isolation level absent from it is not offered."
  [:and
   [:map {:closed true}
    [:kubernetes/kubectl NonBlank]
    [:kubernetes/kubeconfig {:optional true} NonBlank]
    [:kubernetes/context NonBlank]
    [:kubernetes/image PinnedImage]
    [:kubernetes/keep-alive [:vector {:min 1} NonBlank]]
    [:kubernetes/runtimes [:map-of Isolation [:map {:closed true}
                                              [:runtime/class [:maybe NonBlank]]
                                              [:runtime/node-selector [:map-of NonBlank NonBlank]]]]]
    [:kubernetes/max-limits Limits]
    [:kubernetes/command-timeout-ms pos-int?]
    [:kubernetes/delete-timeout-seconds pos-int?]]
   [:fn dedicated-kernel-names-a-class?]])

(def ^{:stratum 1} defaults
  (delay (:agent-environment-kubernetes/provider (edn/read-string (slurp (io/resource defaults-resource))))))
