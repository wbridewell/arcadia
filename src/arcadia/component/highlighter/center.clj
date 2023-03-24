(ns arcadia.component.highlighter.center
  "This component requests a fixation to an object in the center of the
  sensor's field of view if such an object exists. This behavior aligns with
  the center bias when viewing stimuli and enables the system to handle tasks
  that require center foveation.

  Focus Responsive
  No

  Default Behavior
  Look for image segments that appear in the center of the visual field and
  produce a fixation request to the corresponding location.

  Produces
   * fixation
       includes a :segment that contains the segment driving fixation,
       a :sensor that contains the sensor associated with the visual field, and
       a :reason that specifies this as a task-specific fixation request based
       on instructions for center foveation."
  (:require [arcadia.component.core :refer [Component merge-parameters]]
            [arcadia.sensor.core :refer [poll]]
            [arcadia.utility [general :as g]]
            [arcadia.utility.geometry :as geo]))

(def ^:parameter ^:required sensor "a sensor that provides visual input (required)" nil)

(defn- make-fixation [segment component]
  {:name "fixation"
   :arguments {:segment segment
               :sensor (:sensor component)
               :reason "task"}
   :world nil
   :type "instance"})

(defn- segment-at-point
  "Get the smallest segment that contains the specified location."
  [x y segments]
  ;; prefer smaller segments that contain the point.
  (g/find-first (fn [s] (geo/contains? (:region s) {:x x :y y}))
                (sort-by (fn [s] (* (geo/width (:region s))
                                    (geo/height (:region s))))
                         <
                         segments)))

(defn- get-center-segment
  "Get the segment in the center of the visual field."
  [segments sensor]
  (let [{x :xpos y :ypos} (:location (poll sensor))]
    (segment-at-point x y segments)))

(defrecord CenterHighlighter [sensor buffer]
  Component
  (receive-focus
    [component focus content]
    (let [current-segments (:segments
                            (:arguments
                             (g/find-first #(and (= (:name %) "image-segmentation")
                                                 (= (:sensor (:arguments %))
                                                    (:sensor component)))
                                           content)))]
      (reset! (:buffer component) (get-center-segment current-segments (:sensor component)))))

  (deliver-result
    [component]
    (when @buffer
      (list (make-fixation @buffer component)))))

(defmethod print-method CenterHighlighter [comp ^java.io.Writer w]
  (.write w (format "CenterHighlighter{}")))

(defn start
  [& {:as args}]
  (->CenterHighlighter (:sensor (merge-parameters args)) (atom nil)))
