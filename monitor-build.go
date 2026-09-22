// Command monitor-build polls a GitHub Actions run until it finishes,
// then exits 0 on success or 1 on any other conclusion.
//
// Usage:
//
//	go run monitor-build.go [run-id] [-timeout 25m] [-interval 20s]
//
// With no run-id it follows the latest run on the current branch.
// Replaces fixed sleeps when waiting on CI.
package main

import (
	"encoding/json"
	"flag"
	"fmt"
	"os"
	"os/exec"
	"strconv"
	"strings"
	"time"
)

type runInfo struct {
	Status     string `json:"status"`
	Conclusion string `json:"conclusion"`
	URL        string `json:"url"`
}

func ghJSON(args ...string) ([]byte, error) {
	cmd := exec.Command("gh", args...)
	cmd.Stderr = os.Stderr
	return cmd.Output()
}

func latestRunID() (string, error) {
	out, err := ghJSON("run", "list", "--limit", "1", "--json", "databaseId", "--jq", ".[0].databaseId")
	if err != nil {
		return "", fmt.Errorf("cannot list runs: %w", err)
	}
	id := strings.TrimSpace(string(out))
	if id == "" || id == "null" {
		return "", fmt.Errorf("no runs found")
	}
	return id, nil
}

func pollRun(id string) (runInfo, error) {
	var info runInfo
	out, err := ghJSON("run", "view", id, "--json", "status,conclusion,url")
	if err != nil {
		return info, fmt.Errorf("cannot view run %s: %w", id, err)
	}
	if err := json.Unmarshal(out, &info); err != nil {
		return info, fmt.Errorf("cannot parse run JSON: %w", err)
	}
	return info, nil
}

func main() {
	timeout := flag.Duration("timeout", 25*time.Minute, "give up after this long")
	interval := flag.Duration("interval", 20*time.Second, "poll interval")
	flag.Parse()

	id := ""
	for _, a := range flag.Args() {
		if _, err := strconv.ParseUint(a, 10, 64); err == nil {
			id = a
			break
		}
	}
	if id == "" {
		var err error
		id, err = latestRunID()
		if err != nil {
			fmt.Fprintln(os.Stderr, "error:", err)
			os.Exit(2)
		}
	}

	start := time.Now()
	deadline := start.Add(*timeout)
	fmt.Printf("following run %s (timeout %s, every %s)\n", id, timeout, interval)
	for {
		info, err := pollRun(id)
		if err != nil {
			fmt.Fprintln(os.Stderr, "error:", err)
			os.Exit(2)
		}
		elapsed := time.Since(start).Round(time.Second)
		if info.Status == "completed" {
			fmt.Printf("[%s] completed: %s %s\n", elapsed, info.Conclusion, info.URL)
			if info.Conclusion == "success" {
				os.Exit(0)
			}
			// Best-effort human summary of what failed.
			cmd := exec.Command("gh", "run", "view", id)
			cmd.Stdout = os.Stdout
			cmd.Stderr = os.Stderr
			_ = cmd.Run()
			os.Exit(1)
		}
		fmt.Printf("[%s] %s ...\n", elapsed, info.Status)
		if time.Now().Add(*interval).After(deadline) {
			fmt.Fprintln(os.Stderr, "error: timed out waiting for run", id)
			os.Exit(2)
		}
		time.Sleep(*interval)
	}
}
