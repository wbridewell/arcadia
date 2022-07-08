(ns
  ^{:doc "The focus selector applies the currently active attentional
         strategy (found in the registry) and selects the highest priority focus.

         The focus selector can update the attentional strategy in the
         registry in response to certain actions receiving the focus:
         \"adopt-attentional-strategy\"

         An attentional strategy is a function that must take the accessible
         contents as input and must return a single element which will be the
         focus of attention on the following cycle."}
  arcadia.architecture.focus-selector
  (:require (arcadia.utility  [descriptors :as d][general :as g])
            (arcadia.architecture [registry :as reg])))

(def print-contents false)

(defn- select-focus-default 
  "A general default-mode attentional program that is useful across models."
  [expected]
  (or (d/rand-element expected :type "action")
      (d/rand-element expected :name "object" :type "instance" :world nil)
      (d/rand-element expected :name "fixation")
      (g/rand-if-any (seq expected))))

(defn- apply-strategy
 "Ensures that strategy application results in a vector of the form [focus updated-strategy]
 where the updated strategy is either the same as the current strategy or has had its internal
 priorities updated."
 [strategy content]
 (#(if (nil? (first %)) [(select-focus-default content) (second %)] %) ;; ensure that there is a focus
   (#(if (vector? %) % [% strategy]) ;; ensure the result is a vector of the proper form
     (if strategy (strategy content) (select-focus-default content)))))

(defn select-focus
  "Selects the next focus of attention from among the items in accessible content.
  Updates any dynamic priorities in the current attentional strategy. If there is
  an adopt-attentional-strategy element on this cycle, then the strategy is
  replaced with the new starting with the next cycle."
  [registry]
  ;; pick the focus from the strategy with the highest priority that also
  ;; makes a suggestion.
  (let [[focus new-strategy] (apply-strategy (reg/strategy registry) (reg/content registry))
        adopt-strategy (d/rand-element (reg/content registry) :name "adopt-attentional-strategy")]

    (when print-contents
      (println "Accessible Content:")
      (doall (map g/print-element (reg/content registry)))
      (g/print-element "Focus:" focus))

    ;; NOTE we have switched behavior such that adopt-attentional-strategy does not
    ;; need to be the focus of attention to invoke a strategy change. the expectation is
    ;; that this element will only be produced after a task-switch was the focus
    ;; of attention and this avoids errors where the task is adopted but there is a
    ;; stale strategy.
    (if (d/element-matches? adopt-strategy :name "adopt-attentional-strategy" :strategy some?)
      (reg/set-strategy (reg/set-focus registry focus) (-> adopt-strategy :arguments :strategy))
      (reg/set-strategy (reg/set-focus registry focus) new-strategy))))
