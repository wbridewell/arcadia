(ns arcadia.component.vstm-enumerator-simple
  "Reports the number of tracked objects in vSTM.

  Focus Responsive
    * n/a

  Default Behavior
  Produces vstm-enumeration when either vSTM is at capacity, or when
    the visual layout is spare enough that there are fewer objects 
    to look at than vSTM can hold. 

  Produces
  vstm-enumeration - contains a :count of the number of elements encoded in vSTM.

  Displays
    * n/a"
  (:require [arcadia.utility.objects :as obj]
            [arcadia.utility.descriptors :as d]
            [arcadia.component.core :refer [Component merge-parameters]]))

(def ^:parameter vstm-size "number of elements in vSTM" 4)

(defn- generate-enumeration [number component]
  {:name "vstm-enumeration"
   :arguments {:count number}
   :world nil
   :source component
   :type "instance"})

(defn- potential-objects [content]
  (println (-> (d/first-element content :name "spatial-map" :perspective "egocentric") :arguments :layout :objects))
  (-> (d/first-element content :name "spatial-map" :perspective "egocentric") :arguments :layout :objects))

(defrecord VSTMEnumeratorSimple [buffer vstm-size]
  Component
  (receive-focus
    [component focus content]
    (let [nobjects (count (obj/get-vstm-objects content :tracked? true))]
      (if (and (> nobjects 0)
               (>= nobjects
                   (min vstm-size (count (potential-objects content)))))
        (reset! (:buffer component) (generate-enumeration nobjects component))
        (reset! (:buffer component) nil))))

  (deliver-result
    [component]
    #{@(:buffer component)}))

(defmethod print-method VSTMEnumeratorSimple [comp ^java.io.Writer w]
  (.write w (format "VSTMEnumeratorSimple{}")))

(defn start
  [& {:as args}]
  (VSTMEnumeratorSimple. (atom nil) (:vstm-size (merge-parameters args))))
