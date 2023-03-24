(ns arcadia.component.saccade-requester
  "There are at least two sorts of eye movements. Ballistic saccades move
  rapidly to a location, and smooth pursuit follows moving targets by
  matching their velocity across the retinal field. This component enables
  gaze to mimic ballistic saccades.

  Focus Responsive
    * scan

  When the focus element is a covert attention scan, then request a saccade
  to the scan location instead of to an object.

  Default Behavior
  Look for a maintenance fixation, which attempts to keep gaze on a particular
  object, and if necessary, request a ballistic saccade to the associated
  object. This activity uses older gaze information to ensure that the gaze
  and object info are in sync.

  Produces
   * saccade
       includes several values that adjust the movement of the saccade over
       time
       :x-amp and :y-amp contain directional amplitudes
       :amplitude contains the total distance to be traveled by the saccade
       :x-PE and :y-PE contain the positional error along the x and y axes
       :x-RS and :y-RS contain the retinal slip along the x and y axes
       :gx-vel and :gy-vel contain the gaze velocity the along x and y axes
       :duration contains the time that the saccade will take
       :target contains either an object or scan location being saccaded to

  NOTE: References to \"scan\" are based on a model that is in progress.

  NOTE: Parameters for setting the saccade length. These are taken from
  Schreiber, C., Missal, M., & Lefèvre, P. (2006). Asynchrony between position
  and motion signals in the saccadic system. Journal of Neurophysiology,
  95(2), 960–9. http://doi.org/10.1152/jn.00315.2005

  This implementation uses modified parameters. The originally reported
  parameters are included in comments.

  positional error is the difference between target and eye location.
  retinal slip is the difference between target and eye velocity."
  (:require [clojure.math.numeric-tower :as math]
            [arcadia.sensor.stable-viewpoint :as sense]
            [arcadia.component.core :refer [Component merge-parameters]]
            [arcadia.utility [general :as g] [gaze :as gaze] [objects :as obj]]
            [arcadia.utility.geometry :as geo]
            clojure.core.matrix))

(def ^:parameter ^:required sensor "a sensor that provides visual input (required)" nil)

(def ^:private x-intercept 0.11)
(def ^:private x-positional-error 1) ;; 0.87
(def ^:private x-retinal-slip 0.281) ;; 0.081

(def ^:private y-intercept -0.09)
(def ^:private y-positional-error 1) ;; 0.84
(def ^:private y-retinal-slip 0.281) ;; 0.081

;; Specialized parameters for saccading to scans. Smaller retinal slip
;; coefficients work because the scan does not move during the saccade. However,
;; some retinal slip is required because the scan proceeds during the 3-4 cycle
;; lag between when the scan is happening and when "old-scan" and "old-gaze"
;; are available for triggering a saccade.

(def ^:private x-retinal-slip-scans 0.07)
(def ^:private y-retinal-slip-scans 0.07)

;; Saccades must be at least this far for them to be requested.
(def ^:private min-saccade-amplitude 3)

;; This is also from Schreiber, Missal and Lefevre, but it draws on the values
;; for one participant, because mean values across participants were not
;; provided.
(defn- amplitude->duration
  "Calculate saccade duration from an amplitude."
  [amp]
  (+ 0.069 (* 0.002 amp) (* -0.039 (math/expt Math/E (* amp -0.482)))))

(defn- convert-object-info
  "Convert a maintenance fixation's pixels to visual degrees and per cycle
  velocities to degrees per second."
  [fixation sensor]
  (if (and (-> fixation :arguments :object) (-> fixation :arguments :delta-x))
    [(gaze/x-pixels->degrees (-> fixation :arguments :object :arguments :region geo/center :x) sensor)
     (gaze/pixels-incr->degrees-second (-> fixation :arguments :delta-x) sensor)
     (gaze/y-pixels->degrees (-> fixation :arguments :object :arguments :region geo/center :y) sensor)
     (gaze/pixels-incr->degrees-second (-> fixation :arguments :delta-y) sensor)]
    [nil nil nil nil]))

(defn- convert-scan-info
  "Convert a covert attentional scan's pixels to visual degrees and per cycle
  velocities to degrees per second."
  [scan sensor]
  (if (and (-> scan :arguments :x) (-> scan :arguments :x-vel))
    [(gaze/x-pixels->degrees (-> scan :arguments :x) sensor)
     (gaze/pixels-incr->degrees-second (-> scan :arguments :x-vel) sensor)
     (gaze/y-pixels->degrees (-> scan :arguments :y) sensor)
     (gaze/pixels-incr->degrees-second (-> scan :arguments :y-vel) sensor)]
    [nil nil nil nil]))

(defn- make-saccade
  [x x-vel y y-vel gx gx-vel gy gy-vel scan obj]
  (let [x-amp (+ x-intercept
                 (* (- x gx) x-positional-error)
                 (* (- x-vel gx-vel) (if scan x-retinal-slip-scans x-retinal-slip)))
        y-amp (+ y-intercept
                 (* (- y gy) y-positional-error)
                 (* (- y-vel gy-vel) (if scan y-retinal-slip-scans y-retinal-slip)))
        amplitude (math/sqrt (+ (* x-amp x-amp) (* y-amp y-amp)))]
    {:name "saccade"
     :arguments {:x-amp x-amp
                 :y-amp y-amp
                 :amplitude amplitude
                 :x-PE (- x gx)
                 :y-PE (- y gy)
                 :x-RS (- x-vel gx-vel)
                 :y-RS (- y-vel gy-vel)
                 :gx-vel gx-vel
                 :gy-vel gy-vel
                 :duration (amplitude->duration amplitude)
                 :target (or scan obj)}
     :world nil
     :type "action"}))

(defn- valid-saccade?
  "Determines whether a saccade should be carried out."
  [saccade gaze-x gaze-y sensor]
  ;; Does it traverse a far enough distance?
  ;; Does it require going outside the boundaries of the visual input?
  (when saccade
    (let [half-width (/ (sense/camera-viewing-width sensor) 2.0)
          half-height (/ (sense/camera-viewing-height sensor) 2.0)]
      (and (> (-> saccade :arguments :amplitude) min-saccade-amplitude)
           (<= (- half-width) (+ gaze-x (-> saccade :arguments :x-amp)) half-width)
           (<= (- half-height) (+ gaze-y (-> saccade :arguments :y-amp)) half-height)))))

(defrecord SaccadeRequester [buffer sensor]
  Component
  (receive-focus
    [component focus content]
    (let [fixation (g/find-first #(and (= (:name %) "fixation")
                                       (= (-> % :arguments :reason) "maintenance"))
                                 content)
          old-scan (g/find-first #(= (:name %) "old-scan") content)
          old-gaze (g/find-first #(= (:name %) "old-gaze") content)
          scan (when (and (= (:name focus) "scan") old-scan) focus)
          [x x-vel y y-vel] (if scan
                              (convert-scan-info old-scan (:sensor component))
                              (convert-object-info fixation (:sensor component)))
          saccade (when (and x-vel (-> old-gaze :arguments :x-vel))
                    (make-saccade x x-vel y y-vel
                                  (-> old-gaze :arguments :x)
                                  (-> old-gaze :arguments :x-vel)
                                  (-> old-gaze :arguments :y)
                                  (-> old-gaze :arguments :y-vel)
                                  scan
                                  (-> fixation :arguments :object (obj/updated-object content))))]
      (if (valid-saccade? saccade (-> old-gaze :arguments :x) (-> old-gaze :arguments :y) (:sensor component))
        (reset! (:buffer component) saccade)
        (reset! (:buffer component) nil))))
  
  (deliver-result
    [component]
    (list @buffer)))

(defmethod print-method SaccadeRequester [comp ^java.io.Writer w]
  (.write w (format "SaccadeRequester{}")))

(defn start [& {:as args}]
  (->SaccadeRequester (atom ()) (:sensor (merge-parameters args))))
