(ns arcadia.component.sweep-inhibitor
  "This component is part of a left-to-right horizontal sweep inhibition
  process used for explicit counting.

  Focus Responsive
    * n/a

  Default Behavior
  Produces fixation-inhibition for all fixation candidates to the left of
  the current sweep-position

  Produces
  fixation-inhibition - contains a location that the attentional strategy will
    inhibit when selecting from candidate proto-object fixations.

  Displays
  n/a

  mode: exclude - inhibit regions that exclude the point
        include - inhibit regions that include the point"
  (:require [arcadia.component.core :refer [Component]]
            [arcadia.vision.regions :as reg]))

(defn make-inhibition [location component]
  {:name "fixation-inhibition"
   :arguments {:region "location"
               :mode "include"
               :reason "sweep"}

   :world nil
   :source component
   :type "instance"})

(defrecord SweepInhibitor [buffer]
  Component
  (receive-focus
   [component focus content]

   (let [fixations (filter #(= (:name %) "fixation") content)
         centers (map #(-> % :arguments :segment :region reg/center) fixations)
         sweep-position (:value (:arguments (first (filter #(= (:name %) "sweep-position") content))))
         past-centers (if (nil? sweep-position)
                        []
                        (filter #(<= (first %) sweep-position) centers))
         inhibitions (map #(make-inhibition % component) past-centers)]
      (reset! (:buffer component) inhibitions)))

  (deliver-result
   [component]
   (set @(:buffer component))))

(defmethod print-method SweepInhibitor [comp ^java.io.Writer w]
  (.write w (format "SweepInhibitor{}")))

(defn start []
  (SweepInhibitor. (atom nil)))
