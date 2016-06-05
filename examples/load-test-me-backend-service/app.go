// Copyright (C) 2016 Electronic Arts Inc. All rights reserved.
package main

import (
	"fmt"
	"log"
	"net/http"
)

func init() {
}

func handler(w http.ResponseWriter, r *http.Request) {
	fmt.Fprint(w, "pong")
}

func main() {
	http.HandleFunc("/", handler)
	log.Print("Listening on port 8080")
	log.Fatal(http.ListenAndServe(":8080", nil))
}
