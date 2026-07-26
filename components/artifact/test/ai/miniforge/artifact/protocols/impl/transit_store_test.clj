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
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.artifact.interface.protocols.artifact-store :as p]
            [ai.miniforge.artifact.protocols.impl.transit-store :as impl]
            [ai.miniforge.artifact.protocols.records.transit-store :as store]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]])
  (:import [java.nio.file Files]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private parent-id "parent-id")

(def ^{:stratum 0} ^:private child-id "child-id")

(def ^{:stratum 0} ^:private missing-parent-id "missing-parent")

(def ^{:stratum 0} ^:private missing-child-id "missing-child")

(defn- ^{:stratum 0} artifact
  [id type]
  {:artifact/id id
   :artifact/type type
   :artifact/version "1.0.0"
   :artifact/parents []
   :artifact/children []})

(defn- ^{:stratum 0} delete-dir! [file]
  (when (.exists (io/file file))
    (doseq [f (reverse (file-seq (io/file file)))]
      (io/delete-file f true))))

(defn- ^{:stratum 0} temp-store []
  (let [dir (.toFile (Files/createTempDirectory
                      "transit-store-test"
                      (make-array java.nio.file.attribute.FileAttribute 0)))]
    [(store/create-transit-store {:dir (.getPath dir)}) dir]))

(defn- ^{:stratum 0} save! [store artifact]
  (p/save store artifact)
  artifact)

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} parent-artifact []
  (artifact parent-id :plan))

(defn- ^{:stratum 1} child-artifact []
  (artifact child-id :code))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} test-find-link-target
  (let [[artifact-store dir] (temp-store)]
    (try
      (testing "given existing target -> returns artifact"
        (let [parent (parent-artifact)]
          (is (= parent (impl/find-link-target
                         artifact-store (:artifact/id (save! artifact-store parent)) :parent)))))
      (testing "given missing target -> returns canonical anomaly"
        (let [result (impl/find-link-target artifact-store missing-parent-id :parent)]
          (is (anomaly/anomaly? result))
          (is (= :not-found (:anomaly/type result)))
          (is (= {:artifact/id missing-parent-id
                  :artifact/role :parent}
                 (:anomaly/data result)))))
      (testing "given unknown role -> returns anomaly data instead of throwing"
        (is (= :unknown (get-in (impl/find-link-target artifact-store "missing" :unknown)
                                [:anomaly/data :artifact/role]))))
      (finally
        (delete-dir! dir)))))

(deftest ^{:stratum 2} test-link-artifacts-success
  (let [[artifact-store dir] (temp-store)]
    (try
      (save! artifact-store (parent-artifact))
      (save! artifact-store (child-artifact))
      (testing "given existing parent and child -> links artifacts"
        (is (true? (impl/link-artifacts artifact-store parent-id child-id)))
        (is (contains? (set (:artifact/children (p/load-artifact artifact-store parent-id)))
                       child-id))
        (is (contains? (set (:artifact/parents (p/load-artifact artifact-store child-id)))
                       parent-id)))
      (finally
        (delete-dir! dir)))))

(defn- ^{:stratum 2} missing-target-cases []
  [["missing parent" [(child-artifact)] missing-parent-id child-id]
   ["missing child" [(parent-artifact)] parent-id missing-child-id]
   ["missing both" [] missing-parent-id missing-child-id]])

;------------------------------------------------------------------------------ Layer 3

(deftest ^{:stratum 3} test-link-artifacts-missing-targets
  (doseq [[label artifacts parent-id child-id]
          (missing-target-cases)]
    (let [[artifact-store dir] (temp-store)]
      (try
        (doseq [artifact artifacts]
          (save! artifact-store artifact))
        (testing (str "given " label " -> returns false")
          (is (false? (impl/link-artifacts artifact-store parent-id child-id))))
        (finally
          (delete-dir! dir))))))
