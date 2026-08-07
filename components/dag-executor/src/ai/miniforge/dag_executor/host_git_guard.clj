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
(ns ai.miniforge.dag-executor.host-git-guard
  "Detects a task run mutating the git state its worktree shares with the
   host checkout.

   A linked `git worktree` owns HEAD, the index, and the working tree.
   `.git/config` and every ref outside HEAD live in the shared common dir.
   So `git remote set-url`, `git config`, and `git fetch` run inside a task
   worktree all write the host checkout. On 2026-08-06 that rewrote a live
   checkout's `remote.origin.url` to a local mirror and force-updated its
   `refs/remotes/origin/main` backwards; branches cut afterwards were based
   on a stale commit and pushed to the mirror instead of GitHub.

   Two invariants are watched, chosen because they are the ones a task run
   has no legitimate reason to break:

   - Remote URLs are immutable for the duration of a run. Nothing a task
     does should repoint a remote. The release path's HTTPS-token fallback
     does `set-url` then restores; a run that dies in between leaves a
     token in the host's config, and that shows up here as drift.
   Remote-tracking refs are reported alongside, but are not part of the
   verdict. A moved ref that is not a fast-forward looks identical whether
   a task rewound it or upstream force-pushed and an ordinary `git fetch`
   followed: the default refspec is `+refs/heads/*:refs/remotes/origin/*`,
   and the `+` forces every opportunistic update. Miniforge force-pushes
   its own task branches, so a stacked DAG run produces one as a matter of
   course, and enforcing on it would fail ordinary runs. Telling a local
   rewrite from an upstream one needs the remote's own answer
   (`git ls-remote`), which this does not ask for.

   Refs appearing or disappearing are not reported at all — `fetch.prune`
   legitimately retires a remote-tracking ref when its upstream branch is
   deleted.

   This detects; it does not prevent. Only a sandbox that does not share a
   common dir (a clone rather than a linked worktree) prevents."
  (:require
   [ai.miniforge.dag-executor.host-git-guard.messages :as msg]
   [ai.miniforge.dag-executor.result :as result]
   [clojure.java.shell :as shell]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

;; Snapshot shape and pure comparison
(def ^{:stratum 0} remote-url-config-pattern
  "Matches `remote.<name>.url` and `remote.<name>.pushurl`. Anchored on
   `url$` rather than `\\.url$` so pushurl — which carries the same
   redirect power and none of the visibility — is not missed."
  "^remote\\..*url$")

(def ^{:stratum 0} remote-ref-namespace
  "Ref namespace holding remote-tracking refs. `refs/remotes/origin/main`
   is what the 2026-08-06 incident moved backwards."
  "refs/remotes/")

(def ^{:stratum 0} field-separator
  "ASCII unit separator (U+001F). Delimits `for-each-ref --format` fields;
   it cannot occur in a ref name or a SHA, so splitting on it is total."
  (str (char 0x1F)))

(defn ^{:stratum 0} changed-remote-urls
  "Remote-URL keys whose value differs between two snapshots' url maps.

   A key present in only one of the two counts: adding a remote mid-run is
   as much a redirect as editing one, and removing the remote a push is
   about to target is a silent failure waiting to happen."
  [before after]
  (->> (into (set (keys before)) (keys after))
       sort
       (keep (fn [k]
               (let [was (get before k)
                     is  (get after k)]
                 (when (not= was is)
                   {:config-key k :before was :after is}))))
       vec))

(defn ^{:stratum 0} moved-remote-refs
  "Remote-tracking refs present in BOTH snapshots whose SHA differs.

   Refs only one side has are skipped on purpose: a new upstream branch
   appearing, or `fetch.prune` retiring a deleted one, is routine. The
   caller decides which of these moves were fast-forwards."
  [before after]
  (->> (keys before)
       sort
       (keep (fn [ref]
               (when-let [is (get after ref)]
                 (let [was (get before ref)]
                   (when (not= was is)
                     {:ref ref :before was :after is})))))
       vec))

;; git plumbing reads against the shared common dir
;;
;; Every read below is issued with `-C repo-path`. Config reads and ref
;; reads both resolve to the common dir, so pointing at the host checkout
;; and pointing at one of its linked worktrees observe the same state —
;; which is exactly the sharing this namespace exists to police.
(defn- ^{:stratum 0} git
  "Run git in `repo-path` and return the raw shell map. A git that cannot
   be launched at all comes back as a non-zero exit rather than a throw, so
   callers stay total."
  [repo-path & args]
  (try
    (apply shell/sh "git" "-C" (str repo-path) args)
    (catch Exception e
      {:exit 1 :out "" :err (.getMessage e)})))

(defn- ^{:stratum 0} parse-pairs
  "Parse `sep`-delimited two-field lines into a map. Blank lines and lines
   missing the second field are dropped — a partial line carries no
   comparable value and inventing one would fabricate drift."
  [text sep]
  (->> (str/split-lines (str/trim (or text "")))
       (remove str/blank?)
       (reduce (fn [acc line]
                 (let [[k v] (str/split line (re-pattern sep) 2)]
                   (if (and k v (seq (str/trim k)) (seq (str/trim v)))
                     (assoc acc (str/trim k) (str/trim v))
                     acc)))
               {})))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} remote-urls
  "Map of `remote.<name>.url` config key to its value.

   `git config --get-regexp` exits 1 when nothing matches, which is a valid
   state (a repo with no remotes), not a failure — it is reported as an
   empty map. Any other non-zero exit is a genuine read failure."
  [repo-path]
  (let [r (git repo-path "config" "--get-regexp" remote-url-config-pattern)]
    (case (long (get r :exit 1))
      0 (result/ok (parse-pairs (get r :out "") " "))
      1 (result/ok {})
      (result/err :host-git-snapshot-failed
                  (msg/t :snapshot/remote-urls-failed)
                  {:repo-path repo-path :stderr (get r :err "")}))))

(defn ^{:stratum 1} remote-tracking-refs
  "Map of remote-tracking ref name to the SHA it points at."
  [repo-path]
  (let [r (git repo-path "for-each-ref"
               (str "--format=%(refname)" field-separator "%(objectname)")
               remote-ref-namespace)]
    (if (zero? (long (get r :exit 1)))
      (result/ok (parse-pairs (get r :out "") field-separator))
      (result/err :host-git-snapshot-failed
                  (msg/t :snapshot/remote-refs-failed)
                  {:repo-path repo-path :stderr (get r :err "")}))))

(defn ^{:stratum 1} fast-forward?
  "True when `after` contains `before` in its history — the ref moved
   forward along its own line.

   `git merge-base --is-ancestor` exits 0 for yes and 1 for no. Any other
   exit means the question could not be answered (an object pruned out of
   the store, most likely); that is returned as nil so the caller can fail
   closed rather than read an unanswered probe as a pass."
  [repo-path before after]
  (let [r (git repo-path "merge-base" "--is-ancestor" (str before) (str after))]
    (case (long (get r :exit 1))
      0 true
      1 false
      nil)))

;------------------------------------------------------------------------------ Layer 2

;; Snapshot capture and drift report
(defn ^{:stratum 2} snapshot
  "Capture the host checkout's remote URLs and remote-tracking refs.

   Returns result/ok with `{:repo-path :remote-urls :remote-refs}` — the
   value `drift` compares against — or result/err when either read failed."
  [repo-path]
  (-> (remote-urls repo-path)
      (result/and-then
       (fn [urls]
         (-> (remote-tracking-refs repo-path)
             (result/map-ok
              (fn [refs]
                {:repo-path   (str repo-path)
                 :remote-urls urls
                 :remote-refs refs})))))))

(defn ^{:stratum 2} drift
  "Compare two snapshots of the same checkout and report what a task run
   changed that it had no business changing.

   Reports; does not judge. `:clean?` tracks the remote URLs alone — it is
   true when every one came through the run unchanged. `:remote-url-drift`
   carries the before and after values.

   `:ref-rewinds` lists remote-tracking refs that moved to a commit not
   descended from where they started, and deliberately does **not** feed
   `:clean?`; see the namespace docstring for why an ordinary fetch
   produces one. It is context for a redirect that did happen, not a
   verdict of its own. A ref whose ancestry probe could not be answered is
   listed with `:fast-forward? nil`.

   Whether a dirty report should fail a run is the caller's policy, not
   this namespace's; see `protocols.impl.host-guarded`."
  [before after]
  (letfn [(rewind [repo-path {:keys [before after] :as move}]
            (let [ff (fast-forward? repo-path before after)]
              (when-not (true? ff)
                (assoc move
                       :fast-forward? ff
                       :reason (if (nil? ff)
                                 (msg/t :drift/ancestry-unknown)
                                 (msg/t :drift/remote-ref-rewound))))))]
    (let [repo-path (get before :repo-path)
          urls      (changed-remote-urls (get before :remote-urls)
                                         (get after :remote-urls))
          rewinds   (->> (moved-remote-refs (get before :remote-refs)
                                            (get after :remote-refs))
                         (keep (partial rewind repo-path))
                         vec)]
      {:clean?           (empty? urls)
       :repo-path        repo-path
       :remote-url-drift urls
       :ref-rewinds      rewinds})))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Capture, do something, and check what it cost the host checkout.
  (def before (result/unwrap-or (snapshot "/path/to/checkout") nil))
  ;; ... a task run happens in a linked worktree of that checkout ...
  (drift before (result/unwrap-or (snapshot "/path/to/checkout") nil))
  ;=> {:clean? true :repo-path "/path/to/checkout"
  ;    :remote-url-drift [] :ref-rewinds []}

  ;; A fetch that advanced origin/main stays clean; a force-update
  ;; backwards comes back as :host-git-drift with the ref and both SHAs.
  (fast-forward? "/path/to/checkout" "HEAD~3" "HEAD")
  ;=> true

  :leave-this-here)
