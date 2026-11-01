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
(ns ai.miniforge.dag-executor.protocols.impl.runtime.oci-cli.env
  "Container environment for the OCI-CLI executor: the env file that keeps
   values off the runtime argv, trust-level filtering of the env, and reading
   image ENV."
  (:require
   [ai.miniforge.dag-executor.execution-plan :as execution-plan]
   [ai.miniforge.dag-executor.protocols.impl.runtime.messages :as messages]
   [ai.miniforge.dag-executor.protocols.impl.runtime.oci-cli.git-credentials
    :refer [git-config-env-pattern]]
   [ai.miniforge.redaction.interface :as redaction]
   [clojure.edn :as edn]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} env-file-entry?
  "True when an env entry fits an env file. Docker and Podman read one
   NAME=value line at a time and take the value literally, so the value
   must have no line break and the name must match
   `execution-plan/env-name-pattern`: a line break in it would start a
   second assignment the plan does not show, and a bare NAME line copies
   that variable from the runtime CLI's own environment."
  [[k v]]
  (and (re-find execution-plan/env-name-pattern (name k))
       (not (re-find #"[\r\n]" (str v)))))

(defn- ^{:stratum 0} write-env-file!
  "Write `entries` as NAME=value lines to a new temp file for `--env-file`.
   Files/createTempFile makes it owner-only (rw-------) on POSIX."
  ^java.io.File [entries]
  (let [path (java.nio.file.Files/createTempFile
              "miniforge-env-" ".env"
              (make-array java.nio.file.attribute.FileAttribute 0))]
    (spit (.toFile path) (str/join (for [[k v] entries] (str (name k) "=" v "\n"))))
    (.toFile path)))

(defn ^{:stratum 0} run-with-runtime-env
  "Call (run-fn), then delete the env file `prepare-runtime-env!` wrote.
   An env it refused is a failed runtime call (exit 1) carrying the reason,
   and run-fn does not run."
  [{:keys [file error]} run-fn]
  (if error
    {:exit 1 :out "" :err error :out-bytes (byte-array 0) :err-bytes (byte-array 0)}
    (try
      (run-fn)
      (finally
        (when file (.delete ^java.io.File file))))))

(def ^{:stratum 0} untrusted-env-names
  "Env vars an untrusted capsule takes from env-config whatever their value:
   what a shell and its locale need. LC_* and CLAUDE_CODE_* knobs pass by
   prefix (`untrusted-env-allowed?`)."
  #{"PATH" "HOME" "USER" "LOGNAME" "SHELL" "LANG" "LANGUAGE" "TERM" "COLORTERM"
    "TZ" "TMPDIR" "CI" "NO_COLOR"})

(def ^{:stratum 0} credential-word-pattern
  "A `_`-separated word that makes a variable a credential: it keeps a
   CLAUDE_CODE_* name out of the allowlist (CLAUDE_CODE_OAUTH_TOKEN, but not
   the knob CLAUDE_CODE_MAX_OUTPUT_TOKENS) and blanks an image's ENV entry
   (DEPLOY_KEY, GITHUB_PAT, MYSQL_PWD, NPM_CONFIG__AUTH)."
  #"(?i)(?:^|_)(?:TOKEN|KEY|SECRET|PASS|PASSWORD|PASSPHRASE|PWD|PAT|AUTH|AUTHORIZATION|CREDENTIALS?|OAUTH|COOKIE|WEBHOOK)(?:_|$)")

(defn- ^{:stratum 0} proxy-without-userinfo
  "Proxy setting `v` without `user:password@`: everything from the scheme
   to the last `@` before the path goes. A value that still holds an `@`
   or a line break, which this cannot parse safely, becomes empty, so the
   result is safe on argv."
  [v]
  (let [s (str/replace (str/trim (str v)) #"^([A-Za-z][A-Za-z0-9+.-]*://)?[^/]*@" "$1")]
    (if (re-find #"[@\r\n]" s) "" s)))

(def ^{:stratum 0} proxy-env-names
  "Proxy variables curl and git read, in both cases. Docker adds them to a
   container from the client's ~/.docker/config.json, and Podman from its
   own environment, unless the launch sets the name with -e."
  (into #{} (mapcat (juxt identity str/lower-case))
        ["HTTP_PROXY" "HTTPS_PROXY" "FTP_PROXY" "ALL_PROXY" "NO_PROXY"]))

(def ^{:stratum 0} agent-credential-names
  "Env vars N11 §8.1 injects into every capsule, whatever its trust level,
   for the agent itself: ANTHROPIC_API_KEY, the primary secret for current
   tasks. `filter-env-by-trust` keeps these, and `build-launch-plan` adds
   the agent backend's own (`llm/backend-credential-env`)."
  #{"ANTHROPIC_API_KEY"})

(defn ^{:stratum 0} refused-env-name?
  "True for a container env name no launch or exec passes, whatever the
   trust level: a non-identifier, which Podman's `-e NAME` reads as a
   pattern over its own environment, or DOCKER_CUSTOM_HEADERS, a docker CLI
   variable `env-arg` would put inline on argv for `ps` to show (by name,
   it would set the CLI's own headers)."
  [n]
  (or (= "DOCKER_CUSTOM_HEADERS" n)
      (not (re-find execution-plan/env-name-pattern n))))

;; ============================================================================
;; Capsule security gate — runtime enforcement of the runtime-* policies
;; ============================================================================
(defn ^{:stratum 0} normalize-plan-env
  "Normalize an env-config :env map to the string→string shape
   ExecutionPlanSchema requires: keyword keys via `name`, values via `str`
   (a numeric port or boolean flag becomes its printed form — the same
   text the container would see on the wire). The plan is the canonical,
   schema-conformant record of the launch, so both normalize here — once,
   at the writer — rather than at every reader."
  [env]
  (into {}
        (map (fn [[k v]] [(name k) (str v)]))
        env))

(def ^{:stratum 0} env-inspect-format
  "`inspect --format` template printing each Config.Env entry as a JSON
   string on its own line, so a value with a line break stays one entry."
  "{{range .Config.Env}}{{json .}}{{println}}{{end}}")

(defn ^{:stratum 0} parse-inspected-env
  "{name value} from `env-inspect-format` output: each line is a JSON string,
   which the EDN reader reads, split once at the first `=`."
  [out]
  (into {} (map #(let [[k v] (str/split (edn/read-string %) #"=" 2)] [k (or v "")]))
        (remove str/blank? (str/split-lines out))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} untrusted-env-allowed?
  "True when an untrusted capsule may take env var `n` from env-config:
   `untrusted-env-names`, LC_*, or a CLAUDE_CODE_* knob with no
   `credential-word-pattern` word."
  [n]
  (or (contains? untrusted-env-names n)
      (str/starts-with? n "LC_")
      (and (str/starts-with? n "CLAUDE_CODE_")
           (not (re-find credential-word-pattern n)))))

(defn ^{:stratum 1} prepare-runtime-env!
  "Deliver env-map to the container without putting its values on the
   runtime argv (`ps` shows argv to every host user) or into the runtime
   CLI's own environment (where LD_PRELOAD or DOCKER_DEFAULT_PLATFORM would
   change the CLI itself): entries go to an owner-only temp file passed as
   `--env-file`. Returns {:args :file}; the caller deletes :file with
   `run-with-runtime-env`. An env file holds one line per entry, and every
   other channel exposes the value, so env-map with an entry that does not
   fit one line (`env-file-entry?`) is refused: {:error message}, and
   nothing is written."
  [env-map]
  (if-let [bad (seq (remove env-file-entry? env-map))]
    {:error (messages/t :oci/env-line-break {:names (str/join ", " (map (comp pr-str name key) bad))})}
    (let [file (when (seq env-map) (write-env-file! env-map))]
      {:args (when file ["--env-file" (str file)])
       :file file})))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} untrusted-env-blanks
  "Empty values that override, in an untrusted capsule, what it would get
   from outside env-config: every proxy variable (`proxy-env-names`), and
   each entry of `image-env` (the image's ENV, {name value}) whose name the
   redaction policy calls a secret or has a `credential-word-pattern` word,
   whose value has a secret's shape (`redaction/secret-string?`: a token,
   a URL with `user:password@`), or that is GIT_CONFIG_PARAMETERS or in a
   GIT_CONFIG_* tuple, unless `keep-names` or `untrusted-env-allowed?`
   allow it. The image's own toolchain settings (JAVA_HOME and the like)
   are left alone, so this side is a denylist."
  [image-env keep-names]
  (into (zipmap proxy-env-names (repeat ""))
        (for [[n v] image-env
              :when (and (not (contains? keep-names n))
                         (not (untrusted-env-allowed? n))
                         (or (redaction/secret-key? n)
                             (re-find credential-word-pattern n)
                             (redaction/secret-string? v)
                             (re-matches git-config-env-pattern n)
                             (= "GIT_CONFIG_PARAMETERS" n)))]
          [n ""])))

(defn ^{:stratum 2} filter-env-by-trust
  "Return env-map as an untrusted capsule may have it, unless trust-level is
   :trusted or :privileged; :untrusted, nil and any unknown level all filter.
   `build-launch-plan` applies it to the container env, which every process
   in the capsule inherits; host-side callers that need a secret for one
   command (release's GH_TOKEN for `gh`) pass it on that exec instead.

   Default deny: an entry stays only when its name is in `keep-names` (the
   agent's own credentials, `agent-credential-names` unless given, which
   N11 §8.1 injects at every trust level) or `untrusted-env-allowed?`. A
   proxy variable (`proxy-env-names`) stays without any `user:password@`
   (`proxy-without-userinfo`), so an authenticating proxy needs a trusted
   launch."
  ([env-map trust-level] (filter-env-by-trust env-map trust-level agent-credential-names))
  ([env-map trust-level keep-names]
   (if (contains? #{:trusted :privileged} trust-level)
     env-map
     (into {} (keep (fn [[k v :as entry]]
                      (let [n (name k)]
                        (cond
                          (contains? proxy-env-names n) [k (proxy-without-userinfo v)]
                          (or (contains? keep-names n) (untrusted-env-allowed? n)) entry))))
           env-map))))
