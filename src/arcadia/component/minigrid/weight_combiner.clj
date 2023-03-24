(ns arcadia.component.minigrid.weight-combiner 
  "Focus Responsive
     No.
  Default Behavior
    Takes all weighted affordances, combines the weights for each potential action, and 
    outputs minigrid-action requests for each potential action. The request with the 
    highest weight has :best? set to true.
  Produces
    * minigrid-action 
       :type - action
       :action-name - string description of action
       :action-command - a minigrid action identifier (integer)
       :patient - an object that is the target of the action (if any)
       :weight - a number indicating the value of the potential action
       :best? - true if this is the action with the highest weight"  
  (:require [arcadia.utility.descriptors :as d]
            [arcadia.component.core :refer [Component merge-parameters]]))

(defn- propose-action [a w]
  {:name "minigrid-action"
   :arguments (merge (:arguments a) {:weight w})
   :type "action"
   :world nil})

(defn- sum-weights [[_ affordances]]
  (when (seq affordances)
    (propose-action (first affordances)
                    (reduce + (map #(-> % :arguments :weight) affordances)))))

(defn- label-best [actions]
  (let [[best & remaining] (sort-by #(-> % :arguments :weight) > actions)]
    (if (seq remaining)
      (cons (assoc-in best [:arguments :best?] true) remaining)
      (assoc-in best [:arguments :best?] true))))

(defrecord WeightCombiner [buffer parameters]
  Component
  (receive-focus
    [component focus content]
    (let [wa (d/filter-elements content :name "weighted-affordance" :type "instance" :world nil)]
      (if (seq wa)
        (reset! buffer (label-best (map #(sum-weights %)
                                        (group-by #(-> % :arguments :action-name) wa))))
        (reset! buffer nil))))


  (deliver-result
    [component]
    (into () @buffer)))

(defmethod print-method WeightCombiner [comp ^java.io.Writer w]
  (.write w (format "WeightCombiner{}")))

(defn start [& {:as args}]
  (let [p (merge-parameters args)]
    (->WeightCombiner (atom nil) p)))