(ns ai.miniforge.task.builder
  "Canonical builders for task maps.

   Eliminates scattered inline task construction by providing
   standard builders for common task types.")

;------------------------------------------------------------------------------ Layer 0
;; Base task builder

(defn task
  "Create a canonical task map.

   Arguments:
   - type: Task type keyword (:verify, :implement, etc.)
   - opts: Map with optional keys:
     - :id - Task ID (defaults to random-uuid)
     - :title - Task title string
     - :description - Task description
     - :intent - Intent declaration
     - :constraints - Constraint vector
     - :code - Code artifact for the task
     - :spec - Spec/metadata map
     - :artifact - Artifact to process
     - :tests - Test results
     - Additional keys passed through

   Returns:
   {:task/id uuid
    :task/type keyword
    ...additional fields...}

   Examples:
     (task :verify {:code artifact :spec {:title \"Test generation\"}})
     (task :review {:artifact code :tests test-results})"
  [type opts]
  (let [base {:task/id (or (:id opts) (random-uuid))
              :task/type type}]
    (cond-> base
      ;; Standard fields with task/ namespace
      (:title opts) (assoc :task/title (:title opts))
      (:description opts) (assoc :task/description (:description opts))
      (:intent opts) (assoc :task/intent (:intent opts))
      (:constraints opts) (assoc :task/constraints (:constraints opts))

      ;; Data fields without namespace (expected by agents)
      (:code opts) (assoc :code (:code opts))
      (:spec opts) (assoc :spec (:spec opts))
      (:artifact opts) (assoc :artifact (:artifact opts))
      (:tests opts) (assoc :tests (:tests opts))

      ;; Pass through any other keys
      true (merge (dissoc opts :id :type :title :description :intent
                          :constraints :code :spec :artifact :tests)))))

;------------------------------------------------------------------------------ Layer 1
;; Specific task type builders

(defn verify-task
  "Create a verification task.

   Arguments:
   - code-artifact: Code to generate tests for
   - spec: Spec map with metadata (title, description, acceptance criteria, etc.)

   Returns canonical task map for tester agent.

   Example:
     (verify-task code-artifact
                  {:title \"Generate tests\"
                   :description \"Create unit tests\"
                   :task/acceptance-criteria [...]})"
  [code-artifact spec]
  (task :verify
        {:code code-artifact
         :spec (merge spec
                     {:description (:description spec)
                      :title (:title spec)
                      :intent (:intent spec)
                      :constraints (:constraints spec)
                      :task/acceptance-criteria (:task/acceptance-criteria spec)})}))

(defn review-task
  "Create a review task.

   Arguments:
   - artifact: Code artifact to review
   - opts: Map with :tests, :spec, etc.

   Returns canonical task map for reviewer agent.

   Example:
     (review-task code-artifact {:tests test-results :spec {...}})"
  [artifact opts]
  (task :review
        (merge {:artifact artifact}
               (select-keys opts [:tests :spec :title :description :intent :constraints]))))

(defn implement-task
  "Create an implementation task.

   Arguments:
   - spec: Implementation spec with requirements

   Returns canonical task map for implementer agent.

   Example:
     (implement-task {:title \"Add login\"
                      :description \"Implement user login\"
                      :constraints [...]})"
  [spec]
  (task :implement spec))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Generic task
  (task :verify {:code {:code/files []}
                 :spec {:title "Generate tests"}})

  ;; Verify task
  (def example-code {:code/id 123 :code/files []})
  (verify-task example-code
               {:title "Test generation"
                :description "Generate unit tests"
                :task/acceptance-criteria ["80% coverage"]})
  ;; => {:task/id #uuid "..."
  ;;     :task/type :verify
  ;;     :code {:code/id 123 ...}
  ;;     :spec {:description "Generate unit tests"
  ;;            :title "Test generation"
  ;;            :task/acceptance-criteria [...]}}

  ;; Review task
  (def example-tests {:test/id 456})
  (review-task example-code
               {:tests example-tests
                :spec {:title "Code review"}})

  :end)
