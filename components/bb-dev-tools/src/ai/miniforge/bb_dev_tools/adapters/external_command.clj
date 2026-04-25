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

(ns ai.miniforge.bb-dev-tools.adapters.external-command
  "Adapter for tools declared as explicit shell command stages."
  (:require
   [ai.miniforge.bb-dev-tools.adapter-registry :as registry]
   [babashka.fs :as fs]))

(defn- absolute-cwd
  [repo-root cwd]
  (str (fs/path repo-root (or cwd "."))))

(defn- normalize-commands
  [stage]
  (let [commands (get stage :commands [])
        command (get stage :command)]
    (if command
      [command]
      commands)))

(defn- stage-config
  [tool stage-key]
  (get tool (keyword "tool" (name stage-key))))

(defmethod registry/stage-plans :external/command
  [repo-root tool stage-key]
  (let [stage (stage-config tool stage-key)
        cwd (absolute-cwd repo-root (get stage :cwd))]
    (mapv (fn [command]
            {:tool/id (get tool :tool/id)
             :stage stage-key
             :cwd cwd
             :command command})
          (normalize-commands stage))))
