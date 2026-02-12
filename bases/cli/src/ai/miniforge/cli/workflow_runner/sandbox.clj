(ns ai.miniforge.cli.workflow-runner.sandbox
  "Docker sandbox setup for isolated workflow execution."
  (:require
   [clojure.string :as str]
   [babashka.process :as p]
   [ai.miniforge.cli.workflow-runner.display :as display]
   [ai.miniforge.dag-executor.interface :as dag]))

;------------------------------------------------------------------------------ Layer 0
;; Sandbox helpers

(defn- sandbox-release-fn [executor environment-id]
  (fn []
    (try
      (dag/release-environment! executor environment-id)
      (catch Exception _ nil))))

(defn- infer-repo-url [spec enriched-spec]
  (or (get-in spec [:spec/raw-data :repo-url])
      (get-in enriched-spec [:spec/context :repo-url])
      (try
        (str/trim (:out (p/shell {:out :string :err :string :continue true}
                                 "git" "remote" "get-url" "origin")))
        (catch Exception _ nil))))

(defn- infer-branch [spec enriched-spec]
  (or (get-in spec [:spec/raw-data :branch])
      (get-in enriched-spec [:spec/context :git-branch])
      "main"))

;------------------------------------------------------------------------------ Layer 1
;; Sandbox preparation

(defn- prepare-sandbox [spec enriched-spec]
  (let [prep-result (dag/prepare-docker-executor! {:image-type :clojure})]
    (if-not (dag/ok? prep-result)
      prep-result
      (let [executor (:executor (dag/unwrap prep-result))
            gh-token (System/getenv "GH_TOKEN")
            env-config (cond-> {} gh-token (assoc :env {:GH_TOKEN gh-token}))
            env-result (dag/acquire-environment! executor (random-uuid) env-config)]
        (if-not (dag/ok? env-result)
          env-result
          (let [env-id (:environment-id (dag/unwrap env-result))
                repo-url (infer-repo-url spec enriched-spec)
                branch (infer-branch spec enriched-spec)]
            (when repo-url
              (dag/clone-and-checkout! executor env-id repo-url branch {}))
            (dag/ok {:executor executor
                     :environment-id env-id
                     :sandbox-workdir "/workspace"})))))))

;------------------------------------------------------------------------------ Layer 2
;; Sandbox context setup

(defn setup-sandbox-context [base-context sandbox? spec enriched-spec quiet]
  (if-not sandbox?
    [base-context nil]
    (do
      (when-not quiet
        (println (display/colorize :yellow "🐳 Setting up sandbox container...")))
      (let [result (prepare-sandbox spec enriched-spec)]
        (if-not (dag/ok? result)
          [(assoc base-context :sandbox-error result) nil]
          (let [{:keys [executor environment-id sandbox-workdir]} (dag/unwrap result)]
            (when-not quiet
              (println (display/colorize :green "  ✓ Sandbox container ready")))
            [(assoc base-context
                    :executor executor
                    :environment-id environment-id
                    :sandbox-workdir sandbox-workdir)
             (sandbox-release-fn executor environment-id)]))))))
