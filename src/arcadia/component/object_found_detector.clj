(ns arcadia.component.object-found-detector
  "Focus Responsive
    Yes. When a navigation-affordance is the focus, it is removed from the tracker.
  Default Behavior
    When there is an adopt-navigation-target element, produce an object-found event.
  Produces
    * event 
        :event-name - object-found
        :goal - the object being navigated to
        :location - the location of the object (a region)"  
  (:require [arcadia.utility.descriptors :as d]
            [arcadia.component.core :refer [Component merge-parameters]]))

(defrecord ObjectFoundDetector [buffer parameters]
  Component
  (receive-focus
    [component focus content]
    (reset! buffer nil)
    (when-let [x (d/first-element content :name "adopt-navigation-target" :type "action")]
      (reset! buffer {:name "event"
                      :arguments {:event-name "object-found"
                                  :object (-> x :arguments :goal)
                                  :location (-> x :arguments :location)}
                      :type "instance"
                      :world nil})))
  (deliver-result
    [component]
    (list @buffer)))

(defmethod print-method ObjectFoundDetector [comp ^java.io.Writer w]
  (.write w (format "ObjectFoundDetector{}")))

(defn start [& {:as args}]
  (let [p (merge-parameters args)]
    (->ObjectFoundDetector (atom nil) p)))