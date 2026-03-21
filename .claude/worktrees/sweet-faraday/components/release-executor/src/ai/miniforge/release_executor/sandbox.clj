(ns ai.miniforge.release-executor.sandbox
  "Sandbox operations for release executor.

   Mirrors the git.clj API but routes commands through the DAG executor's
   Docker backend. Used when the workflow runs in sandbox mode, where the
   container serves as an isolated workspace for file I/O, git ops, and PR creation."
  (:require
   [ai.miniforge.dag-executor.interface :as dag]
   [ai.miniforge.release-executor.result :as result]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0
;; Helpers

(defn exec!
  "Execute a command in the sandbox environment.
   Returns {:success? bool :output string :error string}."
  [executor env-id command]
  (let [r (dag/executor-execute! executor env-id command {:capture-output? true})]
    (if (dag/ok? r)
      (let [{:keys [exit-code stdout stderr]} (:data r)]
        (if (zero? exit-code)
          (result/shell-success {:output (str/trim (or stdout ""))})
          (result/shell-failure (str/trim (or stderr "")) {:output (str/trim (or stdout ""))})))
      (result/shell-failure (str "Executor error: " (:error r))))))

;------------------------------------------------------------------------------ Layer 1
;; Git / GH operations (mirrors git.clj)

(defn check-gh-auth!
  "Check if gh CLI is available and authenticated inside the container."
  [executor env-id]
  (let [r (exec! executor env-id "gh auth status")]
    (if (result/succeeded? r)
      {:available? true :authenticated? true :user "container-token"}
      {:available? true :authenticated? false :error (:error r)})))

(defn detect-default-branch
  "Detect the default branch from the remote."
  [executor env-id]
  (let [r (exec! executor env-id
                 "git symbolic-ref refs/remotes/origin/HEAD 2>/dev/null || echo refs/remotes/origin/main")]
    (-> (:output r "refs/remotes/origin/main")
        str/trim
        (str/replace #"refs/remotes/origin/" ""))))

(defn try-checkout-branch
  "Try to checkout a branch, retrying with timestamp suffix if it already exists."
  [executor env-id branch-name base-branch]
  (let [checkout-r (exec! executor env-id
                          (str "git checkout -b " branch-name " origin/" base-branch))]
    (if (result/succeeded? checkout-r)
      (result/shell-success {:branch branch-name :base-branch base-branch})
      (let [ts-name (str branch-name "-" (System/currentTimeMillis))
            retry-r (exec! executor env-id
                           (str "git checkout -b " ts-name " origin/" base-branch))]
        (if (result/succeeded? retry-r)
          (result/shell-success {:branch ts-name :base-branch base-branch})
          (result/shell-failure (str "Failed to create branch: " (:error retry-r))
                               {:branch nil}))))))

(defn create-branch!
  "Create a new git branch inside the sandbox container.
   Returns {:success? bool :branch string :base-branch string :error string}"
  [executor env-id branch-name]
  (let [default-branch (detect-default-branch executor env-id)
        fetch-r (exec! executor env-id (str "git fetch origin " default-branch))]
    (if-not (result/succeeded? fetch-r)
      (result/shell-failure (str "Failed to fetch: " (:error fetch-r)) {:branch nil})
      (try-checkout-branch executor env-id branch-name default-branch))))

(defn write-file!
  "Write content to a file inside the sandbox container.
   Uses base64 encoding to safely transfer arbitrary content."
  [executor env-id path content]
  (let [encoded (.encodeToString (java.util.Base64/getEncoder)
                                  (.getBytes content "UTF-8"))
        cmd (str "mkdir -p \"$(dirname '" path "')\" && "
                 "echo '" encoded "' | base64 -d > '" path "'")]
    (exec! executor env-id cmd)))

(defn delete-file!
  "Delete a file inside the sandbox container."
  [executor env-id path]
  (exec! executor env-id (str "rm -f '" path "'")))

(defn stage-files!
  "Stage files in the sandbox container."
  [executor env-id file-paths]
  (let [cmd (if (= file-paths :all)
              "git add ."
              (str "git add " (str/join " " (map #(str "'" % "'") file-paths))))]
    (exec! executor env-id cmd)))

(defn commit-changes!
  "Commit staged changes inside the sandbox container.

   Returns {:success? bool :commit-sha string :error string}"
  [executor env-id commit-message]
  (let [escaped-msg (str/replace commit-message "'" "'\\''")
        commit-r (exec! executor env-id (str "git commit -m '" escaped-msg "'"))]
    (if (result/succeeded? commit-r)
      (let [sha-r (exec! executor env-id "git rev-parse HEAD")]
        (result/shell-success {:commit-sha (:output sha-r "")
                               :output (:output commit-r)}))
      (result/shell-failure (:error commit-r) {:commit-sha nil}))))

(defn push-branch!
  "Push branch to origin inside the sandbox container."
  [executor env-id branch-name]
  (exec! executor env-id (str "git push -u origin " branch-name)))

(defn create-pr!
  "Create a pull request using gh CLI inside the sandbox container.
   Returns {:success? bool :pr-number int :pr-url string :error string}"
  [executor env-id {:keys [title body base-branch]}]
  (let [base (or base-branch "main")
        escaped-title (str/replace title "'" "'\\''")
        escaped-body (str/replace (or body "") "'" "'\\''")
        cmd (str "gh pr create"
                 " --title '" escaped-title "'"
                 " --body '" escaped-body "'"
                 " --base " base)
        r (exec! executor env-id cmd)]
    (if (result/succeeded? r)
      (let [pr-url (str/trim (:output r ""))
            pr-num (when-let [match (re-find #"/pull/(\d+)" pr-url)]
                     (parse-long (second match)))]
        (result/shell-success {:pr-url pr-url :pr-number pr-num}))
      (result/shell-failure (:error r) {:pr-url nil :pr-number nil}))))

;------------------------------------------------------------------------------ Layer 2
;; File batch operations (mirrors files.clj write-and-stage-files!)

(defn apply-file-operation!
  "Apply a single file operation (create, modify, delete) in the sandbox."
  [executor env-id {:keys [action path content]}]
  (case action
    :create (write-file! executor env-id path content)
    :modify (write-file! executor env-id path content)
    :delete (delete-file! executor env-id path)
    (result/shell-failure (str "Unknown action: " action))))

(defn track-operation
  "Update metrics for a completed file operation."
  [metrics {:keys [action path]} op-result]
  (if (result/succeeded? op-result)
    (case action
      :create (update metrics :created inc)
      :modify (update metrics :modified inc)
      :delete (update metrics :deleted inc)
      metrics)
    (update metrics :errors conj
           {:type :file-operation-failed
            :message (:error op-result)
            :file path
            :action action})))

(defn metrics->result
  "Convert operation metrics to a final result, staging files if no errors."
  [executor env-id written-paths {:keys [created modified deleted errors]}]
  (let [file-metrics {:files-written created
                      :files-modified modified
                      :files-deleted deleted}]
    (if (seq errors)
      {:success? false :errors errors :metrics file-metrics}
      (let [stage-r (stage-files! executor env-id written-paths)]
        (if (result/succeeded? stage-r)
          {:success? true
           :metrics (assoc file-metrics :total-operations (+ created modified deleted))}
          {:success? false
           :errors [{:type :git-stage-failed :message (:error stage-r)}]
           :metrics file-metrics})))))

(defn write-and-stage-files!
  "Write code artifact files into the sandbox and stage them.
   Returns result map matching files/write-and-stage-files! contract."
  [executor env-id code-artifacts]
  (let [all-files (mapcat :code/files code-artifacts)
        metrics (reduce
                 (fn [m file-op]
                   (let [r (apply-file-operation! executor env-id file-op)]
                     (track-operation m file-op r)))
                 {:created 0 :modified 0 :deleted 0 :errors []}
                 all-files)]
    (metrics->result executor env-id (map :path all-files) metrics)))
