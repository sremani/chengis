(ns chengis.db.connection
  (:require [next.jdbc :as jdbc]))

(defn create-datasource
  "Create a SQLite datasource from a database path."
  [db-path]
  (jdbc/get-datasource {:dbtype "sqlite" :dbname db-path}))

(defn test-connection
  "Verify the database connection works."
  [ds]
  (jdbc/execute-one! ds ["SELECT 1 AS result"]))
