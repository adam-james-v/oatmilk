(ns oatmilk.wave-collapse-2
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [clojure.math.combinatorics :as combo]
            [forge.brep.curves :as brep.c]
            [forge.brep.surfaces :as brep.s]
            [forge.brep.mesh :as brep.m]
            [forge.model :as mdl]
            [forge.compile.scad :as scad]
            [oatmilk.wave-collapse :as wfc1]
            [svg-clj.composites :refer [svg]]
            [svg-clj.elements :as el]
            [svg-clj.layout :as lo]
            [svg-clj.path :as path]
            [svg-clj.parametric :as p]
            [svg-clj.tools :as tools]
            [svg-clj.transforms :as tf]
            [svg-clj.utils :as utils]
            [nextjournal.clerk :as clerk]))

(def gx 320)
(def gx-5 (* gx 0.5))
(def gx-25 (* gx 0.25))
(def gr 20)

(defn corner
  []
  (let [shape (-> (mdl/circle gr)
                  (mdl/extrude gx-5))
        a (-> shape
              (mdl/rotate [0 90 0]))
        b (-> shape
              (mdl/rotate [90 0 0])
              (mdl/translate [0 gx-5 0]))]
    [(mdl/sphere gr) a b]))

(defn side
  []
  (let [shape (-> (mdl/circle gr)
                  (mdl/extrude gx))
        a (-> shape
              (mdl/rotate [0 90 0])
              (mdl/translate [(- gx-5) 0 0]))]
    [(mdl/sphere (* gr 1.5)) a]))

(defn inner
  []
  (rand-nth
    [nil
     (mdl/sphere (* gr 1.5 (rand)))]))

(defn rotate-module
  "Rotates a tile `deg` degrees counter-clockwise"
  [{:keys [sockets tile]} deg]
  (let [n (/ deg 90)]
    {:sockets (if (= n 0)
                sockets
                (vec (take 4 (drop n (cycle sockets)))))
     :tile (fn [] (mdl/rotate (tile) [0 0 (* -1 deg)]))}))

;; [N E S W] --> [-Y +X +Y -X] in openscad
(def basic-module-set
  (concat
    (map #(rotate-module {:sockets [0 1 1 0] :tile corner} %) [0 90 180 270])
    (map #(rotate-module {:sockets [0 1 0 1] :tile side} %) [#_0 90])
    (repeat 1 {:sockets [0 0 0 0] :tile inner})))

(defn render-gridmap2d
  [gridmap]
  (for [[pos modules] gridmap]
    (let [{:keys [sockets tile]} (first modules)]
      (if (> (count modules) 1)
        (map-indexed #(-> ((:tile %2))
                          (mdl/translate (utils/v* [gx gx gx] (concat pos [%1])))) modules)
        (-> (tile)
            (mdl/translate (utils/v* [gx gx 0] pos)))))))

(def result (wfc1/generate 20 20 basic-module-set))
(spit "wfc2.scad" (scad/write (render-gridmap2d result)))

(defn neighbour-keys
  [[col row layer]]
  (let [d2 [[col (dec row)]
            [(inc col) row]
            [col (inc row)]
            [(dec col) row]]]
    (if layer
      (vec
        (concat (mapv #(conj % layer) d2)
                [[col row (dec layer)]
                 [col row (inc layer)]]))
      d2)))

(defn voxel-grid
  [ncols nrows nlayers]
  (for [layer (range nlayers)
        row (range nrows)
        col (range ncols)]
    [col row layer]))

(defn initial-grid
  [ncols nrows nlayers module-set]
  (zipmap (voxel-grid ncols nrows nlayers) (repeat (vec module-set))))

(defn valid-sockets-for-module
  [{:keys [sockets]} module-set]
  (let [[n e s w d u] sockets
        all-sockets (set (map :sockets module-set))]
    [(set (filter #(= n (nth % 2)) all-sockets))
     (set (filter #(= e (nth % 3)) all-sockets))
     (set (filter #(= s (nth % 0)) all-sockets))
     (set (filter #(= w (nth % 1)) all-sockets))
     (set (filter #(= d (nth % 5)) all-sockets))
     (set (filter #(= u (nth % 4)) all-sockets))]))

(defn merge-socket-sets
  [socket-sets]
  (let [[ns es ss ws ds us] (map (fn [n] (map #(get % n) socket-sets)) [0 1 2 3 4 5])]
    (mapv #(apply set/union %) [ns es ss ws ds us])))

(defn valid-sockets-for-neighbours
  [pos gridmap module-set]
  (merge-socket-sets (map #(valid-sockets-for-module % module-set) (get gridmap pos))))

(defn lowest-entropy-pos
  [gridmap]
  (let [entropies (group-by second (update-vals gridmap count))
        [le he] (apply (juxt min max) (keys entropies))
        choices (cond
                  ;; all cells have the same entropy
                  (= 1 (count entropies))
                  (first (vals entropies))

                  ;; only one cell left un-collapsed
                  (and (= 2 (count entropies)) (= 1 (count (get entropies he))))
                  (get entropies he)

                  ;; cells either have full entropy or minimum possible entropy
                  (and (= 2 (count entropies)) (= 1 (count (get entropies 1))))
                  (get entropies he)

                  ;; some cells have greater than minimum possible entropy, pick those
                  :else (get entropies (apply min (keys (dissoc entropies 1 0)))))
        [k _] (rand-nth choices)]
    (if (< le 1)
      (println (str "Entropy Too low -> Propagation Error perhaps? Entropy: " le))
      k)))

(defn collapse-one-at
  [pos gridmap]
  (update gridmap pos #(vector (rand-nth %))))

(defn update-neighbours
  [pos gridmap module-set]
  (let [neighbours (neighbour-keys pos)
        socket-sets (zipmap neighbours (valid-sockets-for-neighbours pos gridmap module-set))
        new-neighbours (for [k neighbours]
                         (when-let [modules (get gridmap k)]
                           (let [valid-modules (filter #((get socket-sets k) (:sockets %)) modules)]
                             [k valid-modules])))]
    (into {} new-neighbours)))

(defn propagate
  [gridmap module-set]
  (let [seed (lowest-entropy-pos gridmap)]
    (loop [gm (collapse-one-at seed gridmap)
           stack [seed]]
      (if (< (count stack) 1)
        gm
        (let [[pos stack] ((juxt peek pop) stack)
              nks (neighbour-keys pos)
              old-neighbours (into {} (mapv #(vector % (get gm %)) nks))
              new-neighbours (update-neighbours pos gm module-set)
              new-stack (into stack (comp
                                      (filter #(not= (count (get new-neighbours %))
                                                     (count (get old-neighbours %))))
                                      (map conj)) nks)]
          (recur (merge gm new-neighbours) new-stack))))))

(defn collapsed?
  [gridmap]
  (let [counts (set (map count (vals gridmap)))]
    (or (= #{1} counts)
        (= #{0 1} counts))))

(defn collapse
  [gridmap module-set]
  (if (collapsed? gridmap)
    gridmap
    (let [gridmap (-> gridmap
                      (propagate module-set))]
      (recur (propagate gridmap module-set) module-set))))

(defn generate
  [ncols nrows nlayers module-set]
  (collapse (initial-grid ncols nrows nlayers module-set) module-set))

(defn shape
  [r]
  (let [xs (brep.c/bezier [[(- r) 0 0]
                           [(- (* r 0.05) r) (* r 1.25) 0]
                           [(- r (* r 0.2)) (* r 1.25) 0]
                           [(- r (* r 0.2)) gr 0]
                           [gx-5 gr 0]])
        sf (brep.s/revolve xs [[0 0 0] [1 0 0]])
        {:keys [pts quads]} (brep.m/surface sf 12 12)]
    (-> (mdl/polyhedron pts quads)
        (mdl/rotate [0 -90 0]))))

(defn shape2
  [r]
  (let [p (brep.c/bezier [[gx-5 0 0]
                          [0 0 0]
                          [0 gx-5 0]])
        xs (brep.c/circle gr)
        sf (brep.s/extrude-along xs p)
        {:keys [pts quads]} (brep.m/surface sf 12 12)]
    (-> (mdl/polyhedron pts quads))))

(defn corner3da
  []
  (let [shape (shape (+ (* 1.1 gr) (* 80 (rand))))
        #_(-> (mdl/circle gr)
                  (mdl/extrude gx-5))
        a (-> shape
              (mdl/rotate [0 90 0]))
        b (-> shape
              (mdl/rotate [-90 0 0]))
        c (-> shape
              (mdl/rotate [0 180 0]))]
    [#_(mdl/sphere gr) a b c]))

(defn corner3db
  []
  (let [shape (shape (+ (* 1.1 gr) (* 80 (rand))))
        #_(-> (mdl/circle gr)
                  (mdl/extrude gx-5))
        a (-> shape
              (mdl/rotate [0 90 0]))
        b (-> shape
              (mdl/rotate [-90 0 0]))
        c (-> shape
              (mdl/rotate [0 0 0]))]
    [#_(mdl/sphere gr) a b c]))

(defn corner3dc
  []
  [(shape2 10)]
  #_(let [shape (shape (+ (* 1.1 gr) (* 80 (rand))))
        #_(-> (mdl/circle gr)
                  (mdl/extrude gx-5))
        a (-> shape
              (mdl/rotate [0 90 0]))
        b (-> shape
              (mdl/rotate [-90 0 0]))]
    [#_(mdl/sphere gr) a b]))

(defn corner3dd
  []
  (let [shape (shape (+ (* 1.1 gr) (* 80 (rand))))
        #_(-> (mdl/circle gr)
                  (mdl/extrude gx-5))
        a (-> shape
              (mdl/rotate [0 90 0]))
        b (-> shape
              #_(mdl/rotate [-90 0 0]))]
    [#_(mdl/sphere gr) a b]))

(defn corner3de
  []
  (let [shape (shape (+ (* 1.1 gr) (* 80 (rand))))
        #_(-> (mdl/circle gr)
                  (mdl/extrude gx-5))
        a (-> shape
              (mdl/rotate [0 90 0]))
        b (-> shape
              (mdl/rotate [180 0 0]))]
    [#_(mdl/sphere gr) a b]))

(defn side3da
  []
  (let [shape (-> (mdl/circle gr)
                  (mdl/extrude gx))
        a (-> shape
              (mdl/rotate [0 90 0])
              (mdl/translate [(- gx-5) 0 0]))]
    [#_(mdl/sphere (* gr 1.5)) a]))

(defn side3db
  []
  (let [shape (-> (mdl/circle gr)
                  (mdl/extrude gx))
        a (-> shape
              (mdl/translate [0 0 (- gx-5)]))]
    [a]))

(defn inner3d
  []
  (rand-nth
    [nil
     #_(mdl/sphere (* gr 1.5 (rand)))]))

(defn rotate-module3d
  "Rotates a tile `deg` degrees counter-clockwise"
  [{:keys [sockets tile]} deg]
  (let [[xy z] (split-at 4 sockets)
        n (/ deg 90)]
    {:sockets (if (= n 0)
                sockets
                (vec (concat (take 4 (drop n (cycle xy))) z)))
     :tile (fn [] (mdl/rotate (tile) [0 0 (* -1 deg)]))}))

;; [N E S W] --> [-Y +X +Y -X] in openscad
(def basic-module-set3d
  (concat
    #_(map #(rotate-module3d {:sockets [0 1 1 0 1 0] :tile corner3da} %) [0 90 180 270])
    #_(map #(rotate-module3d {:sockets [0 1 1 0 0 1] :tile corner3db} %) [0 90 180 270])
    (map #(rotate-module3d {:sockets [0 1 1 0 0 0] :tile corner3dc} %) [0 90 180 270])
    (map #(rotate-module3d {:sockets [0 1 0 0 0 1] :tile corner3dd} %) [0 90 180 270])
    (map #(rotate-module3d {:sockets [0 1 0 0 1 0] :tile corner3de} %) [0 90 180 270])
    #_(map #(rotate-module3d {:sockets [0 1 0 1 0 0] :tile side3da} %) [#_0 90])
    #_(repeat 20 {:sockets [0 0 0 0 0 0] :tile inner3d})))

(defn render-gridmap3d
  [gridmap]
  (pmap (fn [[pos modules]]
          (let [{:keys [sockets tile]} (first modules)]
            (-> (tile)
                (mdl/translate (utils/v* [gx gx gx] pos))))) gridmap))

(def result3d (generate 6 6 6 basic-module-set3d))
(spit "wfc2.scad" (scad/write (render-gridmap3d result3d)))
