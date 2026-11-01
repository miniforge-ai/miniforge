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
(ns ai.miniforge.gate.messages-test
  "Every gate message key used in the gate sources has a catalog entry.

   A missing key does not throw: the translator renders the bare key name
   (\"skipped\", \"description\") and warns once on stderr. A gate result
   carrying a key name instead of a sentence reads as a word, not as a
   defect, so this test makes the gap fail loudly instead."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} catalog
  [resource section]
  (-> (io/resource resource) slurp edn/read-string (get section)))

(defn- ^{:stratum 0} gate-sources
  "The gate component's .clj sources, found next to a known namespace on
   the classpath."
  []
  (let [root (-> (io/resource "ai/miniforge/gate/registry.clj") io/file .getParentFile)]
    (->> (file-seq root)
         (filter #(.endsWith (.getName ^java.io.File %) ".clj")))))

(def ^{:stratum 0} ^:private key-use
  ;; (messages/t :k ...), (msg/t :k ...), (messages/system-t :k ...)
  #"\((?:messages|msg)/(t|system-t)\s+:([a-z0-9-]+/[a-z0-9-]+)")

(defn- ^{:stratum 0} used-keys
  "[{:file name :fn \"t\"|\"system-t\" :key kw}] for every literal key use."
  []
  (for [f (gate-sources)
        [_ fn-name k] (re-seq key-use (slurp f))]
    {:file (.getName ^java.io.File f) :fn fn-name :key (keyword k)}))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} every-used-key-has-a-catalog-entry
  (let [user-facing (catalog "config/gate/messages/en-US.edn" :gate/messages)
        system      (catalog "config/gate/messages/system.edn" :gate/system)
        uses        (used-keys)]
    (testing "the scan sees the sources (an empty scan would pass vacuously)"
      (is (< 30 (count uses))))
    (testing "each key resolves in the catalog its translator reads"
      (doseq [{:keys [file fn key]} uses
              :let [cat (if (= "t" fn) user-facing system)]]
        (is (string? (get cat key))
            (str file " uses " key " via " fn " but the catalog has no entry"))))))
