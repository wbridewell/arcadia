(ns
  ^{:doc "Functions that assist in processing gaze information including
         position and velocity conversions."}
  arcadia.utility.gaze
  (:require [arcadia.sensor.stable-viewpoint :as sense]))

;; These functions involve converting between pixels, the unit of measurement for object
;; locations; and degrees, the unit of measurement for gaze location. Also includes
;; speed conversions between per video increment and per second.

;; NOTE: Pixels use the upper left corner as the origin, while degrees use the image center.

;; Much of the gaze movement code involves synching up the gaze with a focused object.
;; Object information has a delay of 3-4 cycles between input and the point where location
;; and trajectory information is available.  Therefore, we build in a similar delay for gaze
;; (this would be as if it took 3 cycles to extract gaze information, which might be true,
;; but there's currently no mechanistic explanation for why).
(def gaze-cycle-lag 3)

;; We then support this delay with a queue of recent component outputs for gaze-updater or
;; anything else that needs a similar delay.
(defn update-delay-queue
  "Add a new item to the delay queue."
  [new-item item-name queue]
  (cons (assoc new-item :name item-name)
        (take (- gaze-cycle-lag 1) queue)))

(defn delay-queue-output
  "Returns the front of the delay queue."
  [queue]
  (when (= (count queue) gaze-cycle-lag)
    (last queue)))

(defn x-pixels->degrees [x sensor]
  (let [pix-width (sense/camera-width sensor)
        deg-width (sense/camera-viewing-width sensor)]
   (* (- x (/ pix-width 2)) (/ deg-width pix-width))))

(defn y-pixels->degrees [y sensor]
  (let [pix-height (sense/camera-height sensor)
        deg-height (sense/camera-viewing-height sensor)]
   (* (- y (/ pix-height 2)) (/ deg-height pix-height))))

(defn pixels->degrees [value sensor]
  (let [pix-width (sense/camera-width sensor)
        deg-width (sense/camera-viewing-width sensor)]
    (* value (/ deg-width pix-width))))

(defn x-degrees->pixels [x sensor]
  (let [pix-width (sense/camera-width sensor)
        deg-width (sense/camera-viewing-width sensor)]
   (* (+ x (/ deg-width 2)) (/ pix-width deg-width))))

(defn y-degrees->pixels [y sensor]
  (let [pix-height (sense/camera-height sensor)
        deg-height (sense/camera-viewing-height sensor)]
   (* (+ y (/ deg-height 2)) (/ pix-height deg-height))))

(defn degrees->pixels [value sensor]
  (let [pix-width (sense/camera-width sensor)
        deg-width (sense/camera-viewing-width sensor)]
    (* value (/ pix-width deg-width))))

(defn velocity->seconds
  "Converts velocity from units per cycle to units per second."
  [value sensor]
  (let [increment (sense/camera-increment sensor)]
    (/ value increment)))

(defn velocity->incr
  "Converts velocity from units per second to units per cycle."
  [value sensor]
  (let [increment (sense/camera-increment sensor)]
    (* value increment)))

(defn pixels-incr->degrees-second
  "Converts pixels per cycle to degrees per second."
  [value sensor]
  (pixels->degrees (velocity->seconds value sensor) sensor))

(defn degrees-second->pixels-incr
  "Converts degrees per second to pixels per cycle."
  [value sensor]
  (degrees->pixels (velocity->incr value sensor) sensor))

;; If we're converting durations instead of velocities, do the opposite.
(defn duration->seconds
  "Converts duration from cycles to seconds."
  [value sensor]
  (let [increment (sense/camera-increment sensor)]
    (* value increment)))

(defn duration->incr
  "Converts duration from seconds to cycles."
  [value sensor]
  (let [increment (sense/camera-increment sensor)]
    (/ value increment)))

(defn x-within-degree-bounds?
  "Returns true if the x value in visual degrees is within the image bounds."
  [x sensor]
  (let [half-deg-width (/ (sense/camera-viewing-width sensor) 2)]
    (and (> x (- half-deg-width))
         (< x half-deg-width))))

(defn y-within-degree-bounds?
  "Returns true if the y value in visual degrees is within the image bounds."
  [y sensor]
  (let [half-deg-height (/ (sense/camera-viewing-height sensor) 2)]
    (and (> y (- half-deg-height))
         (< y half-deg-height))))
