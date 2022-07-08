(ns arcadia.sensor.core)

;; The poll function should return the structured information provided by the
;; sensor. Components that make use of this information are responsible for
;; interpreting the idiosyncratic structure and data formats appropriate to
;; the type of sensor being used.

(defprotocol Sensor
  (poll [sensor])
  (swap-environment [sensor new-env]))
