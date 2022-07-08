(ns ^{:doc "These macros and functions support evaluation of ARCADIA models by
      providing external code with access to the output of individual components.
      When modelers call startup to run their models, they can use the :output-map
      keyword argument to set up their output hashmap. Each value in the hashmap
      will be a call to one of the macros defined below. These macros set up
      evaluation functions, which will be called on the focus or accessible
      content at specified points during the model run. The output of these
      evaluation functions will be bound to the associated keys in the output-map.
      Consider, for example:
      (startup
      ...
      :output-map
       {:guesses (final-content filter-elements :name \"new-object-guess\")
        :prediction (first-focus element-matches? :name \"fixation\" :reason
        \"maintenance\"})
      This call will return an output-map in which the :guesses key is associated
      with the list of elements with name \"new-object-guess\" found in accessible
      content after the final cycle. The :prediction key will be associated with
      true if, at some point during the model run, the focus is an element with
      name \"fixation\" and reason \"maintenance.\""}

  arcadia.utility.evaluation
  (:require [arcadia.architecture.registry :as reg]))

(defmacro first-content
  "Sets up a function that will be called on accessible content every cycle that the
  model is run until the first time it returns a non-nil, non-false, non-empty value.
  That value will be returned in the output-map (or else nil)."
  [predicate & args]
  `{:src :content :type :first :resolved? false
    :fn (fn [] (~predicate (arcadia.architecture.registry/content) ~@args))})

(defmacro first-focus
  "Sets up a function that will be called on the focus every cycle that the
  model is run until the first time it returns a non-nil, non-false, non-empty value.
  That value will be returned in the output-map (or else nil)."
  [predicate & args]
  `{:src :content :type :first :resolved? false
    :fn (fn [] (~predicate (arcadia.architecture.registry/focus) ~@args))})

(defmacro content-number
  "Sets up a function that will be called on accessible content on the specified cycle
  number. The result will be returned in the output-map."
  [number predicate & args]
  `{:src :content :type :numbered :number ~number :resolved? false
    :fn (fn [] (~predicate (arcadia.architecture.registry/content) ~@args))})

(defmacro focus-number
  "Sets up a function that will be called on the focus on the specified cycle
  number. The result will be returned in the output-map."
  [number predicate & args]
  `{:src :content :type :numbered :number ~number :resolved? false
    :fn (fn [] (~predicate (arcadia.architecture.registry/focus) ~@args))})

(defmacro final-content
  "Sets up a function that will be called on accessible content after the final
  cycle of the model run concludes. The result will be returned in the output-map."
  [predicate & args]
  `{:src :content :type :final :resolved? false
    :fn (fn [] (~predicate (arcadia.architecture.registry/content) ~@args))})

(defmacro final-focus
  "Sets up a function that will be called on the focus after the final cycle of the
  model run concludes. The result will be returned in the output-map."
  [predicate & args]
  `{:src :content :type :final :resolved? false
    :fn (fn [] (~predicate (arcadia.architecture.registry/focus) ~@args))})

(defn- try-evaluation-function
  "Checks whether it is appropriate to call an evaluation function on a model's
  focus or accessible content at this time. If it is, then mark the evaluation
  function as resolved and store the results. final? indicates that the final
  cycle of the model run has concluded."
  [item cycle-num final?]
  (if (and (not (:resolved? item))
           (or (= (:type item) :first)
               (and (= (:type item) :final) final?)
               (and (= (:type item) :numbered) (= (:number item) cycle-num))))
    (let [result ((:fn item))]
      (if (not (or (nil? result) (false? result) (and (seq? result) (empty? result))))
        (assoc item :resolved? true :result result)
        item))
    item))

(defn update-output-map
  "Updates a model's output-map by calling any evaluation functions that are
  appropriate at this time and associating their results with the corresponding keys
  in the map."
  [output-map registry]
  (reg/with-registry registry
    (zipmap (keys output-map)
            (map #(try-evaluation-function % (reg/cycle-num registry) false)
                 (vals output-map)))))

(defn finalize-output-map
  "Updates a model's output-map one final time after the last cycle of a model
  run has completed. Calls any evaluation functions that are appropriate and
  associates their results with the corresponding keys in the map."
  [output-map registry]
  (reg/with-registry registry
    (zipmap (keys output-map)
            (map #(:result (try-evaluation-function % (reg/cycle-num registry) true))
                 (vals output-map)))))
