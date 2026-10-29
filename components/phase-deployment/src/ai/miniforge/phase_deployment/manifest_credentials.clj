;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-deployment.manifest-credentials
  "Find plaintext credentials in rendered Kubernetes manifests by their
   structure, not their text. Detector for the deployment-safety pack's
   :deploy/no-secrets-in-manifests rule.

   Per YAML document:
   1. kind Secret with a literal `data` or `stringData` value is a finding,
      unless the rule's :detector-config sets :allow-secret-data? true.
   2. A container env entry whose name is a credential name and that has a
      literal `value` (not `valueFrom`) is a finding.
   3. A ConfigMap `data` key that is a credential name with a non-empty
      literal value is a finding; multiword values count. A data value that
      is a whole file (application.yaml, application.properties) is also
      scanned with the rule's line patterns, so `password: \"...\"` and
      `db.password=...` inside it are findings, unless the value the line
      assigns is a placeholder (rule 5).
   4. Annotations and labels (`metadata`) are never read.
   5. One rule decides what is literal, for fields and file lines alike: a
      placeholder-only value, `$(NAME)`, `${NAME}` or `{{ ... }}`, is not.
   A literal value holding a PEM private key is a finding wherever rules 2
   and 3 look. Findings name where the value is, never the value.

   Content that is not YAML, or YAML documents that are not manifests (maps),
   falls back to the rule's line patterns (:detector-config :fallback-patterns)."
  (:require [ai.miniforge.phase-deployment.messages :as msg]
            [ai.miniforge.policy-pack.interface :as policy-pack]
            [clj-yaml.core :as yaml]
            [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} credential-name?
  "True when a key or env var name ends in a credential term: DB_PASSWORD,
   apiKey, client-secret, AWS_SECRET_ACCESS_KEY. secretName, passwordRef and
   ACCESS_KEY_ID end in another word and are not credentials."
  [s]
  (let [terms #{["password"] ["passwd"] ["pwd"] ["pass"] ["passphrase"] ["secret"]
                ["token"] ["credential"] ["credentials"] ["apikey"]
                ["api" "key"] ["access" "key"] ["private" "key"] ["secret" "key"]}
        tokens (->> (str/split (str/replace (str s) #"([a-z0-9])([A-Z])" "$1 $2")
                               #"[^A-Za-z0-9]+")
                    (remove str/blank?)
                    (mapv str/lower-case))]
    (boolean (some #(= % (vec (take-last (count %) tokens))) terms))))

(defn ^{:stratum 0} literal-value?
  "A non-empty literal value. A placeholder-only value is not one: a reference
   to another env var, `$(NAME)` or `${NAME}`, or a template, `{{ ... }}`. A
   placeholder carrying a default, `${DB_PASSWORD:hunter2}`, holds a literal."
  [v]
  (and (some? v)
       (not (coll? v))
       (not (str/blank? (str v)))
       (not (re-matches #"\$\([A-Za-z_]\w*\)|\$\{[A-Za-z_]\w*\}|\{\{[^{}]*\}\}"
                        (str/trim (str v))))))

(defn- ^{:stratum 0} reference?
  "True when valueFrom names a source. A null or empty valueFrom is no source,
   so the entry's literal value is what the container gets."
  [value-from]
  (and (map? value-from)
       (boolean (some #(map? (get value-from %))
                      ["secretKeyRef" "configMapKeyRef" "fieldRef" "resourceFieldRef"]))))

(defn- ^{:stratum 0} private-key? [v]
  (and (string? v) (str/includes? v "-----BEGIN") (str/includes? v "PRIVATE KEY")))

(defn- ^{:stratum 0} env-entries
  "Every env entry in a document's pod specs, wherever the workload nests them.
   metadata (annotations and labels) is not walked."
  [document]
  (->> (tree-seq coll? seq (dissoc document "metadata"))
       (filter map?)
       (keep #(get % "env"))
       (filter sequential?)
       (mapcat identity)
       (filter map?)))

(defn- ^{:stratum 0} fallback-match?
  "Does any line match a fallback pattern? With `literal?`, a matching line
   counts only when the value it assigns is literal by that rule, or it assigns
   none. The assigned value is a quoted value's content, or a plain value up
   to a ` #` comment."
  ([patterns content] (fallback-match? patterns content (constantly true)))
  ([patterns content literal?]
   (let [compiled (map re-pattern patterns)
         assigned (fn [line]
                    (when-let [[_ v] (re-find #"[:=]\s*(.*)$" line)]
                      (if-let [[_ _ quoted] (re-find #"^([\"'])(.*?)\1" v)]
                        quoted
                        (str/trim (first (str/split v #"\s+#" 2))))))
         counts? (fn [line]
                   (and (some #(re-find % line) compiled)
                        (let [value (assigned line)] (or (nil? value) (literal? value)))))]
     (boolean (some counts? (str/split-lines (str content)))))))

(defn- ^{:stratum 0} documents
  "Parse rendered multi-document YAML, or nil when it is not YAML."
  [content]
  (try
    (vec (remove nil? (yaml/parse-string (str content) :load-all true :keywords false)))
    (catch Exception _ nil)))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} document-findings
  [allow-secret-data? patterns document]
  (let [kind (get document "kind")
        label (str kind "/" (get-in document ["metadata" "name"]))
        secret (when (and (= "Secret" kind) (not allow-secret-data?))
                 (for [field ["data" "stringData"]
                       [k v] (get document field)
                       :when (literal-value? v)]
                   (str label ": " field "." k)))
        config (when (= "ConfigMap" kind)
                 (for [[k v] (get document "data")
                       :when (or (and (credential-name? k) (literal-value? v))
                                 (private-key? v)
                                 (and (string? v) (fallback-match? patterns v literal-value?)))]
                   (str label ": data." k)))
        env (for [entry (env-entries document)
                  :let [value (get entry "value")]
                  :when (and (not (reference? (get entry "valueFrom")))
                             (literal-value? value)
                             (or (credential-name? (get entry "name")) (private-key? value)))]
              (str label ": env " (get entry "name")))]
    (concat secret config env)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} check
  "Custom detector: (check artifact context). Returns a violation naming where
   plaintext credentials sit in the rendered manifests, or nil."
  [artifact context]
  (let [content (or (:artifact/content artifact) (:content artifact))
        config (get-in context [:policy-pack/rule :rule/detection :detector-config])
        parsed (documents content)
        patterns (:fallback-patterns config [])
        structural (mapcat (partial document-findings (true? (:allow-secret-data? config)) patterns)
                           (filter map? parsed))
        ;; Not YAML, or YAML that is not all manifests: the line patterns apply.
        fallback (when (and (or (nil? parsed) (not-every? map? parsed))
                            (fallback-match? patterns content))
                   [(msg/t :policy/manifest-not-yaml)])
        findings (vec (concat structural fallback))]
    (when (seq findings)
      {:message (msg/t :policy/manifest-credentials {:findings (str/join "; " findings)})
       :findings findings})))

;; Register at load, as the deployment policy detectors do, so the pack's
;; :custom-fn resolves deterministically.
(policy-pack/register-custom-fn! 'ai.miniforge.phase-deployment.manifest-credentials/check check)

(comment
  (check {:artifact/content "kind: ConfigMap\ndata:\n  password: hunter2\n"} {}))
