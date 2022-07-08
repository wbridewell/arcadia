(ns
  ^{:doc "Provides an environment where an image is displayed for as long as
         necessary."}
  arcadia.simulator.environment.static-image
  (:require [arcadia.simulator.environment.core :refer [Environment]]
            [arcadia.utility [image :as img] [opencv :as cv]]
            [clojure.math.numeric-tower :as math])
  (:import javax.swing.JFrame))

(def ^:private default-increment
  "Time per timestep."
  0.025)

(defn grab-image
  [image-source]
  (javax.imageio.ImageIO/read image-source))

(defn grab-image-from-url
  [url-name]
  (grab-image (java.net.URL. url-name)))

(defn grab-image-from-file
  [file-name]
  (grab-image (java.io.File. file-name)))

(defn- height [si] (.getHeight (:image si)))
(defn- width [si] (.getWidth (:image si)))

(def jframe (atom nil))

;; the values never change for the static image.
(defn poll-location [si]
  {:xpos (math/floor (/ (width si) 2))
   :ypos (math/floor (/ (height si) 2))
   :xcorner 0
   :ycorner 0})


;; consider adding "render modes" as retrievable properties
;; consider adding "render keywords" as retrievable properties
;; consider a better way to get dimensional parameters
(defrecord StaticImage [image args]
  Environment

  (info [env]
        {:dimensions [:height :width]
         :height (height env)
         :width (width env)
         :increment (or (:increment args) default-increment)
         :viewing-width (:viewing-width args)
         :render-modes ["human" "buffered-image"]
         :file-path (or (:image-path args) (:file-path args))
         :actions nil})

  ;; nothing changes and there are no actions.
  (step [env action]
        {:done? false})
  (reset [env] nil)

  ;; Draw for human consumption or get a Java BufferedImage.
  ;; render returns its results in key-value pairs
  (render [env mode close]
          (case mode
            "human"
            (img/display-image! (if @jframe @jframe
                                    (reset! jframe (JFrame. "StaticImage Environment")))
                                (:image env))

            "buffered-image"
            {:image (:image env)
             :location (poll-location env)}

            "opencv-matrix"
            {:image (img/bufferedimage-to-mat (:image env) cv/CV_8UC3)
             :location (poll-location env)}))

  (close [env] nil)
  (seed [env seed-value] nil))

;; arguments must be a map that includes the path to an image file
;; {:image-path "path"}
(defn configure [env-args]
  (let [file-path (or (:image-path env-args) (:file-path env-args))]
    (if (nil? file-path)
      (throw (NullPointerException. "Environmental argument :image-path/:file-path is nil."))
      (->StaticImage (grab-image-from-file file-path) env-args))))
