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
(ns ai.miniforge.governance-provenance.git
  "Read-only local git queries for the incident provenance projection."
  (:require
   [ai.miniforge.governance-provenance.git-parse :as parse]
   [clojure.java.shell :as shell]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} shell-exec
  "Execute an argv vector in a repository without invoking a shell string."
  [repo-root args]
  (try
    (let [{:keys [exit out err]} (apply shell/sh (concat args [:dir repo-root]))]
      (if (zero? exit)
        {:success? true :output (str/trim (or out ""))}
        {:success? false :error (str/trim (or err "")) :exit exit}))
    (catch Exception e
      {:success? false :error (.getMessage e)})))

(defn ^{:stratum 0} valid-location?
  [{:keys [path start-line end-line symbol]}]
  (and (string? path)
       (not (str/blank? path))
       (not (.isAbsolute (java.io.File. path)))
       (not-any? #{".."} (str/split path #"[/\\]"))
       (pos-int? start-line)
       (pos-int? end-line)
       (<= start-line end-line)
       (or (nil? symbol)
           (and (string? (:symbol/id symbol))
                (not (str/blank? (:symbol/id symbol)))))))

(defn ^{:stratum 0} git-result
  [exec-fn repo-root & args]
  (exec-fn repo-root (into ["git"] args)))

(defn ^{:stratum 0} sha?
  [value]
  (boolean (and (string? value) (re-matches #"[0-9a-f]{40}" value))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} resolve-revision
  [exec-fn repo-root revision]
  (let [result (git-result exec-fn repo-root "rev-parse" "--verify" "--end-of-options"
                           (str (or revision "HEAD") "^{commit}"))]
    (if (and (:success? result) (sha? (:output result)))
      result
      {:success? false :error (get result :error "git returned an invalid commit id")})))

(defn ^{:stratum 1} blob-sha
  [exec-fn repo-root revision path]
  (let [result (git-result exec-fn repo-root "ls-tree" revision "--" path)]
    (when (:success? result)
      (some-> (re-find #"\bblob ([0-9a-f]{40})\t" (:output result)) second))))

(defn ^{:stratum 1} blamed-shas
  [exec-fn repo-root revision {:keys [path start-line end-line]}]
  (let [line-range (str start-line "," end-line)
        result (git-result exec-fn repo-root "blame" "--line-porcelain"
                           "-L" line-range revision "--" path)]
    (if (:success? result)
      (let [shas (parse/parse-blame-shas (:output result))]
        (cond-> {:shas shas}
          (empty? shas) (assoc :gap {:gap/type :git-blame-empty
                                     :gap/path path})))
      {:shas [] :gap {:gap/type :git-blame-unavailable
                      :gap/path path
                      :gap/detail (:error result)}})))

(defn ^{:stratum 1} commit-fact
  [exec-fn repo-root sha]
  (let [result (git-result exec-fn repo-root "show" "-s"
                           "--format=%H%x1f%aI%x1f%an%x1f%s" sha)]
    (when (:success? result)
      (let [[commit authored-at author subject] (parse/parse-fields (:output result))]
        (when (sha? commit)
          {:commit/sha commit :commit/authored-at authored-at
           :commit/author author :commit/subject subject})))))

(defn ^{:stratum 1} pull-request-fact
  [exec-fn repo-root revision sha]
  (let [result (git-result exec-fn repo-root "log" "--merges" "--ancestry-path"
                           "--reverse" "--format=%H%x1f%s" (str sha ".." revision))]
    (when (:success? result)
      (some-> (parse/parse-pull-request (:output result))
              (assoc :pull-request/contains-commit sha)))))
