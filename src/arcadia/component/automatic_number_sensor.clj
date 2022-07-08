(ns arcadia.component.automatic-number-sensor
  "This component implements ARCADIA's approximate number system (ANS)
  The ANS is described as \"core system 1\" of numerical cognition in:

  Feigenson, L., Dehaene, S., and Spelke, E. (2004). \"Core systems of number.\"
    TRENDS in Cognitive Science. Vol. 8. No. 7. (pp. 307--314).

  Focus Responsive
    * group-fixation


  Default Behavior
  Responds to a group of proto-objects as encoded in a group-fixation element
  and produces a noisy representation of the number of proto-objects in the
  group-fixation.

  Produces
  number-sense - encodes a noisy representation of the number of proto-objects,
    specifically, a gaussian with mean of the true number (n) and std. deviation
    equal to w*n (where w is the weber fraction parameter).

  weber-fraction based off of observations of adult human populations from
  empirical work such as:

  Halberda, J., and Feigenson, L. (2008). \"Developmental Change in the Acuity
    of the 'Number Sense': The Approximate Number System in 3-, 4-, 5-, and 6-
    Year-Olds and Adults.\" Developmental Psychology. Vol. 44, No. 5. (pp. 1457--1465).

  Displays
  n/a"
  (:require [arcadia.component.core :refer [Component]]))

(def ^:private weber-fraction 0.13)

(defn number-sense [mean source]
  {:name "number-sense"
   :arguments {:mean mean :weberfrac weber-fraction}
   :type "instance"
   :world nil
   :source source})

(defrecord AutomaticNumberSensor [buffer]
  Component
  (receive-focus
   [component focus content]
   ;; assumes all changes are task relevant
   (let [fixations (filter #(= (:name %) "fixation") content)
         number (first (filter #(= (:name %) "number") content))
         old-ans (first (filter #(= (:name %) "number-sense") content))]
        (cond
            (= (:name focus) "group-fixation")
            (do (println (count (:segments (:arguments focus))))
             (reset! (:buffer component) (number-sense (count (:segments (:arguments focus))) component)))

            :else
            (reset! (:buffer component) nil))))

  (deliver-result
   [component]
   #{@(:buffer component)}))

(defmethod print-method AutomaticNumberSensor [comp ^java.io.Writer w]
  (.write w (format "AutomaticNumberSensor{}")))

(defn start []
  (AutomaticNumberSensor. (atom nil)))
