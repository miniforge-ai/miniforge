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
(ns ai.miniforge.release-executor.push-auth
  "Credentials for the host-mode push fallback, passed to one git push as
   environment-only config (GIT_CONFIG_COUNT / GIT_CONFIG_KEY_n /
   GIT_CONFIG_VALUE_n, git 2.31+). The token is never written to
   .git/config and never appears in argv, so a push that hangs, times out
   or is killed leaves nothing to scrub.

   The settings are keyed on exact URLs so they win over whatever the
   repository already configures:
   - git rewrites a remote URL once, with the longest matching insteadOf /
     pushInsteadOf prefix. A rule whose prefix is the whole configured URL
     is the longest possible, so it beats an alias (`gh:` -> SSH) or a
     pushInsteadOf that sends HTTPS pushes over SSH.
   - http.<url>.extraHeader entries apply from the most specific URL match.
     Keying ours on the full push URL means a repository-scoped header
     cannot outrank it; an empty entry first clears inherited headers.")

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} https-url
  "HTTPS form of an effective push URL — scp-style SSH (user@host:path),
   ssh://[user@]host[:port]/path, or https://[user@]host/path — or nil for
   anything else (a local path, file://)."
  [url]
  (let [url (str url)]
    (when-let [[_ host path]
               (or (re-matches #"[^@/:]+@([^:/]+):/?(.+)" url)
                   (re-matches #"ssh://(?:[^@/]+@)?([^:/]+)(?::\d+)?/(.+)" url)
                   (re-matches #"https://(?:[^@/]+@)?([^/]+)/(.+)" url))]
      (str "https://" host "/" path))))

(defn ^{:stratum 0} basic-auth-header
  "HTTP header carrying `token` as Basic x-access-token:<token>, the form
   GitHub accepts for app and personal tokens."
  [token]
  (str "Authorization: Basic "
       (.encodeToString (java.util.Base64/getEncoder)
                        (.getBytes (str "x-access-token:" token) "UTF-8"))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} token-env
  "Environment that sends one push to origin over HTTPS with `token`.

   `raw` are origin's push targets as configured (remote.origin.pushurl
   values when `pushurl?`, else remote.origin.url values); `effective` are
   the same targets after git's rewriting (`git remote get-url --push --all
   origin`), in the same order. Each raw target is rewritten to the HTTPS
   form of its effective URL — through pushInsteadOf for a url (which
   pushInsteadOf applies to), insteadOf for an explicit pushurl (which it
   does not) — and each HTTPS URL gets the header. nil when the lists are
   empty or uneven, or a target has no HTTPS form."
  [{:keys [raw effective pushurl?]} token]
  (let [targets (mapv https-url effective)]
    (when (and (seq raw) (= (count raw) (count targets)) (every? some? targets))
      (let [header  (basic-auth-header token)
            rewrite (if pushurl? ".insteadOf" ".pushInsteadOf")
            entries (into (vec (mapcat (fn [h] [[(str "http." h ".extraHeader") ""]
                                               [(str "http." h ".extraHeader") header]])
                                       (distinct targets)))
                          (map (fn [r h] [(str "url." h rewrite) r]) raw targets))]
        (reduce-kv (fn [env i [k v]]
                     (assoc env (str "GIT_CONFIG_KEY_" i) k (str "GIT_CONFIG_VALUE_" i) v))
                   {"GIT_CONFIG_COUNT" (str (count entries))}
                   entries)))))
