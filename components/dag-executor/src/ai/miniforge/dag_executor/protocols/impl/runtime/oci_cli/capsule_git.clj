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
(ns ai.miniforge.dag-executor.protocols.impl.runtime.oci-cli.capsule-git
  "Git inside the capsule for the OCI-CLI executor: the credential env for
   one command, numbered after the container's own git config, the workspace
   bootstrap clone, and the exec-fn persist and restore run git through."
  (:require
   [ai.miniforge.dag-executor.protocols.impl.runtime.messages :as messages]
   [ai.miniforge.dag-executor.protocols.impl.runtime.oci-cli.container
    :refer [chain-container-command container-exec-fn container-git-count]]
   [ai.miniforge.dag-executor.protocols.impl.runtime.oci-cli.git-credentials
    :refer [combine-git-env credential-git-check git-auth git-config-entries git-credential-env
            git-network-command? git-remote git-trace-off-env]]
   [ai.miniforge.dag-executor.result :as result]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} workspace-opts
  "persist/restore opts. With a recorded remote, push and fetch target its
   repository URL rather than the capsule's `origin`, which untrusted code
   can repoint at another repository on the same host."
  [opts git-remotes environment-id]
  (if-let [url (some-> git-remotes deref (get-in [environment-id :https-url]))]
    (assoc opts :remote url)
    opts))

(defn ^{:stratum 0} with-git-credentials
  "execute! opts: when they ask for :git-credentials?, `cred-env` (a delay
   of the recorded remote's credential env) joins :env. Release's
   in-capsule fetch and push use this; since origin carries no credential,
   they are unauthenticated without it. The two GIT_CONFIG_* blocks are
   combined (`combine-git-env`), the caller's last, so a caller's own git
   config, such as release's push retry, is kept and wins. An env left
   holding GIT_CONFIG_* entries gets `git-trace-off-env` last, so a
   caller's own header is not traced when no credential resolved."
  [opts cred-env]
  (let [opts (cond-> opts
               (:git-credentials? opts) (update :env #(combine-git-env @cred-env %)))]
    (cond-> opts
      (seq (git-config-entries (:env opts))) (update :env merge git-trace-off-env))))

(defn- ^{:stratum 0} capsule-credential-env
  "`git-credential-env` for one command in `container-id`, its GIT_CONFIG_*
   entries numbered after the container's own (`container-git-count`), so
   a trusted launch's or image's tuple (an http.proxy) stays in effect from
   the container env, unsent, and the credential's come last and win.
   `git-trace-off-env` keeps an inherited trace setting from writing the
   header to a file the capsule can read."
  [descriptor container-id https-url auth]
  (let [cred (git-credential-env https-url auth)]
    (if (seq cred)
      (merge (combine-git-env {"GIT_CONFIG_COUNT" (str (container-git-count descriptor container-id))}
                              cred)
             git-trace-off-env)
      cred)))

(defn ^{:stratum 0} workspace-exec-fn
  "exec-fn for `workspace/git-persist!` and `git-restore!`. `cred-env` is a
   delay that only push and fetch force: a persist with nothing to push
   resolves no token, and add, status and commit, which run hooks and
   fsmonitor the untrusted workspace defines, never see the credential."
  [descriptor container-id workdir cred-env]
  (let [exec! (container-exec-fn descriptor container-id workdir)]
    (fn [cmd]
      (exec! cmd (when (git-network-command? cmd) @cred-env)))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} remote-credential-env
  "Credential env (`capsule-credential-env`) for the git remote recorded
   when `environment-id` was bootstrapped, or {} when none was recorded."
  [descriptor git-remotes environment-id]
  (if-let [remote (some-> git-remotes deref (get environment-id))]
    (capsule-credential-env descriptor environment-id (:https-url remote) (git-auth remote))
    {}))

(defn ^{:stratum 1} bootstrap-workspace!
  "Clone a git repository into the container workspace after creation.
   Runs git clone, configures git identity, and checks out the target branch.
   Returns a result map with bootstrap metadata. No-op success when
   repo-url is nil (local mode / pre-existing workspace).
   Uses config-based token resolution (profile + env fallback) rather than
   URL-guessing. With a credential, checks the image's git can take it
   (`credential-git-check`), clones the plain URL and hands the credential
   to the clone command alone (`capsule-credential-env`): origin's URL in the
   capsule carries none, and no push URL is set. Clones every branch, so
   release can compare against a base other than the checked-out branch."
  [descriptor container-name workdir env-config]
  (if-let [{:keys [repo-url https-url] :as remote} (git-remote env-config)]
    (let [branch    (get env-config :branch "main")
          auth      (git-auth remote)
          clone-url (if auth https-url repo-url)
          exec!  (container-exec-fn descriptor container-name "/"
                                    :error-on-failure? true
                                    :error-prefix (messages/t :oci/bootstrap-failed)
                                    :sanitize? true)
          clone-cmd ["git" "clone" "--branch" (str branch) "--" clone-url workdir]
          result (-> (if auth (credential-git-check (exec! ["git" "--version"])) (result/ok nil))
                     (chain-container-command
                      #(exec! clone-cmd (capsule-credential-env descriptor container-name https-url auth)))
                     ;; Use --local (not --global) since container rootfs is read-only
                     (chain-container-command
                      #(exec! ["git" "-C" workdir "config" "user.email" "miniforge@miniforge.ai"]))
                     (chain-container-command
                      #(exec! ["git" "-C" workdir "config" "user.name" "miniforge"])))]
      (if (result/err? result)
        result
        (let [sha-r (exec! ["git" "-C" workdir "rev-parse" "HEAD"])]
          (if (result/ok? sha-r)
            (let [base-sha (str/trim (get-in sha-r [:data :stdout] ""))]
              (result/ok (cond-> {:base-branch (str branch)}
                           (seq base-sha) (assoc :base-sha base-sha))))
            sha-r))))
    (result/ok nil)))
