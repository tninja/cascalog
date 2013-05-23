(ns cascalog.vars
  "This namespace deals with all Cascalog variable
  transformations."
  (:require [clojure.set :refer (intersection)]
            [clojure.walk :refer (postwalk)]
            [cascalog.util :as u]
            [cascalog.fluent.cascading :refer (gen-var-fn gen-unique-suffix)]
            [cascalog.fluent.types :refer (generator?)]))

;; # Var Generation
;;
;; This first section contains functions that allow Cascalog to
;; generate logic variables. There are three types of logic variables;
;; nullable (prefixed by !), non-nullable (prefixed by ?), and
;; ungrounding (prefixed by !!).

(def gen-non-nullable-var
  "Returns a unique non-nullable var with a optional suffix."
  (gen-var-fn "?"))

(def gen-nullable-var
  "Returns a unique nullable var with a optional suffix."
  (gen-var-fn "!"))

(def gen-ungrounding-var
  "Returns a unique ungrounding var with an optional suffix."
  (gen-var-fn "!!"))

(defn uniquify-var
  "Appends a unique suffix to the supplied input."
  [v]
  (str v (gen-unique-suffix)))

(defn gen-nullable-vars
  "Generates the given number, 'amt', of nullable variables in a sequence.

  Example:
  (let [var-seq (gen-nullable-vars n)]
    (?<- (hfs-textline out-path)
         var-seq
         (in :>> var-seq)))"
  [amt]
  (->> (repeatedly gen-nullable-var)
       (take amt)))

(defn gen-non-nullable-vars
  "Generates the given number, 'amt', of non-nullable variables in a sequence.

  Example:
  (let [var-seq (gen-non-nullable-vars n)]
    (?<- (hfs-textline out-path)
         var-seq
         (in :>> var-seq)))"
  [amt]
  (->> (repeatedly gen-non-nullable-var)
       (take amt)))

;; ## Reserved Keywords
;;
;; Certain keywords are reserved by Cascalog.

(def cascalog-keywords
  "Keywords that have special meaning within Cascalog's predicates."
  #{:> :< :<< :>> :fn> :#> :?})

(def cascalog-keyword?
  "Returns true if the supplied keyword is reserved by cascalog, false
otherwise."
  (comp boolean cascalog-keywords))

(def logic-prefixes
  "Symbol prefixes reserved by Cascalog for use within predicates. Any
symbol or string prefixed by one of these characters will be
interpreted as a logic variable."
  #{"?" "!" "!!"})

(def wildcards
  "Wildcard strings reserved by Cascalog."
  #{"_"})

(defn- extract-varname
  "returns the name of the supplied logic variable. Expected to be
  used with symbols or strings. If the supplied symbol or string is a
  wildcard, gen-var will be used to swap in a logic variable."
  ([v] (extract-varname v gen-nullable-var))
  ([v gen-var]
     (let [s (str v)]
       (if (contains? wildcards s)
         (gen-var)
         s))))

(defn prefixed-by?
  "Returns true if the supplied var `v` is prefixed by the supplied
  prefix, false otherwise."
  [prefix v]
  (try (.startsWith (extract-varname v) prefix)
       (catch Exception _ false)))

(defn non-nullable-var?
  "Returns true if the supplied symbol (or string) references a
  non-nullable logic variable (prefixed by ?), false otherwise."
  [sym-or-str]
  (prefixed-by? "?" sym-or-str))

(def nullable-var?
  "Returns true of the supplied symbol (or string) references a
  nullable logic variable (prefixed by ! or !!)"
  (complement non-nullable-var?))

(defn unground-var?
  "Returns true if the supplied symbol (or string) references an
  ungrounding logic variable (prefixed by !!), false otherwise."
  [sym-or-str]
  (prefixed-by? "!!" sym-or-str))

(def ground-var?
  "Returns true of the supplied var is capable of triggering a join
  (prefixed by ! or ?), false otherwise."
  (complement unground-var?))

(def fully-ground?
  "Returns true if every supplied var is a ground variable, false
  otherwise."
  (partial every? ground-var?))

(defn cascalog-var?
  "A predicate on 'obj' to check is it a cascalog variable."
  [obj]
  (boolean (some #(prefixed-by? % obj)
                 logic-prefixes)))

(def logic-sym?
  "Returns true if the supplied symbol is a Cascalog logic variable,
  false otherwise. & and _ are also munged."
  (every-pred symbol? (some-fn cascalog-var? #{'&})))

(defmacro with-logic-vars
  "Binds all logic variables within the body of `with-logic-vars` to
  their string equivalents, allowing the user to write bare symbols. For example:

  (with-logic-vars
    (str ?a ?b :see))
  ;=>  \"?a?b:see\""
  [& body]
  (let [syms (->> (u/flatten body)
                  (filter logic-sym?)
                  (distinct))]
    `(let [~@(mapcat (fn [s] [s (str s)]) syms)]
       ~@body)))

;; # Sanitizing
;;
;; The following code serves to 'sanitize' a query by converting its
;; logic variables to strings.

(defn sanitize-fn
  "Returns a function that sanitizes an element by resolving logic
  variable names and replacing wildcards using the supplied
  generator."
  [anon-gen]
  (fn [x]
    (cond (cascalog-var? x) (extract-varname x anon-gen)
          (= (str x) "&") "&"
          :else x)))

(defn sanitize
  "Accepts a (potentially nested) data structure and returns a
  transformed, sanitized predicate generated by replacing all
  wildcards and logic variables with strings."
  [pred]
  (let [generator (if (some unground-var? (u/flatten pred))
                    gen-ungrounding-var
                    gen-nullable-var)]
    (postwalk (sanitize-fn generator) pred)))

;; # Variable Uniqueing

(defn unique-vars
  "Returns two things. The first entry is a sequence of uniqued
  variables. To unique, we check that the supplied map of equalities
  doesn't have an entry yet for a given variable.

  If it doesn't, we add an entry pointing to a singleton vector with
  the item.

  If it does, we swap out the duplicate var in `vars` with a uniqued
  version created by appending a unique suffix onto the end."
  [vars equalities]
  (letfn [(update [[acc equality-m] v]
            (if-not (cascalog-var? v)
              [(conj acc v) equality-m]
              (let [existing (get equalities v [])
                    varlist  (cond (empty? existing) (conj existing v)
                                   (ground-var? v)
                                   (conj existing (uniquify-var v))
                                   :else existing)
                    newname (last varlist)]
                [(conj acc newname) (assoc equalities v varlist)])))]
    (reduce update [[] equalities] vars)))

(defn mk-drift-map
  "Accepts a map of item -> [item, item-dup-a, item-dup-b...] and
  returns a map up duplicate back to the original item."
  [vmap]
  (let [update-fn (fn [m [original & more]]
                    (reduce (fn [m duplicate]
                              (assoc m duplicate original)) m more))]
    (reduce update-fn {} (vals vmap))))

(defn intersect-drift-maps
  [drift-maps]
  (let [tokeep (->> drift-maps
                    (map set)
                    (apply intersection))]
    (u/pairs->map (seq tokeep))))
