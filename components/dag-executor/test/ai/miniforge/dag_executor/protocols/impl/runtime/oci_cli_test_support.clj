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
(ns ai.miniforge.dag-executor.protocols.impl.runtime.oci-cli-test-support
  "Helpers the OCI-CLI executor tests share: private-var access across the
   oci-cli namespaces, the runtime kinds under test, the fake token and its
   Basic header, env-file and git-config readers, an executor with a
   recorded remote, and the stand-in runtime CLI (`with-fake-runtime`)."
  (:require
   [ai.miniforge.dag-executor.protocols.impl.runtime.descriptor :as descriptor]
   [ai.miniforge.dag-executor.protocols.impl.runtime.oci-cli :as oci-cli]
   [clojure.string]))

;------------------------------------------------------------------------------ Layer 0

;; Private fn accessor helper
(defn ^{:stratum 0} private-fn [sym]
  (var-get (some #(ns-resolve % sym)
                ['ai.miniforge.dag-executor.protocols.impl.runtime.oci-cli
                 'ai.miniforge.dag-executor.protocols.impl.runtime.oci-cli.acquisition
                 'ai.miniforge.dag-executor.protocols.impl.runtime.oci-cli.capsule-git
                 'ai.miniforge.dag-executor.protocols.impl.runtime.oci-cli.container
                 'ai.miniforge.dag-executor.protocols.impl.runtime.oci-cli.env
                 'ai.miniforge.dag-executor.protocols.impl.runtime.oci-cli.git-credentials
                 'ai.miniforge.dag-executor.protocols.impl.runtime.oci-cli.image
                 'ai.miniforge.dag-executor.protocols.impl.runtime.oci-cli.launch
                 'ai.miniforge.dag-executor.protocols.impl.runtime.oci-cli.process])))

;; Phase 2: argument-construction tests run against every supported kind so
;; a Podman regression in flag shaping shows up at unit-test time.
(def ^{:stratum 0} supported-kinds-under-test
  [:docker :podman])

(def ^{:stratum 0} fake-token "tok-FAKE-0123456789")

(defn ^{:stratum 0} basic-auth [user token]
  (.encodeToString (java.util.Base64/getEncoder)
                   (.getBytes (str user ":" token) "UTF-8")))

(defn- ^{:stratum 0} prefixed-entries [prefix call]
  (into {} (keep #(when (.startsWith ^String % prefix)
                    (clojure.string/split (subs % (count prefix)) #"=" 2)))
        call))

(defn ^{:stratum 0} git-config-of
  "{key value} that GIT_CONFIG_COUNT / KEY_n / VALUE_n in `env` set."
  [env]
  (into {} (for [i (range (parse-long (get env "GIT_CONFIG_COUNT" "0")))]
             [(get env (str "GIT_CONFIG_KEY_" i)) (get env (str "GIT_CONFIG_VALUE_" i))])))

(defn ^{:stratum 0} env-file-path [argv]
  (second (drop-while #(not= "--env-file" %) argv)))

(defn ^{:stratum 0} recorded-remote-executor
  "An executor that bootstrapped `c1` from https://github.com/o/r.git."
  []
  (doto (oci-cli/create-docker-executor {:image "test:latest"})
    (-> :git-remotes (reset! {"c1" {:repo-url  "https://github.com/o/r.git"
                                    :https-url "https://github.com/o/r.git"
                                    :host-kind :github}}))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} parse-fake-runtime-log [lines]
  (->> lines
       (partition-by #(= "END" %))
       (remove #(= ["END"] %))
       (mapv (fn [call]
               {:argv     (vec (keep #(when (.startsWith ^String % "ARG ") (subs % 4)) call))
                :env      (prefixed-entries "ENV " call)
                :env-file (prefixed-entries "FILE " call)}))))

(defn ^{:stratum 1} token-in? [s]
  (or (clojure.string/includes? s fake-token)
      (clojure.string/includes? s (basic-auth "x-access-token" fake-token))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} with-fake-runtime
  "Run (f descriptor calls) against a stand-in runtime CLI for `kind`: a
   script that logs each invocation's argv, the contents of any
   `--env-file`, and the values of `env-names` in its own environment (line
   breaks shown as |), then prints a fake id. `calls` returns the
   invocations as [{:argv [...] :env {name value} :env-file {name value}}]:
   what `ps`, the CLI's environment and the container would see."
  [kind env-names f]
  (let [dir    (.toFile (java.nio.file.Files/createTempDirectory
                         "fake-runtime" (make-array java.nio.file.attribute.FileAttribute 0)))
        log    (java.io.File. dir "calls.log")
        script (java.io.File. dir "runtime")]
    (try
      (spit script
            (str "#!/bin/sh\n"
                 "log='" log "'\n"
                 "prev=''\n"
                 "for a in \"$@\"; do\n"
                 "  printf 'ARG %s\\n' \"$a\" >> \"$log\"\n"
                 "  [ \"$prev\" = --env-file ] && sed 's/^/FILE /' \"$a\" >> \"$log\"\n"
                 "  prev=\"$a\"\n"
                 "done\n"
                 (apply str (for [n env-names]
                              (str "[ -n \"${" n "+x}\" ] && printf 'ENV " n "=%s\\n' "
                                   "\"$(printf '%s' \"$" n "\" | tr '\\n' '|')\" >> \"$log\"\n")))
                 "echo END >> \"$log\"\n"
                 "echo abc123\n"))
      (.setExecutable script true)
      (f (descriptor/make-descriptor {:runtime-kind kind :executable (str script)})
         #(if (.exists log) (parse-fake-runtime-log (clojure.string/split-lines (slurp log))) []))
      (finally
        (run! #(.delete ^java.io.File %) (reverse (file-seq dir)))))))
