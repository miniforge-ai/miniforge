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
(ns deps-resources-test
  "Every explicit path list in deps.edn that puts a brick's src on the
   classpath must put its resources there too. A brick whose src loads
   without its message catalog renders every message as its bare key
   name -- the trap bench shipped implementers a denial that said
   \"stale\" instead of naming the stale file, five reps running."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} path-vectors
  "Every vector of path strings anywhere in the deps map."
  [form]
  (cond
    (and (vector? form) (seq form) (every? string? form)) [form]
    (map? form) (mapcat path-vectors (vals form))
    (sequential? form) (mapcat path-vectors form)
    :else []))

(defn- ^{:stratum 0} brick-src? [p] (re-matches #"(components|bases)/[^/]+/src" p))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} resource-gaps
  "Resources dirs that exist but are missing from a path vector in
   `file` that lists the brick's src."
  [file]
  (let [form (edn/read-string {:default (fn [_ v] v)} (slurp file))]
    (for [v (path-vectors form)
          p v :when (brick-src? p)
          :let [brick (str/replace p #"/src$" "")
                res (str brick "/resources")]
          :when (and (.isDirectory (io/file res)) (not (some #{res} v)))]
      res)))

(deftest ^{:stratum 1} listed-brick-src-brings-its-resources
  (let [deps (edn/read-string (slurp "deps.edn"))
        gaps (for [v (path-vectors deps)
                   p v :when (brick-src? p)
                   :let [brick (str/replace p #"/src$" "")
                         res (str brick "/resources")]
                   :when (and (.isDirectory (io/file res)) (not (some #{res} v)))]
               res)]
    (is (empty? (vec gaps))
        (str "resources dirs that exist but are missing from a path list that has the brick's src: "
             (pr-str (vec gaps))))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} bb-task-path-lists-bring-resources-too
  ;; bb.edn tasks (`bb miniforge run` is how dogfood launches the
  ;; workflow) carry their own :extra-paths lists; the trap bench read
  ;; bare message keys from exactly this gap after deps.edn was fixed.
  (let [gaps (vec (resource-gaps "bb.edn"))]
    (is (empty? gaps)
        (str "bb.edn path lists missing a listed brick's resources: " (pr-str gaps)))))
