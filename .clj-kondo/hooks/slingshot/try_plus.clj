;; clj-kondo hook for slingshot/try+
;;
;; Source of truth: clj-kondo/configs repo.
;; https://github.com/clj-kondo/configs/blob/main/configs/slingshot/slingshot/resources/clj-kondo.exports/clj-kondo/slingshot/clj_kondo/slingshot/try_plus.clj
;;
;; Handles all three slingshot catch selector shapes correctly:
;;   (catch ExceptionClass binding body)  — class-based
;;   (catch [:key :val]    binding body)  — vector selector, destructures
;;   (catch predicate-fn   binding body)  — predicate, wrapped as a fn
;;
;; Sets up the implicit `&throw-context` binding that slingshot establishes
;; across the whole try+ body. Our prior hand-rolled hook only handled
;; class-based catches; vector-selector usage in response/anomaly and its
;; tests would have been mis-expanded.
;;
;; Keep this file in sync with upstream (check the URL above when kondo
;; lint surfaces new warnings on try+ forms). Do not modify in place; if
;; something needs changing, upstream the fix and re-sync.

(ns hooks.slingshot.try-plus
  (:require [clj-kondo.hooks-api :as api]))

(defn expand-catch [catch-node]
  (let [[catch catchee & exprs] (:children catch-node)
        catchee-sexpr (api/sexpr catchee)]
    (cond (vector? catchee-sexpr)
          (let [[selector & exprs] exprs]
            (api/list-node
             [catch (api/token-node 'Exception) (api/token-node '_e#)
              (api/list-node
               (list* (api/token-node 'let)
                      (api/vector-node [selector (api/token-node nil)])
                      exprs))]))
          (seq? catchee-sexpr)
          (api/list-node
           (list* catch (api/token-node 'Exception) (api/token-node '_e#)
                  (api/list-node
                   (list (api/token-node 'fn)
                         (api/vector-node [(api/token-node '%)])
                         catchee))
                  exprs))
          :else catch-node)))

(defn try+ [{:keys [node]}]
  (let [children (rest (:children node))
        [body catches]
        (loop [body children
               body-exprs []
               catches []]
          (if (seq body)
            (let [f (first body)
                  f-sexpr (api/sexpr f)]
              (if (and (seq? f-sexpr) (= 'catch (first f-sexpr)))
                (recur (rest body)
                       body-exprs
                       (conj catches (expand-catch f)))
                (recur (rest body)
                       (conj body-exprs f)
                       catches)))
            [body-exprs catches]))
        new-node (api/list-node
                  [(api/token-node 'let)
                   (api/vector-node
                    [(api/token-node '&throw-context) (api/token-node nil)])
                   (api/token-node '&throw-context) ;; use throw-context to avoid warning
                   (with-meta (api/list-node (list* (api/token-node 'try)
                                                    (concat body catches)))
                     (meta node))])]
    {:node new-node}))
