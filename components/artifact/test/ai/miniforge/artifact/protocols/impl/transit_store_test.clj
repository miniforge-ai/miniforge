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

(ns ai.miniforge.artifact.protocols.impl.transit-store-test
  (:require [ai.miniforge.artifact.interface.protocols.artifact-store :as p]
            [ai.miniforge.artifact.protocols.impl.transit-store :as impl]
            [ai.miniforge.artifact.protocols.records.transit-store :as store]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]])
  (:import [java.nio.file Files]))

(def parent
  {:artifact/id "parent-id"
   :artifact/type :plan
   :artifact/version "1.0.0"
   :artifact/parents []
   :artifact/children []})

(def child
  {:artifact/id "child-id"
   :artifact/type :code
   :artifact/version "1.0.0"
   :artifact/parents []
   :artifact/children []})

(defn- delete-dir! [file]
  (when (.exists (io/file file))
    (doseq [f (reverse (file-seq (io/file file)))]
      (io/delete-file f true))))

(defn- temp-store []
  (let [dir (.toFile (Files/createTempDirectory
                      "transit-store-test"
                      (make-array java.nio.file.attribute.FileAttribute 0)))]
    [(store/create-transit-store {:dir (.getPath dir)}) dir]))

(defn- save! [store artifact]
  (p/save store artifact)
  artifact)

(deftest find-link-target-test
  (let [[artifact-store dir] (temp-store)]
    (try
      (is (= parent (impl/find-link-target
                     artifact-store (:artifact/id (save! artifact-store parent)) :parent)))
      (let [result (impl/find-link-target artifact-store "missing-parent" :parent)]
        (is (= :anomalies/not-found (:anomaly/category result)))
        (is (= "missing-parent" (:artifact-id result)))
        (is (= :parent (:role result))))
      (is (= :unknown (:role (impl/find-link-target artifact-store "missing" :unknown))))
      (finally
        (delete-dir! dir)))))

(deftest link-artifacts-success-test
  (let [[artifact-store dir] (temp-store)]
    (try
      (save! artifact-store parent)
      (save! artifact-store child)
      (is (true? (impl/link-artifacts artifact-store "parent-id" "child-id")))
      (is (contains? (set (:artifact/children (p/load-artifact artifact-store "parent-id")))
                     "child-id"))
      (is (contains? (set (:artifact/parents (p/load-artifact artifact-store "child-id")))
                     "parent-id"))
      (finally
        (delete-dir! dir)))))

(deftest link-artifacts-missing-targets-test
  (doseq [[label artifacts parent-id child-id]
          [["missing parent" [child] "missing-parent" "child-id"]
           ["missing child" [parent] "parent-id" "missing-child"]
           ["missing both" [] "missing-parent" "missing-child"]]]
    (let [[artifact-store dir] (temp-store)]
      (try
        (doseq [artifact artifacts]
          (save! artifact-store artifact))
        (is (false? (impl/link-artifacts artifact-store parent-id child-id)) label)
        (finally
          (delete-dir! dir))))))
