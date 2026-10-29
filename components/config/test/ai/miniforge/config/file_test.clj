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
(ns ai.miniforge.config.file-test
  (:require
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.config.file :as sut]
   [ai.miniforge.config.interface.file :as config-file]
   [ai.miniforge.file-durability.interface :as durability]
   [babashka.fs :as fs]
   [clojure.edn :as edn]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} temp-config-path
  "A config.edn path inside a fresh temp directory, holding `content` when given."
  ([] (str (fs/path (fs/create-temp-dir) "config.edn")))
  ([content] (doto (temp-config-path) (spit content))))

(def ^{:stratum 0} ^:private concurrent-writer-count
  "Writers racing on one file: enough for read-modify-write races to lose a
   repo if the lock did not serialize them."
  16)

(deftest ^{:stratum 0} parse-config-test
  (is (nil? (sut/parse-config "p" "")))
  (is (nil? (sut/parse-config "p" ";; only a comment\n")))
  (is (= {:a 1} (sut/parse-config "p" "{:a 1} ;; trailing comment")))
  (is (anomaly/anomaly? (sut/parse-config "p" "#=(+ 1 2)"))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} refuses-to-overwrite-unparseable-file-test
  (doseq [[label content] [["truncated map" "{:llm {:backend :claude} :policy-packs"]
                           ["not a map" "[:fleet]"]
                           ["two top-level forms" "{:llm {}} {:fleet {}}"]]]
    (testing label
      (let [path   (temp-config-path content)
            result (config-file/update-config! path #(assoc-in % [:fleet :repos] ["a/b"]))]
        (is (anomaly/anomaly? result))
        (is (= :invalid-input (:anomaly/type result)))
        (is (= path (get-in result [:anomaly/data :config/path])))
        (is (str/includes? (:anomaly/message result) path))
        (is (= content (slurp path)) "file left untouched")))))

(deftest ^{:stratum 1} update-config-writes-parseable-map-test
  (testing "absent and blank files reach f as nil"
    (doseq [path [(temp-config-path) (temp-config-path "  \n")]]
      (let [result (config-file/update-config! path #(assoc % :seen (some? %)))]
        (is (= {:before nil :after {:seen false}} result))
        (is (= {:seen false} (edn/read-string (slurp path)))))))

  (testing "keeps every other key and leaves no temp file behind"
    (let [path (temp-config-path (pr-str {:llm {:backend :claude} :policy-packs [:core]}))]
      (config-file/update-config! path #(assoc-in % [:fleet :repos] ["a/b"]))
      (is (= {:llm {:backend :claude} :policy-packs [:core] :fleet {:repos ["a/b"]}}
             (edn/read-string (slurp path))))
      (is (= #{"config.edn" "config.edn.lock"}
             (set (map fs/file-name (fs/list-dir (fs/parent path))))))))

  (testing "a caller's *print-length* does not truncate the file"
    (let [path (temp-config-path)]
      (binding [*print-length* 2]
        (config-file/update-config! path (constantly {:fleet {:repos ["a/b" "c/d" "e/f"]}})))
      (is (= ["a/b" "c/d" "e/f"] (get-in (edn/read-string (slurp path)) [:fleet :repos]))))))

(deftest ^{:stratum 1} write-keeps-mode-and-symlink-test
  (let [real (temp-config-path "{:llm {}}")
        link (str (fs/path (fs/create-temp-dir) "config.edn"))]
    (fs/set-posix-file-permissions real "rw-------")
    (fs/create-sym-link link real)
    (config-file/update-config! link #(assoc % :a 1))
    (is (fs/sym-link? link))
    (is (= {:llm {} :a 1} (edn/read-string (slurp real))))
    (is (= "rw-------" (fs/posix->str (fs/posix-file-permissions real))))))

(deftest ^{:stratum 1} concurrent-writers-share-the-lock-test
  (let [path    (temp-config-path "{}")
        repos   (mapv #(str "owner/repo-" %) (range concurrent-writer-count))
        results (->> repos
                     (mapv (fn [repo]
                             (future (config-file/update-config!
                                      path #(update-in % [:fleet :repos] (fnil conj []) repo)))))
                     (mapv deref))]
    (is (not-any? anomaly/anomaly? results))
    (is (= (set repos) (set (get-in (edn/read-string (slurp path)) [:fleet :repos]))))))

(deftest ^{:stratum 1} failed-write-names-the-file-and-keeps-it-test
  (let [path (temp-config-path "{:llm {}}")]
    (with-redefs [durability/write-temporary-bytes! (fn [_ _] (anomaly/anomaly :fault "disk full" {}))]
      (let [result (config-file/update-config! path #(assoc % :a 1))]
        (is (= :fault (:anomaly/type result)))
        (is (str/includes? (:anomaly/message result) path))))
    (is (= "{:llm {}}" (slurp path)))
    (is (= #{"config.edn" "config.edn.lock"} (set (map fs/file-name (fs/list-dir (fs/parent path))))))))

(deftest ^{:stratum 1} write-config-replaces-unparseable-file-test
  (let [path (temp-config-path "{:broken")]
    (is (= {:llm {}} (config-file/write-config! path {:llm {}})))
    (is (= {:llm {}} (edn/read-string (slurp path))))))
