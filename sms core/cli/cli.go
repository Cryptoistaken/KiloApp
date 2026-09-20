// Command kilocli — admin CLI for the KiloSMS gateway. Full gateway access.
// Env: KILO_GATEWAY (base url), KILO_API_KEY (app key), ADMIN_KEY (admin key).
package main

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"os"
	"strings"
	"time"
)

var (
	base   = strings.TrimSuffix(envOr("KILO_GATEWAY", "http://localhost:8080"), "/")
	appKey = os.Getenv("KILO_API_KEY")
	admKey = os.Getenv("ADMIN_KEY")
	client = &http.Client{Timeout: 20 * time.Second}
)

func envOr(k, d string) string {
	if v := os.Getenv(k); v != "" {
		return v
	}
	return d
}

func call(method, path string, body any, admin bool) (int, map[string]any) {
	var rdr io.Reader
	if body != nil {
		b, _ := json.Marshal(body)
		rdr = bytes.NewReader(b)
	}
	req, err := http.NewRequest(method, base+path, rdr)
	if err != nil {
		fmt.Fprintln(os.Stderr, "error: "+err.Error())
		os.Exit(1)
	}
	req.Header.Set("Content-Type", "application/json")
	key := appKey
	if admin {
		key = admKey
	}
	if key != "" {
		req.Header.Set("Authorization", "Bearer "+key)
	}
	res, err := client.Do(req)
	if err != nil {
		fmt.Fprintln(os.Stderr, "error: "+err.Error())
		os.Exit(1)
	}
	defer res.Body.Close()
	b, _ := io.ReadAll(res.Body)
	var out map[string]any
	if err := json.Unmarshal(b, &out); err != nil {
		out = map[string]any{"raw": string(b)}
	}
	return res.StatusCode, out
}

func dump(v any) {
	b, _ := json.MarshalIndent(v, "", "  ")
	fmt.Println(string(b))
}

func main() {
	args := os.Args[1:]
	cmd := ""
	if len(args) > 0 {
		cmd, args = args[0], args[1:]
	}
	switch cmd {
	case "health":
		_, out := call("GET", "/v1/health", nil, false)
		dump(out)
	case "pool":
		_, out := call("GET", "/v1/admin/pool", nil, true)
		arr, _ := out["pool"].([]any)
		if len(arr) == 0 {
			fmt.Println("(empty pool — set VOLTX_API_KEY on the gateway)")
			return
		}
		for _, it := range arr {
			m, _ := it.(map[string]any)
			on := "OFF"
			if m["enabled"] == true {
				on = "ON "
			}
			fmt.Printf("%s  %-10v  kind=%-5v  key=%v\n", on, m["id"], m["kind"], m["key6"])
		}
	case "enable":
		if len(args) == 0 {
			fmt.Fprintln(os.Stderr, "usage: kilocli enable <id...>")
			os.Exit(1)
		}
		_, out := call("PUT", "/v1/admin/pool", map[string]any{"enabled": args}, true)
		dump(out)
	case "stats":
		_, out := call("GET", "/v1/admin/stats", nil, true)
		dump(out)
	case "feed":
		n := "5"
		if len(args) > 0 {
			n = args[0]
		}
		_, out := call("GET", "/v1/feed?limit="+n, nil, false)
		arr, _ := out["items"].([]any)
		if len(arr) == 0 {
			fmt.Println("(empty feed — upstream poll found nothing yet)")
			return
		}
		for _, it := range arr {
			m, _ := it.(map[string]any)
			fmt.Printf("%v  %v  %v/%v  %v\n", m["masked"], m["code"], m["svc"], m["method"], m["range"])
		}
	case "meta":
		_, out := call("GET", "/v1/meta", nil, false)
		b, _ := json.MarshalIndent(out, "", "  ")
		s := string(b)
		if len(s) > 2000 {
			s = s[:2000]
		}
		fmt.Println(s)
	case "get":
		if len(args) == 0 {
			fmt.Fprintln(os.Stderr, "usage: kilocli get 229016XXX")
			os.Exit(1)
		}
		_, out := call("POST", "/v1/numbers", map[string]any{"range": args[0]}, false)
		dump(out)
	case "otp":
		if len(args) == 0 {
			fmt.Fprintln(os.Stderr, "usage: kilocli otp <full-number>")
			os.Exit(1)
		}
		_, out := call("GET", "/v1/otp?number="+args[0], nil, false)
		dump(out)
	default:
		fmt.Printf("usage: kilocli <cmd> [args]  (KILO_GATEWAY=%s)\n", base)
		fmt.Println("  health | pool | enable <id...> | stats | feed [n] | meta | get <range> | otp <number>")
		if cmd != "" && cmd != "--help" && cmd != "-h" {
			os.Exit(1)
		}
	}
}
