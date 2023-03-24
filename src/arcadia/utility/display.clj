(ns
  ^{:doc "Provides macros and functions that can be used to depict information
          in display components."}
  arcadia.utility.display
  (:refer-clojure :exclude [try time ->])
  (:require [arcadia.display.support :as d]
            [clojure.java.shell :refer (sh)]
            [arcadia.utility.general :as gen]
            clojure.walk
            clojure.string))

(def debug-display-alias
  "When functions like display, display-element, and display-panel are called, by
   default they will send information to the debug display component with this
   alias." :display.scratchpad)

(def broadcast-fn
  "Function used to broadcast information to debug display components."
  'arcadia.architecture.registry/broadcast-to-logger)

(defmacro element-helper
  "Helper for displaying elements to debug display components. This macro
   should be called only by the other macros in this file."
  ([element alias macro-form args]
   `(element-helper
     ~alias ~macro-form
     ~(if (clojure.core/-> args count odd?) ;;Includes a captions?
        (cons (first args) (cons element (rest args)))
        (cons element args))))
  ([alias macro-form args]
  `((resolve '~broadcast-fn)
    ~alias
    (d/initialize-element false ~(assoc (meta macro-form) :file *file*) ~@args))))

(defn stack-trace-element->file+line
  "Helper fn for try. Should not be called directly. Converts a strack
   trace element into a filepath:line-number string."
  [elem]
  (let [filename (->> (.getFileName elem) (drop-last 4) (apply str))
        classname (.getClassName elem)
        ind (clojure.string/index-of classname filename)]
    (when ind
      (str "src/" (clojure.core/-> (subs classname 0 ind) (clojure.string/replace "." "/"))
           filename ".clj:" (.getLineNumber elem)))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Clear out java windows

(defn clear
  "Clear out all java windows"
  []
  (doseq [w (java.awt.Window/getWindows)] (.dispose w)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Functions for displaying debugging information at runtime

(defmacro break
  "Adds a breakpoint at the line where this macro is used. An interface for
   continuing past the breakpoint will be provided in the debug display with the
   default alias."
  [& args]
  `(element-helper
    "Continue" ~debug-display-alias
    ~(vary-meta &form assoc
                :break-point? true :new? `(atom true) :active? `(atom false))
    ~args))

(defn panel
  "Quick and easy way to add a panel to the debug display, using the default
   alias for the debug display component."
  [& args]
  ((resolve '~broadcast-fn)
   debug-display-alias (apply d/initialize-panel false args)))

(defmacro element
  "Quick and easy way to add an element to the debug display, using the default
   alias for the debug display component."
  [& args]
  `(element-helper ~debug-display-alias ~&form ~args))

(defmacro elem
  "Quick and easy way to add an element to the debug display, using the default
   alias for the debug display component. Shorthand for the element macro."
  [& args]
  `(element-helper ~debug-display-alias ~&form ~args))

(defn glyph
  "Quick and easy way to add a glyph to the debug display, using the default
   alias for the debug display component."
  [& args]
  ((resolve '~broadcast-fn)
   debug-display-alias (apply d/initialize-glyph false args)))

(defmacro break-for
  "Adds a breakpoint at the line where this macro is used. An interface for
   continuing past the breakpoint will be provided in the debug display with the
   specified alias."
  [alias & args]
  `(element-helper
    "Continue" ~alias
    ~(vary-meta &form assoc
                :break-point? true :new? `(atom true) :active? `(atom false))
    ~args))

(defn panel-for
  "Quick and easy way to add a panel to the debug display with the specified
   alias."
  [alias & args]
  ((resolve broadcast-fn)
   alias (apply d/initialize-panel false args)))

(defmacro element-for
  "Quick and easy way to add an element to the debug display with the specified
   alias."
  [alias & args]
  `(element-helper ~alias ~&form ~args))

(defmacro elem-for
  "Quick and easy way to add an element to the debug display with the specified
   alias. Shorthand for the element-for macro."
  [alias & args]
  `(element-helper ~alias ~&form ~args))

(defn glyph-for
  "Quick and easy way to add a glyph to the debug display with the specified
   alias."
  [alias & args]
  ((resolve broadcast-fn)
   alias (apply d/initialize-glyph false args)))

;;Help with the macro from here:
;;https://clojureverse.org/t/how-do-i-get-env-data-in-cljs-macros/4105
(defmacro env-for
  "Quick and easy to display all the variable bindings in the local environment.
   They will be displayed as a hashmap in the debug display with the specified alias."
  [alias & args]
  (let [env
        (into {} (for [k (remove #(= (subs (str %) 0 1) "_") (keys &env))]
                   [`'~k k]))]
    `(element-helper ~env ~alias ~&form ~args)))

(defmacro env
  "Quick and easy to display all the variable bindings in the local environment.
   They will be displayed as a hashmap in the debug display component with the
   default alias."
  [& args]
  (let [env
        (into {} (for [k (remove #(= (subs (str %) 0 1) "_") (keys &env))]
                   [`'~k k]))]
    `(element-helper ~env ~debug-display-alias ~&form ~args)))

(defmacro st-for
  "Quick and easy to display the current stacktrace in the debug display with the
   specified alias."
  [alias & args]
  `(element-helper (clojure.core/-> (Thread/currentThread) (.getStackTrace) rest vec) ~alias ~&form ~args))

(defmacro st
  "Quick and easy to display the current stacktrace in the debug display with the
   default alias."
  [& args]
  `(element-helper (clojure.core/-> (Thread/currentThread) (.getStackTrace) rest vec)
                           ~debug-display-alias ~&form ~args))

(defmacro time-for
  "Quick and easy way to add the time for some operation to the debug display.
   Follows the implementation of the time macro. Should take the form
   (time-for debug-display-alias
                   [optional-caption optional-param1 optional-value1 ...]
                   code)"
  [alias args code]
  `(let [start-time# (. java.lang.System (clojure.core/nanoTime))
         result# ~code]
     (element-helper
      (format "%.4f ms" (/ (double (- (. java.lang.System (clojure.core/nanoTime)) start-time#))
         1000000.0))
      ~alias ~&form ~args)
     result#))

(defmacro time
  "Quick and easy way to add the time for some operation to the debug display,
   using the debug display component with the default alias.
   Follows the implementation of the time macro. Should take the form
   (time [optional-caption optional-param1 optional-value1 ...]
               code)"
  [args code]
  `(let [start-time# (. java.lang.System (clojure.core/nanoTime))
         result# ~code]
     (element-helper
      (format "%.4f ms" (/ (double (- (. java.lang.System (clojure.core/nanoTime)) start-time#))
         1000000.0))
      ~debug-display-alias ~&form ~args)
     result#))

(defmacro time-let
  "Quick and easy way to add the time for each binding in a let statement to the
   debug display, using the debug display component with the default alias.
   Replace any 'let' with 'time-let' to use it."
  [bindings & body]
  `(let
     ~(reduce (fn [items index]
                (assoc items index
                       `(let [start-time# (. java.lang.System (clojure.core/nanoTime))
                              result# ~(get items index)]
                          (element-helper
                           (format "%.4f ms" (/ (double (- (. java.lang.System (clojure.core/nanoTime)) start-time#))
                                                1000000.0))
                           ~debug-display-alias ~&form ~[(str (get items (dec index)))])
                          result#)))
              (cons bindings (->> bindings count range (remove even?))))
     ~@body))

(defmacro ->
  "Quick and easy way to add each incremental step in a -> threading macro to the
   debug display, using the debug display component with the default alias.
   Replace any '->' with 'display/-> [optional-caption optional-params]' to use it."
  [args & steps]
  `(clojure.core/->
    ~@(interleave
       steps
       (repeat (count steps)
               `((fn [result#]
                   (element-helper result# ~debug-display-alias ~&form ~args)
                   result#))))))

(defmacro try
  "Attempts a piece of code. If an exception is thrown, opens the Atom browser to
   the line of code at which the exception occurred, if available."
  [args & code]
  `(clojure.core/try ~@code
     (catch Exception e#
       (if (:caught? (ex-data e#))
         (throw e#)
         (do (env ~@args)
           (some-> e# (.getStackTrace) seq
                   (->> (gen/seek #(->> % (.getFileName) (take-last 3) (apply str) (= "clj"))))
                   stack-trace-element->file+line
                   (#(sh "atom" %)))
           (clojure.repl/pst e#)
           (throw (ex-info "display/try caught the exception"
                           {:caught? true :original e#})))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Functions for providing information to display components

(defmacro information-fn
  "This macro should be used in place of #() to define an anonymous information
  function. Information functions are used by display components to extract
  information from ARCADIA's state. They are always single-arity functions that
  take a hash-map of the form {:focus focus, :content content, :element element},
  where element refers to an element that's already been extracted by another
  information function. In many cases, element is nil if none has been specified.

  As with #(), use the symbol % to represent the argument."
  [body]
  (let [vname (gensym)]
    `(with-meta (fn [~vname]
                  ~(clojure.walk/postwalk-replace {'% vname} body))
                ~(assoc (meta &form) :information-function? true :unresolved? true
                        :ns *ns* :file *file*
                        :referents (->> body flatten
                                        (filter #{:panel :element :glyph})
                                        set)))))

(def #^{:macro true} i#
  "shorthand for information-fn macro"
  #'information-fn)

(defmacro pause-fn 
  "This macro is a variant of the information-fn. Instead of displaying the results 
   of the defined function on each cycle, the system will check whether the results 
   are non-nil, and if they are, then the model will pause."
  [body]
  `(vary-meta (information-fn ~body) assoc :pause-function? true :information-function? false))

(defn map-to-panels
  "Specifies a list of parameter values that should be mapped to a list of panels
   that are being displayed."
  ([items]
   (with-meta items {:map-to-panels? true}))
  ([items final-value]
   (with-meta items {:map-to-panels? true :final-value final-value})))

(defn map-to-elements
  "Specifies a list of parameter values that should be mapped to a list of elements
   that are being displayed. For example :color (map-to-elements [red green blue])
   would indicate that if we have a list of elements, the first should be displayed
   in red, the second in green, the third in blue, the fourth in red, etc..."
  ([items]
   (with-meta items {:map-to-elements? true}))
  ([items final-value]
   (with-meta items {:map-to-elements? true :final-value final-value})))

(defn map-to-glyphs
  "Specifies a list of parameter values that should be mapped to a list of glyphs
   that are being displayed. For example :color (map-to-glyphs [red green blue])
   would indicate that if we have a list of glyphs, the first should be displayed
   in red, the second in green, the third in blue, the fourth in red, etc..."
  ([items]
   (with-meta items {:map-to-glyphs? true}))
  ([items final-value]
   (with-meta items {:map-to-glyphs? true :final-value final-value})))

(defn nth-value
  "Specifies a list of values and an index, or something that resolves to an index.
   On each cycle, (nth values index) will be returned. If index is >=
   (length values), the values will cycle back around. Or, if final-value is
   provided, then final-value will be returned when index is >= (length values).
   Note: final-value will also be used if index does not resolve to a number."
  ([values index]
   (with-meta (vec values) {:index index :unresolved? true}))
  ([values index final-value]
   (with-meta (vec values) {:index index :final-value final-value :unresolved? true})))

(defn progression
  "Provides an initial parameter value and a sequence of [check-fn val] pairs,
   where check-fn is an information function. When the first check-fn returns true,
   the parameter value will progress to the first val. On subsequent cycles, when
   the second check-fn returns true, the parameter value will progress to the second
   val. And so forth."
  [initial-value sequence-of-values]
  (with-meta [(atom (cons initial-value sequence-of-values))]
             {:progression? true :unresolved? true}))

(defn remember-previous
  "Provides an information function that will save its own output every cycle
   and make that output available on the following cycle by including :previous
   previous in the hash-map that serves as input to the information function."
  ([funct]
   (remember-previous funct nil))
  ([funct initial-previous]
   (if (clojure.core/-> funct meta :information-function?)
     (vary-meta funct assoc :previous (atom initial-previous))
     (throw (IllegalArgumentException.
             (str "Input to arcadia.utility.display/previous"
                  " must be defined using the arcadia.utility.display/information-fn"
                  " or arcadia.utility.display/i# macro. (" funct ")"))))))

(defmacro pause-button
  "This macro sets up a pause-button element that can appear in a display."
  []
  `(with-meta ["Pause Button"]
              (assoc ~(meta &form) :pause-button? true :ns ~*ns* :file ~*file*)))

(defmacro blank-canvas 
  "Indicates an element that will be displayed visually as a blank canvas with the specified color.
   A color might be :red, \"green\", or [0 0 255]. This is primarily useful for providing
   a space onto which glyphs can be drawn."
  [color]
  `(with-meta {:color ~color}
     ~(assoc (meta &form) :blank-canvas? true :ns *ns* :file *file*)))

(defn information-fn?
  "Returns true if element is an information function."
  [element]
  (or (clojure.core/-> element meta :information-function?)
      false))