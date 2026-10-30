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
(ns ai.miniforge.release-executor.fake-remote
  "A stateful stand-in for a sandbox worktree, its origin and GitHub: a
   dag/TaskExecutor that answers the release executor's git and gh
   commands from one state atom (local branches, HEAD, pushed branches,
   PRs), so a test can release, release again, and count what reached the
   remote."
  (:require
   [ai.miniforge.dag-executor.interface :as dag]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} initial-state
  "One dirty worktree on main, an origin with main only, no PRs."
  []
  {:branches #{"main"} :head "main" :dirty? true :commits 0
   :pushed {} :prs []})

(defn- ^{:stratum 0} command-line [command]
  (if (string? command) command (str/join " " command)))

(defn- ^{:stratum 0} ok
  ([] (ok ""))
  ([stdout] {:exit-code 0 :stdout stdout :stderr ""}))

(defn- ^{:stratum 0} fail [stderr]
  {:exit-code 1 :stdout "" :stderr stderr})

(defn- ^{:stratum 0} last-word [line]
  (last (str/split line #"\s+")))

(defn- ^{:stratum 0} pr-lines [prs branch]
  (->> prs
       (filter #(= branch (:head %)))
       reverse
       (map #(str (:number %) "\t" (:url %) "\t" (:state %) "\t" (:head-sha %)))
       (str/join "\n")))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} answer!
  "Apply one command to `state` (an atom) and return the executor's reply."
  [state command]
  (let [line (command-line command)
        {:keys [branches head dirty? commits prs]} (swap! state update :log (fnil conj []) line)
        sha (str "sha-" commits)]
    (cond
      (str/starts-with? line "git symbolic-ref") (ok "refs/remotes/origin/main")
      (str/starts-with? line "git checkout -b ")
      (let [b (last-word line)]
        (if (contains? branches b)
          (fail (str "fatal: a branch named '" b "' already exists"))
          (do (swap! state #(-> % (update :branches conj b) (assoc :head b))) (ok))))
      (str/starts-with? line "git rev-parse --verify")
      (if (contains? branches (str/replace (last-word line) "refs/heads/" "")) (ok) (fail ""))
      (str/starts-with? line "git merge-base --is-ancestor") (ok)
      (str/starts-with? line "git checkout ")
      (do (swap! state assoc :head (last-word line)) (ok))
      (str/starts-with? line "git diff --cached --name-only") (ok (if dirty? "src/a.clj" ""))
      (str/starts-with? line "git rev-list --count")
      (ok (if (:merged-into-base? @state) "0" (str commits)))
      (str/includes? line "--numstat") (ok "1\t0\tsrc/a.clj")
      (str/includes? line "-U0") (ok "+(ns a)")
      (str/starts-with? line "git commit -m")
      (do (swap! state #(-> % (assoc :dirty? false) (update :commits inc))) (ok))
      (str/starts-with? line "git rev-parse HEAD") (ok sha)
      (str/starts-with? line "git push -u origin ")
      (do (swap! state assoc-in [:pushed (last-word line)] sha) (ok))
      (str/starts-with? line "gh pr list --head ")
      (ok (pr-lines prs (nth (str/split line #"\s+") 4)))
      (str/starts-with? line "gh pr create")
      (let [n (inc (count prs))
            url (str "https://github.com/o/r/pull/" n)]
        (swap! state update :prs conj {:number n :url url :state "OPEN" :head head
                                       :head-sha (get-in @state [:pushed head])})
        (ok url))
      :else (ok))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} executor
  "A TaskExecutor over `state` (an atom from initial-state). `intercept`, a
   fn of [state command] called before each command, can throw to stand
   for a crash at that command."
  ([state] (executor state (fn [_ _])))
  ([state intercept]
   (reify
     dag/TaskExecutor
     (executor-type [_] :fake-remote)
     (available? [_] (dag/ok {:available? true}))
     (acquire-environment! [_ _ _] (dag/ok {:environment-id "fake-env"}))
     (execute! [_ _env-id command _opts]
       (intercept state command)
       (dag/ok (answer! state command)))
     (copy-to! [_ _ _ _] (dag/ok {}))
     (copy-from! [_ _ _ _] (dag/ok {}))
     (release-environment! [_ _] (dag/ok {:released? true}))
     (environment-status [_ _] (dag/ok {:status :running})))))
