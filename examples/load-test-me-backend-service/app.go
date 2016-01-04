// Copyright (C) 2016 Electronic Arts Inc. All rights reserved.
package ping 

import (
    "fmt"
    "net/http"
)

func init() {
    http.HandleFunc("/", handler)
}

func handler(w http.ResponseWriter, r *http.Request) {
    fmt.Fprint(w, "pong")
}
