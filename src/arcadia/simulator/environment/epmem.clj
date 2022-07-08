(ns
 ^{:doc "Provides an environment for building models to test what/where episodic memory."}
 arcadia.simulator.environment.epmem
  (:require [arcadia.simulator.environment.core :refer [Environment]]
            [arcadia.utility [image :as img] [opencv :as cv]]
            [clojure.math.numeric-tower :refer [floor]])
  (:import [javax.swing JFrame]
           [java.awt Color RenderingHints Polygon]
           [java.awt.image BufferedImage]))

(def jframe (atom nil))

(def height 512)
(def width 512)
(def shape-size "square bounding-box size for the shapes" 50)

(def location
  {:xpos (floor (/ width 2))
   :ypos (floor (/ height 2))
   :xcorner 0
   :ycorner 0})

(def background-color Color/WHITE)
(def foreground-color Color/BLACK)

(def display-colors "map of color name to color value" 
  {:red (Color. 208 57 75) 
   :blue (Color. 76 114 203) 
   :green (Color. 101 178 78)})

(def screen-locations "map of location name to pixel values"
  {:top {:x (floor (- (/ width 2) (/ shape-size 2))) :y 100}
   :bottom {:x (floor (- (/ width 2) (/ shape-size 2))) :y (- height 100 shape-size)}
   :left {:x 100 :y (floor (/ height 2))}
   :right {:x (- width 100 shape-size) :y (floor (/ height 2))}})

(def cycle-time "ARCADIA cycle time in milliseconds" 25)

(def colors [:red :blue :green])
(def shapes [:triangle :square :circle])
(def locations [:top :bottom :right :left])

;; press a button to advance to the next screen
;; - :no-button if the screen is new
;; - :yes-button if the screen was seen earlier
(def actions "environment action commands" [:no-button :yes-button :go-button])

(defn- get-canvas [width height]
  (let [bi (BufferedImage. width height BufferedImage/TYPE_3BYTE_BGR)]
    (doto (.createGraphics bi)
      (.setPaint background-color)
      (.fillRect 0 0 width height)
      .dispose)
    bi))

(def blank-screen (get-canvas width height))

(defn- draw-square [canvas color location]
  (doto (.createGraphics canvas)
    (.setRenderingHints (RenderingHints. RenderingHints/KEY_ANTIALIASING RenderingHints/VALUE_ANTIALIAS_ON))
    (.setColor (get display-colors color))
    (.fillRect (-> screen-locations location :x) (-> screen-locations location :y) shape-size shape-size)
    .dispose)
  canvas)

(defn- draw-circle [canvas color location]
  (doto (.createGraphics canvas)
    (.setRenderingHints (RenderingHints. RenderingHints/KEY_ANTIALIASING RenderingHints/VALUE_ANTIALIAS_ON))
    (.setColor (get display-colors color))
    (.fillOval (-> screen-locations location :x) (-> screen-locations location :y) shape-size shape-size)
    .dispose)
  canvas)

(defn- triangle [location]
  (doto (Polygon.)
    ;; bottom left
    (.addPoint (-> screen-locations location :x) (+ (-> screen-locations location :y) shape-size))
    ;; bottom right
    (.addPoint (+ (-> screen-locations location :x) shape-size) (+ (-> screen-locations location :y) shape-size))
    ;; top
    (.addPoint (+ (-> screen-locations location :x) (floor (/ shape-size 2))) (-> screen-locations location :y))))

(defn- draw-triangle [canvas color location]
  (doto (.createGraphics canvas)
    (.setRenderingHints (RenderingHints. RenderingHints/KEY_ANTIALIASING RenderingHints/VALUE_ANTIALIAS_ON))
    (.setColor (get display-colors color))
    (.fillPolygon (triangle location))
    .dispose)
  canvas)

(defn- draw-shape [canvas shape-description]
  (case (:shape shape-description)
    :triangle (draw-triangle canvas (:color shape-description) (:location shape-description))
    :square (draw-square canvas (:color shape-description) (:location shape-description))
    :circle (draw-circle canvas (:color shape-description) (:location shape-description))))

;; (defn- draw-centered-text [s & {:keys [color] :or {color foreground-color}}]
;;   (let [canvas (get-canvas width height)
;;         g (.createGraphics canvas)]
;;     (.setRenderingHints g (RenderingHints. RenderingHints/KEY_TEXT_ANTIALIASING RenderingHints/VALUE_TEXT_ANTIALIAS_ON))
;;     (.setColor g (get display-colors color))
;;     (.setFont g (Font. "Arial" Font/PLAIN 80))
;;     (.drawString g s
;;                  (- (/ width 2) (int (/ (-> g .getFontMetrics (.stringWidth s)) 2)))
;;                  (+ (/ height 2) (int (-> g .getFontMetrics .getDescent))))
;;     (.dispose g)
;;     canvas))

(defn- state-value [env state-key]
  (-> env :state deref state-key))

(defn- new-state [n-trials]
  {:display-type :intertrial
   :current-time 0
   :current-stimulus nil
   :current-trial-type nil
   :previous-stimulus nil
   :previous-trial-type nil
   :sum-rt {:congruent [0 0] :incongruent [0 0] :neutral [0 0]}
   :remaining-trials n-trials})

(defn- opposite-location [location]
  (case location
    :top :bottom
    :bottom :top
    :left :right
    :right :left))

;; it's okay to have identical shapes in two different locations for 
;; the episodic memory case. this might be a confound for experiments 
;; using people, but for the initial arcadia components, it'll be fine.
(defn- next-stimulus []
  (let [location (rand-nth locations)]
    [{:shape (rand-nth shapes) :color (rand-nth colors) :location location}
     {:shape (rand-nth shapes) :color (rand-nth colors) :location (opposite-location location)}]))

;; always the same stimulus to debug recall
#_(defn- next-stimulus []
  (let [location (first locations)]
    [{:shape (first shapes) :color (first colors) :location location}
     {:shape (first shapes) :color (first colors) :location (opposite-location location)}]))


(defn- draw-screen [env]
  (if (= (state-value env :display-type) :intertrial)
    blank-screen
    (let [canvas (get-canvas width height)]
      (draw-shape canvas (first (state-value env :current-stimulus)))
      (draw-shape canvas (second (state-value env :current-stimulus))))))

;; you can only ever be right
(defn- correct-response? [action stimulus] true)

;; state
;;   display type: stimulus, intertrial
;;   current time: number of milliseconds in the state
;;   current stimulus
;;   remaining trials: number of trials left

;; advance
;;   move from intertrial to trial display on :go-button press
;;   move from trial display to intertrial on :yes-button or :no-button press

;; TODO: 
;;   store a history of states seen, so we can determine if the answer is correct
;;   for a :yes-button or :no-button response. 

;; uses vars in the namespace: height, width, colors, shapes, locations, actions.
(defrecord Epmem [n-trials state]
  Environment

  (info [env]
    {:dimensions [:height :width]
     :height height
     :width width
         ;; visual degrees isn't important for this task.
         ;; treat the display as 12x12 visual degrees.
     :viewing-width 12
     :increment 0.025 ;; 40 hz
     :render-modes ["human" "buffered-image"]
     :stimuli {:shapes shapes :colors colors}
     :task-parameters {:total-trials n-trials}
     :actions actions})

  ;; intertrial -(button press)-> cue -(vocalization)-> intratrial
  ;; does not display error notification on incorrect response
  ;; returns [observation reward done? diagnostic-info]
  ;;
  ;; as an action, expects either an empty sequence or a sequence
  ;; containing one of the available actions for the environment.
  (step [env action]
    (cond (zero? (state-value env :remaining-trials))
          {:observation nil
           :reward 0
           :done? true}

          ;; intertrial and we get a button press, switch to the stimulus
          ;; restart the counter
          (and (= (state-value env :display-type) :intertrial)
               (= (first action) :go-button)) ;; check for :go-button specifically.
          ;; note: it's possible that yes/no responses will bleed over for a few cycles,
          ;; so we need to be robust to those duplicate responses.

          (do
            (println action)
            (swap! (:state env) assoc
                   :display-type :stimulus
                   :current-stimulus (next-stimulus)
                   :previous-stimulus (state-value env :current-stimulus)
                   :current-time 0)
            {:observation @(:state env)
             :reward 0
             :done? false})

          ;; if button press occurs in stimulus mode, register response type and move to next trial.
          (and (= (state-value env :display-type) :stimulus)
               (#{:yes-button :no-button} (first action))) ;; can only be one of :yes-button or :no-button
            ;; switch to intertrial mode

          (let [trial (- (:n-trials env) (state-value env :remaining-trials))
                response-time (state-value env :current-time)
                correct (correct-response?
                         (first action)
                         (state-value env :current-stimulus))]
            (swap! (:state env) assoc
                   :display-type :intertrial
                   :remaining-trials (dec (state-value env :remaining-trials))
                   :current-time 0)
            {:observation @(:state env)
             :reward (if correct 1 -1)
             :done? false
             :data {:trial trial
                    :response-time response-time
                    :correct correct}
             :message (-> action first name)
             :increment-message-count? true})

      ;; else update state counter if no change
          :else
          (do
            (swap! (:state env) assoc :current-time (+ (state-value env :current-time) cycle-time))
            {:observation @(:state env)
             :reward 0
             :done? false})))

  (reset [env]
    (reset! state (new-state (:n-trials env))))

  ;; Draw for human consumption or get a Java BufferedImage.
  ;; render returns its results in key-value pairs
  (render [env mode close]
    (case mode
      "human"
      (img/display-image! (if @jframe @jframe
                              (reset! jframe
                                      (doto (JFrame. "Episodic Memory Test Environment")
                                        (.setLocation 500 300))))
                          (draw-screen env))

      "buffered-image"
      {:image (draw-screen env)
       :location location}

      "opencv-matrix"
      {:image (img/bufferedimage-to-mat (draw-screen env) cv/CV_8UC3)
       :location location}

      "state-descriptor"
      {:status (state-value env :display-type)
       :current-stimulus (state-value env :current-stimulus)
       :time (state-value env :current-time)
       :trial (- n-trials (state-value env :remaining-trials))}))

  (close [env] (reset! state {}))

  ;; no randomness
  (seed [env seed-value] nil))

;; the argument must be a map that includes the following keys
;; :n-trials -- the number of trials to run
(defn configure [env-args]
  (->Epmem (:n-trials env-args)
           (atom (new-state (:n-trials env-args)))))
