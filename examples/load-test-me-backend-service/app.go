// Copyright (C) 2016 Electronic Arts Inc. All rights reserved.
package main

import (
	"fmt"
	"net/http"
)

func init() {
    http.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
	fmt.Fprint(w, "pong")
    })
}

