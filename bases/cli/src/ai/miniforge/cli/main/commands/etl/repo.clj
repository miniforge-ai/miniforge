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
(ns ai.miniforge.cli.main.commands.etl.repo
  "`etl repo <url>` support: git-URL validation, shallow-clone to a temp
   directory, and running the repo-analyzer against the checkout.
   Extracted from `ai.miniforge.cli.main.commands.etl` (rule 210: the
   combined namespace measured 4 real layers, max 3)."
  (:require
   [babashka.fs :as fs]
   [babashka.process :as process]
   [clojure.string :as str]
   [ai.miniforge.cli.main.display :as display]
   [ai.miniforge.cli.messages :as messages]
   [ai.miniforge.repo-analyzer.interface :as repo-analyzer]
   [ai.miniforge.schema.interface :as schema]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} validate-git-url
  "Return true when url begins with a recognised git transport prefix."
  [url]
  (boolean
   (or (str/starts-with? url "https://")
       (str/starts-with? url "git@")
       (str/starts-with? url "ssh://")
       (str/starts-with? url "http://"))))

(defn- ^{:stratum 0} git-clone-temp
  "Shallow-clone `url` into a temporary directory.
   Returns a schema/success or schema/failure result."
  [url]
  (try
    (let [tmp-dir (str (fs/path (System/getProperty "java.io.tmpdir")
                                (str "miniforge-etl-" (random-uuid))))
          result  (process/sh "git" "clone" "--depth" "1" url tmp-dir)]
      (if (zero? (:exit result))
        (schema/success :path tmp-dir)
        (do
          (try (fs/delete-tree tmp-dir) (catch Exception _ nil))
          (schema/failure :path (str/trim (:err result))))))
    (catch Exception e
      (schema/failure :path (ex-message e)))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} analyze-repo-url!
  "Clone repo and run repo-analyzer against the temporary checkout."
  [url]
  (let [clone-result (git-clone-temp url)]
    (if (schema/failed? clone-result)
      (do
        (display/print-error (messages/t :etl/clone-failed {:error (:error clone-result)}))
        1)
      (let [repo-path (:path clone-result)]
        (try
          (let [analysis (repo-analyzer/analyze-repo repo-path)]
            (display/print-success (messages/t :etl/analysis-complete))
            (println (messages/t :etl/technologies {:value (pr-str (:technologies analysis))}))
            (println (messages/t :etl/git-host {:value (get analysis :git-host "unknown")}))
            (println (messages/t :etl/packs {:value (pr-str (:packs analysis))}))
            (println)
            (println (display/style (messages/t :etl/install-note) :foreground :yellow))
            0)
          (catch Exception e
            (display/print-error (messages/t :etl/analysis-failed {:error (ex-message e)}))
            1)
          (finally
            (try (fs/delete-tree repo-path)
                 (catch Exception _ nil))))))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (validate-git-url "https://github.com/miniforge-ai/miniforge")
  (analyze-repo-url! "https://github.com/miniforge-ai/miniforge")
  :end)
