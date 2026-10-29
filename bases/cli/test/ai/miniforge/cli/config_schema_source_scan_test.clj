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
(ns ai.miniforge.cli.config-schema-source-scan-test
  "Every user-config key the code reads with a literal get-in / assoc-in /
   update-in path is in the config schema, so `config set` and
   `config validate` accept it. Scans components/*/src and bases/*/src;
   run from the workspace root."
  (:require
   [babashka.fs :as fs]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.cli.config-schema :as sut]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private access-pattern
  "A get-in / assoc-in / update-in with a literal key vector: group 1 is
   the map expression, group 2 the vector's text."
  #"\((?:get-in|assoc-in|update-in)\s+(\([^()]*\)|[^\s\[\]()]+)\s+\[([^\[\]]*)\]")

(def ^{:stratum 0} ^:private user-config-expression
  "A map expression that may be the loaded user config: a call to one of
   its loaders, or a local named config, cfg, user-config or user-cfg."
  #"^(config|cfg|user-config|user-cfg)$|load-config|load-merged-config|load-user-config|load-fleet-config")

(def ^{:stratum 0} ^:private not-user-config
  "Source files whose `config` / `cfg` is another map than the user
   config, with what it is. A new file whose get-in on such a local is
   not the user config belongs here; one that is belongs in the schema."
  {"agent/budget.clj"                    "the agent's budget config"
   "config/governance.clj"               "a governance resource; [:governance k] is read from user-cfg"
   "dag_executor/protocols/impl/worktree.clj" "the worktree executor's registry config"
   "phase_deployment/provision.clj"      "the phase config"
   "phase_deployment/validate.clj"       "the phase config"
   "phase_software_factory/release.clj"  "the release phase context"
   "pipeline_runner/run.clj"             "a pipeline stage"
   "policy_calibration/runner.clj"       "the calibration run config"
   "reliability/dependency_health.clj"   "the dependency-health config"
   "training_capture/quality.clj"        "the training-capture quality config"
   "workflow/state_publication.clj"      "a workflow state definition"
   "workflow/state_structure_scan.clj"   "a workflow state definition"})

(defn- ^{:stratum 0} source-files
  "Every .clj/.cljc file under components/*/src and bases/*/src."
  []
  (->> ["components" "bases"]
       (mapcat #(file-seq (io/file (System/getProperty "user.dir") %)))
       (filter #(re-find #"/src/.+\.cljc?$" (str %)))))

(defn- ^{:stratum 0} keyword-prefix
  "The leading keywords of a key vector's text: [:agents :default-models]
   for `:agents :default-models tier`."
  [vector-text]
  (->> (str/split (str/trim vector-text) #"\s+")
       (take-while #(str/starts-with? % ":"))
       (mapv #(keyword (subs % 1)))))

(defn- ^{:stratum 0} known-path?
  "True when `path` is a schema key, a section of it, or below a :map key."
  [path]
  (or (some? (sut/key-type sut/schema path))
      (sut/section? sut/schema path)))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} user-config-accesses
  "{path #{file}} for every literal get-in / assoc-in / update-in on the
   user config in src: on an expression that may be it (see
   `user-config-expression`, minus `not-user-config` files), or with a
   path that starts at one of its top-level sections."
  []
  (let [roots (set (map first (keys sut/schema)))]
    (reduce (fn [acc [path file]] (update acc path (fnil conj #{}) file))
            {}
            (for [f (remove (fn [file] (some #(str/ends-with? (str file) %) (keys not-user-config)))
                           (source-files))
                  [_ expr text] (re-seq access-pattern (slurp f))
                  :let [path (keyword-prefix text)]
                  :when (and (seq path)
                             (or (re-find user-config-expression expr)
                                 (contains? roots (first path))))]
              [path (str (fs/relativize (System/getProperty "user.dir") (str f)))]))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} schema-covers-every-user-config-access-in-src-test
  (let [accesses (user-config-accesses)]
    (testing "the scan reads the source tree (run from the workspace root)"
      (is (<= 8 (count accesses)) (str "found " (count accesses) " accesses")))
    (testing "every literal user-config path in src is in the schema"
      (doseq [[path files] (sort-by (comp str key) accesses)]
        (is (known-path? path) (str path " read in " (str/join ", " (sort files))))))))
