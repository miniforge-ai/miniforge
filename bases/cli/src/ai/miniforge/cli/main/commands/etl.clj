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

(ns ai.miniforge.cli.main.commands.etl
  "ETL commands: repo <url>.

   The `etl repo` command clones a repository and runs the ETL pipeline
   to produce a structured data representation for downstream analysis.

   Delegates to ai.miniforge.etl-pipe.interface when available."
  (:require
   [babashka.fs :as fs]
   [babashka.process :as process]
   [clojure.string :as str]
   [ai.miniforge.cli.main.commands.shared :as shared]
   [ai.miniforge.cli.main.display :as display]
   [ai.miniforge.cli.messages :as messages]
   [ai.miniforge.schema.interface :as schema]))

;------------------------------------------------------------------------------ Layer 0
;; Helpers

(defn validate-git-url
  "Return true when url begins with a recognised git transport prefix."
  [url]
  (boolean
   (or (str/starts-with? url "https://")
       (str/starts-with? url "git@")
       (str/starts-with? url "ssh://")
       (str/starts-with? url "http://"))))

(defn- git-clone-temp
  "Shallow-clone `url` into a temporary directory.
   Returns a schema/success or schema/failure result."
  [url]
  (try
    (let [tmp-dir (str (System/getProperty "java.io.tmpdir") "/miniforge-etl-"
                       (System/currentTimeMillis))
          result  (process/sh "git" "clone" "--depth" "1" url tmp-dir)]
      (if (zero? (:exit result))
        (schema/success :path tmp-dir)
        (schema/failure :path (str/trim (:err result)))))
    (catch Exception e
      (schema/failure :path (ex-message e)))))

;------------------------------------------------------------------------------ Layer 1
;; Fallback analysis

(defn- etl-repo-fallback
  "Clone repo and run repo-analyzer as ETL fallback."
  [url]
  (let [clone-result (git-clone-temp url)]
    (if (schema/failed? clone-result)
      (display/print-error (messages/t :etl/clone-failed {:error (:error clone-result)}))
      (let [repo-path (:path clone-result)]
        (try
          (let [analyze-fn (requiring-resolve 'ai.miniforge.cli.repo-analyzer/analyze-repo)
                analysis   (analyze-fn repo-path)]
            (display/print-success (messages/t :etl/analysis-complete))
            (println (messages/t :etl/technologies {:value (pr-str (:technologies analysis))}))
            (println (messages/t :etl/git-host {:value (get analysis :git-host "unknown")}))
            (println (messages/t :etl/packs {:value (pr-str (:packs analysis))}))
            (println)
            (println (display/style (messages/t :etl/install-note) :foreground :yellow)))
          (catch Exception e
            (display/print-error (messages/t :etl/analysis-failed {:error (ex-message e)})))
          (finally
            (try (fs/delete-tree repo-path)
                 (catch Exception _ nil))))))))

;------------------------------------------------------------------------------ Layer 2
;; Command implementation

(defn etl-repo-cmd
  "Run the ETL pipeline against a git repository URL.

   Clones the repository, extracts structured metadata (languages, packs,
   dependency graph, symbol index), and persists the result to the artifacts
   directory for use by downstream analysis commands.

   Usage: miniforge etl repo <url>"
  [opts]
  (let [{:keys [url]} opts]
    (if-not url
      (shared/usage-error! :etl/repo-usage "etl repo <url>")
      (if-not (validate-git-url url)
        (do (display/print-error (messages/t :etl/invalid-url {:url url}))
            (shared/exit! 1))
        (do
          (display/print-info (messages/t :etl/running {:url url}))
          (let [result (shared/try-resolve-fn 'ai.miniforge.etl-pipe.interface/etl-repo url opts)]
            (cond
              ;; Component available and successful
              (and result (schema/succeeded? result))
              (do
                (display/print-success (messages/t :etl/complete))
                (when-let [artifacts (:artifacts result)]
                  (println (messages/t :etl/artifacts-produced {:count (count artifacts)})))
                (when-let [path (:output-path result)]
                  (println (messages/t :etl/output-path {:path path}))))

              ;; Component returned error
              (and result (schema/failed? result))
              (display/print-error (messages/t :etl/failed {:error (get result :error "unknown error")}))

              ;; Component not available — fallback to repo analysis
              :else
              (etl-repo-fallback url))))))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (etl-repo-cmd {:url "https://github.com/miniforge-ai/miniforge"})
  :end)
