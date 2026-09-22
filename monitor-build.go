// Command monitor-build follows a GitHub Actions run until it finishes:
// live-tails the running job logs, prints a job table on change, dumps
// the failed logs at the end, and exits 0 on success / 1 on failure.
//
// Usage:
//
//	go run monitor-build.go [run-id] [-timeout 25m] [-interval 5s] [-tail 60]
//
// With no run-id it follows the latest run on the current branch.
// -jobs=false disables the job table, -log=false disables live log tailing.
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

type job struct {
	ID         uint64 `json:"databaseId"`
	Name       string `json:"name"`
	Status     string `json:"status"`
	Conclusion string `json:"conclusion"`
}

type runInfo struct {
	Status     string `json:"status"`
	Conclusion string `json:"conclusion"`
	URL        string `json:"url"`
	Jobs       []job  `json:"jobs"`
}

func gh(outArgs ...string) ([]byte, error) {
	cmd := exec.Command("gh", outArgs...)
	cmd.Stderr = os.Stderr
	return cmd.Output()
}

func latestRunID() (string, error) {
	raw, err := gh("run", "list", "--limit", "1", "--json", "databaseId", "--jq", ".[0].databaseId")
	if err != nil {
		return "", fmt.Errorf("cannot list runs: %w", err)
	}
	id := strings.TrimSpace(string(raw))
	if id == "" || id == "null" {
		return "", fmt.Errorf("no runs found")
	}
	return id, nil
}

func pollRun(id string) (runInfo, error) {
	var info runInfo
	raw, err := gh("run", "view", id, "--json", "status,conclusion,url,jobs")
	if err != nil {
		return info, fmt.Errorf("cannot view run %s: %w", id, err)
	}
	if err := json.Unmarshal(raw, &info); err != nil {
		return info, fmt.Errorf("cannot parse run JSON: %w", err)
	}
	return info, nil
}

// jobLogs returns the full log lines of one job (best effort).
// gh prints "still in progress" chatter to stderr while the job runs —
// swallowed, the caller only cares about stdout lines.
func jobLogs(jobID uint64) []string {
	cmd := exec.Command("gh", "run", "view", "--job", strconv.FormatUint(jobID, 10), "--log")
	raw, err := cmd.Output()
	if err != nil {
		return nil
	}
	text := strings.TrimRight(string(raw), "\n")
	if text == "" {
		return nil
	}
	return strings.Split(text, "\n")
}

func jobTable(info runInfo) string {
	var b strings.Builder
	for _, j := range info.Jobs {
		state := j.Status
		if j.Status == "completed" {
			state = j.Conclusion
		}
		fmt.Fprintf(&b, "    %-28s %s\n", j.Name, state)
	}
	return b.String()
}

func main() {
	timeout := flag.Duration("timeout", 25*time.Minute, "give up after this long")
	interval := flag.Duration("interval", 5*time.Second, "poll interval")
	tailN := flag.Int("tail", 60, "failed-log lines to dump at the end")
	showJobs := flag.Bool("jobs", true, "print the job table when it changes")
	tailLogs := flag.Bool("log", true, "live-tail running job logs")
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
	printed := map[uint64]int{} // jobID -> log lines already shown
	lastTable := ""
	fmt.Printf("following run %s (timeout %s, every %s)\n", id, *timeout, *interval)

	for {
		info, err := pollRun(id)
		if err != nil {
			fmt.Fprintln(os.Stderr, "error:", err)
			os.Exit(2)
		}
		elapsed := time.Since(start).Round(time.Second)
		if *showJobs {
			if tbl := jobTable(info); tbl != lastTable {
				fmt.Printf("[%s] jobs:\n%s", elapsed, tbl)
				lastTable = tbl
			}
		}
		if *tailLogs {
			for _, j := range info.Jobs {
				if j.Status == "completed" {
					continue
				}
				lines := jobLogs(j.ID)
				if fresh := lines[printed[j.ID]:]; len(fresh) > 0 {
					if len(fresh) > 150 {
						fmt.Printf("    ... (%d log lines skipped)\n", len(fresh)-60)
						fresh = fresh[len(fresh)-60:]
					}
					for _, l := range fresh {
						fmt.Printf("    | %s\n", l)
					}
				}
				printed[j.ID] = len(lines)
			}
		}
		if info.Status == "completed" {
			fmt.Printf("[%s] completed: %s %s\n", elapsed, info.Conclusion, info.URL)
			if info.Conclusion == "success" {
				os.Exit(0)
			}
			if raw, err := gh("run", "view", id, "--log-failed"); err == nil {
				lines := strings.Split(strings.TrimRight(string(raw), "\n"), "\n")
				if len(lines) > *tailN {
					lines = lines[len(lines)-*tailN:]
				}
				fmt.Printf("---- last %d lines of failed logs ----\n%s\n", len(lines), strings.Join(lines, "\n"))
			} else {
				cmd := exec.Command("gh", "run", "view", id)
				cmd.Stdout = os.Stdout
				cmd.Stderr = os.Stderr
				_ = cmd.Run()
			}
			os.Exit(1)
		}
		if time.Now().Add(*interval).After(deadline) {
			fmt.Fprintln(os.Stderr, "error: timed out waiting for run", id)
			os.Exit(2)
		}
		time.Sleep(*interval)
	}
}
