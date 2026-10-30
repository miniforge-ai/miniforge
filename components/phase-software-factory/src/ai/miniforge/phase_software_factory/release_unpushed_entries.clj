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
(ns ai.miniforge.phase-software-factory.release-unpushed-entries
  "The :code/files entries for paths unpublished commits change, read at
   HEAD: a path HEAD lacks is a deletion, a submodule's is its commit, a
   file's is its content. Backend-neutral: the caller supplies how to run
   git (host or capsule).

   Split out of `release-unpushed` (rule 210), so each step of the scan
   stays one expression without a fourth layer."
  (:require [ai.miniforge.phase-software-factory.messages :as messages]
            [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} nul-fields
  "`out` split on NUL, keeping every non-empty field as it is: a path of
   spaces is a path."
  [out]
  (vec (remove empty? (str/split (or out "") #"\u0000"))))

(defn ^{:stratum 0} failure
  "The scan's failure for git `command` that answered `result`
   ({:exit :out :err}): {:failure {:detail :data}}."
  [command {:keys [exit err]}]
  (let [line (str/join " " command)]
    {:failure {:detail (or (not-empty (str/trim (str err)))
                           (messages/t :release/git-command-failed {:command line :exit exit}))
               :data   {:command line :exit exit :stderr err}}}))

(defn- ^{:stratum 0} deleted-entry [path]
  {:path path :content "" :action :delete})

(defn- ^{:stratum 0} submodule-entry [path object]
  {:path path :content (str "Subproject commit " object) :action :modify})

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} tree-entries
  "`git ls-tree -z` output as {path {:type t :object sha}}."
  [out]
  (into {}
        (keep (fn [entry]
                (when-let [[_ type object path] (re-matches #"(?s)\d+ (\S+) (\S+)\t(.*)" entry)]
                  [path {:type type :object object}])))
        (nul-fields out)))

(defn- ^{:stratum 1} blob-entry
  "`path`'s entry with the content of blob `object`, or the failure of the
   git call that read it."
  [run path object]
  (let [command ["git" "cat-file" "-p" object]
        shown   (run command)]
    (if (= 0 (:exit shown))
      {:path path :content (:out shown) :action :modify}
      (failure command shown))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} file-entry
  "`path`'s entry, given `entries` (`tree-entries` of HEAD): a deletion
   when HEAD lacks it, a submodule's commit, else the file's content (or
   the failure reading it)."
  [run entries path]
  (let [{:keys [type object]} (get entries path)]
    (case type
      nil      (deleted-entry path)
      "commit" (submodule-entry path object)
      (blob-entry run path object))))

(comment ::release-unpushed-entries)
