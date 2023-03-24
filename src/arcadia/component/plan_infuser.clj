(ns arcadia.component.plan-infuser
  "Focus Responsive
     Monitors the focus to ensure that the complete set of plan steps is encoded.
   
   Default Behavior
     This component is used to inject an initial \"plan\" into a model. It keeps producing 
     adopt-plan elements until they have all received focus.
   
   Produces
     adopt-plan
       :plan - a plan item that includes the following information
          :handle - a keyword name for the item
          :conditions - a sequence of descriptors that determine when the plan item will activate
          :task-set - a function that takes the same number of arguments as there are conditions 
                      and instantiates a task-set schema using those as input
          :outcome - a function that takes the same number of arguments as there are conditions and
                     generates a sequence of descriptors that indicate that the plan item has been 
                     satisfied."
  (:require [arcadia.utility.general :as g]
            [arcadia.component.core :refer [Component merge-parameters]]))

(def ^:parameter initial-plan "plan to load first when ARCADIA starts from the outset" nil)

(defn- plan-request [plan-item component]
  {:name "adopt-plan"
   :arguments {:plan plan-item}
   :type "action"
   :world nil
   :component component})

(defrecord PlanInfuser [buffer plan-to-load]
  Component
  (receive-focus
    [component focus content]
    ;; keep posting the same plan request until it is adopted.
    (when (= focus @buffer)
      (reset! buffer nil))
    (when (and (nil? @buffer) (seq @plan-to-load))
      (reset! buffer (plan-request (peek @plan-to-load) component))
      (swap! plan-to-load pop)))

  (deliver-result
    [component]
    (list @buffer)))

(defmethod print-method PlanInfuser [comp ^java.io.Writer w]
  (.write w (format "PlanInfuser{}")))

(defn start [& {:as args}]
  (let [p (merge-parameters args)]
    (->PlanInfuser (atom nil) (atom (g/queue (:initial-plan p))))))