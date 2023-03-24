(ns arcadia.component.minigrid.action-detector
  "Report an event when an action request is the focus of attention.
  Focus Responsive
  * :type \"action\" -- output an event that reports the action. the 
     report should appear on the same cycle that the corresponding 
     environment-action appears

  Default Behavior
    none

  Produces
   * event -- :event-name - \"action\" 
              :action-command - the string name of the action 
              :patient - the object that is the target of the action
              :activity - the name of the action element"
  (:require [arcadia.utility.descriptors :as d]
            [arcadia.utility.minigrid :as mg]
            [arcadia.component.core :refer [Component]]))

(defrecord MinigridActionDetector [buffer]
  Component
  (receive-focus
    [component focus content]
    (if (d/element-matches? focus :type "action"
                            :action-command #{(mg/action-value "toggle")
                                              (mg/action-value "pickup")
                                              (mg/action-value "done")})
      (reset! (:buffer component)
              {:name "event"
               :type "instance"
               :world nil
               :arguments {:event-name "action"
                           :action-command (mg/action-name (-> focus :arguments :action-command))
                           :patient (-> focus :arguments :patient)
                           :activity (:name focus)}})
      (reset! (:buffer component) nil)))
  
  (deliver-result
    [component]
    (list @buffer)))

(defmethod print-method MinigridActionDetector [comp ^java.io.Writer w]
  (.write w (format "MinigridActionDetector{}")))

(defn start []
  (->MinigridActionDetector (atom nil)))