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
(ns ai.miniforge.knowledge.loader-test
  (:require
   [clojure.java.io :as io]
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.knowledge.loader :as sut]
   [ai.miniforge.knowledge.store :as store])
  (:import
   [java.nio.file Files]
   [java.nio.file.attribute FileAttribute]))

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} initialize-knowledge-store-default-rules-dir-test
  (testing "defaults rules loading to .cursor/rules"
    (let [captured-rules-dir (atom nil)]
      (with-redefs [sut/load-rules (fn [_store rules-dir]
                                     (reset! captured-rules-dir rules-dir)
                                     {:loaded 0 :failed 0})
                    sut/load-docs (fn [_store _project-root]
                                    {:loaded 0 :failed 0 :files []})]
        (is (= 0 (:total (sut/initialize-knowledge-store! ::store {}))))
        (is (= ".cursor/rules" @captured-rules-dir))))))

(deftest ^{:stratum 0} initialize-knowledge-store-explicit-rules-dir-test
  (testing "respects an explicit rules-dir override"
    (let [captured-rules-dir (atom nil)]
      (with-redefs [sut/load-rules (fn [_store rules-dir]
                                     (reset! captured-rules-dir rules-dir)
                                     {:loaded 2 :failed 0})
                    sut/load-docs (fn [_store _project-root]
                                    {:loaded 1 :failed 0 :files []})]
        (let [result (sut/initialize-knowledge-store! ::store {:rules-dir ".standards"})]
          (is (= ".standards" @captured-rules-dir))
          (is (= 3 (:total result))))))))

(defn- temp-project-root
  "A fresh temp directory with `files` ({relative-path content}) written
   under it."
  [files]
  (let [dir (.toFile (Files/createTempDirectory "loader-test"
                                                (make-array FileAttribute 0)))]
    (doseq [[path content] files]
      (let [file (io/file dir path)]
        (io/make-parents file)
        (spit file content)))
    dir))

(defn- delete-tree [dir]
  (doseq [file (reverse (file-seq dir))]
    (io/delete-file file true)))

(defn- load-docs-from [dir]
  (let [knowledge-store (store/create-store)]
    {:result (sut/load-project-docs knowledge-store (str dir))
     :store knowledge-store}))

(deftest ^{:stratum 0} load-project-docs-names-loaded-files-test
  (testing "given a lowercase agents.md → it loads and :files names it"
    (let [dir (temp-project-root {"agents.md" "# Agents\n\nProject guidance."})]
      (try
        (let [{:keys [result]} (load-docs-from dir)]
          (is (= 1 (:loaded result)))
          (is (= 0 (:failed result)))
          (is (= ["agents.md"] (:files result))))
        (finally
          (delete-tree dir))))))

(deftest ^{:stratum 0} load-project-docs-uppercase-names-test
  (testing "given uppercase AGENTS.md and CLAUDE.md → each loads once, under
            its real name, on case-sensitive filesystems too"
    (let [dir (temp-project-root {"AGENTS.md" "# Agents"
                                  "CLAUDE.md" "# Claude"})]
      (try
        (let [{:keys [result store]} (load-docs-from dir)]
          (is (= 2 (:loaded result)))
          (is (= ["AGENTS.md" "CLAUDE.md"] (:files result)))
          (is (= "AGENTS.md"
                 (get-in (store/get-zettel-by-uid store "AGENTS")
                         [:zettel/source :origin]))))
        (finally
          (delete-tree dir))))))

(deftest ^{:stratum 0} load-project-docs-dot-claude-test
  (testing "given CLAUDE.md and .claude/CLAUDE.md → both load, each with its
            own origin and uid"
    (let [dir (temp-project-root {"CLAUDE.md" "# Root"
                                  ".claude/CLAUDE.md" "# Nested"})]
      (try
        (let [{:keys [result store]} (load-docs-from dir)]
          (is (= ["CLAUDE.md" ".claude/CLAUDE.md"] (:files result)))
          (is (= "# Nested"
                 (:zettel/content (store/get-zettel-by-uid store ".claude/CLAUDE"))))
          (is (= "# Root"
                 (:zettel/content (store/get-zettel-by-uid store "CLAUDE")))))
        (finally
          (delete-tree dir))))))

(deftest ^{:stratum 0} load-project-docs-symlink-loads-once-test
  (testing "given CLAUDE.md symlinked to AGENTS.md → the file loads once, as AGENTS.md"
    (let [dir (temp-project-root {"AGENTS.md" "# Agents"})]
      (try
        (Files/createSymbolicLink (.toPath (io/file dir "CLAUDE.md"))
                                  (.toPath (io/file "AGENTS.md"))
                                  (make-array FileAttribute 0))
        (let [{:keys [result]} (load-docs-from dir)]
          (is (= 1 (:loaded result)))
          (is (= ["AGENTS.md"] (:files result))))
        (finally
          (delete-tree dir))))))

(deftest ^{:stratum 0} load-project-docs-hard-link-loads-once-test
  (testing "given CLAUDE.md hard-linked to AGENTS.md → the file loads once, as AGENTS.md"
    (let [dir (temp-project-root {"AGENTS.md" "# Agents"})]
      (try
        (Files/createLink (.toPath (io/file dir "CLAUDE.md"))
                          (.toPath (io/file dir "AGENTS.md")))
        (let [{:keys [result]} (load-docs-from dir)]
          (is (= 1 (:loaded result)))
          (is (= ["AGENTS.md"] (:files result))))
        (finally
          (delete-tree dir))))))
