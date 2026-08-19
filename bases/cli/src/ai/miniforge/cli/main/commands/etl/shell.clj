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
(ns ai.miniforge.cli.main.commands.etl.shell
  "JVM shell-out used by `etl run|list|validate|registry`: locates the
   miniforge checkout root and shells out to `ai.miniforge.etl.main` on
   the JVM (source connectors use hato/POI which aren't BB-safe).
   Extracted from `ai.miniforge.cli.main.commands.etl` (rule 210: the
   combined namespace measured 4 real layers, max 3)."
  (:require
   [babashka.fs :as fs]
   [babashka.process :as process]
   [ai.miniforge.cli.main.display :as display]
   [ai.miniforge.cli.messages :as messages]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} find-miniforge-root
  "Walk up from `start` until a directory containing both `workspace.edn`
   and `bases/etl/deps.edn` is found. Returns the absolute path or nil
   if no such ancestor exists (i.e., not inside a miniforge checkout
   that ships the etl base)."
  ([] (find-miniforge-root (fs/file (System/getProperty "user.dir"))))
  ([start]
   (loop [dir (fs/absolutize start)]
     (cond
       (nil? dir)
       nil

       (and (fs/exists? (fs/file dir "workspace.edn"))
            (fs/exists? (fs/file dir "bases/etl/deps.edn")))
       (str dir)

       :else
       (recur (fs/parent dir))))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} shell-etl!
  "Shell out to the JVM etl entry point from the miniforge root. `args`
   are the post-`-m` args: the subcommand name and its flags. Streams
   stdout/stderr to the user's terminal. Returns the subprocess exit
   code."
  [args]
  (if-let [root (find-miniforge-root)]
    (let [argv (into ["clojure" "-M:dev" "-m" "ai.miniforge.etl.main"] args)
          {:keys [exit]} (deref (process/process argv {:dir  root
                                                       :out  :inherit
                                                       :err  :inherit}))]
      exit)
    (do (display/print-error (messages/t :etl/run-requires-checkout))
        1)))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (shell-etl! ["list" "."])
  :end)
