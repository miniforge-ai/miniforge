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
  "Whether the host-mode push fallback may send the GitHub token, and the
   per-command environment that sends it.

   The retry runs only in the plain case: origin has exactly one push URL,
   git does not rewrite it (no insteadOf / pushInsteadOf applies), and it is
   on github.com, the host the pipeline's GH_TOKEN belongs to (nothing in
   miniforge configures a GitHub Enterprise host). Mirrors, rewritten URLs
   and other hosts get no retry, so the token never goes anywhere the
   repository's own config redirects it.

   The settings travel as GIT_CONFIG_COUNT / GIT_CONFIG_KEY_n /
   GIT_CONFIG_VALUE_n (git 2.31+): nothing is written to .git/config and
   the token never appears in argv.")

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} trusted-host
  "The only host the token is sent to."
  "github.com")

(defn ^{:stratum 0} https-url
  "HTTPS form of a push URL — scp-style SSH (user@host:path),
   ssh://[user@]host[:port]/path, or https://[user@]host/path — as
   [host url], or nil for anything else (a local path, file://)."
  [url]
  (when-let [[_ host path]
             (or (re-matches #"[^@/:]+@([^:/]+):/?(.+)" (str url))
                 (re-matches #"ssh://(?:[^@/]+@)?([^:/]+)(?::\d+)?/(.+)" (str url))
                 (re-matches #"https://(?:[^@/]+@)?([^/]+)/(.+)" (str url)))]
    [host (str "https://" host "/" path)]))

(defn ^{:stratum 0} basic-auth-header
  "HTTP header carrying `token` as Basic x-access-token:<token>, the form
   GitHub accepts for app and personal tokens."
  [token]
  (str "Authorization: Basic "
       (.encodeToString (java.util.Base64/getEncoder)
                        (.getBytes (str "x-access-token:" token) "UTF-8"))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} retry-plan
  "Decide the token retry for origin. `configured` are origin's push targets
   as written (remote.origin.pushurl values when `pushurl?`, else
   remote.origin.url values); `effective` is what git pushes to
   (`git remote get-url --push --all origin`).

   Returns {:env <environment>} for the plain case, else {:skip <message
   key>} naming why no credential is sent. The env clears inherited
   http.<url>.extraHeader values for the exact push URL, adds the token
   header there, and for an SSH URL adds a rule sending that one configured
   URL over HTTPS (pushInsteadOf for a url, insteadOf for a pushurl, which
   pushInsteadOf does not reach). No existing rule matches the URL —
   otherwise it would differ from `effective` — so the rule cannot tie."
  [{:keys [configured effective pushurl?]} token]
  (let [target      (first effective)
        [host https] (https-url target)]
    (cond
      (not= 1 (count configured) (count effective)) {:skip :push/token-retry-not-single-target}
      (not= target (first configured))             {:skip :push/token-retry-rewritten}
      (not= trusted-host host)                      {:skip :push/token-retry-untrusted-host}
      :else
      (let [entries (cond-> [[(str "http." https ".extraHeader") ""]
                             [(str "http." https ".extraHeader") (basic-auth-header token)]]
                      (not= https target)
                      (conj [(str "url." https (if pushurl? ".insteadOf" ".pushInsteadOf")) target]))]
        {:env (reduce-kv (fn [env i [k v]]
                           (assoc env (str "GIT_CONFIG_KEY_" i) k (str "GIT_CONFIG_VALUE_" i) v))
                         {"GIT_CONFIG_COUNT" (str (count entries))}
                         entries)}))))
