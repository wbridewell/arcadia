(ns
  ^{:doc "Provides an environment for playing a video by stepping through
         the frames."}
  arcadia.simulator.environment.video-player
  (:import javax.swing.JFrame)
  (:require
   (arcadia.camera [theater :as theater])
   [arcadia.simulator.environment.core :refer [Environment]]
   [arcadia.utility.image :as img]))

(def jframe (atom nil))

;; consider adding "render modes" as retrievable properties
;; consider adding "render keywords" as retrievable properties
;; consider a better way to get dimensional parameters
(defrecord VideoPlayer [camera file-path]
  Environment

  (info [env]
        {:dimensions [:height :width]
         :height (theater/height (:camera env))
         :width (theater/width (:camera env))
         :viewing-width (:viewing-width (:camera env))
         :increment (:increment (:camera env))
         :render-modes ["human" "buffered-image"]
         :file-path (:file-path env)
         :actions nil})

  ;; This environment is passive, being a prerecorded video.
  ;; each call advances the video by one frame.
  (step [env action]
        {:done? (nil? (theater/advance-frames (:camera env) 1))})

  (reset [env]
         (theater/reset (:camera env)))

  ;; Draw for human consumption or get a Java BufferedImage.
  ;; render returns its results in key-value pairs
  (render [env mode close]
          (case mode
            "human"
            (img/display-image! (if @jframe @jframe
                                    (reset! jframe (JFrame. "VideoPlayer Environment")))
                                (theater/poll-camera (:camera env)))

            "buffered-image"
            {:image (img/mat-to-bufferedimage (theater/poll-camera (:camera env)))
             :location (theater/poll-location (:camera env))}

            "opencv-matrix"
            {:image (theater/poll-camera (:camera env))
             :location (theater/poll-location (:camera env))}))

  (close [env]
         (theater/release (:camera env)))

  ;; no randomness
  (seed [env seed-value] nil))

;; arguments must be a map that includes the path to a video file
;; {:video-path "path"}
(defn configure [env-args]
  (let [file-path (or (:video-path env-args) (:file-path env-args))]
    (if (nil? file-path)
      (throw (NullPointerException. "Environmental argument :video-path/:file-path is nil."))
      (->VideoPlayer (theater/create-theater-camera file-path env-args) file-path))))
