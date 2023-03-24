(ns arcadia.component.action-detector
  (:require [arcadia.component.core :refer [Component]]))

;; Report an event when an action request is the focus of attention.
;;
;; Focus Responsive
;;   * :type "action" -- output an event that reports the action. the 
;;      report should appear on the same cycle that the corresponding 
;;      environment-action appears
;;
;; Default Behavior
;;   none
;;
;; Produces
;;  * event -- :event-name will be "action" the activity will be the :name 
;;             of the action request
;;

(defrecord ActionDetector [buffer]
  Component
  (receive-focus
    [component focus content]
    (if (= (:type focus) "action")
      (reset! (:buffer component)
              {:name "event"
               :type "instance"
               :world nil
               :arguments {:event-name "action"
                           :button-id (-> focus :arguments :button-id)
                           :activity (:name focus)}})
      (reset! (:buffer component) nil)))
  
  (deliver-result
    [component]
    (list @buffer)))

(defmethod print-method ActionDetector [comp ^java.io.Writer w]
  (.write w (format "ActionDetector{}")))

(defn start []
  (->ActionDetector (atom nil)))