(ns
  ^{:doc "Utility functions for creating, traversing, and modifying Clojure objects"}
  arcadia.utility.general
  (:import java.time.LocalDateTime
           java.text.DecimalFormat
           java.math.RoundingMode)
  (:require clojure.walk
            clojure.java.io
            [clojure.core.matrix.random :refer [sample-normal]]
            [clojure.data.generators :as dgen]
            clojure.string))

;; print queues as <-(contents)-< for readability
;; this might not be great for interlingua elements
(defmethod print-method clojure.lang.PersistentQueue [q w] 
  (print-method '<- w)
  (print-method (seq q) w)
  (print-method '-< w))

(defn append-queue [q coll]
  (reduce conj q coll))

(defn queue
  ([] (clojure.lang.PersistentQueue/EMPTY))
  ([coll]
   (append-queue clojure.lang.PersistentQueue/EMPTY coll)))

(defn type-string
  "Returns a string naming the type of a data structure. Strips off any
   class hierarchy information."
  [item]
  (when item
    (->> item
         type str
         (re-matches #".*[.]([^.]*)")
         second)))

(defn self-type-string
  "Similar to type-string, but can be called when item is already a type."
  [item]
  (when item
    (->> item
         str
         (re-matches #".*[.]([^.]*)")
         second)))

(defn print-element
  "Prints an interlingua element's toplevel data, and a list of its argument keys.
  Optionally, a prefix can be provided to be printed before the element."
  ([e]
   (println (list (:name e) (:type e) (:world e)
                  (sort (keys (:arguments e)))
                  (:source (meta e)))))
  ([prefix e]
   (println prefix
            (list (:name e) (:type e) (:world e)
                  (sort (keys (:arguments e)))
                  (:source (meta e))))))

;; https://dev.clojure.org/jira/browse/CLJ-2056
;; apparently will never be included in Clojure, but useful anyway.
(defn seek
  "Returns first item from coll for which (pred item) returns true.
   Returns nil if no such item is present, or the not-found value if supplied."
  ([pred coll] (seek pred coll nil))
  ([pred coll not-found]
   (reduce (fn [_ x]
             (if (pred x)
               (reduced x)
               not-found))
           not-found coll)))

(def find-first
  "Return the first item in a sequence that matches the given predicate,
  and nil if the sequence is empty."
  seek)
  ;; (first (filter pred coll))

(defn rand-if-any
  "Returns a random item from a list, or nil if the list is empty."
  [items]
  (when (seq items)
    (dgen/rand-nth items)))


;;  private double nextNextGaussian;
;;  private boolean haveNextNextGaussian = false;

;;  public double nextGaussian() {
;;    if (haveNextNextGaussian) {
;;      haveNextNextGaussian = false;
;;      return nextNextGaussian;
;;    } else {
;;      double v1, v2, s;
;;      do {
;;        v1 = 2 * nextDouble() - 1;   // between -1.0 and 1.0
;;        v2 = 2 * nextDouble() - 1;   // between -1.0 and 1.0
;;        s = v1 * v1 + v2 * v2;
;;      } while (s >= 1 || s == 0);
;;      double multiplier = StrictMath.sqrt(-2 * StrictMath.log(s)/s);
;;      nextNextGaussian = v2 * multiplier;
;;      haveNextNextGaussian = true;
;;      return v1 * multiplier;
;;    }
;;  }

(defn next-gaussian 
  "Returns a random floating point number from a Gaussian distribution with mean 0 and s.d. 1.
   The implementation is based on the algorithm in java.util.Random and uses clojure.data.generator
   to enable a seed."
  []
  (loop [v1 (- (* 2.0 (dgen/double)) 1.0)
         v2 (- (* 2.0 (dgen/double)) 1.0)
         s (+ (* v1 v1) (* v2 v2))]
    (if (or (>= s 1.0) (zero? s))
      (let [v1x (-  (* 2.0 (dgen/double)) 1.0) 
            v2x (- (* 2.0 (dgen/double)) 1.0)
            sx (+ (* v1x v1x) (* v2x v2x))]
        (recur v1x v2x sx))
      (* v1 (java.lang.StrictMath/sqrt (* -2.0 (/ (java.lang.StrictMath/log s) s)))))))

(defn nested-merge
  "Merges two maps, recursively merging their values if they are also maps.
  Note that if the second of the two values is nil, rather than {}, then it is
  not treated as a map. In that case, even if the first value is a map, the
  function will return nil. This can occur at any level of depth."
  ([] nil)
  ([m1]
   m1)
  ([m1 m2]
   (merge-with
    (fn [x y]
      (if (and (map? x) (map? y))
        (nested-merge x y)
        y))
    m1 m2)))

(defn apply-map
  "Like apply, but expects a hash-map as the last argument. Calls f with some
  required arguments, appending the associations in m as additional keyword arguments.
  Useful for incorporating default parameters into functions with keyword arguments.
  eg., (apply-map f x y {:debug true}) => (f x y :debug true)"
  ([f m] (apply f (apply concat m)))
  ([f x m] (apply f x (apply concat m)))
  ([f x y m] (apply f x y (apply concat m)))
  ([f x y z & args] (apply f x y z (concat (butlast args)
                                           (apply concat (last args))))))

(defn partial-kw
  "Implements currying with keyword arguments. Similar to partial, except
  the keyword arguments are added to the end of the argument list.
  Returns a function that calls f with the specified keyword arguments.
  The keyword arguments set in this function are permanent, and can not
  be overridden. However, additional keyword arguments can still be added."
  [f & keyword-parameters]
  (fn [& args]
    (apply f (concat args keyword-parameters))))

(defn recursive-dissoc
  "Dissoc all key/value pairs of the keys provided
   in a recursive manner using postwalk. So, pairs
   are dissociated at every nested level of the map."
  ;; do nothing if there are no keys
  ([map] map)
  ;; use dissoc on each sub-form of map
  ([map & keys]
   (if (empty? keys)
     map
     (clojure.walk/postwalk #(if (map? %)
                               (apply dissoc (conj keys %))
                               %) map))))

;;Taken from https://clojuredocs.org/clojure.core/when-let
;; (defmacro when-let*
;;   "Similar to when-let, but supports multiple bindings, executing the body when
;;   all bindings evaluate to true."
;;   [bindings & body]
;;   `(let ~bindings
;;      (when (and ~@(take-nth 2 bindings))
;;        (do ~@body))))

(defmacro when-let*
  ([bindings & body]
   (if (seq bindings)
     `(when-let [~(first bindings) ~(second bindings)]
        (when-let* ~(drop 2 bindings) ~@body))
     `(do ~@body))))

(defn approx=
  "Tests whether e1 and e2 are equal, ignoring any values
   stored by the given keys"
  ([e1 e2] (= e1 e2))
  ([e1 e2 & keys] (= (apply recursive-dissoc e1 keys)
                     (apply recursive-dissoc e2 keys))))

(defn safe-divide
  "Divides n by d unless d is 0. If d is 0, returns d instead."
  [n d]
  (if (zero? d)
    d
    (/ n d)))

(defn clamp-value
  "Restrict a number to be between min-value and max-value"
  [value max-val min-val]
  (max min-val (min max-val value)))

(defn filter-indices
  "Returns a sequence of indices for which (pred (get coll index)) is true"
  [pred coll]
  (keep-indexed #(when (pred %2) %1) coll))

(defn seq-transpose
  "Given a matrix (sequence of sequences), returns a list
   of lists which is equal to the transpose of the matrix"
  [m]
  (when (seq m) (apply map list m)))

(defn seq-transposev
  "Given a matrix (sequence of sequences), returns a vector
   of vectors which is equal to the transpose of the matrix"
  [m]
  (when (seq m) (apply mapv vector m)))

(defn normalize
  "Normalize x to [0-1] with respect to x's min and max values.
   By default, min=0 and max=1, so x is already normalized"
  ([x] x)
  ([x min max] (/ (- x min) (- max min))))

(defn normal
  "Randomly draws a sample from a normal curve with
   mean x and with standard-deviation s"
  ([] (first (sample-normal 1)))
  ([x] (+ x (normal)))
  ([x s] (+ x (* s (normal)))))

(defn perturb
  "Returns a random number roughly in the range
   (x*(1 - p), x*(1 + p)) by sampling a normal distribution
   with mean x and standard deviation (px)/4."
  [x p]
  (normal x (* x p 1/4)))

(defn as-seq
  "If x is a list, vector, or set, return x unchanged.
   Otherwise return a list containing x. Useful for ensuring an argument
   is a seq of interlingua elements rather than a single element"
  [x]
  (if (or (map? x) (not (coll? x)))
    (list x)
    x))

(defn find-in
  "Get the item as it appears in the sequence. Useful for
   extracting things from working-memory, ensuring that the
   metadata is up to date."
  [item seq]
  (find-first (partial = item) seq))

(defn remove-keys
  "Removes all key/value pairs from hmap for which (f key) returns true.
   Optional arguments to f can also be provided."
  ([hmap f]
   (apply dissoc (cons hmap (filter f (keys hmap)))))
  ([hmap f & args]
   (apply dissoc (cons hmap (filter #(apply f % args) (keys hmap))))))

(defn filter-keys
  "Removes all key/value pairs from hmap except those for which
  (f key) returns true. Optional arguments to f can also be provided."
  ([hmap f]
   (select-keys hmap (filter f (keys hmap))))
  ([hmap f & args]
   (select-keys hmap (filter #(apply f % args) (keys hmap)))))

(defn remove-vals
  "Removes all key/value pairs from hmap for which (f val) returns true.
   Optional arguments to f can also be provided."
  ([hmap f]
   (apply dissoc (cons hmap (filter #(f (get hmap %)) (keys hmap)))))
  ([hmap f & args]
   (apply dissoc (cons hmap (filter #(apply f (get hmap %) args) (keys hmap))))))

(defn filter-vals
  "Removes all key/value pairs from hmap except those for which
  (f val) returns true. Optional arguments to f can also be provided."
  ([hmap f]
   (filter-keys hmap #(f (get hmap %))))
  ([hmap f & args]
   (filter-keys hmap #(apply f (get hmap %) args))))

#_{:clj-kondo/ignore [:redefined-var]}
(defn update-keys
  "Updates values associated with keys ks in a map m by applying f to each value.
  Like update but updates multiple keys at once using the same update function.
  Optional arguments to f can also be provided."
  ([m ks f] (merge m (zipmap ks (map #(f (% m)) ks))))
  ([m ks f & args]
   (merge m (zipmap ks (map #(apply f (get m %) args) ks)))))

(defn update-all
  "Updates values for all keys in a map m by applying f to each value. Optional
  arguments to f can also be provided."
  ([m f] (zipmap (keys m) (map f (vals m))))
  ([m f & args]
   (zipmap (keys m) (map #(apply f % args) (vals m)))))

(defn update-all-keywords
  "Updates all keys in a map m by applying f to each key. Optional
  arguments to f can also be provided."
  ([m f] (zipmap (map f (keys m)) (vals m)))
  ([m f & args]
   (zipmap (map #(apply f % args) (keys m)) (vals m))))

(defn assoc-fn
  "Update the value of a key k in a map m as a function fn-m of the map.
  Like update, but takes multiple key/values and f is applied to m rather than (k m)."
  ([m k fn-m] (assoc m k (fn-m m)))
  ([m k fn-m & keyvals]
   (if (and keyvals (next keyvals))
     (recur (assoc-fn m k fn-m) (first keyvals) (second keyvals) (nnext keyvals))
     (assoc-fn m k fn-m))))

(defn seq-valfun->map
  "Creates a hashmap by applying a function f to every item in a sequence s.
  The items in s become the keys in the hashmap. Optional arguments to f
  can also be provided."
  ([s f] (zipmap s (map f s)))
  ([s f & args]
   (zipmap s (map #(apply f % args) s))))

(defn seq-keyfun-valfun->map
  "Creates a hashmap by applying a function f1 to every item in a sequence s to
  get the keys and applying a function f2 to every item in the sequence to get the
  values."
  [s f1 f2] (zipmap (map f1 s) (map f2 s)))

(defn careful-walk
  "Variant of clojure.walk/walk that has been updated to avoid turning PersistentQueue
   into a sequence. Traverses form, an arbitrary data structure.  inner and outer are
   functions.  Applies inner to each element of form, building up a data structure of 
   the same type, then applies outer to the result. Recognizes all Clojure data structures. 
   Consumes seqs as with doall."
  [inner outer form]
  (cond
    (and (list? form) (not (instance? clojure.lang.PersistentQueue form))) 
    (outer (apply list (map inner form)))
    (instance? clojure.lang.IMapEntry form)
    (outer (clojure.lang.MapEntry/create (inner (key form)) (inner (val form))))
    (seq? form) (outer (doall (map inner form)))
    (instance? clojure.lang.IRecord form)
    (outer (reduce (fn [r x] (conj r (inner x))) form form))
    (coll? form) (outer (into (empty form) (map inner form)))
    :else (outer form)))

(defn careful-postwalk
  "Variant of clojure.walk/walk that has been updated to avoid turning PersistentQueue
   into a sequence. Performs a depth-first, post-order traversal of form.  Calls f on
   each sub-form, uses f's return value in place of the original. Recognizes all Clojure 
   data structures. Consumes seqs as with doall."
  [f form]
  (careful-walk (partial careful-postwalk f) f form))

(defn careful-prewalk
  "Variant of clojure.walk/walk that has been updated to avoid turning PersistentQueue
   into a sequence. Like careful-postwalk, but does pre-order traversal."
  [f form]
  (careful-walk (partial careful-prewalk f) identity (f form)))

(defn data-str
  "Returns a string representing a seq, with sep in between each element
   and end at the end. By default sep = \" \" and end = \"\\n\"
   Example: (data-str [1 2 3 4]) => \"1 2 3 4\\n\"
   Useful for generating .csv files."
  ([coll] (data-str coll " " "\n"))
  ([coll sep] (data-str coll sep "\n"))
  ([coll sep end] (str (apply str (interpose sep coll)) end)))

(defn flatten-str
  "Takes one or more arguments and flattens them before applying str to them."
  [& args]
  (apply str (flatten args)))

(defn format-number
  "Takes a number and the desired max number of digits after the decimal and formats
   the number so that it will have at most that many digits. Precision can be
   negative, e.g., -1 means round to the nearest tens, -2 means round to the
   nearest hundreds, etc. If num is a string, then attempt to format any
   numbers within that string.
   Always returns a string.
   If the keyword argument preserve-type? is true, then if the number is a float,
   output it with a single digit after the decimal point, even if that digit is
   0.
   If the keyword argument min-characters is non-nil, then add leading spaces to 
   the number if its formatting length has fewer characters than min-characters."
  [num precision & {:keys [preserve-type? min-characters]}]
  (cond
    (nil? precision)
    (str num)

    (string? num)
    (cond->
     (clojure.string/replace
      num #"(?<![0-9\.])(([1-9][0-9]*)|0)\.[0-9]+(?![0-9]|\.[0-9])" ;;Find all doubles
      #(-> % first Double/valueOf
           (format-number precision :preserve-type? preserve-type? :min-characters min-characters)))
      
      (or (neg? precision) min-characters)
      (clojure.string/replace
       #"(?<![0-9\.])(([1-9][0-9]*)|0)(?![0-9]|\.[0-9])" ;;Find all integers
       #(-> % first Integer/valueOf
            (format-number precision :preserve-type? preserve-type? :min-characters min-characters))))

    (or (Double/isNaN num) (Double/isInfinite num))
    (str num)

    :else
    (-> num double Double/toString (BigDecimal.)
        (.setScale precision RoundingMode/HALF_UP) (.doubleValue)
        (->> (.format (DecimalFormat.
                       (flatten-str "#" (when (pos? precision) ".")
                                    (repeat precision "#")))))
        (cond->
         (and preserve-type? (float? num))
          ((fn [s] (if (not (.contains s "."))
                     (str s ".0")
                     s)))
          min-characters
          (->> (format (str "%1$" min-characters "s")))))))

(defn ranged
  "Make a range of doubles from fractions. This partially
  resolves floating point rounding errors"
  [start end inc]
  (map double (range start end inc)))

(defn distinctp
  "Returns coll with duplicates removed (similar to distinct).
  By default, uses = to test for duplicates. Optionally, a binary
  user predicate can be used in its place."
  ([coll] (distinctp = coll))
  ([p coll]
   (reduce (fn [c e] (if (not-any? (partial p e) c) (conj c e) c))
    (list) coll)))

(defn dedupe-p
  "Removes consecutive duplicates in coll conditioned on pred?"
  [pred? coll]
  (reduce (fn [c e] (cond
                      (empty? c) (list e)
                      (pred? (last c) e) c
                      :else (concat c (list e))))
          (list)
          coll))

(defn group
  "Partitions coll into seqs of duplicate elements.
  By default, uses = to test for duplicates. Optionally, a binary
  user predicate can be used in its place."
  ([coll] (group = coll))
  ([p coll]
   (reduce (fn [groups item]
             (if-let [g (find-first #(some (partial p item) %) groups)]
               (replace {g (conj g item)} groups)
               (conj groups (list item))))
     (list) coll)))

(defmacro apply-if
  "Apply f to x when the predicate is true of x, otherwise return x."
  [pred f x & args]
  `(if (~pred ~x) (~f ~x ~@args) ~x))

(defn min-keys
  "Return a seq of all x's for which (k x), a number, is least."
  [k & args]
  (some->> args seq (group-by k) (apply min-key first) second))

(defn max-keys
  "Return a seq of all x's for which (k x), a number, is greatest."
  [k & args]
  (some->> args seq (group-by k) (apply max-key first) second))

(defn max-abs
  "Returns the number in the list with the highest absolute value."
  [& args]
  (first (sort-by #(Math/abs %) > args)))

(defn min-abs
  "Returns the number in the list with the smallest absolute value."
  [& args]
  (first (sort-by #(Math/abs %) < args)))

(defn near-equal?
  "True if the difference between two values is less than delta (using 0.01 by default)."
  ([x y] (near-equal? x y 0.01))
  ([x y delta] (< (Math/abs (- x y)) delta)))

(defn epsilon
  "Calculate the difference between x and y as a percentage of y."
  [x y]
  (/ (Math/abs (- x y)) y))

(defn between?
  "Returns true iff val is between (inclusive) v0 and v1,
  assuming no ordering between v0 and v1."
  [val v0 v1]
  (and (<= (min v0 v1) val) (<= val (max v0 v1))))

(defn insert
  "Inserts an item into a sequence."
  [sequence pos item]
  (let [v (vec sequence)]
    (seq (apply conj (subvec v 0 pos) item (subvec v pos)))))

(defn time-string
  "Returns a string describing the current time in HH:MM:SS.SS"
  []
  (let [time (LocalDateTime/now)]
    (format "%02d:%02d:%05.2f" (.getHour time) (.getMinute time)
            (+ (.getSecond time) (/ (.getNano time) 1000000000.0)))))

(defn date+time-string
  "Returns a string describing the current time in YYYY-MM-DD_HH-MM-SS, where the
   first MM is month, and the second is minute."
  []
  (let [time (LocalDateTime/now)]
    (format "%d-%02d-%02d_%02d-%02d-%02d" (.getYear time) (.getMonthValue time) (.getDayOfMonth time)
            (.getHour time) (.getMinute time) (.getSecond time))))

(defn make-directories-for-path
  "Ensures that the necessary directories for a file path are created, if they
  don't exist already."
  [path]
  (.mkdirs (.getParentFile (java.io.File. path))))

(defmacro suppress-text-output
  "Executes contained code without printing text to the console. Unlike with-out-str,
  returns whatever is returned by the contained code."
  [code]
  `(let [results# (atom nil)]
     (with-out-str
       (reset! results# ~code))
     @results#))

(defmacro suppress-text-output-if
  "If test returns true, executes contained code without printing text to the
  console. Unlike with-out-str, returns whatever is returned by the contained code."
  [test code]
  `(if ~test
     (let [results# (atom nil)]
       (with-out-str
        (reset! results# ~code))
       @results#)
     ~code))

(defn write-component!
  "Write a template component file for a component in the given namespace.

  e.g. the call (component-template! 'component-name) creates a component
  called ComponentName in the namespace arcadia.component.component-name,
  and writes the file to src/arcadia/component/component_name.clj."
  [ns-symbol]
  (let [filename (clojure.string/replace (str "src/arcadia/component/" ns-symbol ".clj") #"-" "_")
        class-name (apply str (map clojure.string/capitalize (clojure.string/split (str ns-symbol) #"-")))]
    (if (.exists (clojure.java.io/as-file filename))
      (println "File exists:" filename)
      (do
        (println "Creating file:" filename)
        (spit filename
              (format "(ns arcadia.component.%s
  \"Focus Responsive
                       
    Default Behavior
                       
    Produces\"
  (:require [arcadia.component.core :refer [Component merge-parameters]]))

(defrecord %s [buffer parameters] 
  Component
  (receive-focus
   [component focus content])
  (deliver-result
   [component]))

(defmethod print-method %s [comp ^java.io.Writer w]
  (.write w (format \"%s{}\")))

(defn start [& {:as args}]
  (let [p (merge-parameters args)]
    (->%s (atom nil) p)))"
                      (str ns-symbol) class-name class-name class-name class-name))))))
