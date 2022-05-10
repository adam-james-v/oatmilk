# ;;

```{=org}
#+STARTUP: overview
```
```{=org}
#+PROPERTY: header-args :cache yes :noweb yes :results none :mkdirp yes :padline yes :async
```
## deps.edn

``` {#deps.edn .clojure tangle="./deps.edn"}
{:deps
 {org.clojure/clojure         {:mvn/version "RELEASE"}
  org.clojure/math.combinatorics {:mvn/version "0.1.6"}
  io.github.nextjournal/clerk {:mvn/version "RELEASE"}
  criterium/criterium {:mvn/version "0.4.6"}
  forge/forge                 {:local/root "/Users/adam/dev/forge"}
  svg-clj/svg-clj             {:local/root "/Users/adam/dev/svg-clj"}}}

```

# user

The user ns that loads when you connect to the project with a REPL.

``` {.clojure tangle="./src/user.clj"}
(ns user
  (:require [nextjournal.clerk :as clerk]))

(defn start! []
  (clerk/serve! {:browse true
                 :watch-paths ["notebooks"]}))

(start!)

```

# notebooks

The notebooks to work on.

[bubbles](./notebooks/bubbles.org) [Wave Function
Collapse](./notebooks/wave-collapse.org) [Wave Function Collapse
2](./notebooks/wave-collapse-2.org)
