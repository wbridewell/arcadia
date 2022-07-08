(ns arcadia.component.touch-detector
  (:require [arcadia.sensor.core :as sense]
            [arcadia.component.core :refer [Component merge-parameters]]))

;; The TouchDetector outputs an event whenever the touch sensor produces output
;;
;; Focus Responsive
;;   No.
;;
;; Default Behavior
;;  If the touch sensor produces output, creates touch events for that output.
;;
;; Produces
;;  event- a touch event signaling the presence of contact in the environment.
;;

(def ^:parameter ^:required sensor "a sensor that provides visual input (required)" nil)

(defn touch-event [label component]
  {:name "event"
   :arguments {:event-name "touch"
               :label label}
   :type "instance"
   :source component
   :world nil})

(defrecord TouchDetector [buffer sensor] Component
  (receive-focus
    [component focus content]
    (->> (sense/poll (:sensor component))
         (map #(touch-event % component))
         (reset! (:buffer component))))
  (deliver-result
    [component]
    (set @(:buffer component))))

(defmethod print-method TouchDetector [comp ^java.io.Writer w]
  (.write w (format "TouchDetector{}")))

(defn start
  [& {:as args}]
  (let [p (merge-parameters args)]
    (->TouchDetector (atom nil) (:sensor p))))
