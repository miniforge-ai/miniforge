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

   Remote URLs, and the `url.<base>.insteadOf` rewrites that override
   them, must be identical across a run. Nothing a task legitimately does
   repoints where the host pushes and fetches. The release path's
   HTTPS-token fallback does `set-url` then restores; a run that dies in
   between leaves a token in the host's config, and that shows up here.

   Config that makes git run a program (`command-config-pattern`) must be
   identical too: `git config core.hooksPath <dir>` in a task worktree
   makes the host's next commit, the operator's own included, run those
   hooks. Ordinary task work sets none of these keys.

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

   Credentials are stripped from a URL as it is read, so no snapshot and
   no report ever holds one. The likeliest drift is exactly the
   token-bearing URL above, and a guard that published it while reporting
   it would be its own leak. Command config values are not held at all,
   only their `fingerprint`.

   This detects; it does not prevent. Only a sandbox that does not share a
   common dir (a clone rather than a linked worktree) prevents."
  (:require
   [ai.miniforge.dag-executor.host-git-guard.messages :as msg]
   [ai.miniforge.dag-executor.result :as result]
   [clojure.java.shell :as shell]
   [clojure.string :as str])
  (:import
   [java.io File]
   [java.nio.charset StandardCharsets]
   [java.security SecureRandom]
   [javax.crypto Mac]
   [javax.crypto.spec SecretKeySpec]))

;------------------------------------------------------------------------------ Layer 0

;; Snapshot shape and pure comparison
(def ^{:stratum 0} redirect-config-pattern
  "Config keys that decide where the host pushes and fetches.

   `remote\\..*url` is anchored on `url$` rather than `\\.url$` so `pushurl`
   — same redirect power, less visibility — is not missed.
   `url\\..*insteadof` covers `url.<base>.insteadOf` and `pushInsteadOf`,
   which rewrite every URL git resolves and would otherwise redirect the
   host without touching any `remote.*` key at all. `--get-regexp`
   lowercases the key, so the pattern is written lowercase."
  "^(remote\\..*url|url\\..*insteadof)$")

(def ^{:stratum 0} command-config-pattern
  "Config keys that make git run a program (hooks, fsmonitor, gc and
   pack-objects hooks, ssh, pager, editor, credential helper, filter, diff
   and merge drivers, archive filters, signing, remote helpers, man and web viewers, gui
   tools, trailer commands, send-email and imap-send helpers, aliases), or
   load config that can (`include.path`, `includeIf.*.path`). Taken from
   the keys the git 2.48.1 man pages document. Lowercase for the same
   reason as `redirect-config-pattern`.

   `submodule.<name>.update` is left out although its `!command` form runs
   a shell command: `git submodule init` copies it from `.gitmodules`, so
   an ordinary submodule checkout sets it."
  (let [key-patterns
        [;; core programs and hooks
         "core\\.(hookspath|fsmonitor|sshcommand|askpass|gitproxy|pager|editor|alternaterefscommand)"
         "gc\\.recentobjectshook"
         "uploadpack\\.packobjectshook"
         ;; config that loads more config
         "include\\.path"
         "includeif\\..*\\.path"
         ;; editors, pagers, and aliases
         "sequence\\.editor"
         "pager\\..*"
         "alias\\..*"
         "interactive\\.difffilter"
         ;; credentials and signing
         "credential\\.(.*\\.)?helper"
         "gpg\\.(.*\\.)?program"
         "gpg\\.ssh\\.defaultkeycommand"
         ;; content drivers
         "filter\\..*\\.(clean|smudge|process)"
         "diff\\.external"
         "diff\\..*\\.(command|textconv)"
         "merge\\..*\\.driver"
         "(difftool|mergetool)\\..*\\.(cmd|path)"
         "tar\\..*\\.command"
         ;; transport
         "remote\\..*\\.(uploadpack|receivepack|vcs)"
         ;; viewers and other tools
         "man\\.viewer"
         "(man|browser)\\..*\\.(cmd|path)"
         "(web|help|instaweb)\\.browser"
         "instaweb\\.httpd"
         "guitool\\..*\\.cmd"
         "trailer\\..*\\.(command|cmd)"
         "imap\\.tunnel"
         "sendemail\\.(.*\\.)?(tocmd|cccmd|headercmd|sendmailcmd|smtpserver)"]]
    (str "^(" (str/join "|" key-patterns) ")$")))

(def ^{:stratum 0} remote-ref-namespace
  "Ref namespace holding remote-tracking refs. `refs/remotes/origin/main`
   is what the 2026-08-06 incident moved backwards."
  "refs/remotes/")

(def ^{:stratum 0} field-separator
  "ASCII unit separator (U+001F). Delimits `for-each-ref --format` fields;
   it cannot occur in a ref name or a SHA, so splitting on it is total."
  (str (char 0x1F)))

(def ^{:stratum 0} config-entry-separator
  "NUL, which `git config --null` ends each entry with. It cannot occur in
   a config key or value, so a value that spans lines stays one value."
  (str (char 0)))

(def ^{:stratum 0} fingerprint-algorithm
  "MAC that `fingerprint` computes. Keyed, so a fingerprint cannot be
   checked against a guessed value without the per-process key."
  "HmacSHA256")

(def ^{:stratum 0} fingerprint-digest-bytes
  "Bytes of the MAC a fingerprint keeps. 16 bytes (128 bits): a collision
   would make a changed value look unchanged, and at this length one is
   not a practical concern even for a value chosen to cause it, since the
   key is secret."
  16)

(def ^{:stratum 0} fingerprint-prefix
  "Marks a fingerprint in a report, so it is not read as the value itself."
  "hmac-sha256:")

(def ^{:stratum 0} valueless-entry
  "Stands in for the value of a key written with no `=` (a bare `log`
   under `[pager]`), which git reads as boolean true. A keyword, so it
   never equals a scrubbed string; dropping the key instead would let a
   bare `core.fsmonitor` or pager switch go unreported."
  :valueless)

(defonce ^{:stratum 0} ^:private
  ^{:doc "Random key for `fingerprint`, made once per process. Both
   snapshots a drift report compares are taken in one process, so equality
   is all a fingerprint has to support."}
  fingerprint-key
  ;; 32 bytes, the HMAC-SHA256 output length; RFC 2104 discourages shorter.
  (let [key-bytes 32
        k         (byte-array key-bytes)]
    (.nextBytes (SecureRandom.) k)
    k))

(defn ^{:stratum 0} redact-credentials
  "Replace the userinfo in a URL that carries one.

   Matches only `scheme://user:secret@host`, so an scp-style SSH remote
   keeps its literal `git@` — that is a username, not a secret, and losing
   it would make a report harder to read for no gain. The token-bearing
   HTTPS URL the release path writes is exactly the matched shape."
  [value]
  (str/replace (str value) #"://[^/@]*@" "://***@"))

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

(defn- ^{:stratum 0} parse-fields
  "Split `sep`-delimited two-field lines into `[key value]` pairs.

   A line with no separator is dropped. Each field is trimmed, which keeps
   a CRLF line ending out of the value. The split limit is 2, so a value
   containing the separator survives whole."
  [text sep]
  (->> (str/split-lines (or text ""))
       (remove str/blank?)
       (keep (fn [line]
               (let [[k v] (str/split line (re-pattern sep) 2)]
                 (when (and k v (seq (str/trim k)))
                   [(str/trim k) (str/trim v)]))))))

(defn- ^{:stratum 0} config-entry
  "`[key value]` of one `git config --null` entry, where the key ends at
   the first newline. An empty value is kept: a key set to the empty string
   is a real state, and treating it as absent would hide a blanked remote
   URL. An entry with no newline is a valueless key and gives `[key nil]`."
  [entry]
  (let [[k v] (str/split entry #"\n" 2)]
    (when (seq k)
      [k v])))

(defn ^{:stratum 0} changed-config
  "Watched config keys whose values differ between two snapshots.

   A key present in only one of the two counts: adding a remote, an
   `insteadOf` rewrite, or a hooks path mid-run is as much a change as
   editing one, and removing the remote a push is about to target is a
   silent failure waiting to happen.

   Values are vectors because git config is multi-valued — a second
   `remote.origin.url` line is legal, and `git remote get-url` resolves to
   the first, so comparing only one value would miss a rewrite of it."
  [before after]
  (->> (into (set (keys before)) (keys after))
       sort
       (keep (fn [k]
               (let [was (get before k)
                     is  (get after k)]
                 (when (not= was is)
                   {:config-key k :before (vec was) :after (vec is)}))))
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
   callers stay total.

   An interrupt restores the thread's interrupt flag before returning, so a
   cancellation still propagates to whatever is waiting on this thread;
   swallowing it here, as a bare `catch Exception` would, strands a
   shutdown. It reports the same failing exit as any other launch failure —
   the cancellation travels on the flag, not in this map."
  [repo-path & args]
  (try
    (apply shell/sh "git" "-C" (str repo-path) args)
    (catch InterruptedException e
      (.interrupt (Thread/currentThread))
      {:exit 1 :out "" :err (.getMessage e)})
    (catch Exception e
      {:exit 1 :out "" :err (.getMessage e)})))

;------------------------------------------------------------------------------ Layer 1

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

(defn ^{:stratum 1} common-dir
  "Absolute path of the git common dir `repo-path` resolves to.

   A checkout and every linked worktree of it share one common dir, so
   this is the identity of the state this namespace watches — two paths
   that resolve to the same common dir are the same checkout as far as
   config and refs are concerned.

   `--git-common-dir` may answer relatively, so the result is resolved
   against `repo-path` and then canonicalized: `.` segments and symlinks
   have to collapse, or one checkout named two ways would look like two."
  [repo-path]
  (let [r    (git repo-path "rev-parse" "--git-common-dir")
        path (str/trim (get r :out ""))]
    (if-not (and (zero? (long (get r :exit 1))) (seq path))
      (result/err :host-git-snapshot-failed
                  (msg/t :snapshot/common-dir-failed)
                  {:repo-path repo-path :stderr (get r :err "")})
      (let [f (File. path)]
        (try
          (result/ok (.getCanonicalPath
                      (if (.isAbsolute f) f (File. (str repo-path) path))))
          (catch java.io.IOException e
            (result/err :host-git-snapshot-failed
                        (msg/t :snapshot/common-dir-failed)
                        {:repo-path repo-path :stderr (.getMessage e)})))))))

(defn ^{:stratum 1} config-values
  "Map of each config key matching `pattern` to the vector of values it
   carries, values passed through `scrub-value`. A failed read reports the
   message under `read-failed`.

   `git config --get-regexp` exits 1 when nothing matches, which is a valid
   state (a repo with no remotes) and reported as an empty map. Any other
   non-zero exit is a genuine read failure. Exit 1 is also what a path that
   is not a repository returns, so this read alone cannot tell those apart
   — `remote-tracking-refs` is what makes a non-repository fail closed.

   No scope flag is passed, so a change to the user's global config counts
   like a change to the shared `.git/config`."
  [repo-path pattern scrub-value read-failed]
  (let [r (git repo-path "config" "--null" "--get-regexp" pattern)]
    (case (long (get r :exit 1))
      0 (result/ok
         ;; Accumulated into vectors, not a plain map: git config is
         ;; multi-valued, and collapsing to the last value would hide a
         ;; rewrite of the first — the one `git remote get-url` resolves to.
         ;;
         ;; Scrubbed on the way in rather than at report time: snapshots
         ;; are parked in a process-lifetime atom by the guarded executor,
         ;; so a token that entered one would outlive the run that made it.
         ;; Keys too: a `<url>` subsection can carry userinfo.
         ;;
         ;; Split on NUL, not lines: each continuation line of a multi-line
         ;; value would become a key, and keys are never fingerprinted.
         (->> (str/split (get r :out "") (re-pattern config-entry-separator))
              (keep config-entry)
              (reduce (fn [acc [k v]]
                        (update acc (redact-credentials k) (fnil conj [])
                                (if (nil? v) valueless-entry (scrub-value v))))
                      {})))
      1 (result/ok {})
      (result/err :host-git-snapshot-failed
                  (msg/t read-failed)
                  {:repo-path repo-path :stderr (get r :err "")}))))

(defn ^{:stratum 1} remote-tracking-refs
  "Map of remote-tracking ref name to the SHA it points at."
  [repo-path]
  (let [r (git repo-path "for-each-ref"
               (str "--format=%(refname)" field-separator "%(objectname)")
               remote-ref-namespace)]
    (if (zero? (long (get r :exit 1)))
      ;; A ref name appears once per `for-each-ref` line, so a plain map is
      ;; the whole story here.
      (result/ok (into {} (parse-fields (get r :out "") field-separator)))
      (result/err :host-git-snapshot-failed
                  (msg/t :snapshot/remote-refs-failed)
                  {:repo-path repo-path :stderr (get r :err "")}))))

(defn ^{:stratum 1} fingerprint
  "Keyed digest standing in for a config value that may carry a secret.
   No pattern can find a secret inside an arbitrary command line, so the
   value is not kept. Equal values give equal fingerprints within one
   process, and only within it."
  [value]
  (let [mac (Mac/getInstance fingerprint-algorithm)]
    (.init mac (SecretKeySpec. ^bytes fingerprint-key fingerprint-algorithm))
    ;; `format`, not `java.util.HexFormat`, which babashka does not expose.
    (->> (.doFinal mac (.getBytes (str value) StandardCharsets/UTF_8))
         (take fingerprint-digest-bytes)
         (map #(format "%02x" (bit-and % 0xff)))
         (apply str fingerprint-prefix))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} drift
  "Compare two snapshots of the same checkout and report what a task run
   changed that it had no business changing.

   Reports; does not judge. `:clean?` is true when every remote URL,
   `insteadOf` rewrite, and command-config key came through the run
   unchanged. `:redirect-drift` carries the before and after values,
   credentials stripped; `:command-drift` carries fingerprints.

   `:ref-rewinds` lists remote-tracking refs that moved to a commit not
   descended from where they started, and deliberately does **not** feed
   `:clean?`. Such a move is indistinguishable from an ordinary `git fetch`
   after upstream force-pushed: the default refspec forces every
   opportunistic update, and miniforge force-pushes its own task branches,
   so a stacked DAG run produces one as a matter of course. It is context
   for a redirect that did happen, not a verdict of its own. A ref whose
   ancestry probe could not be answered is listed with
   `:fast-forward? nil`.

   Whether a dirty report should fail a run is the caller's policy, not
   this namespace's."
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
          redirects (changed-config (get before :redirects)
                                    (get after :redirects))
          commands  (changed-config (get before :command-config)
                                    (get after :command-config))
          rewinds   (->> (moved-remote-refs (get before :remote-refs)
                                            (get after :remote-refs))
                         (keep (partial rewind repo-path))
                         vec)
          clean?    (and (empty? redirects) (empty? commands))]
      {:clean?         clean?
       :repo-path      repo-path
       :redirect-drift redirects
       :command-drift  commands
       :ref-rewinds    rewinds})))

;; Snapshot capture and drift report
(defn ^{:stratum 2} snapshot
  "Capture the host checkout's redirect config, command config, and
   remote-tracking refs.

   Returns result/ok with `{:repo-path :redirects :command-config
   :remote-refs}` — the value `drift` compares against — or result/err
   when any read failed."
  [repo-path]
  (-> (config-values repo-path redirect-config-pattern
                     redact-credentials :snapshot/remote-urls-failed)
      (result/and-then
       (fn [redirects]
         (-> (config-values repo-path command-config-pattern
                            fingerprint :snapshot/command-config-failed)
             (result/and-then
              (fn [commands]
                (-> (remote-tracking-refs repo-path)
                    (result/map-ok
                     (fn [refs]
                       {:repo-path      (str repo-path)
                        :redirects      redirects
                        :command-config commands
                        :remote-refs    refs}))))))))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Capture, do something, and check what it cost the host checkout.
  (def before (result/unwrap-or (snapshot "/path/to/checkout") nil))
  ;; ... a task run happens in a linked worktree of that checkout ...
  (drift before (result/unwrap-or (snapshot "/path/to/checkout") nil))
  ;=> {:clean? true :repo-path "/path/to/checkout"
  ;    :redirect-drift [] :command-drift [] :ref-rewinds []}

  (fast-forward? "/path/to/checkout" "HEAD~3" "HEAD")
  ;=> true

  :leave-this-here)
