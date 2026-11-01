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
(ns ai.miniforge.dag-executor.protocols.impl.runtime.oci-cli.git-credentials
  "Git remotes and credentials for the OCI-CLI executor: parsing the repo
   URL, resolving the token for its host, and the per-command git config env
   (GIT_CONFIG_COUNT/KEY/VALUE) that carries it."
  (:require
   [ai.miniforge.config.interface :as config]
   [ai.miniforge.dag-executor.protocols.impl.runtime.messages :as messages]
   [ai.miniforge.dag-executor.result :as result]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} git-config-env-pattern
  "GIT_CONFIG_COUNT / GIT_CONFIG_KEY_n / GIT_CONFIG_VALUE_n: one config tuple
   whose values can carry a credential (an Authorization header)."
  #"GIT_CONFIG_(COUNT|KEY_\d+|VALUE_\d+)")

;; ============================================================================
;; Workspace Bootstrap (N11 §4.2)
;; ============================================================================
(defn- ^{:stratum 0} infer-host-kind
  "Infer the git hosting provider from a repository URL.
   Returns :github, :gitlab, or :generic."
  [repo-url]
  (cond
    (str/includes? repo-url "github.com")  :github
    (str/includes? repo-url "gitlab.com")  :gitlab
    (str/includes? repo-url "gitlab")      :gitlab
    :else                                  :generic))

(defn- ^{:stratum 0} resolve-git-token
  "Resolve a git authentication token for capsule clone.
   Delegates to config/resolve-token which uses the profile + env chain:
   MINIFORGE_GIT_TOKEN → profile → provider env var → CLI fallback."
  [_repo-url {:keys [host-kind]}]
  (config/resolve-token host-kind))

(def ^{:stratum 0} ^:private git-auth-users
  "HTTP basic-auth username each host kind pairs with a token; hosts not
   listed take `default-git-auth-user`."
  {:gitlab "oauth2"})

(def ^{:stratum 0} ^:private default-git-auth-user "x-access-token")

(defn- ^{:stratum 0} basic-auth-header
  "HTTP Basic Authorization header carrying `token` for `user`."
  [user token]
  (str "Authorization: Basic "
       (.encodeToString (java.util.Base64/getEncoder)
                        (.getBytes (str user ":" token) "UTF-8"))))

(def ^{:stratum 0} ^:private git-credential-config
  "Config every credentialed git command runs with, set through the
   environment, which overrides the workspace's .git/config (written by
   untrusted code): no hooks, no credential helper, and https as the only
   transport, so no hook, helper, ssh command or ext:: transport named in
   that config runs while the credential is in git's environment."
  [["core.hooksPath" "/dev/null"]
   ["credential.helper" ""]
   ["protocol.allow" "never"]
   ["protocol.https.allow" "always"]])

(defn- ^{:stratum 0} git-config-env
  "GIT_CONFIG_COUNT / GIT_CONFIG_KEY_n / GIT_CONFIG_VALUE_n for `entries`,
   a seq of [key value], numbered from `offset` (default 0); the count
   also covers the `offset` entries before them."
  ([entries] (git-config-env entries 0))
  ([entries offset]
   (into {"GIT_CONFIG_COUNT" (str (+ offset (count entries)))}
         (mapcat (fn [i [k v]] [[(str "GIT_CONFIG_KEY_" i) k] [(str "GIT_CONFIG_VALUE_" i) v]])
                 (iterate inc offset) entries))))

(defn ^{:stratum 0} git-config-count
  "The GIT_CONFIG_COUNT in `env`; an empty or non-numeric one, as git reads
   it, is 0."
  [env]
  (or (parse-long (get env "GIT_CONFIG_COUNT" "")) 0))

(defn- ^{:stratum 0} percent-decode
  "Decode %XX escapes in a URL component; `+` stays a plus."
  [s]
  (java.net.URLDecoder/decode (str/replace s "+" "%2B") "UTF-8"))

(defn ^{:stratum 0} credential-git-check
  "`version-result`, from `git --version` in the capsule, or an error when
   that git predates 2.31: it ignores GIT_CONFIG_COUNT, which carries the
   credential, so a private clone would fail without saying why. Output
   it cannot parse passes."
  [version-result]
  (let [out (get-in version-result [:data :stdout] "")]
    (if-let [[_ major minor] (re-find #"git version (\d+)\.(\d+)" out)]
      (if (neg? (compare [(parse-long major) (parse-long minor)] [2 31]))
        (result/err :git-too-old (messages/t :oci/git-too-old {:version (str/trim out)}))
        version-result)
      version-result)))

(defn ^{:stratum 0} git-network-command?
  "True for the git commands persist/restore send to the remote."
  [cmd]
  (and (vector? cmd)
       (= "git" (first cmd))
       (contains? #{"push" "fetch"} (second cmd))))

(defn- ^{:stratum 0} string-keys
  "`env` with each key as its name: an exec env may name a variable by
   keyword or string, and the GIT_CONFIG_* readers look names up as strings."
  [env]
  (update-keys env name))

(def ^{:stratum 0} git-trace-off-env
  "Env that keeps git's tracing from recording the Authorization header:
   header redaction forced on, over an inherited GIT_TRACE_REDACT=0, and
   each trace target empty, which git reads as off. GIT_CURL_VERBOSE stays
   as inherited: git only checks that it is set, so an empty value would
   turn it on; its stderr output goes through the same redaction."
  (into {"GIT_TRACE_REDACT" "1"}
        (map vector ["GIT_TRACE" "GIT_TRACE_CURL" "GIT_TRACE_PACKET" "GIT_TRACE_PACK_ACCESS"
                     "GIT_TRACE_PERFORMANCE" "GIT_TRACE_SETUP" "GIT_TRACE_SHALLOW"
                     "GIT_TRACE2" "GIT_TRACE2_EVENT" "GIT_TRACE2_PERF"]
             (repeat ""))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} git-config-entries
  "The [key value] pairs GIT_CONFIG_COUNT / KEY_n / VALUE_n in `env` set,
   in order, whether `env` names them by keyword or string. An empty or
   non-numeric count, as git reads it, sets none."
  [env]
  (let [env (string-keys env)]
    (for [i (range (or (parse-long (get env "GIT_CONFIG_COUNT" "")) 0))]
      [(get env (str "GIT_CONFIG_KEY_" i)) (get env (str "GIT_CONFIG_VALUE_" i))])))

(defn- ^{:stratum 1} split-userinfo
  "[url auth] for an http(s) URL: the URL without any `user[:password]@`,
   and {:user :token} when it carried a password, else nil."
  [url]
  (if-let [[_ scheme userinfo more] (re-matches #"(?i)(https?://)([^/]*)@(.*)" url)]
    (let [[user password] (str/split userinfo #":" 2)]
      [(str scheme more)
       (when password {:user (percent-decode user) :token (percent-decode password)})])
    [url nil]))

(defn ^{:stratum 1} git-auth
  "{:user :token} that authenticates git to `remote`: the credential its URL
   carried, else the token resolved for its host kind. A resolved token is
   never sent to a plain-http remote. nil without one."
  [{:keys [repo-url https-url host-kind url-auth]}]
  (or url-auth
      (when-let [token (when-not (str/starts-with? (str/lower-case (str https-url)) "http://")
                         (resolve-git-token repo-url {:host-kind host-kind}))]
        {:user  (get git-auth-users host-kind default-git-auth-user)
         :token token})))

(defn ^{:stratum 1} git-credential-env
  "Env that authenticates one git command to the host of `https-url` with
   `auth`: an Authorization header set through GIT_CONFIG_COUNT/KEY/VALUE
   (git 2.31+), scoped by `http.<origin>/.extraheader` so git sends it to
   that host only, plus `git-credential-config` and no askpass program.
   extraheader is multi-valued, so an empty entry first drops any header the
   image or workspace config set for that host; otherwise git would send
   two Authorization headers.
   Env-supplied config is never written to .git/config, and exec passes the
   values in an env file, so the token lands in neither the capsule's repo
   state nor the runtime argv (N11 §2.2). An http origin also allows the
   http transport. Empty without a token or for a URL that is not http(s)."
  [https-url {:keys [user token]}]
  (if-let [[_ origin scheme] (when token (re-find #"^((https?)://[^/]+)(?:/|$)" https-url))]
    (merge (git-config-env (conj (cond-> git-credential-config
                                   (= "http" scheme) (conj ["protocol.http.allow" "always"]))
                                 [(str "http." origin "/.extraheader") ""]
                                 [(str "http." origin "/.extraheader")
                                  (basic-auth-header user token)]))
           {"GIT_TERMINAL_PROMPT" "0"
            "GIT_ASKPASS"         ""})
    {}))

(defn- ^{:stratum 1} without-git-config
  "`env` without its GIT_CONFIG_* tuple."
  [env]
  (into {} (remove (fn [[k _]] (re-matches git-config-env-pattern (name k)))) env))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} git-remote
  "The repo to bootstrap, from env-config: {:repo-url :https-url :host-kind},
   plus :url-auth when :repo-url carried `user:password@`, or nil when
   env-config has no :repo-url. Userinfo is stripped from both URLs, so it
   reaches neither the clone's argv nor origin's config. SSH URLs become
   HTTPS, since containers lack an SSH agent. The executor keeps this, not
   anything read back from the capsule, as the repository its git
   credential is for, so repointing origin inside the capsule cannot
   redirect the credential."
  [env-config]
  (when-let [given-url (:repo-url env-config)]
    (let [[repo-url url-auth] (split-userinfo given-url)]
      (cond-> {:repo-url  repo-url
               :https-url (if-let [[_ host path] (re-matches #"git@([^:]+):(.+)" repo-url)]
                            (str "https://" host "/" path)
                            repo-url)
               ;; `or`, not a get default: an explicit nil :host-kind means
               ;; infer it, as bootstrap always treated it.
               :host-kind (or (:host-kind env-config) (infer-host-kind repo-url))}
        url-auth (assoc :url-auth url-auth)))))

(defn ^{:stratum 2} combine-git-env
  "`base` then `extra`: `extra`'s GIT_CONFIG_* entries are numbered after
   base's GIT_CONFIG_COUNT, so they apply later and win, and base's entries
   stay as they are, including ones it counts but does not hold (the
   container's own, which its env keeps). Other variables merge, `extra`
   winning. Both maps' keys are read as names first, so entries a caller
   names by keyword are kept and renumbered like string-named ones."
  [base extra]
  (let [base    (string-keys base)
        extra   (string-keys extra)
        entries (git-config-entries extra)]
    (cond-> (merge base (without-git-config extra))
      (seq entries) (merge (git-config-env entries (git-config-count base))))))
