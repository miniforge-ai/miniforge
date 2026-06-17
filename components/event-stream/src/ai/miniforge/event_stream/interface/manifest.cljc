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

(ns ai.miniforge.event-stream.interface.manifest
  "Public manifest/archive boundary for JVM workflow lifecycle hooks."
  #?(:bb
     (:require
      [clojure.string :as str])
     :clj
     (:require
      [ai.miniforge.event-stream.archive :as archive]
      [ai.miniforge.event-stream.manifest :as manifest])))

#?(:bb
   (defn- jvm-only!
     [op]
     (throw (ex-info (str/join "" ["Event-stream manifest operation is JVM-only: " op])
                     {:operation op
                      :runtime :bb}))))

#?(:bb
   (defn init-active
     "Build the initial active manifest for workflow-id."
     [workflow-id]
     (jvm-only! :init-active))
   :clj
   (def init-active
     "Build the initial active manifest for workflow-id."
     manifest/init-active))

#?(:bb
   (defn load-manifest
     "Load the manifest from workflow directory dir, or nil when absent."
     [dir]
     (jvm-only! :load-manifest))
   :clj
   (def load-manifest
     "Load the manifest from workflow directory dir, or nil when absent."
     manifest/load-manifest))

#?(:bb
   (defn mark-terminal
     "Return manifest updated to terminal workflow status."
     [manifest status]
     (jvm-only! :mark-terminal))
   :clj
   (def mark-terminal
     "Return manifest updated to terminal workflow status."
     manifest/mark-terminal))

#?(:bb
   (defn save-manifest!
     "Atomically persist manifest under workflow directory dir."
     [dir manifest]
     (jvm-only! :save-manifest!))
   :clj
   (def save-manifest!
     "Atomically persist manifest under workflow directory dir."
     manifest/save-manifest!))

#?(:bb
   (defn start-heartbeat!
     "Start the manifest heartbeat for workflow directory dir."
     [dir]
     (jvm-only! :start-heartbeat!))
   :clj
   (def start-heartbeat!
     "Start the manifest heartbeat for workflow directory dir."
     manifest/start-heartbeat!))

#?(:bb
   (defn stop-heartbeat!
     "Stop a manifest heartbeat handle."
     [heartbeat]
     (jvm-only! :stop-heartbeat!))
   :clj
   (def stop-heartbeat!
     "Stop a manifest heartbeat handle."
     manifest/stop-heartbeat!))

#?(:bb
   (defn archive-workflow!
     "Archive the live workflow directory for workflow-id."
     [workflow-id]
     (jvm-only! :archive-workflow!))
   :clj
   (def archive-workflow!
     "Archive the live workflow directory for workflow-id."
     archive/archive-workflow!))
