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

(defn- exec!
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
    (if (:success? r)
      {:available? true :authenticated? true :user "container-token"}
      ;; gh auth status exits non-zero when not authed
      {:available? true :authenticated? false :error (:error r)})))

(defn create-branch!
  "Create a new git branch inside the sandbox container.
   Returns {:success? bool :branch string :base-branch string :error string}"
  [executor env-id branch-name]
  (let [;; Determine default branch
        default-r (exec! executor env-id
                         "git symbolic-ref refs/remotes/origin/HEAD 2>/dev/null || echo refs/remotes/origin/main")
        default-branch (-> (:output default-r "refs/remotes/origin/main")
                           str/trim
                           (str/replace #"refs/remotes/origin/" ""))
        ;; Fetch latest
        fetch-r (exec! executor env-id (str "git fetch origin " default-branch))]
    (if-not (:success? fetch-r)
      (result/shell-failure (str "Failed to fetch: " (:error fetch-r)) {:branch nil})
      (let [checkout-r (exec! executor env-id
                              (str "git checkout -b " branch-name " origin/" default-branch))]
        (if (:success? checkout-r)
          (result/shell-success {:branch branch-name :base-branch default-branch})
          ;; Retry with timestamp suffix
          (let [ts-name (str branch-name "-" (System/currentTimeMillis))
                retry-r (exec! executor env-id
                               (str "git checkout -b " ts-name " origin/" default-branch))]
            (if (:success? retry-r)
              (result/shell-success {:branch ts-name :base-branch default-branch})
              (result/shell-failure (str "Failed to create branch: " (:error retry-r))
                                   {:branch nil}))))))))

(defn write-file!
  "Write content to a file inside the sandbox container.
   Uses base64 encoding to safely transfer arbitrary content."
  [executor env-id path content]
  (let [encoded (.encodeToString (java.util.Base64/getEncoder)
                                  (.getBytes content "UTF-8"))
        ;; Ensure parent directory exists, then decode base64 into file
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
  (let [;; Escape single quotes in commit message
        escaped-msg (str/replace commit-message "'" "'\\''")
        commit-r (exec! executor env-id (str "git commit -m '" escaped-msg "'"))]
    (if (:success? commit-r)
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
        ;; Escape single quotes in title and body
        escaped-title (str/replace title "'" "'\\''")
        escaped-body (str/replace (or body "") "'" "'\\''")
        cmd (str "gh pr create"
                 " --title '" escaped-title "'"
                 " --body '" escaped-body "'"
                 " --base " base)
        r (exec! executor env-id cmd)]
    (if (:success? r)
      (let [pr-url (str/trim (:output r ""))
            pr-num (when-let [match (re-find #"/pull/(\d+)" pr-url)]
                     (parse-long (second match)))]
        (result/shell-success {:pr-url pr-url :pr-number pr-num}))
      (result/shell-failure (:error r) {:pr-url nil :pr-number nil}))))

;------------------------------------------------------------------------------ Layer 2
;; File batch operations (mirrors files.clj write-and-stage-files!)

(defn write-and-stage-files!
  "Write code artifact files into the sandbox and stage them.
   Returns result map matching files/write-and-stage-files! contract."
  [executor env-id code-artifacts]
  (let [results (atom {:created 0 :modified 0 :deleted 0 :errors []})
        all-files (mapcat :code/files code-artifacts)]

    (doseq [{:keys [action path content]} all-files]
      (let [r (case action
                :create (write-file! executor env-id path content)
                :modify (write-file! executor env-id path content)
                :delete (delete-file! executor env-id path)
                (result/shell-failure (str "Unknown action: " action)))]
        (if (:success? r)
          (case action
            :create (swap! results update :created inc)
            :modify (swap! results update :modified inc)
            :delete (swap! results update :deleted inc)
            nil)
          (swap! results update :errors conj
                 {:type :file-operation-failed
                  :message (:error r)
                  :file path
                  :action action}))))

    (let [{:keys [created modified deleted errors]} @results
          total-operations (+ created modified deleted)]
      (if (seq errors)
        {:success? false
         :errors errors
         :metrics {:files-written created :files-modified modified :files-deleted deleted}}
        (let [stage-r (stage-files! executor env-id :all)]
          (if (:success? stage-r)
            {:success? true
             :metrics {:files-written created :files-modified modified :files-deleted deleted
                       :total-operations total-operations}}
            {:success? false
             :errors [{:type :git-stage-failed :message (:error stage-r)}]
             :metrics {:files-written created :files-modified modified :files-deleted deleted}}))))))
