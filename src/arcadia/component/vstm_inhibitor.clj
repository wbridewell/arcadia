(ns arcadia.component.vstm-inhibitor
  "This component is part of ARCADIA's serial focus subitizing process.
  This prevents double-counting items already encoded in working memory.

  Focus Responsive
    * n/a

  Default Behavior
  Produces fixation-inhibitations for all objects current in vSTM.

  Produces
  fixation-inhibition - contains a location that the attentional strategy will
    inhibit when selecting from candidate proto-object fixations.

  Displays
  n/a

  mode: exclude - inhibit regions that exclude the point
        include - inhibit regions that include the point"
  (:require [arcadia.utility.geometry :as geo]
            [arcadia.component.core :refer [Component]]))

(defn make-inhibition [location]
  {:name "fixation-inhibition"
   :arguments {:region location
               :mode "include"
               :reason "VSTM"}

   :world nil
   :type "instance"})

(defn- oldest-objects [objects]
    ;(println "vstm_inhibitor objects -> " (mapv #(:arguments %) objects))
  (let [sorted-objects (sort-by #(:timestamp (:arguments %)) > objects)]

    (if (>= (count sorted-objects) 4) (rest sorted-objects) sorted-objects)))

(defrecord VSTMInhibitor [buffer]
  Component
  (receive-focus
    [component focus content]

    (let [objects (filter #(and (= (:name %) "object")
                                (= (:world %) "vstm")) content)
          oldest-objects (oldest-objects objects)
         ;locations (map #(:region (:arguments (get-location % content))) oldest-objects)
         ;locations (map #(:region (:arguments (get-location % content))) objects)
          locations (map #(-> % :arguments :region geo/center) objects)
          inhibitions (map #(make-inhibition %)
                           (filter some? locations))]

      (println "locations = " locations)
      (println "vstm inhibitions = " (map #(:arguments %) inhibitions))
     ;(reset! buffer inhibitions))
      (reset! (:buffer component) inhibitions)))

  (deliver-result
    [component]
    @buffer))

(defmethod print-method VSTMInhibitor [comp ^java.io.Writer w]
  (.write w (format "VSTMInhibitor{}")))

(defn start []
  (VSTMInhibitor. (atom nil)))
